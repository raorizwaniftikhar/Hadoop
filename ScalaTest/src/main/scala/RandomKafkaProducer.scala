package com.scala.hadoop

import java.util.{Properties, UUID}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object RandomKafkaProducer {
  def main(args: Array[String]): Unit = {

    val props = new Properties()
    props.put("bootstrap.servers", "localhost:9094") // If using Docker, try "host.docker.internal:9092"
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "all")
    props.put("retries", "1")

    val topic = "hadoop"
    val producer = new KafkaProducer[String, String](props)

    println(s"✅ Starting Kafka Producer for topic [$topic]...")

    Future {
      while (true) {
        val messageValue = Random.alphanumeric.take(64).mkString
        val messageKey = UUID.randomUUID().toString
        val record = new ProducerRecord[String, String](topic, messageKey, messageValue)

        // Asynchronous send with error logging
        producer.send(record, (metadata: RecordMetadata, exception: Exception) => {
          if (exception != null) {
            println(s"❌ Error sending message: ${exception.getMessage}")
          } else {
            println(s"✅ Sent to ${metadata.topic()} [partition ${metadata.partition()}] at offset ${metadata.offset()}:\n  $messageValue\n")
          }
        })

        Thread.sleep(1000)
      }
    }

    // Keep the main thread running
    Thread.sleep(Long.MaxValue)
  }
}
