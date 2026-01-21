package edu.yourorg.msr.graphrag.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.parser._
import io.circe.syntax._

/**
 * Unit tests for EvidenceService response formatting.
 * 
 * Tests verify JSON encoding without requiring a live Neo4j connection.
 */
class EvidenceServiceSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: EvidenceResponse encoding
  // -------------------------------------------------------------------------
  "EvidenceResponse" should "encode to valid JSON with all fields" in {
    val response = EvidenceResponse(
      evidenceId = "evid:chunk-abc123",
      paperId = Some("doc:msr2020-001"),
      chunkId = "chunk-abc123",
      text = "This commit introduces a defect in the code validation logic.",
      docRef = DocRef(
        title = "Commit2Vec-JIT",
        year = Some(2020),
        url = "file:///papers/msr2020/commit2vec.pdf"
      )
    )
    
    import EvidenceResponse.encoder
    val json = response.asJson.noSpaces
    
    json should include ("evid:chunk-abc123")
    json should include ("doc:msr2020-001")
    json should include ("introduces a defect")
    json should include ("Commit2Vec-JIT")
    json should include ("2020")
  }

  // -------------------------------------------------------------------------
  // Test 2: EvidenceResponse with missing optional fields
  // -------------------------------------------------------------------------
  it should "handle missing optional paperId" in {
    val response = EvidenceResponse(
      evidenceId = "evid:orphan",
      paperId = None,
      chunkId = "chunk-orphan",
      text = "Some text without paper reference",
      docRef = DocRef(
        title = "Unknown",
        year = None,
        url = ""
      )
    )
    
    import EvidenceResponse.encoder
    val json = response.asJson.noSpaces
    
    json should include ("evid:orphan")
    json should include ("null") // paperId is None
  }

  // -------------------------------------------------------------------------
  // Test 3: DocRef encoding
  // -------------------------------------------------------------------------
  "DocRef" should "encode document reference correctly" in {
    val docRef = DocRef(
      title = "GraphRAG for Software Engineering",
      year = Some(2024),
      url = "https://example.org/paper.pdf"
    )
    
    import EvidenceResponse.docRefEncoder
    val json = docRef.asJson.noSpaces
    
    json should include ("GraphRAG for Software Engineering")
    json should include ("2024")
    json should include ("https://example.org/paper.pdf")
  }

  // -------------------------------------------------------------------------
  // Test 4: DocRef with no year
  // -------------------------------------------------------------------------
  it should "handle missing year" in {
    val docRef = DocRef(
      title = "Unknown Paper",
      year = None,
      url = "file:///unknown.pdf"
    )
    
    import EvidenceResponse.docRefEncoder
    val json = docRef.asJson.noSpaces
    
    json should include ("Unknown Paper")
    json should not include ("2024")
  }

  // -------------------------------------------------------------------------
  // Test 5: Evidence ID parsing
  // -------------------------------------------------------------------------
  "Evidence ID" should "be extractable from evid: prefix" in {
    val evidenceId = "evid:chunk-b3e0"
    val chunkId = evidenceId.stripPrefix("evid:")
    
    chunkId shouldBe "chunk-b3e0"
  }

  // -------------------------------------------------------------------------
  // Test 6: Evidence ID without prefix
  // -------------------------------------------------------------------------
  it should "handle IDs without evid: prefix" in {
    val evidenceId = "chunk-direct"
    val chunkId = evidenceId.stripPrefix("evid:")
    
    chunkId shouldBe "chunk-direct"
  }
}

