package edu.yourorg.msr

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
import org.apache.tika.Tika

object HashPdfs {
  private val tika = new Tika()

  def main(args: Array[String]): Unit = {
    val input = sys.env.getOrElse("RAG_INPUT_DIR",
      throw new RuntimeException("Set RAG_INPUT_DIR to your corpus path"))
    val root = Paths.get(input)
    require(Files.exists(root), s"Path not found: $input")

    // pick first N PDFs to preview
    val files = Files.walk(root).iterator().asScala
      .filter(Files.isRegularFile(_))
      .filter(p => p.toString.toLowerCase.endsWith(".pdf"))
      .take(5) // change this number if you want more
      .toVector

    println(s"Previewing ${files.size} PDF(s) under: $input")

    files.zipWithIndex.foreach { case (p, i) =>
      try {
        val uri = p.toUri.toString
        val raw  = tika.parseToString(p.toFile)
        val text = raw.replaceAll("\\s+", " ").trim
        val docId       = sha256Hex(uri.getBytes(StandardCharsets.UTF_8))
        val contentHash = sha256Hex(text.getBytes(StandardCharsets.UTF_8))
        val title = p.getFileName.toString

        println(s"\n#${i+1}  ${p.toString}")
        println(s"  title        : $title")
        println(s"  uri          : $uri")
        println(s"  docId        : $docId")
        println(s"  contentHash  : $contentHash")
        println(s"  preview(text): " + text.take(160).replaceAll("\\s+", " ") + (if (text.length > 160) " ..." else ""))
      } catch {
        case e: Throwable =>
          System.err.println(s"[WARN] Failed to parse ${p.toString}: ${e.getMessage}")
      }
    }
  }

  private def sha256Hex(bytes: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    md.digest().map("%02x".format(_)).mkString
  }
}
