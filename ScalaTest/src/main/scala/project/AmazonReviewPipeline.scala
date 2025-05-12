package com.scala.hadoop
package project

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel

object AmazonReviewPipeline {
  def main(args: Array[String]): Unit = {
    System.setProperty("HADOOP_USER_NAME", "ubuntu")

    val spark = SparkSession.builder()
      .appName("AmazonReviewPipeline")
      .master("local[*]") // For dev only
      .enableHiveSupport()
      .config("spark.hadoop.fs.defaultFS", "hdfs://namenode:8020")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.eventLog.enabled", "true")
      .config("spark.eventLog.dir", "hdfs://namenode:8020/spark-history")
      .config("spark.sql.warehouse.dir", "hdfs://namenode:8020/user/hive/warehouse")
      .getOrCreate()

    import spark.implicits._

    val kafkaTopic = "amazon_reviews"
    val rawParquetPath = "hdfs://namenode:8020/data/amazon-reviews"
    val checkpointPath = "hdfs://namenode:8020/checkpoints/amazon"
    val analyticsOutputPath = "hdfs://namenode:8020/output/analytics"
    val windowOutputPath = "hdfs://namenode:8020/output/window-alerts"

    // Define review schema
    val reviewSchema = new StructType()
      .add("asin", StringType)
      .add("reviewText", StringType)
      .add("overall", DoubleType)
      .add("category", StringType)
      .add("summary", StringType)

    // ========== Task 1: Stream from Kafka and write to Hive via Parquet ==========
    val kafkaStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", kafkaTopic)
      .option("startingOffsets", "earliest")
      .load()

    val parsedStream = kafkaStream.selectExpr("CAST(value AS STRING) as json")
      .select(from_json($"json", reviewSchema).as("data"))
      .select("data.*")
      .filter($"asin".isNotNull && $"overall".isNotNull && $"category".isNotNull)

    // Save to Parquet for Hive table
    parsedStream.writeStream
      .format("parquet")
      .option("path", rawParquetPath)
      .option("checkpointLocation", checkpointPath + "/parquet")
      .outputMode("append")
      .start()

    println("✅ Task 1: Streaming from Kafka to Parquet for Hive.")

    // Wait before loading for downstream analytics
    Thread.sleep(10000)

    // ========== Hive External Table Registration ==========
    spark.sql(
      s""""
        |CREATE EXTERNAL TABLE IF NOT EXISTS amazon_reviews (
        |  asin STRING,
        |  reviewText STRING,
        |  overall DOUBLE,
        |  category STRING,
        |  summary STRING
          |)
        |STORED AS PARQUET
        |LOCATION '$rawParquetPath'
    """.stripMargin)

    val hiveDF = spark.sql("SELECT * FROM amazon_reviews WHERE reviewText IS NOT NULL")
      .persist(StorageLevel.MEMORY_AND_DISK)

    // ========== Task 2a: Category-Wise Analytics ==========
    val categoryAgg = hiveDF.groupBy("category")
      .agg(
        avg("overall").as("avg_rating"),
        count("overall").as("total_reviews"),
        stddev("overall").as("std_dev")
      ).orderBy(desc("avg_rating"))

    categoryAgg.write.mode("overwrite").option("header", "true")
      .csv(analyticsOutputPath + "/avg-rating")

    println("✅ Task 2a: Category-wise average rating written to HDFS.")

    // ========== Task 2b: Logistic Regression Model ==========
    import org.apache.spark.ml.feature.{Tokenizer, HashingTF, IDF}
    import org.apache.spark.ml.classification.LogisticRegression
    import org.apache.spark.ml.{Pipeline, PipelineModel}

    val labeledDF = hiveDF.withColumn("label", when($"overall" >= 4.0, 1).oterwise(0))

    val tokenizer = new Tokenizer().setInputCol("reviewText").setOutputCol("words")
    val hashingTF = new HashingTF().setInputCol("words").setOutputCol("rawFeatures")
    val idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    val lr = new LogisticRegression().setMaxIter(10)

    val pipeline = new Pipeline().setStages(Array(tokenizer, hashingTF, idf, lr))
    val model = pipeline.fit(labeledDF)

    val predictions = model.transform(labeledDF)
    predictions.select("label", "prediction", "probability").show(5, truncate = false)

    println("✅ Task 2b: Logistic regression model executed.")

    // ========== Task 3: Windowed Real-Time Alerts ==========
    val streamingAlerts = parsedStream.withColumn("event_time", current_timestamp())
      .filter($"overall" <= 2.0)

    val windowedCounts = streamingAlerts.groupBy(
      window($"event_time", "1 minute"),
      $"category"
    ).count()

    windowedCounts.writeStream
      .outputMode("complete")
      .format("csv")
      .option("checkpointLocation", checkpointPath + "/alerts")
      .option("path", windowOutputPath)
      .option("header", "true")
      .start()

    println("✅ Task 3: Windowed alerts stream launched.")
    spark.streams.awaitAnyTermination()
  }
}
