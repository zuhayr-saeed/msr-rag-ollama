package edu.yourorg.msr.graphrag.api

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.neo4j.GraphNeo4jClient

import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._

import org.neo4j.driver.{AuthTokens, GraphDatabase, SessionConfig, AccessMode}
import scala.collection.JavaConverters._

/**
 * Graph exploration response models
 */
case class NeighborResponse(
  center: GraphNode,
  nodes: List[GraphNode],
  edges: List[GraphEdge],
  page: PageInfo
)

case class GraphNode(
  id: String,
  label: String,
  props: Map[String, String]
)

case class GraphEdge(
  from: String,
  to: String,
  `type`: String,
  props: Map[String, String]
)

case class PageInfo(
  limit: Int,
  nextPageToken: Option[String]
)

case class GraphStats(
  totalConcepts: Long,
  totalChunks: Long,
  totalRelations: Long,
  totalMentions: Long,
  totalCoOccurs: Long
)

object NeighborResponse {
  implicit val pageInfoEncoder: Encoder[PageInfo] = deriveEncoder
  implicit val graphNodeEncoder: Encoder[GraphNode] = deriveEncoder
  implicit val graphEdgeEncoder: Encoder[GraphEdge] = deriveEncoder
  implicit val encoder: Encoder[NeighborResponse] = deriveEncoder
}

object GraphStats {
  implicit val encoder: Encoder[GraphStats] = deriveEncoder
}

/**
 * ExploreService enables graph neighborhood exploration.
 * 
 * Provides interactive exploration of concepts and their relationships
 * for UI graph viewers and programmatic analyses.
 */
class ExploreService(neo4jClient: GraphNeo4jClient) extends Logging {

  private val driver = {
    val uri = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
    val user = sys.env.getOrElse("NEO4J_USER", "neo4j")
    val pass = sys.env.getOrElse("NEO4J_PASS", "test123")
    GraphDatabase.driver(uri, AuthTokens.basic(user, pass))
  }

  /**
   * Get neighbors of a concept node.
   * 
   * @param conceptId The concept to explore
   * @param direction "in", "out", or "both"
   * @param depth How many hops to traverse (1-3)
   * @param limit Maximum nodes to return
   * @param edgeTypes Filter by edge types (empty = all)
   */
  def getNeighbors(
    conceptId: String,
    direction: String,
    depth: Int,
    limit: Int,
    edgeTypes: List[String]
  ): String = {
    
    val normalizedId = if (conceptId.startsWith("concept:")) conceptId else s"concept:$conceptId"
    logger.info(s"Getting neighbors for $normalizedId (direction=$direction, depth=$depth)")
    
    try {
      val session = driver.session(
        SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()
      )
      
      try {
        // Build edge type filter
        val edgeFilter = if (edgeTypes.isEmpty) {
          "RELATES_TO|CO_OCCURS|MENTIONS"
        } else {
          edgeTypes.mkString("|")
        }
        
        // Build direction pattern
        val pathPattern = direction.toLowerCase match {
          case "in" => s"<-[r:$edgeFilter*1..$depth]-"
          case "out" => s"-[r:$edgeFilter*1..$depth]->"
          case _ => s"-[r:$edgeFilter*1..$depth]-"
        }
        
        val cypher = s"""
          |MATCH (center:Concept {conceptId: $$conceptId})
          |OPTIONAL MATCH path = (center)$pathPattern(neighbor)
          |WHERE neighbor IS NOT NULL
          |WITH center, neighbor, relationships(path) AS rels
          |UNWIND rels AS rel
          |RETURN center.conceptId AS centerId,
          |       center.lemma AS centerLemma,
          |       collect(DISTINCT {
          |         id: CASE 
          |           WHEN neighbor:Concept THEN neighbor.conceptId 
          |           WHEN neighbor:Chunk THEN neighbor.chunkId 
          |           ELSE id(neighbor) 
          |         END,
          |         label: labels(neighbor)[0],
          |         lemma: neighbor.lemma,
          |         text: substring(neighbor.text, 0, 100)
          |       })[0..$limit] AS neighbors,
          |       collect(DISTINCT {
          |         from: CASE 
          |           WHEN startNode(rel):Concept THEN startNode(rel).conceptId 
          |           WHEN startNode(rel):Chunk THEN startNode(rel).chunkId 
          |           ELSE toString(id(startNode(rel))) 
          |         END,
          |         to: CASE 
          |           WHEN endNode(rel):Concept THEN endNode(rel).conceptId 
          |           WHEN endNode(rel):Chunk THEN endNode(rel).chunkId 
          |           ELSE toString(id(endNode(rel))) 
          |         END,
          |         type: type(rel),
          |         predicate: rel.predicate,
          |         confidence: rel.confidence
          |       })[0..$limit] AS edges
          |LIMIT 1
          |""".stripMargin
        
        val params = new java.util.HashMap[String, Object]()
        params.put("conceptId", normalizedId)
        params.put("limit", java.lang.Integer.valueOf(limit))
        
        val result = session.run(cypher, params)
        
        if (result.hasNext) {
          val record = result.next()
          
          val centerId = Option(record.get("centerId")).filterNot(_.isNull).map(_.asString()).getOrElse(normalizedId)
          val centerLemma = Option(record.get("centerLemma")).filterNot(_.isNull).map(_.asString()).getOrElse("")
          
          val neighborNodes = parseNeighborNodes(record.get("neighbors"))
          val graphEdges = parseEdges(record.get("edges"))
          
          val response = NeighborResponse(
            center = GraphNode(centerId, "Concept", Map("lemma" -> centerLemma)),
            nodes = neighborNodes,
            edges = graphEdges,
            page = PageInfo(limit, None)
          )
          
          response.asJson.noSpaces
        } else {
          // Try to at least return the center node
          getCenterOnly(normalizedId)
        }
        
      } finally {
        session.close()
      }
      
    } catch {
      case e: Exception =>
        logger.error(s"Error exploring graph: ${e.getMessage}", e)
        errorResponse("Internal Server Error", e.getMessage)
    }
  }

  private def getCenterOnly(conceptId: String): String = {
    val session = driver.session(
      SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()
    )
    
    try {
      val cypher = """
        |MATCH (c:Concept {conceptId: $conceptId})
        |RETURN c.conceptId AS id, c.lemma AS lemma
        |""".stripMargin
      
      val params = new java.util.HashMap[String, Object]()
      params.put("conceptId", conceptId)
      
      val result = session.run(cypher, params)
      
      if (result.hasNext) {
        val record = result.next()
        val id = record.get("id").asString()
        val lemma = Option(record.get("lemma")).filterNot(_.isNull).map(_.asString()).getOrElse("")
        
        NeighborResponse(
          center = GraphNode(id, "Concept", Map("lemma" -> lemma)),
          nodes = Nil,
          edges = Nil,
          page = PageInfo(50, None)
        ).asJson.noSpaces
      } else {
        errorResponse("Not Found", s"Concept not found: $conceptId")
      }
    } finally {
      session.close()
    }
  }

  private def parseNeighborNodes(value: org.neo4j.driver.Value): List[GraphNode] = {
    try {
      value.asList().asScala.toList.flatMap { item =>
        val map = item.asInstanceOf[java.util.Map[String, Any]].asScala
        val id = extractStr(map, "id")
        val label = extractStr(map, "label").getOrElse("Node")
        val lemma = extractStr(map, "lemma").getOrElse("")
        val text = extractStr(map, "text").getOrElse("")
        
        id.filter(s => s.nonEmpty && s != "null").map { nodeId =>
          GraphNode(
            id = nodeId,
            label = if (label.nonEmpty && label != "null") label else "Node",
            props = Map("lemma" -> lemma, "text" -> text).filter { case (_, v) => v.nonEmpty && v != "null" }
          )
        }
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error parsing neighbor nodes: ${e.getMessage}")
        Nil
    }
  }

  private def parseEdges(value: org.neo4j.driver.Value): List[GraphEdge] = {
    try {
      value.asList().asScala.toList.flatMap { item =>
        val map = item.asInstanceOf[java.util.Map[String, Any]].asScala
        val from = extractStr(map, "from")
        val to = extractStr(map, "to")
        val edgeType = extractStr(map, "type").getOrElse("RELATED")
        val predicate = extractStr(map, "predicate").getOrElse("")
        val confidence = extractStr(map, "confidence").getOrElse("")
        
        for {
          f <- from if f.nonEmpty && f != "null"
          t <- to if t.nonEmpty && t != "null"
        } yield GraphEdge(
          from = f,
          to = t,
          `type` = if (edgeType.nonEmpty && edgeType != "null") edgeType else "RELATED",
          props = Map("predicate" -> predicate, "confidence" -> confidence).filter { case (_, v) => v.nonEmpty && v != "null" }
        )
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error parsing edges: ${e.getMessage}")
        Nil
    }
  }

  private def extractStr(map: scala.collection.mutable.Map[String, Any], key: String): Option[String] = {
    map.get(key).flatMap {
      case null => None
      case s: String if s.nonEmpty && s != "null" => Some(s)
      case other if other != null => 
        val str = other.toString
        if (str.nonEmpty && str != "null") Some(str) else None
      case _ => None
    }
  }

  /**
   * Get graph statistics.
   */
  def getStatistics(): String = {
    try {
      val session = driver.session(
        SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()
      )
      
      try {
        val stats = GraphStats(
          totalConcepts = countNodes(session, "Concept"),
          totalChunks = countNodes(session, "Chunk"),
          totalRelations = countEdges(session, "RELATES_TO"),
          totalMentions = countEdges(session, "MENTIONS"),
          totalCoOccurs = countEdges(session, "CO_OCCURS")
        )
        
        stats.asJson.noSpaces
        
      } finally {
        session.close()
      }
      
    } catch {
      case e: Exception =>
        logger.error(s"Error getting statistics: ${e.getMessage}", e)
        errorResponse("Internal Server Error", e.getMessage)
    }
  }

  private def countNodes(session: org.neo4j.driver.Session, label: String): Long = {
    try {
      val result = session.run(s"MATCH (n:$label) RETURN count(n) AS cnt")
      if (result.hasNext) result.next().get("cnt").asLong() else 0L
    } catch {
      case _: Exception => 0L
    }
  }

  private def countEdges(session: org.neo4j.driver.Session, relType: String): Long = {
    try {
      val result = session.run(s"MATCH ()-[r:$relType]->() RETURN count(r) AS cnt")
      if (result.hasNext) result.next().get("cnt").asLong() else 0L
    } catch {
      case _: Exception => 0L
    }
  }

  private def errorResponse(error: String, message: String): String = {
    Json.obj(
      "error" -> Json.fromString(error),
      "message" -> Json.fromString(message)
    ).noSpaces
  }
}

