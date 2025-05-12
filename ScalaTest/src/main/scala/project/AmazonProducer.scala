package com.scala.hadoop
package project

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import java.util.{Properties, UUID}
import java.io.{File, FileNotFoundException}
import scala.io.Source
import scala.util.{Try, Using}

object AmazonProducer {

  def main(args: Array[String]): Unit = {
    val folderPath = "/Users/raorizwaniftikhar/Desktop/Hadoop/amzon-review-data" // 🔁 Replace with actual folder path
    val kafkaTopic = "amazon_reviews"

    // --- Kafka Configuration ---
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

    val producer = new KafkaProducer[String, String](props)

    try {
      val folder = new File(folderPath)
      if (!folder.exists() || !folder.isDirectory) {
        throw new IllegalArgumentException(s"Provided path is not a valid directory: $folderPath")
      }

      val csvFiles = folder.listFiles().filter(_.getName.endsWith(".csv"))
      println(s"[INFO] Found ${csvFiles.length} CSV files in: $folderPath")

      csvFiles.foreach { file =>
        println(s"[INFO] Processing file: ${file.getName}")

        Using(Source.fromFile(file)) { source =>
          val lines = source.getLines().toList
          if (lines.nonEmpty) {
            val header = lines.head.split(",").map(_.trim)
            val dataLines = lines.tail

            dataLines.foreach { line =>
              val values = line.split(",", -1).map(cleanValue)
              if (values.length == header.length) {
                val recordMap = header.zip(values).toMap

                // Create valid JSON manually
                val json = recordMap.map { case (k, v) =>
                  "\"" + escapeJson(k) + "\": \"" + escapeJson(v) + "\""
                }.mkString("{", ", ", "}")

                val key = recordMap.getOrElse("asin", UUID.randomUUID().toString)
                val record = new ProducerRecord[String, String](kafkaTopic, key, json)

                Try {
                  producer.send(record)
                }.recover {
                  case e: Exception =>
                    println(s"[ERROR] Failed to send record for asin=$key: ${e.getMessage}")
                }

                Thread.sleep(50) // Simulate stream delay
              }
            }
          }
        }.recover {
          case ex: FileNotFoundException =>
            println(s"[ERROR] File not found: ${file.getName}")
          case ex: Exception =>
            println(s"[ERROR] Failed to process file ${file.getName}: ${ex.getMessage}")
        }
      }

    } finally {
      println(s"[INFO] Finished sending data to Kafka topic: $kafkaTopic")
      producer.close()
    }
  }

  // --- Utility: Clean each field value ---
  private def cleanValue(value: String): String = {
    value.trim.replaceAll("\"", "").replaceAll("\\\\", "")
  }

  // --- Utility: Escape for JSON string ---
  private def escapeJson(value: String): String = {
    value.replace("\\", "\\\\").replace("\"", "\\\"")
  }
}

