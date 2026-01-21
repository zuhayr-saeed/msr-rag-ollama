package edu.yourorg.msr.graphrag.api

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.neo4j.GraphNeo4jClient

import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._

import org.neo4j.driver.{AuthTokens, GraphDatabase, SessionConfig, AccessMode}
import scala.collection.JavaConverters._

/**
 * Evidence response model
 */
case class EvidenceResponse(
  evidenceId: String,
  paperId: Option[String],
  chunkId: String,
  text: String,
  docRef: DocRef
)

case class DocRef(
  title: String,
  year: Option[Int],
  url: String
)

object EvidenceResponse {
  implicit val docRefEncoder: Encoder[DocRef] = deriveEncoder
  implicit val encoder: Encoder[EvidenceResponse] = deriveEncoder
}

/**
 * EvidenceService retrieves stored evidence snippets that support claims in the graph.
 * 
 * Evidence is tied to chunks and provides auditable citations for relations.
 */
class EvidenceService(neo4jClient: GraphNeo4jClient) extends Logging {

  private val driver = {
    val uri = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
    val user = sys.env.getOrElse("NEO4J_USER", "neo4j")
    val pass = sys.env.getOrElse("NEO4J_PASS", "test123")
    GraphDatabase.driver(uri, AuthTokens.basic(user, pass))
  }

  /**
   * Get evidence by ID.
   * 
   * Evidence ID format: "evid:<chunkId>" or just "<chunkId>"
   */
  def getEvidence(evidenceId: String): String = {
    val chunkId = evidenceId.stripPrefix("evid:")
    
    logger.info(s"Retrieving evidence for: $chunkId")
    
    try {
      val session = driver.session(
        SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()
      )
      
      try {
        val cypher = """
          |MATCH (ch:Chunk {chunkId: $chunkId})
          |RETURN ch.chunkId AS chunkId,
          |       ch.docId AS docId,
          |       ch.text AS text,
          |       ch.sourceUri AS sourceUri,
          |       ch.sectionPath AS sectionPath
          |""".stripMargin
        
        val params = new java.util.HashMap[String, Object]()
        params.put("chunkId", chunkId)
        
        val result = session.run(cypher, params)
        
        if (result.hasNext) {
          val record = result.next()
          
          val response = EvidenceResponse(
            evidenceId = evidenceId,
            paperId = Option(record.get("docId")).filterNot(_.isNull).map(_.asString()),
            chunkId = chunkId,
            text = Option(record.get("text")).filterNot(_.isNull).map(_.asString()).getOrElse(""),
            docRef = DocRef(
              title = Option(record.get("sectionPath")).filterNot(_.isNull).map(_.asString()).getOrElse("Unknown"),
              year = None, // Could be extracted from docId or metadata
              url = Option(record.get("sourceUri")).filterNot(_.isNull).map(_.asString()).getOrElse("")
            )
          )
          
          response.asJson.noSpaces
        } else {
          errorResponse("Not Found", s"Evidence not found: $evidenceId")
        }
        
      } finally {
        session.close()
      }
      
    } catch {
      case e: Exception =>
        logger.error(s"Error retrieving evidence: ${e.getMessage}", e)
        errorResponse("Internal Server Error", e.getMessage)
    }
  }

  /**
   * Get evidence for a relation (the snippet that supports it).
   */
  def getRelationEvidence(fromConceptId: String, toConceptId: String, predicate: String): String = {
    logger.info(s"Retrieving evidence for relation: $fromConceptId -[$predicate]-> $toConceptId")
    
    try {
      val session = driver.session(
        SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()
      )
      
      try {
        val cypher = """
          |MATCH (a:Concept {conceptId: $fromId})-[r:RELATES_TO {predicate: $pred}]->(b:Concept {conceptId: $toId})
          |RETURN r.evidence AS evidence, r.ref AS ref, r.confidence AS confidence
          |""".stripMargin
        
        val params = new java.util.HashMap[String, Object]()
        params.put("fromId", fromConceptId)
        params.put("toId", toConceptId)
        params.put("pred", predicate)
        
        val result = session.run(cypher, params)
        
        if (result.hasNext) {
          val record = result.next()
          
          Json.obj(
            "fromConceptId" -> Json.fromString(fromConceptId),
            "toConceptId" -> Json.fromString(toConceptId),
            "predicate" -> Json.fromString(predicate),
            "evidence" -> Json.fromString(
              Option(record.get("evidence")).filterNot(_.isNull).map(_.asString()).getOrElse("")
            ),
            "ref" -> Json.fromString(
              Option(record.get("ref")).filterNot(_.isNull).map(_.asString()).getOrElse("")
            ),
            "confidence" -> Json.fromDoubleOrNull(
              Option(record.get("confidence")).filterNot(_.isNull).map(_.asDouble()).getOrElse(0.0)
            )
          ).noSpaces
        } else {
          errorResponse("Not Found", "Relation evidence not found")
        }
        
      } finally {
        session.close()
      }
      
    } catch {
      case e: Exception =>
        logger.error(s"Error retrieving relation evidence: ${e.getMessage}", e)
        errorResponse("Internal Server Error", e.getMessage)
    }
  }

  private def errorResponse(error: String, message: String): String = {
    Json.obj(
      "error" -> Json.fromString(error),
      "message" -> Json.fromString(message)
    ).noSpaces
  }
}

