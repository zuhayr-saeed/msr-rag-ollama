package edu.yourorg.msr.graphrag.api

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

import com.typesafe.config.ConfigFactory
import edu.yourorg.msr.graphrag.core.Logging
import edu.yourorg.msr.graphrag.neo4j.GraphNeo4jClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * GraphRAG REST API Server
 * 
 * Exposes RESTful microservices for querying the GraphRAG knowledge graph:
 * 
 *   - POST /v1/query          - Semantic query against the knowledge graph
 *   - GET  /v1/evidence/:id   - Retrieve evidence snippet for a claim
 *   - GET  /v1/graph/concept/:id/neighbors - Explore graph neighborhood
 *   - GET  /v1/explain/trace/:requestId - Execution trace for past query
 *   - GET  /health            - Health check endpoint
 * 
 * All responses are JSON. Uses Neo4j as the backing graph store.
 */
object GraphRagApi extends Logging {

  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("Starting GraphRAG REST API Server")
    logger.info("=" * 60)

    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "graphrag-api")
    implicit val ec: ExecutionContext = system.executionContext

    val config = ConfigFactory.load()
    val host = sys.env.getOrElse("API_HOST", config.getString("graphrag.api.host"))
    val port = sys.env.getOrElse("API_PORT", config.getString("graphrag.api.port")).toInt

    // Initialize services
    val neo4jClient = createNeo4jClient()
    val queryService = new QueryService(neo4jClient)
    val evidenceService = new EvidenceService(neo4jClient)
    val exploreService = new ExploreService(neo4jClient)

    // Create routes
    val routes = createRoutes(queryService, evidenceService, exploreService)

    // Start server
    val bindingFuture = Http().newServerAt(host, port).bind(routes)

    bindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info(s"GraphRAG API server listening at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        logger.error(s"Failed to bind HTTP server: ${ex.getMessage}", ex)
        system.terminate()
    }

    // Handle shutdown
    sys.addShutdownHook {
      logger.info("Shutting down GraphRAG API server...")
      bindingFuture
        .flatMap(_.unbind())
        .onComplete { _ =>
          neo4jClient.close()
          system.terminate()
        }
    }
  }

  private def createNeo4jClient(): GraphNeo4jClient = {
    try {
      GraphNeo4jClient.fromConfig()
    } catch {
      case e: Exception =>
        logger.warn(s"Could not connect to Neo4j from config: ${e.getMessage}")
        logger.warn("Using fallback Neo4j connection with environment variables")
        
        val uri = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
        val user = sys.env.getOrElse("NEO4J_USER", "neo4j")
        val pass = sys.env.getOrElse("NEO4J_PASS", "test123")
        
        import org.neo4j.driver.{AuthTokens, GraphDatabase}
        val driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass))
        new GraphNeo4jClient(driver)
    }
  }

  private def createRoutes(
    queryService: QueryService,
    evidenceService: EvidenceService,
    exploreService: ExploreService
  )(implicit ec: ExecutionContext): Route = {
    
    concat(
      // Health check
      path("health") {
        get {
          complete(HttpEntity(ContentTypes.`application/json`, """{"status":"healthy"}"""))
        }
      },
      
      // API version 1
      pathPrefix("v1") {
        concat(
          // Query endpoint
          path("query") {
            post {
              entity(as[String]) { body =>
                val response = queryService.handleQuery(body)
                complete(HttpEntity(ContentTypes.`application/json`, response))
              }
            }
          },
          
          // Evidence endpoint
          pathPrefix("evidence") {
            path(Segment) { evidenceId =>
              get {
                val response = evidenceService.getEvidence(evidenceId)
                complete(HttpEntity(ContentTypes.`application/json`, response))
              }
            }
          },
          
          // Graph exploration endpoints
          pathPrefix("graph") {
            concat(
              // Concept neighbors
              path("concept" / Segment / "neighbors") { conceptId =>
                get {
                  parameters(
                    "direction".withDefault("both"),
                    "depth".as[Int].withDefault(1),
                    "limit".as[Int].withDefault(50),
                    "edgeTypes".withDefault("")
                  ) { (direction, depth, limit, edgeTypes) =>
                    val response = exploreService.getNeighbors(
                      conceptId, direction, depth, limit, edgeTypes.split(",").filter(_.nonEmpty).toList
                    )
                    complete(HttpEntity(ContentTypes.`application/json`, response))
                  }
                }
              },
              
              // Statistics endpoint
              path("stats") {
                get {
                  val response = exploreService.getStatistics()
                  complete(HttpEntity(ContentTypes.`application/json`, response))
                }
              }
            )
          },
          
          // Explain endpoint
          pathPrefix("explain") {
            path("trace" / Segment) { requestId =>
              get {
                val response = s"""{"requestId":"$requestId","trace":"Query tracing not yet implemented"}"""
                complete(HttpEntity(ContentTypes.`application/json`, response))
              }
            }
          }
        )
      }
    )
  }
}

