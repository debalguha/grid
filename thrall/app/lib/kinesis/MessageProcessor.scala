package lib.kinesis

import com.gu.mediaservice.lib.aws.{EsResponse, BulkIndexRequest, UpdateMessage}
import com.gu.mediaservice.lib.elasticsearch.ElasticNotFoundException
import com.gu.mediaservice.lib.logging.{GridLogger, MarkerAugmentation}
import com.gu.mediaservice.model._
import com.gu.mediaservice.model.leases.MediaLease
import com.gu.mediaservice.model.usage.{Usage, UsageNotice}
import lib._
import lib.elasticsearch._
import org.joda.time.DateTime
import play.api.{Logger, MarkerContext}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}


class MessageProcessor(es: ElasticSearch,
                       store: ThrallStore,
                       metadataEditorNotifications: MetadataEditorNotifications,
                       syndicationRightsOps: SyndicationRightsOps,
                       bulkIndexS3Client: BulkIndexS3Client
                      ) {

  def process(updateMessage: UpdateMessage)(implicit ec: ExecutionContext, mc: MarkerContext): Future[Any] =
    updateMessage.subject match {
      case "image" => indexImage(updateMessage)
      case "reindex-image" => indexImage(updateMessage)
      case "delete-image" => deleteImage(updateMessage)
      case "update-image" => indexImage(updateMessage)
      case "delete-image-exports" => deleteImageExports(updateMessage)
      case "update-image-exports" => updateImageExports(updateMessage)
      case "update-image-user-metadata" => updateImageUserMetadata(updateMessage)
      case "update-image-usages" => updateImageUsages(updateMessage)
      case "replace-image-leases" => replaceImageLeases(updateMessage)
      case "add-image-lease" => addImageLease(updateMessage)
      case "remove-image-lease" => removeImageLease(updateMessage)
      case "set-image-collections" => setImageCollections(updateMessage)
      case "delete-usages" => deleteAllUsages(updateMessage)
      case "upsert-rcs-rights" => upsertSyndicationRights(updateMessage)
      case "update-image-photoshoot" => updateImagePhotoshoot(updateMessage)
      case "batch-index" => batchIndex(updateMessage)
      case unknownSubject => {
        Future.failed(ProcessorNotFoundException(unknownSubject))
      }
    }

  def batchIndex(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext): Future[List[ElasticSearchBulkUpdateResponse]] = {
    val request = message.bulkIndexRequest.get
    Logger.info(s"attempt to parse images from $request")
    val imagesToIndex = bulkIndexS3Client.getImages(request)
    val newMarker = MarkerAugmentation.augmentMarkerContext(markerContext, ("batchImageCount" -> imagesToIndex.length))
    Logger.info(s"Processing a batch index of size: ${imagesToIndex.size}")(newMarker)
    Future.sequence(es.bulkInsert(imagesToIndex))
  }

  def updateImageUsages(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext): Future[List[ElasticSearchUpdateResponse]] = {
    implicit val unw: OWrites[UsageNotice] = Json.writes[UsageNotice]
    withId(message) { id =>
      withUsageNotice(message) { usageNotice =>
        withLastModified(message) { lastModifed =>
          val usages = usageNotice.usageJson.as[Seq[Usage]]
          Future.traverse(es.updateImageUsages(id, usages, dateTimeAsJsLookup(lastModifed)))(_.recoverWith {
            case ElasticNotFoundException => Future.successful(ElasticSearchUpdateResponse())
          }
          )
        }
      }
    }
  }

  def reindexImage(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    Logger.info("Reindexing image")
    indexImage(message)
  }

  private def indexImage(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) =
    Future.sequence(withImage(message)(i => es.indexImage(i.id, Json.toJson(message.image.get))))

  def updateImageExports(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    def asJsLookup(cs: Seq[Crop]): JsLookupResult = JsDefined(Json.toJson(cs))

    withId(message) { id =>
      withCrops(message) { crops =>
        Future.sequence(es.updateImageExports(id, asJsLookup(crops)))
      }
    }
  }

  private def deleteImageExports(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) =
    Future.sequence(withId(message)(id => es.deleteImageExports(id)))

  private def updateImageUserMetadata(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    def asJsLookup(e: Edits): JsLookupResult = JsDefined(Json.toJson(e))

    withEdits(message) { edits =>
      withLastModified(message) { lastModified =>
        Future.sequence(withId(message)(id => es.applyImageMetadataOverride(id, asJsLookup(edits), dateTimeAsJsLookup(lastModified))))
      }
    }
  }

  private def replaceImageLeases(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    def asJsLookup(ls: Seq[MediaLease]): JsLookupResult = JsDefined(Json.toJson(ls))

    withId(message) { id =>
      withLeases(message) { leases =>
        Future.sequence(es.replaceImageLeases(id, leases))
      }
    }
  }

  private def addImageLease(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    def asJsLookup(m: MediaLease): JsLookupResult = JsDefined(Json.toJson(m))

    withId(message) { id =>
      withLease(message) { mediaLease =>
        withLastModified(message) { lastModified =>
          Future.sequence(es.addImageLease(id, asJsLookup(mediaLease), dateTimeAsJsLookup(lastModified)))
        }
      }
    }
  }

  def removeImageLease(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    def asJsLookup(i: String): JsLookupResult = JsDefined(Json.toJson(i))

    withLeaseId(message) { leaseId =>
      withLastModified(message) { lastModified =>
        Future.sequence(withId(message)(id => es.removeImageLease(id, asJsLookup(leaseId), dateTimeAsJsLookup(lastModified))))
      }
    }
  }

  private def setImageCollections(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    def asJsLookup(c: Seq[Collection]): JsLookupResult = JsDefined(Json.toJson(c))

    withCollections(message) { collections =>
      withId(message) { id =>
        Future.sequence(es.setImageCollection(id, asJsLookup(collections)))
      }
    }
  }

  private def deleteImage(updateMessage: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) = {
    Future.sequence(
      withId(updateMessage) { id =>
        // if we cannot delete the image as it's "protected", succeed and delete
        // the message anyway.
        GridLogger.info("ES6 Deleting image: " + id)
        es.deleteImage(id).map { requests =>
          requests.map {
            case _: ElasticSearchDeleteResponse =>
              store.deleteOriginal(id)
              store.deleteThumbnail(id)
              store.deletePng(id)
              metadataEditorNotifications.publishImageDeletion(id)
              EsResponse(s"Image deleted: $id")
          } recoverWith {
            case ImageNotDeletable =>
              GridLogger.info("Could not delete image", id)
              Future.successful(EsResponse(s"Image cannot be deleted: $id"))
          }
        }
      }
    )
  }

  private def deleteAllUsages(updateMessage: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext) =
    Future.sequence(withId(updateMessage)(id => es.deleteAllImageUsages(id)))

  def upsertSyndicationRights(updateMessage: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext): Future[Unit] = {
    withId(updateMessage) { id =>
      withSyndicationRights(updateMessage) { syndicationRights =>
        es.getImage(id) map {
          case Some(image) =>
            val photoshoot = image.userMetadata.flatMap(_.photoshoot)
            GridLogger.info(s"Upserting syndication rights for image $id in photoshoot $photoshoot with rights ${Json.toJson(syndicationRights)}", id)
            syndicationRightsOps.upsertOrRefreshRights(
              image = image.copy(syndicationRights = Some(syndicationRights)),
              currentPhotoshootOpt = photoshoot
            )
          case _ => GridLogger.info(s"Image $id not found")
        }
      }
    }
  }

  def updateImagePhotoshoot(message: UpdateMessage)(implicit ec: ExecutionContext, markerContext: MarkerContext): Future[Unit] = {
    withEdits(message) { upcomingEdits =>
      withId(message) { id =>
        for {
          imageOpt <- es.getImage(id)
          prevPhotoshootOpt = imageOpt.flatMap(_.userMetadata.flatMap(_.photoshoot))
          _ <- updateImageUserMetadata(message)
          _ <- {
            GridLogger.info(s"Upserting syndication rights for image $id. Moving from photoshoot $prevPhotoshootOpt to ${upcomingEdits.photoshoot}.")
            syndicationRightsOps.upsertOrRefreshRights(
              image = imageOpt.get,
              currentPhotoshootOpt = upcomingEdits.photoshoot,
              previousPhotoshootOpt = prevPhotoshootOpt
            )
          }
        } yield GridLogger.info(s"Moved image $id from $prevPhotoshootOpt to ${upcomingEdits.photoshoot}", id)
      }
    }
  }

  private def withId[A](message: UpdateMessage)(f: String => A): A = {
    message.id.map(f).getOrElse {
      sys.error(s"No id present in message: $message")
    }
  }

  private def withImage[A](message: UpdateMessage)(f: Image => A): A = {
    message.image.map(f).getOrElse {
      sys.error(s"No image present in message: $message")
    }
  }

  private def withCollections[A](message: UpdateMessage)(f: Seq[Collection] => A): A = {
    message.collections.map(f).getOrElse {
      sys.error(s"No edits present in message: $message")
    }
  }

  private def withCrops[A](message: UpdateMessage)(f: Seq[Crop] => A): A = {
    message.crops.map(f).getOrElse {
      sys.error(s"No crops present in message: $message")
    }
  }

  private def withEdits[A](message: UpdateMessage)(f: Edits => A): A = {
    message.edits.map(f).getOrElse {
      sys.error(s"No edits present in message: $message")
    }
  }

  private def withLease[A](message: UpdateMessage)(f: MediaLease => A): A = {
    message.mediaLease.map(f).getOrElse {
      sys.error(s"No media lease present in message: $message")
    }
  }

  private def withLeases[A](message: UpdateMessage)(f: Seq[MediaLease] => A): A = {
    message.leases.map(f).getOrElse {
      sys.error(s"No media leases present in message: $message")
    }
  }

  private def withLeaseId[A](message: UpdateMessage)(f: String => A): A = {
    message.leaseId.map(f).getOrElse {
      sys.error(s"No lease id present in message: $message")
    }
  }

  private def withLastModified[A](message: UpdateMessage)(f: DateTime => A): A = {
    message.lastModified.map(f).getOrElse {
      sys.error(s"No lastModified present in message: $message")
    }
  }

  private def withSyndicationRights[A](message: UpdateMessage)(f: SyndicationRights => A): A = {
    message.syndicationRights.map(f).getOrElse {
      sys.error(s"No syndication rights present on data field message: $message")
    }
  }

  private def withUsageNotice[A](message: UpdateMessage)(f: UsageNotice => A): A = {
    message.usageNotice.map(f).getOrElse {
      sys.error(s"No usage notice present in message: $message")
    }
  }

  private def dateTimeAsJsLookup(d: DateTime): JsLookupResult = JsDefined(Json.toJson(d.toString))

}

case class ProcessorNotFoundException(unknownSubject: String) extends Exception(s"Could not find processor for ${unknownSubject} message")
