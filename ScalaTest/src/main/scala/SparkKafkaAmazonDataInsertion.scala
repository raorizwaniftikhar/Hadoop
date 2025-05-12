package com.scala.hadoop

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.{Properties, UUID}
import scala.jdk.CollectionConverters._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SparkKafkaAmazonDataInsertion {

  def main(args: Array[String]): Unit = {

    val folderPath = "/Users/raorizwaniftikhar/Desktop/Hadoop/amzon-review-data"
    val topic = "hadoop"

    val props = new Properties()
    props.put("bootstrap.servers", "localhost:9094") // Use "kafka:9092" if in Docker
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

    val producer = new KafkaProducer[String, String](props)

    println(s"✅ Reading files from [$folderPath] and sending to Kafka topic [$topic]...")

    val files = new File(folderPath).listFiles().filter(_.getName.endsWith(".csv"))

    Future {
      files.foreach { file =>
        println(s"📄 Processing file: ${file.getName}")
        val lines = Files.readAllLines(Paths.get(file.getAbsolutePath)).asScala
        val dataLines = lines.drop(1) // Skip header row

        dataLines.foreach { line =>
          val key = UUID.randomUUID().toString
          val record = new ProducerRecord[String, String](topic, key, line)
          producer.send(record, (metadata: RecordMetadata, exception: Exception) => {
            if (exception != null)
              println(s"❌ Error sending record: ${exception.getMessage}")
            else
              println(s"✅ Sent to ${metadata.topic()} [partition ${metadata.partition()}] at offset ${metadata.offset()}")
          })

          Thread.sleep(500) // Control send rate if needed
        }
      }
    }

    Thread.sleep(Long.MaxValue)
  }
}
