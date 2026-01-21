import sbt._
import Keys._

val flinkVersion = "1.20.2"
val neo4jDriverVersion = "5.25.0"
val akkaVersion = "2.8.5"
val akkaHttpVersion = "10.2.9"
val circeVersion = "0.14.9"

ThisBuild / scalaVersion := "2.12.19"
ThisBuild / organization := "edu.uic"
ThisBuild / version := "1.0.0"

// Common JVM options for JDK 17+ compatibility with Flink/Kryo serialization
val jvmModuleOpens = Seq(
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/java.util.regex=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
  "--add-opens=java.base/java.security=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "--add-opens=java.base/java.time=ALL-UNNAMED"
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-Xlint",
    "-unchecked"
  ),
  // Fork JVM for run and test to apply module opens
  fork := true,
  javaOptions ++= jvmModuleOpens,
  // Test configuration
  Test / fork := true,
  Test / javaOptions ++= jvmModuleOpens,
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  libraryDependencies ++= Seq(
    // Config
    "com.typesafe"              % "config"             % "1.4.3",
    // Logging
    "ch.qos.logback"            % "logback-classic"    % "1.5.6",
    "org.slf4j"                 % "slf4j-api"          % "2.0.16",
    // JSON (for API + LLM)
    "io.circe"                 %% "circe-core"         % circeVersion,
    "io.circe"                 %% "circe-generic"      % circeVersion,
    "io.circe"                 %% "circe-parser"       % circeVersion,
    // Testing
    "org.scalatest"            %% "scalatest"          % "3.2.19" % Test
  )
)

// Root project aggregates all modules
lazy val root = (project in file("."))
  .aggregate(core, ingestion, neo4jModule, llmModule, api)
  .settings(
    name := "cs441-hw3-graphrag",
    // Disable publishing for root
    publish / skip := true
  )

// Core module: domain model, no external dependencies beyond config/logging
lazy val core = (project in file("modules/core"))
  .settings(
    commonSettings,
    name := "graphrag-core",
    description := "Core domain model for GraphRAG pipeline"
  )

// Neo4j module: graph database operations
lazy val neo4jModule = (project in file("modules/neo4j"))
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "graphrag-neo4j",
    description := "Neo4j graph database operations",
    libraryDependencies ++= Seq(
      "org.neo4j.driver" % "neo4j-java-driver" % neo4jDriverVersion
    )
  )

// LLM module: Ollama integration for relation scoring
lazy val llmModule = (project in file("modules/llm"))
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "graphrag-llm",
    description := "Ollama LLM integration for relation extraction"
  )

// Ingestion module: Flink streaming pipeline
lazy val ingestion = (project in file("modules/ingestion"))
  .dependsOn(core, neo4jModule, llmModule)
  .settings(
    commonSettings,
    name := "graphrag-ingestion",
    description := "Apache Flink streaming pipeline for GraphRAG ingestion",
    
    // Flink requires these JVM options for serialization
    Compile / run / fork := true,
    Compile / run / javaOptions ++= jvmModuleOpens ++ Seq(
      "-Xmx2g",
      "-Xms512m"
    ),
    
    libraryDependencies ++= Seq(
      // Flink core + streaming
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion,
      "org.apache.flink" %  "flink-clients"         % flinkVersion,
      "org.apache.flink" %  "flink-core"            % flinkVersion,
      "org.apache.flink" %  "flink-runtime"         % flinkVersion,
      // Required for MiniCluster REST endpoint
      "org.apache.flink" %  "flink-runtime-web"     % flinkVersion,
      
      // Neo4j driver
      "org.neo4j.driver" % "neo4j-java-driver"      % neo4jDriverVersion
    )
  )

// API module: REST microservices
lazy val api = (project in file("modules/api"))
  .dependsOn(core, neo4jModule, llmModule)
  .settings(
    commonSettings,
    name := "graphrag-api",
    description := "REST API microservices for GraphRAG queries",
    
    Compile / run / fork := true,
    Compile / run / javaOptions ++= jvmModuleOpens ++ Seq(
      "-Xmx1g"
    ),
    
    libraryDependencies ++= Seq(
      // Akka HTTP for REST API
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-http"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream"      % akkaVersion,
      // Neo4j driver for direct graph queries
      "org.neo4j.driver"   % "neo4j-java-driver" % neo4jDriverVersion
    )
  )

