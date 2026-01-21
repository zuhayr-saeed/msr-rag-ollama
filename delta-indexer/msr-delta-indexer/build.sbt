ThisBuild / scalaVersion := "2.13.14"
name := "msr-delta-indexer-uima"

libraryDependencies ++= Seq(
  // UIMA + Tika
  "org.apache.uima" % "uimafit-core" % "3.5.0",
  "org.apache.uima" % "uimaj-core" % "3.5.0",
  "org.apache.tika" % "tika-core" % "2.9.2",
  "org.apache.tika" % "tika-parsers-standard-package" % "2.9.2",

  // Config + logging
  "com.typesafe" % "config" % "1.4.3",
  "org.slf4j" % "slf4j-api" % "2.0.12",
  "ch.qos.logback" % "logback-classic" % "1.5.6",
  "org.apache.logging.log4j" % "log4j-to-slf4j" % "2.23.1",

  // DB
  "org.postgresql" % "postgresql" % "42.7.4",
  "com.zaxxer" % "HikariCP" % "5.1.0",

  // HTTP / JSON (sttp core + circe)
  "com.softwaremill.sttp.client3" %% "core" % "3.9.7",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.7",
  "io.circe" %% "circe-core" % "0.14.10",
  "io.circe" %% "circe-parser" % "0.14.10",

  // Tests
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)
