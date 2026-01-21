package edu.yourorg.msr.uima

import org.apache.uima.fit.component.JCasAnnotator_ImplBase
import org.apache.uima.jcas.JCas
import org.apache.uima.cas.text.AnnotationFS
import org.apache.uima.fit.util.JCasUtil
import scala.jdk.CollectionConverters._
import edu.yourorg.msr.db.DeltaStore

class PersistAnnotator extends JCasAnnotator_ImplBase {
  override def process(jCas: JCas): Unit = {
    val ts = jCas.getTypeSystem
    val docType   = ts.getType("edu.yourorg.msr.types.Doc")
    val chunkType = ts.getType("edu.yourorg.msr.types.Chunk")

    val it = jCas.getAnnotationIndex(docType).iterator()
    if (!it.hasNext) return
    val doc = it.next().asInstanceOf[AnnotationFS]

    val fSkip        = docType.getFeatureByBaseName("skip")
    val skip = doc.getBooleanValue(fSkip)
    if (skip) return

    val fDocId       = docType.getFeatureByBaseName("docId")
    val fUri         = docType.getFeatureByBaseName("uri")
    val fTitle       = docType.getFeatureByBaseName("title")
    val fLang        = docType.getFeatureByBaseName("language")
    val fContentHash = docType.getFeatureByBaseName("contentHash")

    val docId = doc.getStringValue(fDocId)
    val uri   = doc.getStringValue(fUri)
    val title = doc.getStringValue(fTitle)
    val lang  = doc.getStringValue(fLang)
    val ch    = doc.getStringValue(fContentHash)

    // Upsert document
    DeltaStore.upsertDocument(docId, uri, title, lang, ch)

    // Upsert chunks
    val fChunkId     = chunkType.getFeatureByBaseName("chunkId")
    val fChunkIx     = chunkType.getFeatureByBaseName("chunkIx")
    val fSectionPath = chunkType.getFeatureByBaseName("sectionPath")
    val fText        = chunkType.getFeatureByBaseName("text")
    val fChHash      = chunkType.getFeatureByBaseName("contentHash")

    val chunks = JCasUtil.select(jCas, classOf[org.apache.uima.jcas.tcas.Annotation])
      .asScala
      .filter(_.getType == chunkType)
      .map(_.asInstanceOf[AnnotationFS])

    chunks.foreach { chn =>
      DeltaStore.upsertChunk(
        chn.getStringValue(fChunkId),
        docId,
        chn.getIntValue(fChunkIx),
        chn.getBegin, chn.getEnd,
        chn.getStringValue(fSectionPath),
        chn.getStringValue(fText),
        chn.getStringValue(fChHash)
      )
    }
  }
}
