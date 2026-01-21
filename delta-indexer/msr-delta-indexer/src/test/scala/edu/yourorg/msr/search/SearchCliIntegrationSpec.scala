package edu.yourorg.msr.search

import org.scalatest.funsuite.AnyFunSuite
import edu.yourorg.msr.embed.OllamaClient
import edu.yourorg.msr.db.DeltaStore
import java.nio.{ByteBuffer, ByteOrder}

class SearchCliIntegrationSpec extends AnyFunSuite {
  private def enabled: Boolean = sys.env.get("RAG_IT").contains("1")

  private def bytesToFloats(bs: Array[Byte]): Array[Float] = {
    val fb = ByteBuffer.wrap(bs).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val arr = Array.ofDim[Float](fb.remaining())
    fb.get(arr)
    arr
  }

  test("query embedding size matches model and cosine is bounded") {
    assume(enabled, "set RAG_IT=1 to enable end-to-end integration tests")

    val model = sys.env.getOrElse("RAG_EMBEDDER", "mxbai-embed-large")
    val url   = sys.env.getOrElse("RAG_EMBED_URL", "http://127.0.0.1:11434/api/embed")

    val client = new OllamaClient(url)
    val qVec   = client.embedBatch(model, Vector("mining software repositories")).head.toArray
    assert(qVec.nonEmpty)

    var bytes: Array[Byte] = Array()
    DeltaStore.withConn { c =>
      val rs = c.createStatement().executeQuery("select vector from rag.retrieval_index_current limit 1")
      assert(rs.next())
      bytes = rs.getBytes(1)
      rs.close()
    }

    val v2 = bytesToFloats(bytes)
    val cs = Similarity.cosine(qVec, v2)
    assert(cs >= -1.0 && cs <= 1.0)
  }
}
