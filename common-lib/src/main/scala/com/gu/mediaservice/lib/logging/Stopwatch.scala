package com.gu.mediaservice.lib.logging

import net.logstash.logback.marker.LogstashMarker
import org.slf4j.Marker

import scala.concurrent.duration._

case class DurationForLogging(duration: Duration) extends LogMarker {
  def toMillis: Long = duration.toMillis

  override def toLogMarker: LogstashMarker = MarkerMap(Map("duration" -> toMillis)).toLogMarker
}

class Stopwatch {
  private val startedAt = System.nanoTime()

  def elapsed: Marker = DurationForLogging((System.nanoTime() - startedAt).nanos).toLogMarker
}

object Stopwatch {
  def start: Stopwatch = new Stopwatch
}
