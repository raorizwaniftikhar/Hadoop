package com.scala.hadoop
import java.util.Properties
import org.apache.kafka.clients.admin.AdminClient

object FetchAllKafkaTopics {
  def main(args: Array[String]): Unit = {
    val props = new Properties()
    props.put("bootstrap.servers", "localhost:9094") // or use your IP

    val client = AdminClient.create(props)
    val topics = client.listTopics().names().get()
    println("✅ Topics: " + topics)
    client.close()
  }
}
