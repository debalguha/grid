package com.gu.mediaservice

import java.util.concurrent.TimeUnit

import com.amazonaws.services.dynamodbv2.document._
import com.amazonaws.services.dynamodbv2.document.spec.{QuerySpec, ScanSpec, UpdateItemSpec}
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap
import com.gu.mediaservice.indexing.IndexInputCreation._
import com.gu.mediaservice.indexing.ProduceProgress
import com.gu.mediaservice.lib.aws.UpdateMessage
import com.gu.mediaservice.model.Image
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.{JsObject, Json}

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class BatchIndexHandlerConfig(
                                    apiKey: String,
                                    projectionEndpoint: String,
                                    imagesEndpoint: String,
                                    batchIndexBucket: String,
                                    kinesisStreamName: String,
                                    dynamoTableName: String,
                                    batchSize: Int,
                                    kinesisEndpoint: Option[String] = None,
                                    kinesisMaximumMetric: Option[(String, Integer)] = None,
                                    maxIdleConnections: Int,
                                    stage: Option[String],
                                    threshold: Option[Integer]
                                  )

case class SuccessResult(foundImagesCount: Int, notFoundImagesCount: Int, progressHistory: String, projectionTookInSec: Long)

class BatchIndexHandler(cfg: BatchIndexHandlerConfig) extends LoggingWithMarkers {

  import cfg._

  private val ProjectionTimeoutInSec = 740
  private val OthersTimeoutInSec = 90
  // lambda max timeout is 15 minuets
  // we need some time to be able to do reset if timeout happen before lambda max timeout will come to place
  private val TimeNeededToResetIfTimeoutInSec = 60
  private val MainProcessingTimeoutInSec = (ProjectionTimeoutInSec + OthersTimeoutInSec) - TimeNeededToResetIfTimeoutInSec

  private val GetIdsTimeout = new FiniteDuration(20, TimeUnit.SECONDS)
  private val GlobalTimeout = new FiniteDuration(MainProcessingTimeoutInSec, TimeUnit.SECONDS)
  private val ImagesProjectionTimeout = new FiniteDuration(ProjectionTimeoutInSec, TimeUnit.MINUTES)
  private val gridClient = GridClient(maxIdleConnections, debugHttpResponse = false)

  private val ImagesBatchProjector = new ImagesBatchProjection(apiKey, projectionEndpoint, ImagesProjectionTimeout, gridClient)
  private val AwsFunctions = new BatchIndexHandlerAwsFunctions(cfg)
  private val InputIdsStore = new InputIdsStore(AwsFunctions.buildDynamoTableClient, batchSize)

  import AwsFunctions._
  import ImagesBatchProjector._
  import InputIdsStore._

  private implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  def checkImages(): Unit = {
    if (!validApiKey(projectionEndpoint)) throw new IllegalStateException("invalid api key")
    val stateProgress = scala.collection.mutable.ArrayBuffer[ProduceProgress]()
    stateProgress += NotStarted
    val mediaIdsFuture = getCompletedMediaIdsBatch
    val mediaIds = Await.result(mediaIdsFuture, GetIdsTimeout)
    logger.info(s"got ${mediaIds.size}, completed mediaIds, $mediaIds")
    Try {
      val processImagesFuture: Future[SuccessResult] = Future {
        stateProgress += updateStateToItemsLocating(mediaIds)
        logger.info(s"Indexing ${mediaIds.length} media ids. Getting images from: $imagesEndpoint")
        val start = System.currentTimeMillis()
        val result = getImages(mediaIds, imagesEndpoint, InputIdsStore)



        logger.info(s"foundImagesIds: ${result.found}")
        logger.info(s"notFoundImagesIds: ${result.notFound}")
        logger.info(s"failedImageIds: ${result.failed}")

        val end = System.currentTimeMillis()
        val imageExistenceCheckTookInSecs = (end - start) / 1000
        logger.info(s"Images received in $imageExistenceCheckTookInSecs seconds. Found ${result.found.size} images, could not find ${result.notFound.size} images, ${result.failed} failed")

        //Mark failed images as "finished" because we couldn't double check them
        updateStateToFinished(result.failed)

        //Mark not found images as not found
        updateStateToInconsistent(result.notFound)

        //Mark found as found
        updateStateToFound(result.found)


        SuccessResult(result.found.size, result.notFound.size, stateProgress.map(_.name).mkString(","), imageExistenceCheckTookInSecs)
      }
      Await.result(processImagesFuture, GlobalTimeout)
    } match {
      case Success(res) =>
        logSuccessResult(res)
      case Failure(exp) =>
        exp.printStackTrace()
        val resetIdsCount = mediaIds.size
        stateProgress += updateStateToFinished(mediaIds)
        logFailure(exp, resetIdsCount, stateProgress.toList)
        // propagating exception
        throw exp
    }
  }

  def processImages(): Unit = {

    if (checkKinesisIsNiceAndFast)
      processImagesOnlyIfKinesisIsNiceAndFast()
    else
      logger.info("Kinesis is too busy; leaving it for now")
  }

  def processImagesOnlyIfKinesisIsNiceAndFast(): Unit = {
    if (!validApiKey(projectionEndpoint)) throw new IllegalStateException("invalid api key")
    val stateProgress = scala.collection.mutable.ArrayBuffer[ProduceProgress]()
    stateProgress += NotStarted
    val mediaIdsFuture = getUnprocessedMediaIdsBatch
    val mediaIds = Await.result(mediaIdsFuture, GetIdsTimeout)
    logger.info(s"got ${mediaIds.size}, unprocessed mediaIds, $mediaIds")
    Try {
      val processImagesFuture: Future[SuccessResult] = Future {
        stateProgress += updateStateToItemsInProgress(mediaIds)
        logger.info(s"Indexing ${mediaIds.length} media ids. Getting image projections from: $projectionEndpoint")
        val start = System.currentTimeMillis()
        val maybeBlobsFuture: List[Either[Image, String]] = getImagesProjection(mediaIds, projectionEndpoint, InputIdsStore)

        val (foundImages, notFoundImagesIds) = partitionToSuccessAndNotFound(maybeBlobsFuture)
        val foundImagesIds = foundImages.map(_.id)
        logger.info(s"foundImagesIds: $foundImagesIds")
        logger.info(s"notFoundImagesIds: $notFoundImagesIds")
        val end = System.currentTimeMillis()
        val projectionTookInSec = (end - start) / 1000
        logger.info(s"Projections received in $projectionTookInSec seconds. Found ${foundImages.size} images, could not find ${notFoundImagesIds.size} images")

        if (foundImages.nonEmpty) {
          logger.info("attempting to store blob to s3")
          val bulkIndexRequest = putToS3(foundImages)
          val indexMessage = UpdateMessage(
            subject = "batch-index",
            bulkIndexRequest = Some(bulkIndexRequest)
          )
          putToKinesis(indexMessage)
          stateProgress += updateStateToFinished(foundImages.map(_.id))
        } else {
          logger.info("all was empty terminating current batch")
          stateProgress += NotFound
        }
        SuccessResult(foundImages.size, notFoundImagesIds.size, stateProgress.map(_.name).mkString(","), projectionTookInSec)
      }
      Await.result(processImagesFuture, GlobalTimeout)
    } match {
      case Success(res) =>
        logSuccessResult(res)
      case Failure(exp) =>
        exp.printStackTrace()
        val resetIdsCount = mediaIds.size
        stateProgress += resetItemsState(mediaIds)
        logFailure(exp, resetIdsCount, stateProgress.toList)
        // propagating exception
        throw exp
    }
  }

  private def partitionToSuccessAndNotFound(maybeBlobsFuture: List[Either[Image, String]]): (List[Image], List[String]) = {
    val images: List[Image] = maybeBlobsFuture.flatMap(_.left.toOption)
    val notFoundIds: List[String] = maybeBlobsFuture.flatMap(_.right.toOption)
    (images, notFoundIds)
  }

}

object InputIdsStore {
  val PKField: String = "id"
  val StateField: String = "progress_state"

  def getAllMediaIdsWithinProgressQuery(progress: ProduceProgress) = {
    new QuerySpec()
      .withKeyConditionExpression(s"$StateField = :sub")
      .withValueMap(new ValueMap().withNumber(":sub", progress.stateId))
  }
}

class InputIdsStore(table: Table, batchSize: Int) extends LazyLogging {

  import InputIdsStore._

  def getUnprocessedMediaIdsBatch(implicit ec: ExecutionContext): Future[List[String]] = Future {
    logger.info("attempt to get mediaIds batch from dynamo")
    val querySpec = new QuerySpec()
      .withKeyConditionExpression(s"$StateField = :sub")
      .withValueMap(new ValueMap().withNumber(":sub", NotStarted.stateId))
      .withMaxResultSize(batchSize)
    val mediaIds = table.getIndex(StateField).query(querySpec).asScala.toList.map(it => {
      val json = Json.parse(it.toJSON).as[JsObject]
      (json \ PKField).as[String]
    })
    mediaIds
  }

  def getProcessedMediaIdsBatch(implicit ec: ExecutionContext): Future[List[String]] = Future {
    logger.info(s"attempt to get mediaIds batch from dynamo: ${table.getTableName}")
    val scanSpec = new ScanSpec()
      .withFilterExpression(s"$StateField in (:finished, :not_found, :in_progress)")
      .withValueMap(new ValueMap()
        .withNumber(":finished", Finished.stateId)
        .withNumber(":not_found", NotFound.stateId)
        .withNumber(":in_progress", InProgress.stateId)
      )
      .withMaxResultSize(batchSize)
    val mediaIds = table.scan(scanSpec).asScala.toList.map(it => {
      val json = Json.parse(it.toJSON).as[JsObject]
      (json \ PKField).as[String]
    })
    mediaIds
  }

  def getCompletedMediaIdsBatch(implicit ec: ExecutionContext): Future[List[String]] =Future {
    logger.info("attempt to get mediaIds batch from dynamo")
    val querySpec = new QuerySpec()
      .withKeyConditionExpression(s"$StateField = :sub")
      .withValueMap(new ValueMap().withNumber(":sub", Finished.stateId))
      .withMaxResultSize(batchSize)
    val mediaIds = table.getIndex(StateField).query(querySpec).asScala.toList.map(it => {
      val json = Json.parse(it.toJSON).as[JsObject]
      (json \ PKField).as[String]
    })
    mediaIds
  }
  /**
    * state is used to synchronise multiple overlapping lambda executions, track progress and avoiding repeated operations
    */

  def updateStateToItemsFound(ids: List[String]): ProduceProgress = {
    logger.info(s"updating items state to found")
    updateItemsState(ids, Found)
  }

  def updateStateToInconsistent(ids: List[String]): ProduceProgress = {
    logger.info(s"updating items state to inconsistent")
    updateItemsState(ids, Inconsistent)
  }

  // used to synchronise situation of other lambda execution will start while previous one is still running
  def updateStateToItemsLocating(ids: List[String]): ProduceProgress = {
    logger.info(s"updating items state to locating")
    updateItemsState(ids, Locating)
  }

  // used to synchronise situation of other lambda execution will start while previous one is still running
  def updateStateToItemsInProgress(ids: List[String]): ProduceProgress = {
    logger.info(s"updating items state to in progress")
    updateItemsState(ids, InProgress)
  }

  // used to track images that were not projected successfully
  def updateStateToNotFoundImage(notFoundId: String) =
    updateItemSate(notFoundId, NotFound.stateId)

  def updateStateToFound(ids: List[String]): ProduceProgress = {
    logger.info(s"updating items state to found")
    updateItemsState(ids, Found)
  }

  def updateStateToFinished(ids: List[String]): ProduceProgress = {
    logger.info(s"updating items state to finished")
    updateItemsState(ids, Finished)
  }

  // used in situation if something failed
  def resetItemsState(ids: List[String]): ProduceProgress = {
    logger.info("resetting items state")
    updateItemsState(ids, Reset)
  }

  // used in situation if something failed
  def resetItemState(id: String): ProduceProgress = {
    logger.info("resetting items state")
    updateItemSate(id, Reset.stateId)
    Reset
  }

  // used in situation if something failed in a expected way and we want to ignore that file in next batch
  def setStateToKnownError(id: String): ProduceProgress = {
    logger.info("setting item to KnownError state to ignore it next time")
    updateItemSate(id, KnownError.stateId)
    KnownError
  }

  private def updateItemSate(id: String, state: Int) = {
    val us = new UpdateItemSpec().
      withPrimaryKey(PKField, id).
      withUpdateExpression(s"set $StateField = :sub")
      .withValueMap(new ValueMap().withNumber(":sub", state))
    table.updateItem(us)
  }

  private def updateItemsState(ids: List[String], progress: ProduceProgress): ProduceProgress = {
    ids.foreach(id => updateItemSate(id, progress.stateId))
    progress
  }

}
