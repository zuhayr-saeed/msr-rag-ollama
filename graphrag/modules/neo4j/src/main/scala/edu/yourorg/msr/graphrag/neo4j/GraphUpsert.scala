package edu.yourorg.msr.graphrag.neo4j

import edu.yourorg.msr.graphrag.core.model._

/**
 * A single Cypher statement with its parameter map.
 *
 * Example:
 *   CypherCommand(
 *     "MERGE (c:Concept {conceptId: $id}) SET c += $props",
 *     Map("id" -> "concept:JIT_Defect_Prediction", "props" -> Map("lemma" -> "JIT defect prediction"))
 *   )
 */
final case class CypherCommand(
  text  : String,
  params: Map[String, Any]
)

/**
 * Translate GraphWrite operations into parameterised Cypher statements
 * that can be executed via the official Neo4j Java driver.
 *
 * We keep this logic here so the Flink pipeline can stay storage-agnostic:
 * it just emits GraphWrite; this module handles the Neo4j specifics.
 */
object GraphUpsert {

  /**
   * Map a GraphWrite into one or more Cypher commands.
   *
   * We use MERGE with stable ids so the pipeline is idempotent:
   * - Re-running the job will not create duplicate nodes/edges.
   */
  def commands(write: GraphWrite): Seq[CypherCommand] =
    write match {

      // ----------------------------
      //  Node upserts
      // ----------------------------

      // Concept nodes get a dedicated key: conceptId
      case UpsertNode("Concept", id, props) =>
        Seq(
          CypherCommand(
            "MERGE (c:Concept {conceptId: $id}) SET c += $props",
            Map("id" -> id, "props" -> props)
          )
        )

      // Chunk nodes get chunkId as primary key
      case UpsertNode("Chunk", id, props) =>
        Seq(
          CypherCommand(
            "MERGE (ch:Chunk {chunkId: $id}) SET ch += $props",
            Map("id" -> id, "props" -> props)
          )
        )

      // Fallback: generic label with an 'id' property as primary key
      case UpsertNode(label, id, props) =>
        Seq(
          CypherCommand(
            // We interpolate the label, but keep id/props as parameters.
            s"MERGE (n:$label {id: $$id}) SET n += $$props",
            Map("id" -> id, "props" -> props)
          )
        )

      // ----------------------------
      //  Edge upserts
      // ----------------------------

      // Chunk -> Concept :MENTIONS edge
      case UpsertEdge("Chunk", from, "MENTIONS", "Concept", to, props) =>
        Seq(
          CypherCommand(
            """MERGE (ch:Chunk {chunkId: $from})
              |MERGE (c:Concept {conceptId: $to})
              |MERGE (ch)-[r:MENTIONS]->(c)
              |SET r += $props""".stripMargin,
            Map("from" -> from, "to" -> to, "props" -> props)
          )
        )

      // Concept -> Concept :RELATES_TO edge
      case UpsertEdge("Concept", a, "RELATES_TO", "Concept", b, props) =>
        Seq(
          CypherCommand(
            """MERGE (a:Concept {conceptId: $a})
              |MERGE (b:Concept {conceptId: $b})
              |MERGE (a)-[r:RELATES_TO]->(b)
              |SET r += $props""".stripMargin,
            Map("a" -> a, "b" -> b, "props" -> props)
          )
        )

      // Concept -> Concept :CO_OCCURS edge, incrementing a freq counter
      case UpsertEdge("Concept", a, "CO_OCCURS", "Concept", b, props) =>
        val inc: Long =
          props.get("inc") match {
            case Some(v: Long) => v
            case Some(v: Int)  => v.toLong
            case _             => 1L
          }

        Seq(
          CypherCommand(
            """MERGE (a:Concept {conceptId: $a})
              |MERGE (b:Concept {conceptId: $b})
              |MERGE (a)-[r:CO_OCCURS]->(b)
              |SET r.freq = coalesce(r.freq, 0) + $inc""".stripMargin,
            Map("a" -> a, "b" -> b, "inc" -> inc)
          )
        )

      // Generic fallback: labeled endpoints with 'id' as primary key
      case UpsertEdge(fromLabel, fromId, rel, toLabel, toId, props) =>
        Seq(
          CypherCommand(
            s"""MERGE (from:$fromLabel {id: $$fromId})
               |MERGE (to:$toLabel {id: $$toId})
               |MERGE (from)-[r:$rel]->(to)
               |SET r += $$props""".stripMargin,
            Map(
              "fromId" -> fromId,
              "toId"   -> toId,
              "props"  -> props
            )
          )
        )
    }
}
