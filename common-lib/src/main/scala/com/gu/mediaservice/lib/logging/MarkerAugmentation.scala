package com.gu.mediaservice.lib.logging

import net.logstash.logback.marker.Markers.appendEntries
import org.slf4j.Marker
import play.api.{Logger, MarkerContext}
import scala.collection.JavaConverters._


object MarkerAugmentation {
  /** *
    *
    * @param markerContext
    * @param augmentation : more details!
    * @return a new markercontext!
    *         it wouldn't be java if it could be cloned
    */
  def augmentMarkerContext(markerContext: MarkerContext, augmentation: Map[String, Any]): MarkerContext = {
    val newMarker = appendEntries(augmentation.asJava)
    markerContext.marker.foreach(newMarker.add)
    MarkerContext(newMarker)
  }
  def augmentMarkerContext(markerContext: MarkerContext, augmentations: (String, Any)*): MarkerContext = {
    augmentMarkerContext(markerContext, augmentations.toMap)
  }
}
