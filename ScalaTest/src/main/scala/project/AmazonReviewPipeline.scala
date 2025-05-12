package com.scala.hadoop
package project
// ===============================
// Amazon Reviews Big Data Pipeline - Full Task Implementation
// Technologies: Kafka, Spark Streaming, HDFS, Parquet, Scala
// Author: Rizwan Iftikhar
// ===============================

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object AmazonReviewPipeline {
  def main(args: Array[String]): Unit = {
    System.setProperty("HADOOP_USER_NAME", "ubuntu") // Required for Hadoop HDFS access

    val spark = SparkSession.builder()
      .appName("AmazonReviewPipeline")
      .master("local[*]") // Remove for YARN/cluster deployment
      .config("spark.hadoop.fs.defaultFS", "hdfs://namenode:8020")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.eventLog.enabled", "true")
      .config("spark.eventLog.dir", "hdfs://namenode:8020/spark-history")
      .getOrCreate()

    import spark.implicits._

    // Config Paths
    val kafkaTopic = "amazon_reviews"
    val parquetPath = "hdfs://namenode:8020/data/amazon-reviews"
    val checkpointPath = "hdfs://namenode:8020/checkpoints/amazon-pipeline"
    val outputAnalyticsPath = "hdfs://namenode:8020/output/analytics"
    val windowOutputPath = "hdfs://namenode:8020/output/windowed-alerts"

    // Schema for parsing JSON messages from Kafka
    val reviewSchema = new StructType()
      .add("asin", StringType)
      .add("reviewText", StringType)
      .add("overall", DoubleType)
      .add("category", StringType)
      .add("summary", StringType)

    // ========== Task 1: Read from Kafka and store in HDFS as Parquet ==========
    val kafkaStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", kafkaTopic)
      .option("startingOffsets", "earliest")
      .load()

    val parsedStream = kafkaStream.selectExpr("CAST(value AS STRING) as json")
      .select(from_json(col("json"), reviewSchema).as("data"))
      .select("data.*")
      .filter(col("asin").isNotNull && col("overall").isNotNull && col("category").isNotNull)

    val writeQuery = parsedStream.writeStream
      .format("parquet")
      .option("path", parquetPath)
      .option("checkpointLocation", checkpointPath)
      .outputMode("append")
      .start()

    println("✅ Task 1: Streaming reviews from Kafka to HDFS as Parquet...")
    writeQuery.awaitTermination(10000) // Wait 10s for some data to write

    // ========== Task 2a: Static ML Analytics on saved Parquet ==========
    val reviewDF = spark.read.parquet(parquetPath)
      .filter(col("reviewText").isNotNull && col("overall").isNotNull && col("category").isNotNull)

    val categoryAgg = reviewDF.groupBy("category")
      .agg(
        avg("overall").as("avg_rating"),
        count("overall").as("total_reviews"),
        stddev("overall").as("std_dev")
      ).orderBy(desc("avg_rating"))

    categoryAgg.write.mode("overwrite")
      .option("header", "true")
      .csv(s"$outputAnalyticsPath/avg-rating-per-category")

    println("✅ Task 2a: Wrote average rating per category to HDFS.")

    // ========== Task 2b: ML Model - Binary Classification ==========
    import org.apache.spark.ml.feature.{Tokenizer, HashingTF, IDF, StringIndexer}
    import org.apache.spark.ml.classification.LogisticRegression
    import org.apache.spark.ml.{Pipeline, PipelineModel}

    val labeled = reviewDF.withColumn("label", when(col("overall") >= 4.0, 1).otherwise(0))

    val tokenizer = new Tokenizer().setInputCol("reviewText").setOutputCol("words")
    val hashingTF = new HashingTF().setInputCol("words").setOutputCol("rawFeatures")
    val idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    val lr = new LogisticRegression().setMaxIter(10)

    val pipeline = new Pipeline().setStages(Array(tokenizer, hashingTF, idf, lr))
    val model = pipeline.fit(labeled)

    val predictions = model.transform(labeled)
    predictions.select("label", "prediction", "probability").show(5, truncate = false)

    println("✅ Task 2b: Logistic Regression model predictions complete.")

    // ========== Task 3: Windowed Streaming Alerts ==========
    val streamingAlerts = parsedStream.withColumn("event_time", current_timestamp())
      .filter(col("overall") <= 2.0)

    val windowedCounts = streamingAlerts.groupBy(
      window(col("event_time"), "1 minute"),
      col("category")
    ).count()

    val alertQuery = windowedCounts.writeStream
      .outputMode("complete")
      .option("checkpointLocation", s"$checkpointPath/windowed")
      .format("csv")
      .option("path", windowOutputPath)
      .option("header", "true")
      .start()

    println("✅ Task 3: Real-time windowed alerting based on low ratings started.")
    alertQuery.awaitTermination()
  }
}
