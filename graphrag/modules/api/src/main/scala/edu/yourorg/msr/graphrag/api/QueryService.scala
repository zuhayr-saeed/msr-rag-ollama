package edu.yourorg.msr.graphrag.api

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.neo4j.GraphNeo4jClient

import io.circe._
import io.circe.parser._
import io.circe.generic.semiauto._
import io.circe.syntax._

import org.neo4j.driver.{Record, Value}
import scala.collection.JavaConverters._
import scala.util.Try

/**
 * Query request model
 */
case class QueryRequest(
  query: String,
  timeRange: Option[TimeRange] = None,
  constraints: Option[QueryConstraints] = None,
  output: Option[OutputOptions] = None
)

case class TimeRange(from: Int, to: Int)
case class QueryConstraints(
  datasets: Option[List[String]] = None,
  baselines: Option[List[String]] = None,
  concepts: Option[List[String]] = None
)
case class OutputOptions(
  groupBy: Option[List[String]] = None,
  metrics: Option[List[String]] = None,
  topKPerGroup: Option[Int] = None,
  includeCitations: Option[Boolean] = None
)

/**
 * Query response models
 */
case class QueryResponse(
  mode: String,
  summary: String,
  results: List[QueryResult],
  evidenceAvailable: Boolean,
  traceId: String
)

case class QueryResult(
  conceptId: String,
  lemma: String,
  relatedConcepts: List[RelatedConcept],
  chunks: List[ChunkResult]
)

case class RelatedConcept(
  conceptId: String,
  lemma: String,
  predicate: String,
  confidence: Double
)

case class ChunkResult(
  chunkId: String,
  docId: String,
  text: String,
  sourceUri: String
)

object QueryRequest {
  implicit val timeRangeDecoder: Decoder[TimeRange] = deriveDecoder
  implicit val constraintsDecoder: Decoder[QueryConstraints] = deriveDecoder
  implicit val outputDecoder: Decoder[OutputOptions] = deriveDecoder
  implicit val decoder: Decoder[QueryRequest] = deriveDecoder
}

object QueryResponse {
  implicit val relatedConceptEncoder: Encoder[RelatedConcept] = deriveEncoder
  implicit val chunkResultEncoder: Encoder[ChunkResult] = deriveEncoder
  implicit val queryResultEncoder: Encoder[QueryResult] = deriveEncoder
  implicit val encoder: Encoder[QueryResponse] = deriveEncoder
}

/**
 * QueryService handles semantic queries against the GraphRAG knowledge graph.
 * 
 * It translates natural language queries into Cypher patterns, executes them
 * against Neo4j, and synthesizes results with evidence citations.
 */
class QueryService(neo4jClient: GraphNeo4jClient) extends Logging {

  import org.neo4j.driver.{AuthTokens, GraphDatabase, Session, SessionConfig, AccessMode}
  
  private val driver = {
    val uri = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
    val user = sys.env.getOrElse("NEO4J_USER", "neo4j")
    val pass = sys.env.getOrElse("NEO4J_PASS", "test123")
    GraphDatabase.driver(uri, AuthTokens.basic(user, pass))
  }

  /**
   * Handle a query request and return JSON response.
   */
  def handleQuery(requestBody: String): String = {
    val traceId = s"trace-${System.currentTimeMillis()}"
    
    parse(requestBody).flatMap(_.as[QueryRequest]) match {
      case Left(error) =>
        logger.warn(s"Failed to parse query request: $error")
        errorResponse("Bad Request", s"Invalid JSON: ${error.getMessage}", traceId)
        
      case Right(request) =>
        logger.info(s"Processing query: ${request.query.take(100)}...")
        processQuery(request, traceId)
    }
  }

  private def processQuery(request: QueryRequest, traceId: String): String = {
    try {
      // Extract key terms from query
      val queryTerms = extractQueryTerms(request.query)
      
      if (queryTerms.isEmpty) {
        return errorResponse("Unprocessable Entity", "Could not extract concepts from query", traceId)
      }
      
      // Build and execute Cypher query
      val results = executeGraphQuery(queryTerms, request.constraints)
      
      // Build summary
      val summary = if (results.isEmpty) {
        "No matching concepts found in the knowledge graph."
      } else {
        s"Found ${results.size} concepts matching your query with ${results.flatMap(_.relatedConcepts).size} related concepts."
      }
      
      val response = QueryResponse(
        mode = "sync",
        summary = summary,
        results = results,
        evidenceAvailable = results.nonEmpty,
        traceId = traceId
      )
      
      response.asJson.noSpaces
      
    } catch {
      case e: Exception =>
        logger.error(s"Error processing query: ${e.getMessage}", e)
        errorResponse("Internal Server Error", e.getMessage, traceId)
    }
  }

  /**
   * Extract key terms from natural language query.
   */
  private def extractQueryTerms(query: String): List[String] = {
    val stopWords = Set(
      "what", "which", "how", "where", "when", "why", "who",
      "the", "a", "an", "is", "are", "was", "were", "be", "been",
      "have", "has", "had", "do", "does", "did", "will", "would",
      "could", "should", "can", "may", "might", "must",
      "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
      "by", "from", "as", "since", "compared", "vs", "versus"
    )
    
    query
      .toLowerCase
      .replaceAll("[^a-z0-9\\s]", " ")
      .split("\\s+")
      .filter(_.length >= 3)
      .filterNot(stopWords.contains)
      .distinct
      .toList
      .take(10)
  }

  /**
   * Execute Cypher query to find matching concepts and relations.
   */
  private def executeGraphQuery(
    terms: List[String],
    constraints: Option[QueryConstraints]
  ): List[QueryResult] = {
    
    val session = driver.session(
      SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()
    )
    
    try {
      // Build pattern for matching concepts
      val termPattern = terms.map(t => s"(?i).*$t.*").mkString("|")
      
      val cypher = """
        |MATCH (c:Concept)
        |WHERE c.lemma =~ $pattern
        |OPTIONAL MATCH (c)-[r:RELATES_TO]->(related:Concept)
        |OPTIONAL MATCH (c)<-[:MENTIONS]-(chunk:Chunk)
        |RETURN c.conceptId AS conceptId,
        |       c.lemma AS lemma,
        |       collect(DISTINCT {
        |         conceptId: related.conceptId,
        |         lemma: related.lemma,
        |         predicate: r.predicate,
        |         confidence: r.confidence
        |       }) AS relations,
        |       collect(DISTINCT {
        |         chunkId: chunk.chunkId,
        |         docId: chunk.docId,
        |         text: chunk.text,
        |         sourceUri: chunk.sourceUri
        |       }) AS chunks
        |LIMIT 20
        |""".stripMargin
      
      val params = new java.util.HashMap[String, Object]()
      params.put("pattern", termPattern)
      
      val result = session.run(cypher, params)
      val records = result.list().asScala.toList
      
      records.flatMap { record =>
        val conceptId = safeGetString(record, "conceptId")
        val lemma = safeGetString(record, "lemma")
        
        if (conceptId.nonEmpty && lemma.nonEmpty) {
          val relations = parseRelations(record.get("relations"))
          val chunks = parseChunks(record.get("chunks"))
          
          Some(QueryResult(
            conceptId = conceptId,
            lemma = lemma,
            relatedConcepts = relations,
            chunks = chunks.take(3) // Limit chunks per concept
          ))
        } else {
          None
        }
      }
      
    } finally {
      session.close()
    }
  }

  private def safeGetString(record: Record, key: String): String = {
    try {
      val value = record.get(key)
      if (value.isNull) "" else value.asString()
    } catch {
      case _: Exception => ""
    }
  }

  private def parseRelations(value: Value): List[RelatedConcept] = {
    try {
      value.asList().asScala.toList.flatMap { item =>
        val map = item.asInstanceOf[java.util.Map[String, Any]].asScala
        val conceptId = extractString(map, "conceptId")
        val lemma = extractString(map, "lemma")
        val predicate = extractString(map, "predicate").filter(_.nonEmpty).getOrElse("related_to")
        val confidence = extractDouble(map, "confidence").getOrElse(0.5)
        
        for {
          cid <- conceptId if cid.nonEmpty && cid != "null"
          lem <- lemma if lem.nonEmpty && lem != "null"
        } yield RelatedConcept(cid, lem, predicate, confidence)
      }.filter(r => r.conceptId.nonEmpty && r.conceptId != "null")
    } catch {
      case _: Exception => Nil
    }
  }

  private def parseChunks(value: Value): List[ChunkResult] = {
    try {
      value.asList().asScala.toList.flatMap { item =>
        val map = item.asInstanceOf[java.util.Map[String, Any]].asScala
        val chunkId = extractString(map, "chunkId")
        val docId = extractString(map, "docId").getOrElse("")
        val text = extractString(map, "text").getOrElse("")
        val sourceUri = extractString(map, "sourceUri").getOrElse("")
        
        chunkId.filter(cid => cid.nonEmpty && cid != "null")
          .map(cid => ChunkResult(cid, docId, text.take(500), sourceUri))
      }.filter(c => c.chunkId.nonEmpty && c.chunkId != "null")
    } catch {
      case _: Exception => Nil
    }
  }

  private def extractString(map: scala.collection.mutable.Map[String, Any], key: String): Option[String] = {
    map.get(key).flatMap {
      case null => None
      case s: String if s.nonEmpty && s != "null" => Some(s)
      case other if other != null => 
        val str = other.toString
        if (str.nonEmpty && str != "null") Some(str) else None
      case _ => None
    }
  }

  private def extractDouble(map: scala.collection.mutable.Map[String, Any], key: String): Option[Double] = {
    map.get(key).flatMap {
      case null => None
      case d: java.lang.Double => Some(d.doubleValue())
      case d: Double => Some(d)
      case n: Number => Some(n.doubleValue())
      case s: String => Try(s.toDouble).toOption
      case other if other != null => Try(other.toString.toDouble).toOption
      case _ => None
    }
  }

  private def errorResponse(error: String, message: String, traceId: String): String = {
    Json.obj(
      "error" -> Json.fromString(error),
      "message" -> Json.fromString(message),
      "traceId" -> Json.fromString(traceId)
    ).noSpaces
  }
}

