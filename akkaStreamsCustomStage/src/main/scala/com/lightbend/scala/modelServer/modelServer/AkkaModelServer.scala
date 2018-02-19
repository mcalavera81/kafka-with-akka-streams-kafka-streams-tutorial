package com.lightbend.scala.modelServer.modelServer

import scala.concurrent.duration._
import scala.util.Success

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.kafka.ConsumerSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.lightbend.java.configuration.kafka.ApplicationKafkaParameters._
import com.lightbend.model.winerecord.WineRecord
import com.lightbend.scala.modelServer.model.DataRecord
import com.lightbend.scala.modelServer.model.ModelToServe
import com.lightbend.scala.modelServer.model.ModelWithDescriptor
import com.lightbend.scala.modelServer.queriablestate.QueriesAkkaHttpResource
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/**
  * Created by boris on 7/21/17.
  */
object AkkaModelServer {

  implicit val system = ActorSystem("ModelServing")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  println(s"Using kafka brokers at ${KAFKA_BROKER} ")

  val dataConsumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
    .withBootstrapServers(KAFKA_BROKER)
    .withGroupId(DATA_GROUP)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  val modelConsumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
    .withBootstrapServers(KAFKA_BROKER)
    .withGroupId(MODELS_GROUP)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")


  def main(args: Array[String]): Unit = {

    val dataStream: Source[WineRecord, Consumer.Control] =
      Consumer.atMostOnceSource(dataConsumerSettings, Subscriptions.topics(DATA_TOPIC))
        .map(record => DataRecord.fromByteArray(record.value))
        .collect { case Success(a) => a }

    val modelPredictions: Source[Option[Double], ModelStateStore] =
      dataStream.viaMat(new ModelStage)(Keep.right)

    val modelStateStore: ModelStateStore =
      modelPredictions
        .to(Sink.ignore) // we do not read the results directly
        .run() // we run the stream, materializing the stage's StateStore

    // model stream
    Consumer.atMostOnceSource(modelConsumerSettings, Subscriptions.topics(MODELS_TOPIC))
      .map(record => ModelToServe.fromByteArray(record.value())).collect { case Success(a) => a }
      .map(record => ModelWithDescriptor.fromModelToServe(record)).collect { case Success(a) => a }
      .runForeach(modelStateStore.setModel)

    startRest(modelStateStore)
  }

  def startRest(service: ModelStateStore): Unit = {

    implicit val timeout = Timeout(10.seconds)
    val host = "localhost"//InetAddress.getLocalHost.getHostAddress
    val port = 5500
    val routes: Route = QueriesAkkaHttpResource.storeRoutes(service)

    Http().bindAndHandle(routes, host, port) map
      { binding => println(s"Starting models observer on port ${binding.localAddress}") } recover {
      case ex =>
        println(s"Models observer could not bind to $host:$port", ex.getMessage)
    }
  }
}