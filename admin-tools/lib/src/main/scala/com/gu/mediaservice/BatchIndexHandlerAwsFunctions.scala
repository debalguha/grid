package com.gu.mediaservice

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.{Date, UUID}

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, EnvironmentVariableCredentialsProvider}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.cloudwatch.model.{Dimension, GetMetricStatisticsRequest}
import com.amazonaws.services.cloudwatch.{AmazonCloudWatch, AmazonCloudWatchAsyncClientBuilder}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.document.{Table, DynamoDB => AwsDynamoDB}
import com.amazonaws.services.kinesis.model.PutRecordRequest
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata
import com.gu.mediaservice.lib.aws.{BulkIndexRequest, UpdateMessage}
import com.gu.mediaservice.lib.json.JsonByteArrayUtil
import com.gu.mediaservice.model.Image
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json
import scala.collection.JavaConverters._

object BatchIndexHandlerAwsFunctions {

  val AwsRegion: String = "eu-west-1"

  lazy val awsCredentials = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider("media-service"),
    new EnvironmentVariableCredentialsProvider()
  )

  def buildDynamoTableClient(dynamoTableName: String): Table = {
    val builder = AmazonDynamoDBClient.builder()
      .withRegion(AwsRegion)
      .withCredentials(awsCredentials)
    val dynamo = new AwsDynamoDB(builder.build())
    dynamo.getTable(dynamoTableName)
  }

}

class BatchIndexHandlerAwsFunctions(cfg: BatchIndexHandlerConfig) extends LazyLogging {

  private val s3client = buildS3Client
  private val kinesis = buildKinesisClient

  import BatchIndexHandlerAwsFunctions.{awsCredentials, AwsRegion}
  import cfg._

  def putToS3(imageBlobs: List[Image]): BulkIndexRequest = {
    import Json.{stringify, toJson}
    val imagesJsonArray = stringify(toJson(imageBlobs))
    val bArr = imagesJsonArray.getBytes
    val key = s"batch-index/${UUID.randomUUID().toString}.json"
    val metadata = new ObjectMetadata
    metadata.setContentType("application/json")
    metadata.setContentLength(bArr.length)
    val res = s3client.putObject(batchIndexBucket, key, new ByteArrayInputStream(bArr), metadata)
    logger.info(s"PUT [s3://$batchIndexBucket/$key] object to s3 response: $res")
    BulkIndexRequest(batchIndexBucket, key)
  }

  def putToKinesis(message: UpdateMessage): Unit = {
    logger.info("attempting to put message to kinesis")
    val payload = JsonByteArrayUtil.toByteArray(message)
    val partitionKey = UUID.randomUUID().toString
    val putReq = new PutRecordRequest()
      .withStreamName(kinesisStreamName)
      .withPartitionKey(partitionKey)
      .withData(ByteBuffer.wrap(payload))

    val res = kinesis.putRecord(putReq)
    logger.info(s"PUT [$message] message to kinesis stream: $kinesisStreamName response: $res")
  }

  def checkKinesisIsNiceAndFast: Boolean = {
    stage match {
      case None => true // did not get a stage. presume it's not prod and go ahead.
      case Some(actualStage) =>
        threshold match {
          case None =>
            logger.error("Got a stage but no threshold; unable to work out if Kinesis is fast or not")
            false

          case Some(actualThreshold) =>

            logger.info("Checking kinesis is nice and fast")

            val dimensionValue = s"media-service-thrall-${actualStage.toUpperCase}"
            val endTime: Date = new Date() // now!
            val startTime: Date = new Date(endTime.getTime - 5 * 60 * 1000L) // five minutes ago

            val dimension = new Dimension()
              .withName("StreamName")
              .withValue(dimensionValue)

            val request: GetMetricStatisticsRequest = new GetMetricStatisticsRequest()
              .withDimensions(Set(dimension).asJava)
              .withEndTime(endTime)
              .withMetricName("GetRecords.IteratorAgeMilliseconds")
              .withNamespace("AWS/Kinesis")
              .withPeriod(300)
              .withStartTime(startTime)
              .withStatistics("Maximum")
              .withUnit("Milliseconds")


            val dataPoints = buildCloudWatchClient
              .getMetricStatistics(request)
              .getDatapoints
              .asScala

            logger.info(s"Got ${dataPoints.size} data points")

            val fastEnough = dataPoints.nonEmpty && ! dataPoints.exists(d => d.getMaximum > actualThreshold)
            logger.info(s"Kinesis stream is fast enough: $fastEnough")
            fastEnough
        }
    }
  }

  private def buildS3Client = {
    val builder = AmazonS3ClientBuilder.standard().withRegion(AwsRegion)
    builder.withCredentials(awsCredentials).build()
  }

  def buildDynamoTableClient: Table = {
    BatchIndexHandlerAwsFunctions.buildDynamoTableClient(dynamoTableName)
  }

  private def buildKinesisClient: AmazonKinesis = {
    val baseBuilder = AmazonKinesisClientBuilder.standard()
    val builder = kinesisEndpoint match {
      case Some(uri) =>
        logger.info(s"building local kinesis client with $uri")
        baseBuilder.withEndpointConfiguration(new EndpointConfiguration(uri, AwsRegion))
      case _ =>
        logger.info("building remote kinesis client")
        baseBuilder.withRegion(AwsRegion)
    }
    builder.withCredentials(awsCredentials).build()
  }

  private def buildCloudWatchClient: AmazonCloudWatch = AmazonCloudWatchAsyncClientBuilder
    .standard()
    .withRegion(AwsRegion)
    .withCredentials(awsCredentials)
    .build()
}
