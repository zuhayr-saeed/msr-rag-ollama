package edu.yourorg.msr.graphrag.llm

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.core.model._

import io.circe.{Decoder, HCursor}
import io.circe.parser._
import io.circe.generic.semiauto._

/**
 * JSON verdict we expect from the LLM for a single relation candidate.
 *
 * Example expected JSON from the model:
 * {
 *   "predicate": "related_to",
 *   "confidence": 0.72,
 *   "evidence": "short supporting snippet",
 *   "ref": "docId:chunkId:0-120"
 * }
 */
final case class LlmVerdict(
  predicate : String,
  confidence: Double,
  evidence  : String,
  ref       : String
)

object LlmVerdict {
  implicit val decoder: Decoder[LlmVerdict] = deriveDecoder[LlmVerdict]
}

/**
 * A light wrapper that:
 *   - creates a classification prompt for each RelationCandidate
 *   - calls Ollama
 *   - parses a JSON verdict into ScoredRelation
 */
final class OllamaRelationScorer(client: OllamaClient) extends Logging {

  /**
   * Score a batch of relation candidates.
   *
   * For simplicity we call Ollama once per candidate. In a real system you might
   * want to batch or do something more efficient, but this is fine for the homework.
   */
  def scoreRelations(candidates: Seq[RelationCandidate]): Seq[ScoredRelation] =
    candidates.flatMap { cand =>
      scoreSingle(cand)
    }

  private def scoreSingle(cand: RelationCandidate): Option[ScoredRelation] = {
    val prompt = buildPrompt(cand)

    client.chat(prompt).flatMap { rawJson =>
      extractVerdict(rawJson).map { verdict =>
        ScoredRelation(
          a          = cand.a,
          predicate  = verdict.predicate,
          b          = cand.b,
          confidence = verdict.confidence,
          evidence   = verdict.evidence,
          ref        = verdict.ref
        )
      }
    }
  }

  /**
   * Build a compact instruction for the model.
   * We tell it to respond ONLY with the JSON object matching LlmVerdict.
   */
  private def buildPrompt(cand: RelationCandidate): String = {
    s"""
       |You are a relation classifier for a knowledge graph built from research papers.
       |
       |We have two concepts:
       |  - concept_a: "${cand.a.lemma}"
       |  - concept_b: "${cand.b.lemma}"
       |
       |Context snippet:
       |\"\"\"${cand.evidence}\"\"\"
       |
       |Decide the most likely predicate relating concept_a to concept_b.
       |Use one of: ["is_a","part_of","causes","synonym_of","related_to","no_relation"].
       |
       |Respond ONLY with a single JSON object of the form:
       |{
       |  "predicate": "<one predicate from the list or 'no_relation'>",
       |  "confidence": <number between 0 and 1>,
       |  "evidence": "<short phrase copied from the snippet>",
       |  "ref": "<opaque reference string (you can reuse 'n/a')>"
       |}
       |
       |Do not include any extra text, backticks, or explanation.
       |""".stripMargin
  }

  /**
   * Given the raw JSON streaming response from Ollama's /api/chat,
   * extract the assistant's content and parse it as LlmVerdict.
   *
   * Ollama's chat responses look like:
   * {
   *   "model": "...",
   *   "created_at": "...",
   *   "message": {
   *     "role": "assistant",
   *     "content": "{... our JSON ...}"
   *   }
   *   ...
   * }
   */
  private def extractVerdict(rawResponse: String): Option[LlmVerdict] = {
    // Parse the top-level response
    parse(rawResponse) match {
      case Left(err) =>
        logger.error(s"Failed to parse Ollama response as JSON: $err, raw: $rawResponse")
        None

      case Right(json) =>
        val cursor: HCursor = json.hcursor
        val contentOpt      = cursor.downField("message").get[String]("content").toOption

        contentOpt.flatMap { content =>
          // Model should have returned the JSON object as a string; parse it again
          parse(content) match {
            case Left(err2) =>
              logger.error(s"Failed to parse LLM content as JSON: $err2, content: $content")
              None

            case Right(jsonObj) =>
              jsonObj.as[LlmVerdict] match {
                case Left(decErr) =>
                  logger.error(s"Failed to decode LlmVerdict: $decErr, json: $jsonObj")
                  None
                case Right(verdict) =>
                  Some(verdict)
              }
          }
        }
    }
  }
}

object OllamaRelationScorer {

  /** Convenience constructor: read config and create both client + scorer. */
  def fromConfig(): OllamaRelationScorer =
    new OllamaRelationScorer(OllamaClient.fromConfig())
}
