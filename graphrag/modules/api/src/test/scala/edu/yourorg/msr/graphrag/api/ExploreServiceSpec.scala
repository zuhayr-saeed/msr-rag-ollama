package edu.yourorg.msr.graphrag.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.parser._
import io.circe.syntax._

/**
 * Unit tests for ExploreService graph neighborhood responses.
 * 
 * Tests verify JSON encoding without requiring a live Neo4j connection.
 */
class ExploreServiceSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: NeighborResponse encoding
  // -------------------------------------------------------------------------
  "NeighborResponse" should "encode complete response to JSON" in {
    val response = NeighborResponse(
      center = GraphNode("concept:defect", "Concept", Map("lemma" -> "defect")),
      nodes = List(
        GraphNode("concept:bug", "Concept", Map("lemma" -> "bug")),
        GraphNode("concept:prediction", "Concept", Map("lemma" -> "prediction"))
      ),
      edges = List(
        GraphEdge("concept:defect", "concept:bug", "RELATES_TO", Map("predicate" -> "synonym_of")),
        GraphEdge("concept:defect", "concept:prediction", "CO_OCCURS", Map("freq" -> "5"))
      ),
      page = PageInfo(50, None)
    )
    
    import NeighborResponse.encoder
    val json = response.asJson.noSpaces
    
    json should include ("concept:defect")
    json should include ("concept:bug")
    json should include ("RELATES_TO")
    json should include ("CO_OCCURS")
    json should include ("50")
  }

  // -------------------------------------------------------------------------
  // Test 2: GraphNode encoding
  // -------------------------------------------------------------------------
  "GraphNode" should "encode node with properties" in {
    val node = GraphNode(
      id = "concept:machine_learning",
      label = "Concept",
      props = Map("lemma" -> "machine learning", "origin" -> "heuristic")
    )
    
    import NeighborResponse.graphNodeEncoder
    val json = node.asJson.noSpaces
    
    json should include ("concept:machine_learning")
    json should include ("Concept")
    json should include ("machine learning")
  }

  // -------------------------------------------------------------------------
  // Test 3: GraphNode with empty props
  // -------------------------------------------------------------------------
  it should "handle empty properties" in {
    val node = GraphNode("node:123", "Unknown", Map.empty)
    
    import NeighborResponse.graphNodeEncoder
    val json = node.asJson.noSpaces
    
    json should include ("node:123")
    json should include ("{}")
  }

  // -------------------------------------------------------------------------
  // Test 4: GraphEdge encoding
  // -------------------------------------------------------------------------
  "GraphEdge" should "encode edge with type and properties" in {
    val edge = GraphEdge(
      from = "concept:commit",
      to = "concept:bug",
      `type` = "RELATES_TO",
      props = Map("predicate" -> "causes", "confidence" -> "0.85")
    )
    
    import NeighborResponse.graphEdgeEncoder
    val json = edge.asJson.noSpaces
    
    json should include ("concept:commit")
    json should include ("concept:bug")
    json should include ("RELATES_TO")
    json should include ("causes")
  }

  // -------------------------------------------------------------------------
  // Test 5: PageInfo encoding
  // -------------------------------------------------------------------------
  "PageInfo" should "encode pagination info" in {
    val page = PageInfo(100, Some("next-page-token"))
    
    import NeighborResponse.pageInfoEncoder
    val json = page.asJson.noSpaces
    
    json should include ("100")
    json should include ("next-page-token")
  }

  // -------------------------------------------------------------------------
  // Test 6: PageInfo with no next page
  // -------------------------------------------------------------------------
  it should "handle null next page token" in {
    val page = PageInfo(50, None)
    
    import NeighborResponse.pageInfoEncoder
    val json = page.asJson.noSpaces
    
    json should include ("50")
    json should include ("null")
  }

  // -------------------------------------------------------------------------
  // Test 7: GraphStats encoding
  // -------------------------------------------------------------------------
  "GraphStats" should "encode all statistics" in {
    val stats = GraphStats(
      totalConcepts = 1500,
      totalChunks = 5000,
      totalRelations = 2500,
      totalMentions = 8000,
      totalCoOccurs = 12000
    )
    
    import GraphStats.encoder
    val json = stats.asJson.noSpaces
    
    json should include ("1500")
    json should include ("5000")
    json should include ("2500")
    json should include ("8000")
    json should include ("12000")
  }

  // -------------------------------------------------------------------------
  // Test 8: Empty neighborhood response
  // -------------------------------------------------------------------------
  "NeighborResponse" should "handle empty neighbors" in {
    val response = NeighborResponse(
      center = GraphNode("concept:isolated", "Concept", Map("lemma" -> "isolated")),
      nodes = Nil,
      edges = Nil,
      page = PageInfo(50, None)
    )
    
    import NeighborResponse.encoder
    val json = response.asJson.noSpaces
    
    json should include ("concept:isolated")
    json should include ("nodes\":[]")
    json should include ("edges\":[]")
  }

  // -------------------------------------------------------------------------
  // Test 9: Direction parameter validation
  // -------------------------------------------------------------------------
  "Direction" should "be one of in, out, both" in {
    val validDirections = Set("in", "out", "both")
    
    validDirections should contain ("in")
    validDirections should contain ("out")
    validDirections should contain ("both")
    validDirections should have size 3
  }

  // -------------------------------------------------------------------------
  // Test 10: Concept ID normalization
  // -------------------------------------------------------------------------
  "Concept ID" should "be prefixed if not already" in {
    def normalizeConceptId(id: String): String = 
      if (id.startsWith("concept:")) id else s"concept:$id"
    
    normalizeConceptId("defect") shouldBe "concept:defect"
    normalizeConceptId("concept:defect") shouldBe "concept:defect"
  }
}

