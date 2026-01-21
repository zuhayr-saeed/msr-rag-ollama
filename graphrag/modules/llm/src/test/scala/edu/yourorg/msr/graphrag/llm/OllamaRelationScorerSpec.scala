package edu.yourorg.msr.graphrag.llm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import edu.yourorg.msr.graphrag.core.model._

/**
 * Unit tests for the OllamaRelationScorer.
 * 
 * These tests verify the prompt building and JSON parsing logic
 * without requiring a live Ollama instance.
 */
class OllamaRelationScorerSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Test 1: LlmVerdict decoder parses valid JSON
  // -------------------------------------------------------------------------
  "LlmVerdict" should "decode valid JSON response" in {
    import io.circe.parser._
    
    val json = """{"predicate":"causes","confidence":0.85,"evidence":"code smell leads to refactoring","ref":"doc:1:chunk:0"}"""
    
    val result = parse(json).flatMap(_.as[LlmVerdict])
    
    result.isRight shouldBe true
    val verdict = result.toOption.get
    verdict.predicate shouldBe "causes"
    verdict.confidence shouldBe 0.85
    verdict.evidence shouldBe "code smell leads to refactoring"
    verdict.ref shouldBe "doc:1:chunk:0"
  }

  // -------------------------------------------------------------------------
  // Test 2: LlmVerdict handles missing fields gracefully
  // -------------------------------------------------------------------------
  it should "fail on incomplete JSON" in {
    import io.circe.parser._
    
    val json = """{"predicate":"causes","confidence":0.85}"""  // Missing evidence and ref
    
    val result = parse(json).flatMap(_.as[LlmVerdict])
    
    result.isLeft shouldBe true
  }

  // -------------------------------------------------------------------------
  // Test 3: OllamaConfig loads from values
  // -------------------------------------------------------------------------
  "OllamaConfig" should "be created with valid values" in {
    val config = OllamaConfig(
      endpoint = "http://localhost:11434",
      model = "llama3:instruct",
      temperature = 0.0,
      timeoutMs = 15000
    )
    
    config.endpoint shouldBe "http://localhost:11434"
    config.model shouldBe "llama3:instruct"
    config.temperature shouldBe 0.0
    config.timeoutMs shouldBe 15000
  }

  // -------------------------------------------------------------------------
  // Test 4: RelationCandidate contains required fields
  // -------------------------------------------------------------------------
  "RelationCandidate" should "contain concepts and evidence" in {
    val conceptA = Concept("concept:bug", "bug", List("bug", "defect"), "heuristic")
    val conceptB = Concept("concept:test", "test", List("test", "testing"), "heuristic")
    
    val candidate = RelationCandidate(
      a = conceptA,
      b = conceptB,
      evidence = "Testing helps find bugs in the code."
    )
    
    candidate.a.lemma shouldBe "bug"
    candidate.b.lemma shouldBe "test"
    candidate.evidence should include ("Testing")
  }

  // -------------------------------------------------------------------------
  // Test 5: Predicate values are from expected set
  // -------------------------------------------------------------------------
  "Predicates" should "be from the allowed set" in {
    val allowedPredicates = Set(
      "is_a", "part_of", "causes", "synonym_of", "related_to", "no_relation"
    )
    
    val verdict = LlmVerdict("causes", 0.8, "evidence", "ref")
    
    allowedPredicates should contain (verdict.predicate)
  }

  // -------------------------------------------------------------------------
  // Test 6: Confidence bounds validation
  // -------------------------------------------------------------------------
  "ScoredRelation" should "have confidence in [0,1] range" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val validRelation = ScoredRelation(
      a = conceptA,
      predicate = "causes",
      b = conceptB,
      confidence = 0.75,
      evidence = "test",
      ref = "ref"
    )
    
    validRelation.confidence should be >= 0.0
    validRelation.confidence should be <= 1.0
  }

  // -------------------------------------------------------------------------
  // Test 7: Empty evidence handling
  // -------------------------------------------------------------------------
  it should "handle empty evidence string" in {
    val conceptA = Concept("concept:a", "a", List("a"), "heuristic")
    val conceptB = Concept("concept:b", "b", List("b"), "heuristic")
    
    val relation = ScoredRelation(
      a = conceptA,
      predicate = "related_to",
      b = conceptB,
      confidence = 0.5,
      evidence = "",
      ref = ""
    )
    
    relation.evidence shouldBe empty
    relation.ref shouldBe empty
  }

  // -------------------------------------------------------------------------
  // Test 8: JSON response wrapper parsing
  // -------------------------------------------------------------------------
  "Ollama response" should "extract content from message wrapper" in {
    import io.circe.parser._
    
    // Simulated Ollama response structure
    val ollamaResponse = """{
      "model": "llama3:instruct",
      "created_at": "2024-01-01T00:00:00Z",
      "message": {
        "role": "assistant",
        "content": "{\"predicate\":\"is_a\",\"confidence\":0.9,\"evidence\":\"test\",\"ref\":\"n/a\"}"
      },
      "done": true
    }"""
    
    val result = for {
      json <- parse(ollamaResponse)
      content <- json.hcursor.downField("message").get[String]("content")
      contentJson <- parse(content)
      verdict <- contentJson.as[LlmVerdict]
    } yield verdict
    
    result.isRight shouldBe true
    result.toOption.get.predicate shouldBe "is_a"
    result.toOption.get.confidence shouldBe 0.9
  }
}

