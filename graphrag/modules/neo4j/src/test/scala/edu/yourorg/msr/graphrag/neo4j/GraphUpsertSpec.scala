package edu.yourorg.msr.graphrag.neo4j

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import edu.yourorg.msr.graphrag.core.model._

/**
 * Unit tests for the GraphUpsert Cypher generation.
 * 
 * Tests verify that GraphWrite operations are correctly
 * translated to parameterized Cypher commands for Neo4j.
 */
class GraphUpsertSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: Concept node upsert generates correct Cypher
  // -------------------------------------------------------------------------
  "GraphUpsert" should "generate correct Cypher for Concept nodes" in {
    val upsert = UpsertNode(
      label = "Concept",
      id = "concept:test",
      props = Map("lemma" -> "test concept", "origin" -> "heuristic")
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("MERGE")
    commands.head.text should include ("Concept")
    commands.head.text should include ("conceptId")
    commands.head.params("id") shouldBe "concept:test"
  }

  // -------------------------------------------------------------------------
  // Test 2: Chunk node upsert generates correct Cypher
  // -------------------------------------------------------------------------
  it should "generate correct Cypher for Chunk nodes" in {
    val upsert = UpsertNode(
      label = "Chunk",
      id = "chunk:test-001",
      props = Map("docId" -> "doc:paper", "text" -> "Test text")
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("MERGE")
    commands.head.text should include ("Chunk")
    commands.head.text should include ("chunkId")
    commands.head.params("id") shouldBe "chunk:test-001"
  }

  // -------------------------------------------------------------------------
  // Test 3: Generic node upsert uses 'id' as key
  // -------------------------------------------------------------------------
  it should "use 'id' key for generic node labels" in {
    val upsert = UpsertNode(
      label = "CustomLabel",
      id = "custom:123",
      props = Map("name" -> "Test")
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("id:")
    commands.head.text should include ("CustomLabel")
  }

  // -------------------------------------------------------------------------
  // Test 4: MENTIONS edge generates correct Cypher
  // -------------------------------------------------------------------------
  it should "generate correct Cypher for MENTIONS edges" in {
    val upsert = UpsertEdge(
      fromLabel = "Chunk",
      fromId = "chunk:test-001",
      rel = "MENTIONS",
      toLabel = "Concept",
      toId = "concept:test",
      props = Map("spanStart" -> 10, "spanEnd" -> 20)
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("MERGE")
    commands.head.text should include ("MENTIONS")
    commands.head.text should include ("chunkId")
    commands.head.text should include ("conceptId")
    commands.head.params("from") shouldBe "chunk:test-001"
    commands.head.params("to") shouldBe "concept:test"
  }

  // -------------------------------------------------------------------------
  // Test 5: RELATES_TO edge generates correct Cypher
  // -------------------------------------------------------------------------
  it should "generate correct Cypher for RELATES_TO edges" in {
    val upsert = UpsertEdge(
      fromLabel = "Concept",
      fromId = "concept:a",
      rel = "RELATES_TO",
      toLabel = "Concept",
      toId = "concept:b",
      props = Map("predicate" -> "causes", "confidence" -> 0.85)
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("MERGE")
    commands.head.text should include ("RELATES_TO")
    commands.head.params("a") shouldBe "concept:a"
    commands.head.params("b") shouldBe "concept:b"
  }

  // -------------------------------------------------------------------------
  // Test 6: CO_OCCURS edge uses increment logic
  // -------------------------------------------------------------------------
  it should "generate increment logic for CO_OCCURS edges" in {
    val upsert = UpsertEdge(
      fromLabel = "Concept",
      fromId = "concept:a",
      rel = "CO_OCCURS",
      toLabel = "Concept",
      toId = "concept:b",
      props = Map("inc" -> 5L)
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("CO_OCCURS")
    commands.head.text should include ("coalesce")
    commands.head.text should include ("freq")
    commands.head.params("inc") shouldBe 5L
  }

  // -------------------------------------------------------------------------
  // Test 7: CO_OCCURS defaults to increment of 1
  // -------------------------------------------------------------------------
  it should "default CO_OCCURS increment to 1 if not specified" in {
    val upsert = UpsertEdge(
      fromLabel = "Concept",
      fromId = "concept:a",
      rel = "CO_OCCURS",
      toLabel = "Concept",
      toId = "concept:b",
      props = Map.empty // No inc specified
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands.head.params("inc") shouldBe 1L
  }

  // -------------------------------------------------------------------------
  // Test 8: Generic edge generates fallback Cypher
  // -------------------------------------------------------------------------
  it should "generate generic Cypher for unknown edge types" in {
    val upsert = UpsertEdge(
      fromLabel = "NodeA",
      fromId = "a:1",
      rel = "CUSTOM_REL",
      toLabel = "NodeB",
      toId = "b:2",
      props = Map("weight" -> 1.5)
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    commands should have size 1
    commands.head.text should include ("MERGE")
    commands.head.text should include ("CUSTOM_REL")
    commands.head.text should include ("NodeA")
    commands.head.text should include ("NodeB")
  }

  // -------------------------------------------------------------------------
  // Test 9: Commands are parameterized (SQL injection safe)
  // -------------------------------------------------------------------------
  it should "use parameters instead of string interpolation for values" in {
    val upsert = UpsertNode(
      label = "Concept",
      id = "concept:'; DROP DATABASE;--",  // Malicious ID
      props = Map("lemma" -> "test")
    )
    
    val commands = GraphUpsert.commands(upsert)
    
    // The malicious value should be in params, not in the query text
    commands.head.text should not include ("DROP DATABASE")
    commands.head.params("id") shouldBe "concept:'; DROP DATABASE;--"
  }
}

