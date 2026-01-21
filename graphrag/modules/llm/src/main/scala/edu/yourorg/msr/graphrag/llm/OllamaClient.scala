package edu.yourorg.msr.graphrag.llm

import edu.yourorg.msr.graphrag.core.Logging
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/**
 * Configuration for talking to an Ollama instance.
 *
 * Expected config in application.conf:
 *
 * ollama {
 *   endpoint   = "http://ollama:11434"
 *   model      = "llama3:instruct"
 *   temperature = 0.0
 *   timeoutMs   = 15000
 * }
 */
final case class OllamaConfig(
  endpoint   : String,
  model      : String,
  temperature: Double,
  timeoutMs  : Int
)

object OllamaConfig {

  def fromConfig(): OllamaConfig = {
    val conf = ConfigFactory.load().getConfig("ollama")
    OllamaConfig(
      endpoint    = conf.getString("endpoint"),
      model       = conf.getString("model"),
      temperature = conf.getDouble("temperature"),
      timeoutMs   = conf.getInt("timeoutMs")
    )
  }
}

/**
 * Minimal HTTP client for Ollama's /api/chat endpoint.
 *
 * We send a single-message chat request with our prompt and get back
 * the raw JSON string. Higher-level parsing happens in the scorer.
 */
final class OllamaClient(config: OllamaConfig) extends Logging {

  private val httpClient: HttpClient =
    HttpClient.newBuilder().build()

  /**
   * Send a chat-style prompt to Ollama and return the raw JSON response body.
   *
   * NOTE: This method does NOT parse the model's output; it just returns
   * the response as a String.
   */
  def chat(prompt: String): Option[String] = {
    val uri       = s"${config.endpoint.stripSuffix("/")}/api/chat"
    val timeout   = java.time.Duration.ofMillis(config.timeoutMs.toLong)

    val payloadJson =
      s"""
         |{
         |  "model": "${config.model}",
         |  "stream": false,
         |  "options": {
         |    "temperature": ${config.temperature}
         |  },
         |  "messages": [
         |    {
         |      "role": "user",
         |      "content": ${encodeJsonString(prompt)}
         |    }
         |  ]
         |}
         |""".stripMargin

    val request = HttpRequest.newBuilder()
      .uri(URI.create(uri))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
      .build()

    logger.debug(s"Calling Ollama at $uri with model '${config.model}'.")

    try {
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      val status   = response.statusCode()

      if (status >= 200 && status < 300) {
        Some(response.body())
      } else {
        logger.error(s"Ollama returned non-2xx status: $status, body: ${response.body()}")
        None
      }
    } catch {
      case ex: Exception =>
        logger.error("Error while calling Ollama", ex)
        None
    }
  }

  /**
   * Very small helper to produce a JSON string literal.
   * We only escape backslash and double quote here; good enough for prompts.
   */
  private def encodeJsonString(s: String): String = {
    "\"" + s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"") + "\""
  }
}

object OllamaClient {

  /** Convenience constructor: create client using Typesafe Config. */
  def fromConfig(): OllamaClient =
    new OllamaClient(OllamaConfig.fromConfig())
}
