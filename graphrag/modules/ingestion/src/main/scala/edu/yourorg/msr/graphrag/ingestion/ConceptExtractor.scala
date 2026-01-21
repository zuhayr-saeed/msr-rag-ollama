package edu.yourorg.msr.graphrag.ingestion

import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.core.model._

/**
 * Concept extraction utilities for the GraphRAG pipeline.
 * 
 * Provides multiple strategies:
 *   1. Heuristic extraction (fast, deterministic, high recall)
 *   2. Keyphrase extraction (NLP-based)
 *   3. Pattern-based extraction (cue patterns like "X is a Y")
 * 
 * These run locally without LLM calls, making them cheap and scalable.
 */
object ConceptExtractor extends Logging {

  /**
   * Stop words to filter out common English words that aren't concepts.
   */
  private val stopWords: Set[String] = Set(
    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
    "be", "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "must", "shall", "can", "need", "it", "its",
    "this", "that", "these", "those", "which", "who", "whom", "whose", "what",
    "when", "where", "why", "how", "all", "each", "every", "both", "few",
    "more", "most", "other", "some", "such", "than", "too", "very", "just",
    "only", "own", "same", "into", "over", "after", "before", "above", "below",
    "between", "under", "again", "further", "then", "once", "here", "there",
    "they", "them", "their", "our", "your", "his", "her", "out", "also", "not",
    "about", "if", "so", "no", "yes", "any", "while", "during", "through"
  )

  /**
   * Technical/research terms that indicate important concepts in MSR papers.
   */
  private val technicalPatterns: List[String] = List(
    "defect", "bug", "fault", "error", "failure",
    "commit", "patch", "pull", "request", "merge",
    "repository", "project", "software", "code", "source",
    "prediction", "detection", "classification", "model",
    "learning", "neural", "network", "deep", "machine",
    "metric", "feature", "dataset", "corpus", "benchmark",
    "mining", "analysis", "technique", "method", "approach",
    "developer", "contributor", "maintenance", "evolution",
    "test", "testing", "coverage", "quality", "smell",
    "refactoring", "clone", "duplicate", "change", "history"
  )

  /**
   * Extract concepts from a chunk using heuristic rules.
   * 
   * Returns a list of (Mention, Concept) pairs for each concept found.
   */
  def extractHeuristic(chunk: Chunk): List[(Mention, Concept)] = {
    val text = chunk.text
    val tokens = tokenize(text)
    
    // Extract single-word concepts (important terms)
    val singleWordConcepts = extractImportantTerms(tokens)
    
    // Extract n-gram phrases (2-3 words)
    val ngramConcepts = extractNgramPhrases(text, tokens)
    
    // Combine and deduplicate
    val allConcepts = (singleWordConcepts ++ ngramConcepts).distinct
    
    // Create Mentions and Concepts
    allConcepts.flatMap { lemma =>
      val conceptId = normalizeToConceptId(lemma)
      val spanStart = text.toLowerCase.indexOf(lemma.toLowerCase)
      
      if (spanStart >= 0) {
        val mention = Mention(
          chunkId = chunk.chunkId,
          conceptId = conceptId,
          spanStart = spanStart,
          spanEnd = spanStart + lemma.length,
          surface = lemma
        )
        val concept = Concept(
          conceptId = conceptId,
          lemma = lemma.toLowerCase,
          surfaceForms = List(lemma),
          origin = "heuristic"
        )
        Some((mention, concept))
      } else {
        None
      }
    }
  }

  /**
   * Extract concepts using cue patterns like "X is a Y", "X such as Y".
   */
  def extractPatternBased(chunk: Chunk): List[(Mention, Concept, Option[RelationCandidate])] = {
    val text = chunk.text
    val results = scala.collection.mutable.ListBuffer[(Mention, Concept, Option[RelationCandidate])]()
    
    // Pattern: "X is a/an Y"
    val isAPattern = """(\w+(?:\s+\w+)?)\s+is\s+(?:a|an)\s+(\w+(?:\s+\w+)?)""".r
    isAPattern.findAllMatchIn(text).foreach { m =>
      val subjectStr = m.group(1).toLowerCase.trim
      val objectStr = m.group(2).toLowerCase.trim
      
      if (isValidConcept(subjectStr) && isValidConcept(objectStr)) {
        val subjectId = normalizeToConceptId(subjectStr)
        val objectId = normalizeToConceptId(objectStr)
        
        val subjectConcept = Concept(subjectId, subjectStr, List(subjectStr), "pattern:is_a")
        val objectConcept = Concept(objectId, objectStr, List(objectStr), "pattern:is_a")
        
        val subjectMention = Mention(chunk.chunkId, subjectId, m.start(1), m.end(1), m.group(1))
        val objectMention = Mention(chunk.chunkId, objectId, m.start(2), m.end(2), m.group(2))
        
        val candidate = RelationCandidate(
          a = subjectConcept,
          b = objectConcept,
          evidence = text.substring(math.max(0, m.start - 20), math.min(text.length, m.end + 20))
        )
        
        results += ((subjectMention, subjectConcept, Some(candidate)))
        results += ((objectMention, objectConcept, None))
      }
    }
    
    // Pattern: "X causes Y" / "X leads to Y"
    val causesPattern = """(\w+(?:\s+\w+)?)\s+(?:causes?|leads?\s+to)\s+(\w+(?:\s+\w+)?)""".r
    causesPattern.findAllMatchIn(text).foreach { m =>
      val causeStr = m.group(1).toLowerCase.trim
      val effectStr = m.group(2).toLowerCase.trim
      
      if (isValidConcept(causeStr) && isValidConcept(effectStr)) {
        val causeId = normalizeToConceptId(causeStr)
        val effectId = normalizeToConceptId(effectStr)
        
        val causeConcept = Concept(causeId, causeStr, List(causeStr), "pattern:causes")
        val effectConcept = Concept(effectId, effectStr, List(effectStr), "pattern:causes")
        
        val causeMention = Mention(chunk.chunkId, causeId, m.start(1), m.end(1), m.group(1))
        val effectMention = Mention(chunk.chunkId, effectId, m.start(2), m.end(2), m.group(2))
        
        val candidate = RelationCandidate(
          a = causeConcept,
          b = effectConcept,
          evidence = text.substring(math.max(0, m.start - 20), math.min(text.length, m.end + 20))
        )
        
        results += ((causeMention, causeConcept, Some(candidate)))
        results += ((effectMention, effectConcept, None))
      }
    }
    
    results.toList
  }

  /**
   * Tokenize text into words.
   */
  private def tokenize(text: String): List[String] = {
    text.split("\\W+").toList.map(_.trim).filter(_.nonEmpty)
  }

  /**
   * Extract important single-word terms.
   */
  private def extractImportantTerms(tokens: List[String]): List[String] = {
    tokens
      .filter(t => t.length >= 4)
      .filter(t => t.matches("(?i)[a-z]+"))
      .filterNot(t => stopWords.contains(t.toLowerCase))
      .filter(t => isTechnicalTerm(t) || t.charAt(0).isUpper) // Technical or capitalized
      .map(_.toLowerCase)
      .distinct
  }

  /**
   * Extract n-gram phrases (2-3 consecutive words).
   */
  private def extractNgramPhrases(text: String, tokens: List[String]): List[String] = {
    val results = scala.collection.mutable.ListBuffer[String]()
    
    // Bigrams
    tokens.sliding(2).foreach { window =>
      if (window.size == 2) {
        val phrase = window.mkString(" ").toLowerCase
        if (isValidNgramPhrase(window)) {
          results += phrase
        }
      }
    }
    
    // Trigrams
    tokens.sliding(3).foreach { window =>
      if (window.size == 3) {
        val phrase = window.mkString(" ").toLowerCase
        if (isValidNgramPhrase(window)) {
          results += phrase
        }
      }
    }
    
    results.toList.distinct
  }

  /**
   * Check if an n-gram is a valid phrase worth extracting.
   */
  private def isValidNgramPhrase(tokens: List[String]): Boolean = {
    // At least one technical term
    val hasTechnical = tokens.exists(isTechnicalTerm)
    // No stop words at edges
    val goodEdges = !stopWords.contains(tokens.head.toLowerCase) && 
                    !stopWords.contains(tokens.last.toLowerCase)
    // All words are reasonable length
    val goodLengths = tokens.forall(_.length >= 2)
    
    hasTechnical && goodEdges && goodLengths
  }

  /**
   * Check if a word is a technical/research term.
   */
  private def isTechnicalTerm(word: String): Boolean = {
    val lower = word.toLowerCase
    technicalPatterns.exists(p => lower.contains(p))
  }

  /**
   * Check if a string is a valid concept (not just stop words).
   */
  private def isValidConcept(s: String): Boolean = {
    val words = s.split("\\s+")
    words.nonEmpty && 
    words.forall(_.length >= 2) &&
    words.exists(w => !stopWords.contains(w.toLowerCase))
  }

  /**
   * Normalize a lemma string to a concept ID.
   * Format: "concept:<normalized_lemma>"
   */
  def normalizeToConceptId(lemma: String): String = {
    val normalized = lemma
      .toLowerCase
      .trim
      .replaceAll("[^a-z0-9]+", "_")
      .stripPrefix("_")
      .stripSuffix("_")
    s"concept:$normalized"
  }
}

