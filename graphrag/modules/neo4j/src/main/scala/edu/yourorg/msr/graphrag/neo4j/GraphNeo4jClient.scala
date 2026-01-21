package edu.yourorg.msr.graphrag.neo4j

import edu.yourorg.msr.graphrag.core.Logging
import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase, SessionConfig, AccessMode}
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConverters._

/**
 * Thin wrapper around the official Neo4j Java driver.
 *
 * It:
 *   - creates a driver from Typesafe Config (application.conf),
 *   - exposes a runCommands method for Seq[CypherCommand],
 *   - uses a single write transaction per batch for atomic upserts.
 */
final class GraphNeo4jClient(private val driver: Driver) extends Logging {

  /**
   * Execute a batch of CypherCommand in a single write transaction.
   *
   * This is intended to be called from the Flink sink later, but can also be
   * used in tests or small scripts.
   */
  def runCommands(commands: Seq[CypherCommand]): Unit = {
    if (commands.isEmpty) {
      logger.debug("GraphNeo4jClient.runCommands called with empty command list – nothing to do.")
      return
    }

    logger.debug(s"Running ${commands.size} Cypher command(s) against Neo4j.")

    val sessionConfig =
      SessionConfig.builder()
        .withDefaultAccessMode(AccessMode.WRITE)
        .build()

    val session = driver.session(sessionConfig)

    try {
      session.writeTransaction { tx =>
        commands.foreach { cmd =>
  logger.trace(s"Executing Cypher: ${cmd.text}")

  // Neo4j Java driver expects java.util.Map[String, Object]
  // so we need to ensure all values are AnyRef (boxed).
  val javaParams: java.util.Map[String, Object] =
    cmd.params
      .map { case (k, v) => k -> v.asInstanceOf[AnyRef] }
      .asJava

  tx.run(cmd.text, javaParams)
       }

        null // transaction function must return something; we don't care about the result.
      }
    } finally {
      session.close()
    }
  }

  /** Close the underlying driver. Call this when shutting down the app. */
  def close(): Unit = {
    logger.info("Closing Neo4j driver.")
    driver.close()
  }
}

/**
 * Factory for GraphNeo4jClient using Typesafe Config.
 *
 * Expected config in application.conf:
 *
 * neo4j {
 *   uri     = "bolt://localhost:7687"
 *   user    = "neo4j"
 *   passEnv = "NEO4J_PASS"  # name of env var that holds the password
 * }
 */
object GraphNeo4jClient extends Logging {

  def fromConfig(): GraphNeo4jClient = {
    val config = ConfigFactory.load()

    val uri     = config.getString("neo4j.uri")
    val user    = config.getString("neo4j.user")
    val passEnv = config.getString("neo4j.passEnv")

    val password =
      sys.env.getOrElse(passEnv, {
        val msg = s"Environment variable '$passEnv' (referenced by neo4j.passEnv) is not set."
        logger.error(msg)
        throw new IllegalStateException(msg)
      })

    logger.info(s"Creating Neo4j driver for $uri as user '$user' (password read from env:$passEnv).")

    val driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))

    new GraphNeo4jClient(driver)
  }
}
