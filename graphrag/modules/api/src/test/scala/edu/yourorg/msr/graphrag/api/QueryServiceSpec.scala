package edu.yourorg.msr.graphrag.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.parser._
import io.circe.syntax._

/**
 * Unit tests for QueryService JSON parsing and response formatting.
 * 
 * These tests verify request/response serialization without requiring
 * a live Neo4j connection.
 */
class QueryServiceSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: QueryRequest decodes valid JSON
  // -------------------------------------------------------------------------
  "QueryRequest" should "decode valid JSON with all fields" in {
    val json = """{
      "query": "defect prediction techniques",
      "timeRange": {"from": 2018, "to": 2025},
      "constraints": {"datasets": ["JITGIT"], "baselines": ["Random Forest"]},
      "output": {"groupBy": ["techFamily"], "topKPerGroup": 5}
    }"""
    
    val result = parse(json).flatMap(_.as[QueryRequest])
    
    result.isRight shouldBe true
    val request = result.toOption.get
    request.query shouldBe "defect prediction techniques"
    request.timeRange.get.from shouldBe 2018
    request.timeRange.get.to shouldBe 2025
    request.constraints.get.datasets.get should contain ("JITGIT")
  }

  // -------------------------------------------------------------------------
  // Test 2: QueryRequest with minimal fields
  // -------------------------------------------------------------------------
  it should "decode JSON with only query field" in {
    val json = """{"query": "machine learning"}"""
    
    val result = parse(json).flatMap(_.as[QueryRequest])
    
    result.isRight shouldBe true
    val request = result.toOption.get
    request.query shouldBe "machine learning"
    request.timeRange shouldBe None
    request.constraints shouldBe None
    request.output shouldBe None
  }

  // -------------------------------------------------------------------------
  // Test 3: QueryRequest rejects invalid JSON
  // -------------------------------------------------------------------------
  it should "fail on missing required query field" in {
    val json = """{"timeRange": {"from": 2018, "to": 2025}}"""
    
    val result = parse(json).flatMap(_.as[QueryRequest])
    
    result.isLeft shouldBe true
  }

  // -------------------------------------------------------------------------
  // Test 4: TimeRange validation
  // -------------------------------------------------------------------------
  "TimeRange" should "parse valid year range" in {
    val json = """{"from": 2015, "to": 2024}"""
    
    import QueryRequest.timeRangeDecoder
    val result = parse(json).flatMap(_.as[TimeRange])
    
    result.isRight shouldBe true
    result.toOption.get.from shouldBe 2015
    result.toOption.get.to shouldBe 2024
  }

  // -------------------------------------------------------------------------
  // Test 5: QueryConstraints parsing
  // -------------------------------------------------------------------------
  "QueryConstraints" should "parse multiple constraint types" in {
    val json = """{
      "datasets": ["JITGIT", "SEOSS"],
      "baselines": ["Random Forest", "SVM"],
      "concepts": ["defect", "bug"]
    }"""
    
    import QueryRequest.constraintsDecoder
    val result = parse(json).flatMap(_.as[QueryConstraints])
    
    result.isRight shouldBe true
    val constraints = result.toOption.get
    constraints.datasets.get should have size 2
    constraints.baselines.get should have size 2
    constraints.concepts.get should have size 2
  }

  // -------------------------------------------------------------------------
  // Test 6: OutputOptions parsing
  // -------------------------------------------------------------------------
  "OutputOptions" should "parse grouping and limit options" in {
    val json = """{
      "groupBy": ["technique", "year"],
      "metrics": ["AUC", "F1"],
      "topKPerGroup": 10,
      "includeCitations": true
    }"""
    
    import QueryRequest.outputDecoder
    val result = parse(json).flatMap(_.as[OutputOptions])
    
    result.isRight shouldBe true
    val options = result.toOption.get
    options.groupBy.get should contain ("technique")
    options.topKPerGroup shouldBe Some(10)
    options.includeCitations shouldBe Some(true)
  }

  // -------------------------------------------------------------------------
  // Test 7: QueryResponse encoding
  // -------------------------------------------------------------------------
  "QueryResponse" should "encode to valid JSON" in {
    val response = QueryResponse(
      mode = "sync",
      summary = "Found 5 concepts",
      results = List(
        QueryResult(
          conceptId = "concept:defect",
          lemma = "defect",
          relatedConcepts = List(
            RelatedConcept("concept:bug", "bug", "synonym_of", 0.9)
          ),
          chunks = List(
            ChunkResult("chunk:001", "doc:001", "Sample text", "file:///test.pdf")
          )
        )
      ),
      evidenceAvailable = true,
      traceId = "trace-123"
    )
    
    import QueryResponse.encoder
    val json = response.asJson.noSpaces
    
    json should include ("sync")
    json should include ("Found 5 concepts")
    json should include ("concept:defect")
    json should include ("trace-123")
  }

  // -------------------------------------------------------------------------
  // Test 8: RelatedConcept encoding
  // -------------------------------------------------------------------------
  "RelatedConcept" should "include all fields in JSON" in {
    val related = RelatedConcept(
      conceptId = "concept:test",
      lemma = "test",
      predicate = "is_a",
      confidence = 0.85
    )
    
    import QueryResponse.relatedConceptEncoder
    val json = related.asJson.noSpaces
    
    json should include ("concept:test")
    json should include ("is_a")
    json should include ("0.85")
  }

  // -------------------------------------------------------------------------
  // Test 9: ChunkResult encoding
  // -------------------------------------------------------------------------
  "ChunkResult" should "include chunk metadata in JSON" in {
    val chunk = ChunkResult(
      chunkId = "chunk:abc123",
      docId = "doc:paper001",
      text = "This is the chunk text content.",
      sourceUri = "file:///papers/paper001.pdf"
    )
    
    import QueryResponse.chunkResultEncoder
    val json = chunk.asJson.noSpaces
    
    json should include ("chunk:abc123")
    json should include ("doc:paper001")
    json should include ("chunk text content")
  }

  // -------------------------------------------------------------------------
  // Test 10: Empty results handling
  // -------------------------------------------------------------------------
  "QueryResponse" should "handle empty results" in {
    val response = QueryResponse(
      mode = "sync",
      summary = "No results found",
      results = Nil,
      evidenceAvailable = false,
      traceId = "trace-empty"
    )
    
    import QueryResponse.encoder
    val json = response.asJson.noSpaces
    
    json should include ("No results found")
    json should include ("[]")
    json should include ("false")
  }
}

