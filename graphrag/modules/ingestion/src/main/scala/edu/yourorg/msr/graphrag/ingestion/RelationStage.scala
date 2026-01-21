package edu.yourorg.msr.graphrag.ingestion

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.core.model._

/**
 * Relation candidate generation utilities.
 * 
 * Builds RelationCandidate instances from:
 *   1. Co-occurrence windows (concepts appearing in same chunk)
 *   2. Pattern-matched relations from ConceptExtractor
 *   
 * These candidates are then scored by the LLM to produce ScoredRelations.
 */
object RelationStage extends Logging {

  /**
   * Build co-occurrence pairs from concepts within the same chunk.
   * 
   * @param chunkId The chunk where concepts were found
   * @param concepts List of concepts found in the chunk
   * @return List of CoOccur records
   */
  def buildCoOccurrences(chunkId: String, concepts: List[Concept]): List[CoOccur] = {
    if (concepts.size < 2) {
      return Nil
    }
    
    // Sort by conceptId to ensure consistent ordering (a < b)
    val sorted = concepts.sortBy(_.conceptId)
    
    // Generate all pairs
    val pairs = for {
      i <- sorted.indices.toList
      j <- (i + 1) until sorted.size
    } yield {
      CoOccur(
        a = sorted(i),
        b = sorted(j),
        windowId = s"chunk:$chunkId",
        freq = 1L
      )
    }
    
    pairs
  }

  /**
   * Build relation candidates from co-occurrences and evidence text.
   * 
   * @param coOccurs List of co-occurrence records
   * @param chunkTexts Map from chunkId to chunk text (for evidence)
   * @return List of RelationCandidate ready for LLM scoring
   */
  def buildCandidatesFromCoOccur(
    coOccurs: List[CoOccur],
    chunkTexts: Map[String, String]
  ): List[RelationCandidate] = {
    coOccurs.flatMap { co =>
      // Extract chunkId from windowId
      val chunkId = co.windowId.stripPrefix("chunk:")
      
      chunkTexts.get(chunkId).map { text =>
        // Find evidence snippet around where concepts appear
        val evidence = extractEvidenceSnippet(text, co.a.lemma, co.b.lemma)
        
        RelationCandidate(
          a = co.a,
          b = co.b,
          evidence = evidence
        )
      }
    }
  }

  /**
   * Extract a short evidence snippet from text containing both concept lemmas.
   */
  private def extractEvidenceSnippet(text: String, lemmaA: String, lemmaB: String): String = {
    val lowerText = text.toLowerCase
    val posA = lowerText.indexOf(lemmaA.toLowerCase)
    val posB = lowerText.indexOf(lemmaB.toLowerCase)
    
    if (posA >= 0 && posB >= 0) {
      val start = math.max(0, math.min(posA, posB) - 30)
      val end = math.min(text.length, math.max(posA + lemmaA.length, posB + lemmaB.length) + 30)
      text.substring(start, end).trim
    } else if (posA >= 0) {
      val start = math.max(0, posA - 50)
      val end = math.min(text.length, posA + 100)
      text.substring(start, end).trim
    } else if (posB >= 0) {
      val start = math.max(0, posB - 50)
      val end = math.min(text.length, posB + 100)
      text.substring(start, end).trim
    } else {
      // Fallback: just take first 150 chars
      text.take(150).trim
    }
  }

  /**
   * Filter scored relations by minimum confidence threshold.
   */
  def filterByConfidence(
    relations: Seq[ScoredRelation],
    minConfidence: Double
  ): Seq[ScoredRelation] = {
    relations.filter { rel =>
      rel.confidence >= minConfidence && rel.predicate != "no_relation"
    }
  }

  /**
   * Merge duplicate relations by keeping the one with highest confidence.
   */
  def mergeRelations(relations: Seq[ScoredRelation]): Seq[ScoredRelation] = {
    relations
      .groupBy(r => (r.a.conceptId, r.predicate, r.b.conceptId))
      .values
      .map(_.maxBy(_.confidence))
      .toSeq
  }
}

