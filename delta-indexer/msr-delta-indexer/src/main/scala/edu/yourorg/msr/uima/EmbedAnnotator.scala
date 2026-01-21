package edu.yourorg.msr.uima

import org.apache.uima.fit.component.JCasAnnotator_ImplBase
import org.apache.uima.jcas.JCas
import org.apache.uima.fit.util.JCasUtil
import org.apache.uima.cas.text.AnnotationFS
import scala.jdk.CollectionConverters._
import edu.yourorg.msr.db.DeltaStore
import edu.yourorg.msr.embed.{OllamaClient, VecCodec}

class EmbedAnnotator extends JCasAnnotator_ImplBase {
  private val embedUrl   = edu.yourorg.msr.config.AppConfig.embed.url
  private val embedder   = edu.yourorg.msr.config.AppConfig.embed.model
  private val embedderVer= edu.yourorg.msr.config.AppConfig.embed.version
  private val batchSize  = sys.env.get("RAG_BATCH_SIZE").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(64)

  private val client = new OllamaClient(embedUrl)

  override def process(jCas: JCas): Unit = {
    val ts = jCas.getTypeSystem
    val docType   = ts.getType("edu.yourorg.msr.types.Doc")
    val chunkType = ts.getType("edu.yourorg.msr.types.Chunk")

    val docIt = jCas.getAnnotationIndex(docType).iterator()
    if (!docIt.hasNext) return
    val doc = docIt.next().asInstanceOf[AnnotationFS]

    // If doc was skipped, don't embed anything.
    val fSkip = docType.getFeatureByBaseName("skip")
    if (doc.getBooleanValue(fSkip)) return

    // Chunk fields
    val fChunkId = chunkType.getFeatureByBaseName("chunkId")
    val fText    = chunkType.getFeatureByBaseName("text")
    val fChHash  = chunkType.getFeatureByBaseName("contentHash")

    // Collect chunks missing embeddings for this (embedder, version)
    val chunks = JCasUtil.select(jCas, classOf[org.apache.uima.jcas.tcas.Annotation])
      .asScala
      .filter(_.getType == chunkType)
      .map(_.asInstanceOf[AnnotationFS])
      .filter(ch => !DeltaStore.hasEmbedding(ch.getStringValue(fChunkId), embedder, embedderVer))
      .toVector

    if (chunks.isEmpty) return

    // Batch to Ollama
    chunks.grouped(batchSize).foreach { group =>
      val ids   = group.map(_.getStringValue(fChunkId)).toVector
      val texts = group.map(_.getStringValue(fText)).toVector

      val embeddings: Vector[Vector[Float]] = client.embedBatch(embedder, texts)
      if (embeddings.length != ids.length) {
        throw new RuntimeException(s"Embedding count mismatch: got ${embeddings.length}, expected ${ids.length}")
      }
      // Upsert into DB
      ids.zip(embeddings).zip(group).foreach { case ((cid, vec), chFs) =>
        val chash = chFs.getStringValue(fChHash)
        DeltaStore.upsertEmbedding(cid, chash, embedder, embedderVer, VecCodec.floatsToBytes(vec))
      }
    }
  }
}
