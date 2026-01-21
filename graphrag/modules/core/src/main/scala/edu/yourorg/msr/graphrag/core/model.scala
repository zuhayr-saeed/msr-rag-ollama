package edu.yourorg.msr.graphrag.core

/**
 * Core domain model for the GraphRAG pipeline.
 *
 * These types do NOT depend on Flink or Neo4j so they can be reused
 * in tests, the API, and the ingestion job.
 *
 * IDs are designed to align with HW1/HW2:
 *   - docId        = sha256(uri)
 *   - contentHash  = sha256(normalized_text)
 *   - chunkId      = sha256(docId:start:end:contentHash)
 *
 * We assume the actual hashing / retrieval already happened in HW2's
 * Postgres pipeline (rag.documents, rag.chunks, rag.retrieval_index).
 */
object model {

  // --------------------------
  //  Basic text containers
  // --------------------------

  /**
   * A chunk of text from a document.
   *
   * @param chunkId     Stable id: sha256(docId:start:end:contentHash)
   * @param docId       Stable document id from HW2 (sha256(uri))
   * @param chunkIx     Order of the chunk within the document
   * @param startPos    Character start offset in the normalized doc text
   * @param endPos      Character end offset in the normalized doc text
   * @param sectionPath Logical section path, e.g. "1.2.3" or "Intro/ThreatModel"
   * @param text        The actual text of the chunk
   * @param sourceUri   Original PDF path or URI (for provenance)
   * @param contentHash Hash over normalized text to detect changes
   * @param language    Optional ISO language tag ("en", "fr", etc.)
   */
  final case class Chunk(
    chunkId    : String,
    docId      : String,
    chunkIx    : Int,
    startPos   : Int,
    endPos     : Int,
    sectionPath: Option[String],
    text       : String,
    sourceUri  : String,
    contentHash: String,
    language   : Option[String]
  )

  // --------------------------
  //  Concepts and mentions
  // --------------------------

  /**
   * Concept = semantic unit in the graph (task, dataset, library, etc.).
   *
   * @param conceptId    Stable id, e.g. normalized lemma + disambiguator.
   * @param lemma        Canonical form, e.g. "just-in-time defect prediction"
   * @param surfaceForms Surface strings seen in text (could be large, so we store a few)
   * @param origin       Where this concept came from: "NER", "keyphrase", "title", "LLM"
   */
  final case class Concept(
    conceptId    : String,
    lemma        : String,
    surfaceForms : List[String],
    origin       : String
  )

  /**
   * A single mention of a Concept inside a Chunk.
   *
   * spanStart / spanEnd are offsets within the chunk text (0-based, end-exclusive).
   */
  final case class Mention(
    chunkId  : String,
    conceptId: String,
    spanStart: Int,
    spanEnd  : Int,
    surface  : String
  )

  // --------------------------
  //  Co-occurrence + relations
  // --------------------------

  /**
   * Co-occurrence of two concepts in a local window (same chunk or neighboring chunks).
   *
   * @param windowId Arbitrary string identifying the co-occur window, e.g. "docId:chunkIx"
   * @param freq     Count of how many times (a,b) co-occurred in that window or aggregation.
   */
  final case class CoOccur(
    a       : Concept,
    b       : Concept,
    windowId: String,
    freq    : Long
  )

  /**
   * Candidate relation to be scored by the LLM.
   *
   * @param evidence Short text snippet that supports the relation (from Chunk(s)).
   */
  final case class RelationCandidate(
    a       : Concept,
    b       : Concept,
    evidence: String
  )

  /**
   * Relation scored by the LLM + rules.
   *
   * @param predicate  Name of the relation, e.g. "is_a", "part_of", "causes", "related_to".
   * @param confidence [0,1] confidence score.
   * @param evidence   Text snippet cited as support.
   * @param ref        Optional doc/chunk reference string (e.g. "docId:chunkId:span").
   */
  final case class ScoredRelation(
    a         : Concept,
    predicate : String,
    b         : Concept,
    confidence: Double,
    evidence  : String,
    ref       : String
  )

  // --------------------------
  //  Graph writes (for Neo4j)
  // --------------------------

  /**
   * Supertype for all graph write operations.
   * We keep this small and immutable so it works well in Flink.
   */
  sealed trait GraphWrite

  /** Upsert a node with a label and stable id. */
  final case class UpsertNode(
    label: String,
    id   : String,
    props: Map[String, Any]
  ) extends GraphWrite

  /**
   * Upsert an edge between two nodes with properties.
   *
   * Example:
   *   UpsertEdge("Chunk", chunkId, "MENTIONS", "Concept", conceptId, Map("spanStart" -> 10, "spanEnd" -> 20))
   */
  final case class UpsertEdge(
    fromLabel: String,
    fromId   : String,
    rel      : String,
    toLabel  : String,
    toId     : String,
    props    : Map[String, Any]
  ) extends GraphWrite
}
