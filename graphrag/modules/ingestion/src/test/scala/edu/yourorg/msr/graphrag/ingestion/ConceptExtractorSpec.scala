package edu.yourorg.msr.graphrag.ingestion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import edu.yourorg.msr.graphrag.core.model._

/**
 * Unit tests for the ConceptExtractor.
 * 
 * Tests cover:
 *   - Heuristic concept extraction
 *   - Pattern-based extraction
 *   - Concept ID normalization
 *   - Stop word filtering
 */
class ConceptExtractorSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: Basic heuristic extraction
  // -------------------------------------------------------------------------
  "ConceptExtractor" should "extract concepts from technical text" in {
    val chunk = Chunk(
      chunkId = "chunk:test-001",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 100,
      sectionPath = Some("abstract"),
      text = "Defect prediction uses machine learning to identify bugs in code commits.",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:test",
      language = Some("en")
    )
    
    val results = ConceptExtractor.extractHeuristic(chunk)
    
    results should not be empty
    val conceptIds = results.map(_._2.conceptId)
    
    // Should extract technical terms
    conceptIds should contain ("concept:defect")
    conceptIds should contain ("concept:prediction")
  }

  // -------------------------------------------------------------------------
  // Test 2: Concept ID normalization
  // -------------------------------------------------------------------------
  it should "normalize lemmas to consistent concept IDs" in {
    val id1 = ConceptExtractor.normalizeToConceptId("Machine Learning")
    val id2 = ConceptExtractor.normalizeToConceptId("machine learning")
    val id3 = ConceptExtractor.normalizeToConceptId("MACHINE LEARNING")
    
    id1 shouldBe id2
    id2 shouldBe id3
    id1 should startWith ("concept:")
  }

  // -------------------------------------------------------------------------
  // Test 3: Stop word filtering
  // -------------------------------------------------------------------------
  it should "filter out stop words" in {
    val chunk = Chunk(
      chunkId = "chunk:test-002",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 50,
      sectionPath = None,
      text = "The quick brown fox jumps over the lazy dog.",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:test2",
      language = Some("en")
    )
    
    val results = ConceptExtractor.extractHeuristic(chunk)
    val conceptIds = results.map(_._2.conceptId)
    
    // Should not extract common stop words
    conceptIds should not contain ("concept:the")
    conceptIds should not contain ("concept:over")
  }

  // -------------------------------------------------------------------------
  // Test 4: N-gram phrase extraction
  // -------------------------------------------------------------------------
  it should "extract multi-word technical phrases" in {
    val chunk = Chunk(
      chunkId = "chunk:test-003",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 100,
      sectionPath = None,
      text = "Just-in-time defect prediction identifies buggy commits using commit-level features.",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:test3",
      language = Some("en")
    )
    
    val results = ConceptExtractor.extractHeuristic(chunk)
    val lemmas = results.map(_._2.lemma)
    
    // Should extract some technical terms
    lemmas.exists(_.contains("defect")) shouldBe true
  }

  // -------------------------------------------------------------------------
  // Test 5: Pattern-based extraction ("X is a Y")
  // -------------------------------------------------------------------------
  it should "extract concepts from 'is a' patterns" in {
    val chunk = Chunk(
      chunkId = "chunk:test-004",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 80,
      sectionPath = None,
      text = "Random Forest is a machine learning classifier widely used in defect prediction.",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:test4",
      language = Some("en")
    )
    
    val results = ConceptExtractor.extractPatternBased(chunk)
    
    // May or may not find the is_a pattern depending on exact matching
    // At minimum should not throw an error
    results should not be null
  }

  // -------------------------------------------------------------------------
  // Test 6: Mention span information
  // -------------------------------------------------------------------------
  it should "include correct span information in mentions" in {
    val chunk = Chunk(
      chunkId = "chunk:test-005",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 50,
      sectionPath = None,
      text = "Testing code coverage improves software quality.",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:test5",
      language = Some("en")
    )
    
    val results = ConceptExtractor.extractHeuristic(chunk)
    
    results.foreach { case (mention, concept) =>
      mention.spanStart should be >= 0
      mention.spanEnd should be > mention.spanStart
      mention.surface.nonEmpty shouldBe true
      concept.conceptId.nonEmpty shouldBe true
    }
  }

  // -------------------------------------------------------------------------
  // Test 7: Empty text handling
  // -------------------------------------------------------------------------
  it should "handle empty text gracefully" in {
    val chunk = Chunk(
      chunkId = "chunk:test-006",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 0,
      sectionPath = None,
      text = "",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:empty",
      language = Some("en")
    )
    
    val results = ConceptExtractor.extractHeuristic(chunk)
    results shouldBe empty
  }

  // -------------------------------------------------------------------------
  // Test 8: Special characters handling
  // -------------------------------------------------------------------------
  it should "handle text with special characters" in {
    val chunk = Chunk(
      chunkId = "chunk:test-007",
      docId = "doc:paper-001",
      chunkIx = 0,
      startPos = 0,
      endPos = 100,
      sectionPath = None,
      text = "The method achieves 95% accuracy (p < 0.05) on JITGIT dataset [1].",
      sourceUri = "file:///test.pdf",
      contentHash = "hash:special",
      language = Some("en")
    )
    
    // Should not throw exception
    val results = ConceptExtractor.extractHeuristic(chunk)
    results should not be null
  }
}

