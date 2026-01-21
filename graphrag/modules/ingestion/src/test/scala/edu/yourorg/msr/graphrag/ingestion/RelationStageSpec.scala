package edu.yourorg.msr.graphrag.ingestion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import edu.yourorg.msr.graphrag.core.model._

/**
 * Unit tests for the RelationStage.
 * 
 * Tests cover:
 *   - Co-occurrence building
 *   - Relation candidate generation
 *   - Confidence filtering
 *   - Relation merging
 */
class RelationStageSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: Build co-occurrences from concepts
  // -------------------------------------------------------------------------
  "RelationStage" should "build co-occurrences from concepts in same chunk" in {
    val concepts = List(
      Concept("concept:a", "concept a", List("a"), "heuristic"),
      Concept("concept:b", "concept b", List("b"), "heuristic"),
      Concept("concept:c", "concept c", List("c"), "heuristic")
    )
    
    val coOccurs = RelationStage.buildCoOccurrences("chunk:test", concepts)
    
    // With 3 concepts, should have 3 pairs: (a,b), (a,c), (b,c)
    coOccurs should have size 3
    
    val pairs = coOccurs.map(co => (co.a.conceptId, co.b.conceptId))
    pairs should contain (("concept:a", "concept:b"))
    pairs should contain (("concept:a", "concept:c"))
    pairs should contain (("concept:b", "concept:c"))
  }

  // -------------------------------------------------------------------------
  // Test 2: Single concept produces no co-occurrences
  // -------------------------------------------------------------------------
  it should "return empty list for single concept" in {
    val concepts = List(
      Concept("concept:only", "only", List("only"), "heuristic")
    )
    
    val coOccurs = RelationStage.buildCoOccurrences("chunk:test", concepts)
    coOccurs shouldBe empty
  }

  // -------------------------------------------------------------------------
  // Test 3: Empty concept list
  // -------------------------------------------------------------------------
  it should "handle empty concept list" in {
    val coOccurs = RelationStage.buildCoOccurrences("chunk:test", Nil)
    coOccurs shouldBe empty
  }

  // -------------------------------------------------------------------------
  // Test 4: Build candidates from co-occurrences
  // -------------------------------------------------------------------------
  it should "build relation candidates from co-occurrences" in {
    val conceptA = Concept("concept:defect", "defect", List("defect"), "heuristic")
    val conceptB = Concept("concept:commit", "commit", List("commit"), "heuristic")
    
    val coOccurs = List(
      CoOccur(conceptA, conceptB, "chunk:test-001", 1L)
    )
    
    val chunkTexts = Map(
      "test-001" -> "This commit introduces a defect in the code."
    )
    
    val candidates = RelationStage.buildCandidatesFromCoOccur(coOccurs, chunkTexts)
    
    candidates should have size 1
    candidates.head.a.conceptId shouldBe "concept:defect"
    candidates.head.b.conceptId shouldBe "concept:commit"
    candidates.head.evidence should not be empty
  }

  // -------------------------------------------------------------------------
  // Test 5: Missing chunk text produces no candidate
  // -------------------------------------------------------------------------
  it should "skip candidates when chunk text is missing" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val coOccurs = List(
      CoOccur(conceptA, conceptB, "chunk:missing", 1L)
    )
    
    val chunkTexts = Map.empty[String, String]
    
    val candidates = RelationStage.buildCandidatesFromCoOccur(coOccurs, chunkTexts)
    candidates shouldBe empty
  }

  // -------------------------------------------------------------------------
  // Test 6: Filter by confidence threshold
  // -------------------------------------------------------------------------
  it should "filter relations by minimum confidence" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val relations = Seq(
      ScoredRelation(conceptA, "related_to", conceptB, 0.8, "evidence", "ref"),
      ScoredRelation(conceptA, "causes", conceptB, 0.5, "evidence", "ref"),
      ScoredRelation(conceptA, "is_a", conceptB, 0.9, "evidence", "ref")
    )
    
    val filtered = RelationStage.filterByConfidence(relations, 0.65)
    
    filtered should have size 2
    filtered.map(_.predicate) should contain allOf ("related_to", "is_a")
    filtered.map(_.predicate) should not contain ("causes")
  }

  // -------------------------------------------------------------------------
  // Test 7: Filter out no_relation predicate
  // -------------------------------------------------------------------------
  it should "filter out no_relation predicate" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val relations = Seq(
      ScoredRelation(conceptA, "no_relation", conceptB, 0.95, "evidence", "ref"),
      ScoredRelation(conceptA, "causes", conceptB, 0.7, "evidence", "ref")
    )
    
    val filtered = RelationStage.filterByConfidence(relations, 0.65)
    
    filtered should have size 1
    filtered.head.predicate shouldBe "causes"
  }

  // -------------------------------------------------------------------------
  // Test 8: Merge duplicate relations keeping highest confidence
  // -------------------------------------------------------------------------
  it should "merge duplicate relations keeping highest confidence" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val relations = Seq(
      ScoredRelation(conceptA, "causes", conceptB, 0.7, "evidence1", "ref1"),
      ScoredRelation(conceptA, "causes", conceptB, 0.9, "evidence2", "ref2"),
      ScoredRelation(conceptA, "causes", conceptB, 0.6, "evidence3", "ref3")
    )
    
    val merged = RelationStage.mergeRelations(relations)
    
    merged should have size 1
    merged.head.confidence shouldBe 0.9
    merged.head.evidence shouldBe "evidence2"
  }

  // -------------------------------------------------------------------------
  // Test 9: Keep different predicates as separate relations
  // -------------------------------------------------------------------------
  it should "keep different predicates as separate relations" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val relations = Seq(
      ScoredRelation(conceptA, "causes", conceptB, 0.8, "evidence1", "ref"),
      ScoredRelation(conceptA, "is_a", conceptB, 0.7, "evidence2", "ref")
    )
    
    val merged = RelationStage.mergeRelations(relations)
    
    merged should have size 2
    merged.map(_.predicate) should contain allOf ("causes", "is_a")
  }
}

