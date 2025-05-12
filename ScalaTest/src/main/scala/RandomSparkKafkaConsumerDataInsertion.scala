package com.scala.hadoop

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object RandomSparkKafkaConsumerDataInsertion {
  def main(args: Array[String]): Unit = {
    System.setProperty("HADOOP_USER_NAME", "ubuntu")

    val spark = SparkSession.builder()
      .appName("KafkaToHDFS")
      .master("local[*]")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.hadoop.fs.defaultFS", "hdfs://namenode:8020")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.eventLog.enabled", "true")
      .config("spark.eventLog.dir", "hdfs://namenode:8020/spark-history")
      .getOrCreate()

    // Read Kafka stream
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", "hadoop")
      .option("startingOffsets", "earliest")
      .load()

    // Parse Kafka messages (key & value as string + timestamp)
    val messages = kafkaDF
      .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
      .withColumn("timestamp", current_timestamp())

    // Debug console output (optional)
    val consoleQuery = messages.writeStream
      .format("console")
      .outputMode("append")
      .option("truncate", false)
      .start()

    // Write to HDFS
    val hdfsQuery = messages.writeStream
      .format("parquet")
      .outputMode("append")
      .option("path", "hdfs://namenode:8020/output/kafka-output")
      .option("checkpointLocation", "hdfs://namenode:8020/checkpoints/kafka-output")
      .start()

    // Block the main thread to keep app running
    spark.streams.awaitAnyTermination()
  }
}

