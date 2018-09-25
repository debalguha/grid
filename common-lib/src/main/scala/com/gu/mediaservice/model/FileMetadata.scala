package com.gu.mediaservice.model

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class FileMetadata(
  iptc: Map[String, String]                     = Map(),
  exif: Map[String, String]                     = Map(),
  exifSub: Map[String, String]                  = Map(),
  xmp: Map[String, String]                      = Map(),
  icc: Map[String, String]                      = Map(),
  getty: Map[String, String]                    = Map(),
  colourModel: Option[String]                   = None,
  colourModelInformation: Map[String, String]   = Map()

)

object FileMetadata {
  // TODO: reindex all images to make the getty map always present
  // for data consistency, so we can fallback to use the default Reads
  implicit val ImageMetadataReads: Reads[FileMetadata] = (
    (__ \ "iptc").read[Map[String,String]] ~
    (__ \ "exif").read[Map[String,String]] ~
    (__ \ "exifSub").read[Map[String,String]] ~
    (__ \ "xmp").read[Map[String,String]] ~
    (__ \ "icc").readNullable[Map[String,String]].map(_ getOrElse Map()).map(removeLongValues) ~
    (__ \ "getty").readNullable[Map[String,String]].map(_ getOrElse Map()) ~
    (__ \ "colourModel").readNullable[String] ~
    (__ \ "colourModelInformation").readNullable[Map[String,String]].map(_ getOrElse Map())

  )(FileMetadata.apply _)

  private val maximumValueLengthBytes = 5000
  private def removeLongValues = { m:Map[String, String] => {
    val (short, long) =  m.partition(_._2.length <= maximumValueLengthBytes)
    if (long.size>0) {
      short + ("removedFields" -> long.map(_._1).mkString(", "))
    } else {
      m
    }
  } }

  implicit val FileMetadataWrites: Writes[FileMetadata] = (
    (JsPath \ "iptc").write[Map[String,String]] and
      (JsPath \ "exif").write[Map[String,String]] and
      (JsPath \ "exifSub").write[Map[String,String]] and
      (JsPath \ "xmp").write[Map[String,String]] and
      (JsPath \ "icc").write[Map[String,String]].contramap[Map[String, String]](removeLongValues) and
      (JsPath \ "getty").write[Map[String,String]] and
      (JsPath \ "colourModel").writeNullable[String] and
      (JsPath \ "colourModelInformation").write[Map[String,String]]
  )(unlift(FileMetadata.unapply))
}
