ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.18" // Spark 3.3.2 uses Scala 2.13.x

// Root project definition
lazy val root = (project in file("."))
  .settings(
    name := "ScalaTest",
    idePackagePrefix := Some("com.scala.hadoop")
  )

// Dependencies
libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.5.5",
  "org.apache.spark" %% "spark-sql" % "3.5.5",
  "org.apache.spark" %% "spark-streaming" % "3.5.5",
  "org.apache.spark" %% "spark-mllib" % "3.5.5",
  "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.5",
  "org.apache.kafka" %% "kafka" % "3.8.0",
  "org.apache.kafka" % "kafka-clients" % "3.8.0",
  "org.apache.hadoop" % "hadoop-client" % "3.4.0"
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", _ @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

// Optional: Handle dependency version conflicts
dependencyOverrides += "com.github.luben" % "zstd-jni" % "1.5.5-4"

// Kafka connector repo
resolvers += "Confluent" at "https://packages.confluent.io/maven/"
