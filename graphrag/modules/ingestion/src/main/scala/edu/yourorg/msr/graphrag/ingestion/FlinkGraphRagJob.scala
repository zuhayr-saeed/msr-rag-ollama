package edu.yourorg.msr.graphrag.ingestion

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.core.model._
import edu.yourorg.msr.graphrag.llm.{OllamaClient, OllamaConfig, OllamaRelationScorer}

import com.typesafe.config.ConfigFactory
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment, _}
import org.apache.flink.streaming.api.functions.sink.{RichSinkFunction, SinkFunction}
import org.apache.flink.api.common.functions.RichFlatMapFunction
import org.apache.flink.util.Collector

import org.neo4j.driver.{AuthTokens, GraphDatabase, Driver}

import java.util.{HashMap => JHashMap, Map => JMap}
import scala.util.Try

/**
 * Flink GraphRAG Pipeline - Main Job
 * 
 * This streaming job implements the full GraphRAG pipeline:
 * 
 *   1. Ingest indexed document chunks from file or dummy source
 *   2. Normalize and tag chunks
 *   3. Extract concepts using heuristic and pattern-based methods
 *   4. Build co-occurrence windows for relation candidates
 *   5. Score relation candidates with Ollama LLM
 *   6. Project all entities into graph primitives (UpsertNode/UpsertEdge)
 *   7. Atomically upsert to Neo4j with idempotent MERGE semantics
 * 
 * Environment Variables:
 *   - MSR_CHUNK_FILE: Path to tab-separated chunk file (optional)
 *   - NEO4J_URI: Neo4j Bolt URI (default: bolt://localhost:7687)
 *   - NEO4J_USER: Neo4j username (default: neo4j)
 *   - NEO4J_PASS: Neo4j password (required for Neo4j sink)
 *   - OLLAMA_ENDPOINT: Ollama API endpoint (default: http://localhost:11434)
 *   - OLLAMA_MODEL: Ollama model name (default: llama3.2:latest)
 *   - ENABLE_LLM: Enable LLM relation scoring (default: false for local testing)
 */
object FlinkGraphRagJob extends Logging {

  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("Starting GraphRAG Flink Ingestion Pipeline")
    logger.info("=" * 60)

    val config = ConfigFactory.load()
    
    // -------------------------------------------------------------------------
    // 1. Create Flink execution environment
    // -------------------------------------------------------------------------
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val parallelism = sys.env.get("FLINK_PARALLELISM").map(_.toInt).getOrElse(2)
    env.setParallelism(parallelism)
    
    logger.info(s"Flink environment created with parallelism: $parallelism")

    // -------------------------------------------------------------------------
    // 2. Chunk source: file if MSR_CHUNK_FILE is set, otherwise dummy
    // -------------------------------------------------------------------------
    val chunkStream: DataStream[Chunk] = chunkSource(env).name("chunk-source")

    // -------------------------------------------------------------------------
    // 3. Normalize chunks (clean whitespace, ensure language tags)
    // -------------------------------------------------------------------------
    val normalizedChunks: DataStream[Chunk] = chunkStream
      .map { ch =>
        ch.copy(
          text = ch.text.replaceAll("\\s+", " ").trim,
          language = ch.language.orElse(Some("en"))
        )
      }
      .name("normalize-chunks")

    // -------------------------------------------------------------------------
    // 4. Project Chunk nodes to graph writes
    // -------------------------------------------------------------------------
    val chunkNodeWrites: DataStream[GraphWrite] = normalizedChunks
      .map { ch: Chunk =>
        UpsertNode(
          label = "Chunk",
          id = ch.chunkId,
          props = Map(
            "docId" -> ch.docId,
            "chunkIx" -> ch.chunkIx,
            "startPos" -> ch.startPos,
            "endPos" -> ch.endPos,
            "sectionPath" -> ch.sectionPath.getOrElse(""),
            "text" -> ch.text.take(5000), // Limit text size for Neo4j
            "sourceUri" -> ch.sourceUri,
            "contentHash" -> ch.contentHash,
            "language" -> ch.language.getOrElse("unknown")
          )
        ): GraphWrite
      }
      .name("chunk-node-writes")

    // -------------------------------------------------------------------------
    // 5. Extract concepts using heuristic + pattern methods
    //    Creates: Concept nodes, MENTIONS edges, CO_OCCURS edges
    // -------------------------------------------------------------------------
    val conceptWrites: DataStream[GraphWrite] = normalizedChunks
      .flatMap(new ConceptExtractorFunction())
      .name("concept-extraction")

    // -------------------------------------------------------------------------
    // 6. LLM-based relation scoring (if enabled)
    //    Creates: RELATES_TO edges with predicate and confidence
    // -------------------------------------------------------------------------
    val enableLlm = sys.env.get("ENABLE_LLM").map(_.toBoolean).getOrElse(false)
    
    val relationWrites: DataStream[GraphWrite] = if (enableLlm) {
      logger.info("LLM relation scoring ENABLED")
      normalizedChunks
        .flatMap(new RelationScorerFunction())
        .name("llm-relation-scoring")
    } else {
      logger.info("LLM relation scoring DISABLED (set ENABLE_LLM=true to enable)")
      // Return empty stream
      normalizedChunks.flatMap(_ => List.empty[GraphWrite]).name("llm-disabled")
    }

    // -------------------------------------------------------------------------
    // 7. Union all GraphWrite streams
    // -------------------------------------------------------------------------
    val allWrites: DataStream[GraphWrite] = chunkNodeWrites
      .union(conceptWrites)
      .union(relationWrites)

    // Debug: print graph writes
    allWrites.map { gw =>
      gw match {
        case UpsertNode(label, id, _) => s"[NODE] $label: $id"
        case UpsertEdge(from, fromId, rel, to, toId, _) => s"[EDGE] $from($fromId) -[$rel]-> $to($toId)"
      }
    }.print().name("debug-print")

    // -------------------------------------------------------------------------
    // 8. Neo4j sink with idempotent upserts
    // -------------------------------------------------------------------------
    val neo4jUri = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
    val neo4jUser = sys.env.getOrElse("NEO4J_USER", "neo4j")
    val neo4jPass = sys.env.getOrElse("NEO4J_PASS", "test123")

    logger.info(s"Neo4j connection: $neo4jUri as user '$neo4jUser'")

    allWrites
      .addSink(new Neo4jGraphSink(neo4jUri, neo4jUser, neo4jPass))
      .name("neo4j-sink")

    // -------------------------------------------------------------------------
    // 9. Execute the Flink job
    // -------------------------------------------------------------------------
    logger.info("Executing Flink job: GraphRAG Ingestion Pipeline")
    env.execute("GraphRAG Ingestion Pipeline")
  }

  // ---------------------------------------------------------------------------
  // Chunk Source Helpers
  // ---------------------------------------------------------------------------

  private def chunkSource(env: StreamExecutionEnvironment): DataStream[Chunk] = {
    sys.env.get("MSR_CHUNK_FILE") match {
      case Some(path) =>
        logger.info(s"Loading chunks from file: $path")
        env.readTextFile(path)
          .flatMap { line => parseChunkLine(line).toList }
          .name("chunk-file-source")

      case None =>
        logger.warn("MSR_CHUNK_FILE not set; using sample chunks for demo")
        val sampleChunks = createSampleChunks()
        env.fromCollection(sampleChunks).name("chunk-sample-source")
    }
  }

  private def parseChunkLine(line: String): Option[Chunk] = {
    val trimmed = line.trim
    if (trimmed.isEmpty || trimmed.startsWith("#")) {
      None
    } else {
      val parts = trimmed.split("\t", -1)
      if (parts.length < 10) {
        logger.warn(s"Malformed line (expected 10 columns, got ${parts.length}): ${line.take(100)}")
        None
      } else {
        Try {
          Chunk(
            chunkId = parts(0),
            docId = parts(1),
            chunkIx = parts(2).toInt,
            startPos = parts(3).toInt,
            endPos = parts(4).toInt,
            sectionPath = Option(parts(5)).filter(_.nonEmpty),
            text = parts(6),
            sourceUri = parts(7),
            contentHash = parts(8),
            language = Option(parts(9)).filter(_.nonEmpty)
          )
        }.toOption
      }
    }
  }

  /**
   * Create sample chunks representing MSR paper content for demo/testing.
   */
  private def createSampleChunks(): List[Chunk] = List(
    Chunk(
      chunkId = "chunk:msr2020-001-0",
      docId = "doc:msr2020-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 450,
      sectionPath = Some("abstract"),
      text = """Just-in-time defect prediction identifies buggy commits immediately after they are made.
               |This paper presents Commit2Vec, a deep learning approach that learns distributed representations
               |of code changes for defect prediction. We evaluate on the JITGIT dataset and achieve
               |AUC of 0.78, outperforming Random Forest baseline by 0.06.""".stripMargin.replaceAll("\n", " "),
      sourceUri = "file:///msr2020/commit2vec.pdf",
      contentHash = "hash:msr2020-001-0-v1",
      language = Some("en")
    ),
    Chunk(
      chunkId = "chunk:msr2021-045-0",
      docId = "doc:msr2021-045",
      chunkIx = 0,
      startPos = 0,
      endPos = 380,
      sectionPath = Some("introduction"),
      text = """Mining software repositories provides insights into software evolution and developer behavior.
               |Code review is a part of the development process that helps identify defects early.
               |Machine learning models can predict which code changes are likely to introduce bugs.""".stripMargin.replaceAll("\n", " "),
      sourceUri = "file:///msr2021/ml-review.pdf",
      contentHash = "hash:msr2021-045-0-v1",
      language = Some("en")
    ),
    Chunk(
      chunkId = "chunk:msr2022-012-0",
      docId = "doc:msr2022-012",
      chunkIx = 0,
      startPos = 0,
      endPos = 420,
      sectionPath = Some("methodology"),
      text = """Graph neural networks can model code structure for bug detection.
               |CodeGraph-JIT uses heterogeneous graphs to represent commits and achieves
               |state-of-the-art results on benchmark datasets. The approach improves over
               |traditional feature engineering methods by learning representations directly from code.""".stripMargin.replaceAll("\n", " "),
      sourceUri = "file:///msr2022/codegraph.pdf",
      contentHash = "hash:msr2022-012-0-v1",
      language = Some("en")
    ),
    Chunk(
      chunkId = "chunk:msr2019-078-0",
      docId = "doc:msr2019-078",
      chunkIx = 0,
      startPos = 0,
      endPos = 390,
      sectionPath = Some("related_work"),
      text = """Technical debt is a metaphor for the implied cost of additional rework caused by
               |choosing an easy solution now instead of using a better approach. Code smells are
               |indicators of technical debt and refactoring helps reduce maintenance effort.""".stripMargin.replaceAll("\n", " "),
      sourceUri = "file:///msr2019/techdebt.pdf",
      contentHash = "hash:msr2019-078-0-v1",
      language = Some("en")
    )
  )
}

/**
 * Flink function for concept extraction (heuristic + pattern-based).
 * 
 * Outputs: Concept nodes, MENTIONS edges, CO_OCCURS edges
 */
class ConceptExtractorFunction extends RichFlatMapFunction[Chunk, GraphWrite] with Logging {

  override def flatMap(chunk: Chunk, out: Collector[GraphWrite]): Unit = {
    // Extract concepts using heuristics
    val heuristicResults = ConceptExtractor.extractHeuristic(chunk)
    
    // Extract concepts using patterns (also produces relation candidates)
    val patternResults = ConceptExtractor.extractPatternBased(chunk)
    
    // Collect all unique concepts (using groupBy for Scala 2.12 compatibility)
    val allConcepts = (heuristicResults.map(_._2) ++ patternResults.map(_._2))
      .groupBy(_.conceptId)
      .values
      .map(_.head)
      .toList
    
    // Emit Concept nodes
    allConcepts.foreach { concept =>
      out.collect(UpsertNode(
        label = "Concept",
        id = concept.conceptId,
        props = Map(
          "lemma" -> concept.lemma,
          "origin" -> concept.origin,
          "surfaceForms" -> concept.surfaceForms.take(5).mkString(", ")
        )
      ))
    }
    
    // Emit MENTIONS edges
    val allMentions = heuristicResults.map(_._1) ++ patternResults.map(_._1)
    val uniqueMentions = allMentions.groupBy(m => (m.chunkId, m.conceptId)).values.map(_.head).toList
    uniqueMentions.foreach { mention =>
      out.collect(UpsertEdge(
        fromLabel = "Chunk",
        fromId = mention.chunkId,
        rel = "MENTIONS",
        toLabel = "Concept",
        toId = mention.conceptId,
        props = Map(
          "spanStart" -> mention.spanStart,
          "spanEnd" -> mention.spanEnd,
          "surface" -> mention.surface
        )
      ))
    }
    
    // Build and emit CO_OCCURS edges
    val coOccurs = RelationStage.buildCoOccurrences(chunk.chunkId, allConcepts)
    coOccurs.foreach { co =>
      out.collect(UpsertEdge(
        fromLabel = "Concept",
        fromId = co.a.conceptId,
        rel = "CO_OCCURS",
        toLabel = "Concept",
        toId = co.b.conceptId,
        props = Map(
          "inc" -> co.freq,
          "window" -> co.windowId
        )
      ))
    }
    
    // Emit pattern-based RELATES_TO edges (high confidence from patterns)
    patternResults.foreach { case (_, concept, candidateOpt) =>
      candidateOpt.foreach { candidate =>
        val predicate = concept.origin.split(":").lastOption.getOrElse("related_to")
        out.collect(UpsertEdge(
          fromLabel = "Concept",
          fromId = candidate.a.conceptId,
          rel = "RELATES_TO",
          toLabel = "Concept",
          toId = candidate.b.conceptId,
          props = Map(
            "predicate" -> predicate,
            "confidence" -> 0.9,
            "evidence" -> candidate.evidence.take(200),
            "source" -> "pattern"
          )
        ))
      }
    }
    
    logger.debug(s"Extracted ${allConcepts.size} concepts, ${allMentions.size} mentions from chunk ${chunk.chunkId}")
  }
}

/**
 * Flink function for LLM-based relation scoring.
 * 
 * Takes chunks, identifies relation candidates from co-occurring concepts,
 * scores them with Ollama, and outputs RELATES_TO edges.
 */
class RelationScorerFunction extends RichFlatMapFunction[Chunk, GraphWrite] with Logging with Serializable {
  
  @transient private var scorer: OllamaRelationScorer = _
  @transient private var minConfidence: Double = _

  override def open(parameters: Configuration): Unit = {
    val config = ConfigFactory.load()
    
    val ollamaConfig = OllamaConfig(
      endpoint = sys.env.getOrElse("OLLAMA_ENDPOINT", config.getString("ollama.endpoint")),
      model = sys.env.getOrElse("OLLAMA_MODEL", config.getString("ollama.model")),
      temperature = config.getDouble("ollama.temperature"),
      timeoutMs = config.getInt("ollama.timeoutMs")
    )
    
    scorer = new OllamaRelationScorer(new OllamaClient(ollamaConfig))
    minConfidence = config.getDouble("relation.llm.minConfidence")
    
    logger.info(s"Initialized OllamaRelationScorer with model: ${ollamaConfig.model}")
  }

  override def flatMap(chunk: Chunk, out: Collector[GraphWrite]): Unit = {
    // Extract concepts from chunk
    val heuristicResults = ConceptExtractor.extractHeuristic(chunk)
    val concepts = heuristicResults.map(_._2).groupBy(_.conceptId).values.map(_.head).toList
    
    if (concepts.size < 2) {
      return
    }
    
    // Build candidates from co-occurrences
    val coOccurs = RelationStage.buildCoOccurrences(chunk.chunkId, concepts)
    val candidates = RelationStage.buildCandidatesFromCoOccur(
      coOccurs,
      Map(chunk.chunkId -> chunk.text)
    )
    
    // Limit candidates to avoid too many LLM calls
    val limitedCandidates = candidates.take(5)
    
    if (limitedCandidates.nonEmpty) {
      logger.debug(s"Scoring ${limitedCandidates.size} relation candidates for chunk ${chunk.chunkId}")
      
      // Score with LLM
      val scoredRelations = scorer.scoreRelations(limitedCandidates)
      val filtered = RelationStage.filterByConfidence(scoredRelations, minConfidence)
      val merged = RelationStage.mergeRelations(filtered)
      
      // Emit RELATES_TO edges
      merged.foreach { rel =>
        out.collect(UpsertEdge(
          fromLabel = "Concept",
          fromId = rel.a.conceptId,
          rel = "RELATES_TO",
          toLabel = "Concept",
          toId = rel.b.conceptId,
          props = Map(
            "predicate" -> rel.predicate,
            "confidence" -> rel.confidence,
            "evidence" -> rel.evidence.take(200),
            "ref" -> rel.ref,
            "source" -> "llm"
          )
        ))
      }
      
      logger.debug(s"Emitted ${merged.size} RELATES_TO edges from LLM scoring")
    }
  }
}

/**
 * Neo4j sink for GraphWrite operations with idempotent MERGE semantics.
 */
final class Neo4jGraphSink(uri: String, user: String, password: String)
  extends RichSinkFunction[GraphWrite] with Serializable with Logging {

  @transient private var driver: Driver = _

  override def open(parameters: Configuration): Unit = {
    driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))
    logger.info(s"Neo4j sink connected to $uri")
  }

  override def close(): Unit = {
    if (driver != null) {
      driver.close()
      logger.info("Neo4j sink connection closed")
    }
  }

  override def invoke(value: GraphWrite, context: SinkFunction.Context): Unit = {
    val session = driver.session()
    try {
      value match {
        case node: UpsertNode =>
          session.writeTransaction { tx =>
            val (query, params) = buildNodeQuery(node)
            tx.run(query, params).consume()
            ()
          }

        case edge: UpsertEdge =>
          session.writeTransaction { tx =>
            val (query, params) = buildEdgeQuery(edge)
            tx.run(query, params).consume()
            ()
          }
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to write to Neo4j: ${e.getMessage}", e)
        throw e
    } finally {
      session.close()
    }
  }

  private def idKeyForLabel(label: String): String = label match {
    case "Chunk" => "chunkId"
    case "Concept" => "conceptId"
    case _ => "id"
  }

  private def buildNodeQuery(node: UpsertNode): (String, JMap[String, Object]) = {
    val idKey = idKeyForLabel(node.label)
    val query = s"""MERGE (n:${node.label} {$idKey: $$id}) SET n += $$props"""
    
    val params = new JHashMap[String, Object]()
    params.put("id", node.id)
    params.put("props", scalaMapToJava(node.props))
    
    (query, params)
  }

  private def buildEdgeQuery(edge: UpsertEdge): (String, JMap[String, Object]) = {
    val fromIdKey = idKeyForLabel(edge.fromLabel)
    val toIdKey = idKeyForLabel(edge.toLabel)

    val (query, params) = edge.rel match {
      case "CO_OCCURS" =>
        val inc = edge.props.get("inc") match {
          case Some(n: Number) => n.longValue()
          case Some(i: Int) => i.toLong
          case Some(l: Long) => l
          case _ => 1L
        }
        
        val q = s"""
          |MERGE (from:${edge.fromLabel} {$fromIdKey: $$fromId})
          |MERGE (to:${edge.toLabel} {$toIdKey: $$toId})
          |MERGE (from)-[r:${edge.rel}]->(to)
          |SET r.freq = coalesce(r.freq, 0) + $$inc
          |""".stripMargin
        
        val p = new JHashMap[String, Object]()
        p.put("fromId", edge.fromId)
        p.put("toId", edge.toId)
        p.put("inc", java.lang.Long.valueOf(inc))
        (q, p)

      case _ =>
        val q = s"""
          |MERGE (from:${edge.fromLabel} {$fromIdKey: $$fromId})
          |MERGE (to:${edge.toLabel} {$toIdKey: $$toId})
          |MERGE (from)-[r:${edge.rel}]->(to)
          |SET r += $$props
          |""".stripMargin
        
        val p = new JHashMap[String, Object]()
        p.put("fromId", edge.fromId)
        p.put("toId", edge.toId)
        p.put("props", scalaMapToJava(edge.props))
        (q, p)
    }
    
    (query, params)
  }

  private def scalaMapToJava(m: Map[String, Any]): JMap[String, Object] = {
    val j = new JHashMap[String, Object]()
    m.foreach {
      case (k, v: String) => j.put(k, v)
      case (k, v: Int) => j.put(k, java.lang.Integer.valueOf(v))
      case (k, v: Long) => j.put(k, java.lang.Long.valueOf(v))
      case (k, v: Double) => j.put(k, java.lang.Double.valueOf(v))
      case (k, v: Float) => j.put(k, java.lang.Float.valueOf(v))
      case (k, v: Boolean) => j.put(k, java.lang.Boolean.valueOf(v))
      case (k, v: Number) => j.put(k, v)
      case (k, v) => j.put(k, v.toString)
    }
    j
  }
}
