package com.scala.hadoop

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object SparkSubmitKafkaAmazonData {
  def main(args: Array[String]): Unit = {
    System.setProperty("HADOOP_USER_NAME", "ubuntu")

    val spark = SparkSession.builder()
      .appName("KafkaToAnalytics")
      .master("local[*]") // remove this for cluster
      .config("spark.hadoop.fs.defaultFS", "hdfs://namenode:8020")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.eventLog.enabled", "true")
      .config("spark.eventLog.dir", "hdfs://namenode:8020/spark-history")
      .getOrCreate()

    import spark.implicits._

    // Step 1: Read stream from Kafka
    val kafkaStreamDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", "hadoop")
      .option("startingOffsets", "earliest")
      .load()

    val parsedStream = kafkaStreamDF
      .selectExpr("CAST(value AS STRING) as raw_value")

    // Step 2: Save stream to Parquet in HDFS
    val streamingQuery = parsedStream.writeStream
      .format("parquet")
      .option("path", "hdfs://namenode:8020/amazon-review")
      .option("checkpointLocation", "hdfs://namenode:8020/checkpoints/kafka-hdfs")
      .outputMode("append")
      .start()

    println("✅ Kafka stream is being written to HDFS...")

    // Wait some time before running analytics
    streamingQuery.awaitTermination(10000) // Wait for 10 seconds (adjust as needed)

    // Step 3: Read saved parquet data
    val parquetDF = spark.read.parquet("hdfs://namenode:8020/amazon-review")

    val reviews = parquetDF.select(
      split($"raw_value", ",").getItem(2).cast("double").as("rating"),
      split($"raw_value", ",").getItem(3).as("category")
    ).filter($"rating".isNotNull && $"category".isNotNull)

    // Step 4: Perform aggregation
    val resultDF = reviews.groupBy("category")
      .agg(
        avg("rating").as("avg_rating"),
        count("rating").as("total_reviews"),
        stddev("rating").as("std_dev")
      )
      .orderBy(desc("avg_rating"))

    // Step 5: Write results to HDFS
    resultDF.write
      .mode("overwrite")
      .option("header", "true")
      .csv("hdfs://namenode:8020/output/avg-rating-per-category")

    println("✅ Done: Average rating per category written to HDFS.")
    spark.stop()
  }
}
