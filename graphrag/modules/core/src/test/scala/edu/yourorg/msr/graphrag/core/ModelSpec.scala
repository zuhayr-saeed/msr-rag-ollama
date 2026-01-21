package edu.yourorg.msr.graphrag.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import edu.yourorg.msr.graphrag.core.model._

/**
 * Unit tests for the GraphRAG core domain model.
 * 
 * Tests cover:
 *   - Chunk creation and validation
 *   - Concept creation and ID normalization
 *   - GraphWrite (UpsertNode/UpsertEdge) creation
 *   - Relation model structures
 */
class ModelSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: Chunk creation with all fields
  // -------------------------------------------------------------------------
  "Chunk" should "be created with all required fields" in {
    val chunk = Chunk(
      chunkId = "chunk:test-001",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 100,
      sectionPath = Some("abstract"),
      text = "This is test text for the chunk.",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:abc123",
      language = Some("en")
    )
    
    chunk.chunkId shouldBe "chunk:test-001"
    chunk.docId shouldBe "doc:paper-001"
    chunk.chunkIx shouldBe 0
    chunk.startPos shouldBe 0
    chunk.endPos shouldBe 100
    chunk.sectionPath shouldBe Some("abstract")
    chunk.text shouldBe "This is test text for the chunk."
    chunk.sourceUri shouldBe "file:///test.pdf"
    chunk.contentHash shouldBe "hash:abc123"
    chunk.language shouldBe Some("en")
  }

  // -------------------------------------------------------------------------
  // Test 2: Chunk with optional fields as None
  // -------------------------------------------------------------------------
  it should "handle optional fields as None" in {
    val chunk = Chunk(
      chunkId = "chunk:test-002",
      docId = "doc:paper-002",
      chunkIx = 1,
      startPos = 100,
      endPos = 200,
      sectionPath = None,
      text = "Another chunk without section path.",
      sourceUri = "file:///test2.pdf",
      contentHash = "hash:def456",
      language = None
    )
    
    chunk.sectionPath shouldBe None
    chunk.language shouldBe None
  }

  // -------------------------------------------------------------------------
  // Test 3: Concept creation
  // -------------------------------------------------------------------------
  "Concept" should "be created with proper structure" in {
    val concept = Concept(
      conceptId = "concept:defect_prediction",
      lemma = "defect prediction",
      surfaceForms = List("defect prediction", "bug prediction", "fault prediction"),
      origin = "heuristic"
    )
    
    concept.conceptId shouldBe "concept:defect_prediction"
    concept.lemma shouldBe "defect prediction"
    concept.surfaceForms should contain allOf ("defect prediction", "bug prediction", "fault prediction")
    concept.origin shouldBe "heuristic"
  }

  // -------------------------------------------------------------------------
  // Test 4: Mention linking chunk and concept
  // -------------------------------------------------------------------------
  "Mention" should "link a chunk to a concept with span information" in {
    val mention = Mention(
      chunkId = "chunk:test-001",
      conceptId = "concept:machine_learning",
      spanStart = 10,
      spanEnd = 26,
      surface = "machine learning"
    )
    
    mention.chunkId shouldBe "chunk:test-001"
    mention.conceptId shouldBe "concept:machine_learning"
    mention.spanStart shouldBe 10
    mention.spanEnd shouldBe 26
    mention.surface shouldBe "machine learning"
  }

  // -------------------------------------------------------------------------
  // Test 5: CoOccur structure
  // -------------------------------------------------------------------------
  "CoOccur" should "capture co-occurrence of two concepts" in {
    val conceptA = Concept("concept:a", "concept a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "concept b", List("b"), "heuristic")
    
    val coOccur = CoOccur(
      a = conceptA,
      b = conceptB,
      windowId = "chunk:test-001",
      freq = 5L
    )
    
    coOccur.a.conceptId shouldBe "concept:a"
    coOccur.b.conceptId shouldBe "concept:b"
    coOccur.windowId shouldBe "chunk:test-001"
    coOccur.freq shouldBe 5L
  }

  // -------------------------------------------------------------------------
  // Test 6: RelationCandidate for LLM scoring
  // -------------------------------------------------------------------------
  "RelationCandidate" should "contain evidence for scoring" in {
    val conceptA = Concept("concept:commit", "commit", List("commit"), "heuristic")
    val conceptB = Concept("concept:bug", "bug", List("bug"), "heuristic")
    
    val candidate = RelationCandidate(
      a = conceptA,
      b = conceptB,
      evidence = "Commits that introduce bugs can be detected early."
    )
    
    candidate.a.lemma shouldBe "commit"
    candidate.b.lemma shouldBe "bug"
    candidate.evidence should include ("Commits")
  }

  // -------------------------------------------------------------------------
  // Test 7: ScoredRelation from LLM output
  // -------------------------------------------------------------------------
  "ScoredRelation" should "contain predicate and confidence" in {
    val conceptA = Concept("concept:code_smell", "code smell", List("code smell"), "heuristic")
    val conceptB = Concept("concept:refactoring", "refactoring", List("refactoring"), "heuristic")
    
    val scoredRelation = ScoredRelation(
      a = conceptA,
      predicate = "causes",
      b = conceptB,
      confidence = 0.85,
      evidence = "Code smells often lead to refactoring activities.",
      ref = "doc:paper-001:chunk-0"
    )
    
    scoredRelation.predicate shouldBe "causes"
    scoredRelation.confidence shouldBe 0.85
    scoredRelation.confidence should be >= 0.0
    scoredRelation.confidence should be <= 1.0
  }

  // -------------------------------------------------------------------------
  // Test 8: UpsertNode GraphWrite
  // -------------------------------------------------------------------------
  "UpsertNode" should "create a graph node write operation" in {
    val upsert: GraphWrite = UpsertNode(
      label = "Concept",
      id = "concept:test",
      props = Map(
        "lemma" -> "test concept",
        "origin" -> "heuristic"
      )
    )
    
    upsert shouldBe a [UpsertNode]
    val node = upsert.asInstanceOf[UpsertNode]
    node.label shouldBe "Concept"
    node.id shouldBe "concept:test"
    node.props("lemma") shouldBe "test concept"
  }

  // -------------------------------------------------------------------------
  // Test 9: UpsertEdge GraphWrite
  // -------------------------------------------------------------------------
  "UpsertEdge" should "create a graph edge write operation" in {
    val upsert: GraphWrite = UpsertEdge(
      fromLabel = "Chunk",
      fromId = "chunk:test-001",
      rel = "MENTIONS",
      toLabel = "Concept",
      toId = "concept:test",
      props = Map(
        "spanStart" -> 10,
        "spanEnd" -> 20
      )
    )
    
    upsert shouldBe a [UpsertEdge]
    val edge = upsert.asInstanceOf[UpsertEdge]
    edge.fromLabel shouldBe "Chunk"
    edge.fromId shouldBe "chunk:test-001"
    edge.rel shouldBe "MENTIONS"
    edge.toLabel shouldBe "Concept"
    edge.toId shouldBe "concept:test"
    edge.props("spanStart") shouldBe 10
  }

  // -------------------------------------------------------------------------
  // Test 10: GraphWrite sealed trait pattern matching
  // -------------------------------------------------------------------------
  "GraphWrite" should "support exhaustive pattern matching" in {
    val writes: List[GraphWrite] = List(
      UpsertNode("Concept", "c1", Map("lemma" -> "test")),
      UpsertEdge("Chunk", "ch1", "MENTIONS", "Concept", "c1", Map.empty)
    )
    
    val descriptions = writes.map {
      case UpsertNode(label, id, _) => s"Node:$label:$id"
      case UpsertEdge(from, fid, rel, to, tid, _) => s"Edge:$from:$fid->$rel->$to:$tid"
    }
    
    descriptions should contain ("Node:Concept:c1")
    descriptions should contain ("Edge:Chunk:ch1->MENTIONS->Concept:c1")
  }
}

