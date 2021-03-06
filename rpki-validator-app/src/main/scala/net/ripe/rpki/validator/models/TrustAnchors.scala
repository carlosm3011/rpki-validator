/**
 * The BSD License
 *
 * Copyright (c) 2010-2012 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator.models

import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.stm.Ref
import scala.concurrent.stm.atomic
import scala.math.Ordering.Implicits._
import org.joda.time.DateTime
import org.joda.time.DateTimeUtils
import com.yammer.metrics.core.MetricsRegistry
import com.yammer.metrics.core.Timer
import grizzled.slf4j.Logger
import grizzled.slf4j.Logging
import net.ripe.rpki.validator.commands.TopDownWalker
import net.ripe.rpki.validator.util.TrustAnchorLocator
import net.ripe.rpki.validator.util.UriToFileMapper
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject
import net.ripe.rpki.commons.crypto.cms.manifest.ManifestCms
import net.ripe.rpki.commons.crypto.crl.X509Crl
import net.ripe.rpki.commons.rsync.Rsync
import net.ripe.rpki.commons.util.Specifications
import net.ripe.rpki.commons.validation.ValidationLocation
import net.ripe.rpki.commons.validation.ValidationOptions
import net.ripe.rpki.commons.validation.ValidationResult
import net.ripe.rpki.commons.validation.ValidationString
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import net.ripe.rpki.commons.crypto.x509cert.X509CertificateUtil
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificate
import net.ripe.rpki.validator.config.MemoryImage
import net.ripe.rpki.validator.fetchers._
import net.ripe.rpki.validator.lib.DateAndTime._
import net.ripe.rpki.validator.statistics.InconsistentRepositoryChecker
import net.ripe.rpki.validator.statistics.Metric
import net.ripe.rpki.validator.store.DataSources
import net.ripe.rpki.validator.store.RepositoryObjectStore
import scalaz._
import org.apache.commons.io.FileUtils

sealed trait ProcessingStatus {
  def isIdle: Boolean
  def isRunning: Boolean = !isIdle
}
case class Idle(nextUpdate: DateTime, errorMessage: Option[String] = None) extends ProcessingStatus {
  def isIdle = true
}
case class Running(description: String) extends ProcessingStatus {
  def isIdle = false
}

case class TrustAnchorData(enabled: Boolean = true)

case class TrustAnchor(
  locator: TrustAnchorLocator,
  status: ProcessingStatus,
  enabled: Boolean = true,
  certificate: Option[X509ResourceCertificate] = None,
  manifest: Option[ManifestCms] = None,
  crl: Option[X509Crl] = None,
  lastUpdated: Option[DateTime] = None) {
  def name: String = locator.getCaName
  def prefetchUris: Seq[URI] = locator.getPrefetchUris.asScala

  def manifestNextUpdateTime: Option[DateTime] = manifest.map { manifest =>
    manifest.getNextUpdateTime min manifest.getCertificate.getValidityPeriod.getNotValidAfter
  }

  def crlNextUpdateTime: Option[DateTime] = crl.map(_.getNextUpdateTime)

  def finishProcessing(result: Validation[String, Map[URI, ValidatedObject]]) = {
    val now = new DateTime

    result match {
      case Success(validatedObjects) =>
        val nextUpdate = now.plusHours(4)
        val trustAnchor = validatedObjects.get(locator.getCertificateLocation).collect {
          case ValidObject(_, _, certificate: X509ResourceCertificate) => certificate
        }
        val manifest = trustAnchor.flatMap(ta => validatedObjects.get(ta.getManifestUri)).collect {
          case ValidObject(_, _, manifest: ManifestCms) => manifest
        }
        val crl = manifest.flatMap(mft => validatedObjects.get(mft.getCrlUri)).collect {
          case ValidObject(_, _, crl: X509Crl) => crl
        }

        copy(lastUpdated = Some(now), status = Idle(nextUpdate), certificate = trustAnchor, manifest = manifest, crl = crl)
      case Failure(errorMessage) =>
        val nextUpdate = now.plusHours(1)
        copy(lastUpdated = Some(now), status = Idle(nextUpdate, Some(errorMessage)))
    }
  }
}

class TrustAnchors(val all: Seq[TrustAnchor]) {
  def startProcessing(locator: TrustAnchorLocator, description: String) = {
    new TrustAnchors(all.map { ta =>
      if (ta.locator == locator) ta.copy(status = Running(description))
      else ta
    })
  }
  def finishedProcessing(locator: TrustAnchorLocator, result: Validation[String, Map[URI, ValidatedObject]]): TrustAnchors = {
    new TrustAnchors(all.map { ta =>
      if (ta.locator == locator)
        ta.finishProcessing(result)
      else ta
    })
  }

  def updateTrustAnchorState(locator: TrustAnchorLocator, enabled: Boolean) = {
    new TrustAnchors(all.map { ta =>
      if (ta.locator == locator) ta.copy(enabled = enabled)
      else ta
    })
  }
}

object TrustAnchors extends Logging {
  def load(files: Seq[File], outputDirectory: String): TrustAnchors = {
    val now = new DateTime
    info("Loading trust anchors...")
    val trustAnchors = for (file <- files) yield {
      val tal = TrustAnchorLocator.fromFile(file)
      new TrustAnchor(
        locator = tal,
        status = Idle(now),
        enabled = true,
        certificate = None,
        manifest = None,
        crl = None)
    }
    new TrustAnchors(trustAnchors)
  }
}

trait ValidationProcess {
  protected[this] val logger = Logger[ValidationProcess]

  def trustAnchorLocator: TrustAnchorLocator

  def runProcess(): Validation[String, Map[URI, ValidatedObject]] = {
    try {
      val certificate = extractTrustAnchorLocator()
      certificate match {
        case ValidObject(uri, checks, trustAnchor: X509ResourceCertificate) =>
          val context = new CertificateRepositoryObjectValidationContext(uri, trustAnchor)
          Success(validateObjects(context) + (uri -> certificate))
        case _ =>
          Success(Map(certificate.uri -> certificate))
      }
    } catch {
      exceptionHandler
    } finally {
      finishProcessing()
    }
  }

  def exceptionHandler: PartialFunction[Throwable, Validation[String, Nothing]] = {
    case e: Exception =>
      val message = if (e.getMessage != null) e.getMessage else e.toString
      Failure(message)
  }

  def objectFetcherListeners: Seq[NotifyingCertificateRepositoryObjectFetcher.Listener] = Seq.empty

  def extractTrustAnchorLocator(): ValidatedObject
  def validateObjects(certificate: CertificateRepositoryObjectValidationContext): Map[URI, ValidatedObject]
  def finishProcessing(): Unit = {}

  def shutdown(): Unit = {}
}

class TrustAnchorValidationProcess(override val trustAnchorLocator: TrustAnchorLocator, maxStaleDays: Int) extends ValidationProcess {

  private val options = new ValidationOptions()
  private val RsyncDiskCacheBasePath = "tmp/cache/"

  options.setMaxStaleDays(maxStaleDays)

  override def extractTrustAnchorLocator() = {
    val uri = trustAnchorLocator.getCertificateLocation

    val validationResult = ValidationResult.withLocation(uri)

    val cro = consistentObjectFetcher.fetch(uri, Specifications.alwaysTrue(), validationResult)
    cro match {
      case certificate: X509ResourceCertificate =>
        validationResult.rejectIfFalse(trustAnchorLocator.getPublicKeyInfo == X509CertificateUtil.getEncodedSubjectPublicKeyInfo(certificate.getCertificate), ValidationString.TRUST_ANCHOR_PUBLIC_KEY_MATCH)
        if (validationResult.hasFailureForCurrentLocation) {
          InvalidObject(uri, validationResult.getAllValidationChecksForLocation(new ValidationLocation(uri)).asScala.toSet)
        } else {
          ValidObject(uri, validationResult.getAllValidationChecksForLocation(new ValidationLocation(uri)).asScala.toSet, certificate)
        }
      case _ =>
        InvalidObject(uri, validationResult.getAllValidationChecksForLocation(new ValidationLocation(uri)).asScala.toSet)
    }
  }

  override def validateObjects(certificate: CertificateRepositoryObjectValidationContext) = {
    val builder = Map.newBuilder[URI, ValidatedObject]
    val fetcher = createFetcher(new RoaCollector(trustAnchorLocator, builder) +: objectFetcherListeners: _*)

    // purge cache
    val cache = new RepositoryObjectStore(DataSources.DurableDataSource)
    cache.purgeExpired(maxStaleDays)

    trustAnchorLocator.getPrefetchUris.asScala.foreach { prefetchUri =>

      logger.info("Prefetching '" + prefetchUri + "'")
      val validationResult = ValidationResult.withLocation(prefetchUri)

      fetcher.prefetch(prefetchUri, validationResult)
      logger.info("Done prefetching for '" + prefetchUri + "'")
    }

    val walker = new TopDownWalker(fetcher)
    walker.addTrustAnchor(certificate)
    walker.execute()

    builder.result()
  }

  def wipeRsyncDiskCache() {
    val diskCache = new File(RsyncDiskCacheBasePath)
    if (diskCache.isDirectory) {
      FileUtils.cleanDirectory(diskCache)
    }
  }

  private def createFetcher(listeners: NotifyingCertificateRepositoryObjectFetcher.Listener*): CertificateRepositoryObjectFetcher = {
    val validatingFetcher = new ValidatingCertificateRepositoryObjectFetcher(new RpkiRepositoryObjectFetcherAdapter(consistentObjectFetcher), options)
    val notifyingFetcher = new NotifyingCertificateRepositoryObjectFetcher(validatingFetcher)
    val cachingFetcher = new CachingCertificateRepositoryObjectFetcher(notifyingFetcher)
    validatingFetcher.setOuterMostDecorator(cachingFetcher)

    listeners.foreach(notifyingFetcher.addCallback)

    cachingFetcher
  }

  private[this] lazy val consistentObjectFetcher = {
    val rsync = new Rsync()
    rsync.setTimeoutInSeconds(300)
    val rsyncFetcher = new RsyncRpkiRepositoryObjectFetcher(rsync, new UriToFileMapper(new File(RsyncDiskCacheBasePath  + trustAnchorLocator.getFile.getName)))

    val remoteFetcher = new RemoteObjectFetcher(rsyncFetcher)

    new ConsistentObjectFetcher(remoteFetcher, new RepositoryObjectStore(DataSources.DurableDataSource))
  }

  private class RoaCollector(trustAnchor: TrustAnchorLocator, objects: collection.mutable.Builder[(URI, ValidatedObject), _]) extends NotifyingCertificateRepositoryObjectFetcher.ListenerAdapter {
    override def afterFetchFailure(uri: URI, result: ValidationResult) {
      objects += uri -> new InvalidObject(uri, result.getAllValidationChecksForLocation(new ValidationLocation(uri)).asScala.toSet)
    }

    override def afterFetchSuccess(uri: URI, obj: CertificateRepositoryObject, result: ValidationResult) {
      objects += uri -> new ValidObject(uri, result.getAllValidationChecksForLocation(new ValidationLocation(uri)).asScala.toSet, obj)
    }
  }
}

trait TrackValidationProcess extends ValidationProcess {
  def memoryImage: Ref[MemoryImage]

  abstract override def runProcess() = {
    val start = atomic { implicit transaction =>
      (for (
        ta <- memoryImage().trustAnchors.all.find(_.locator == trustAnchorLocator)
        if ta.status.isIdle && ta.enabled
      ) yield {
        memoryImage.transform { _.startProcessingTrustAnchor(ta.locator, "Updating certificate") }
      }).isDefined
    }
    if (start) {
      val result = super.runProcess()
      memoryImage.single.transform {
        _.finishedProcessingTrustAnchor(trustAnchorLocator, result)
      }
      result
    } else Failure("Trust anchor not idle or enabled")
  }

  abstract override def validateObjects(certificate: CertificateRepositoryObjectValidationContext) = {
    memoryImage.single.transform { _.startProcessingTrustAnchor(trustAnchorLocator, "Updating ROAs") }
    super.validateObjects(certificate)
  }
}

trait MeasureValidationProcess extends ValidationProcess {
  private[this] val metricsBuilder = Vector.newBuilder[Metric]
  private[this] val startedAt = DateTimeUtils.currentTimeMillis

  abstract override def validateObjects(certificate: CertificateRepositoryObjectValidationContext) = {
    metricsBuilder += Metric("trust.anchor[%s].extracted.elapsed.ms" format trustAnchorLocator.getCertificateLocation, (DateTimeUtils.currentTimeMillis - startedAt).toString, DateTimeUtils.currentTimeMillis)
    val result = super.validateObjects(certificate)
    metricsBuilder += Metric("trust.anchor[%s].validation" format trustAnchorLocator.getCertificateLocation, "OK", DateTimeUtils.currentTimeMillis)
    result
  }

  abstract override def finishProcessing() = {
    super.finishProcessing()
    val stop = DateTimeUtils.currentTimeMillis
    metricsBuilder += Metric("trust.anchor[%s].validation.elapsed.ms" format trustAnchorLocator.getCertificateLocation, (stop - startedAt).toString, DateTimeUtils.currentTimeMillis)
  }
  abstract override def exceptionHandler = {
    case e: Exception =>
      metricsBuilder += Metric("trust.anchor[%s].validation" format trustAnchorLocator.getCertificateLocation, "failed: " + e, DateTimeUtils.currentTimeMillis)
      super.exceptionHandler(e)
  }

  lazy val metrics = metricsBuilder.result()
}

trait MeasureRsyncExecution extends ValidationProcess {
  private[this] val registry = new MetricsRegistry
  override def objectFetcherListeners = super.objectFetcherListeners :+ RsyncExecution

  private object RsyncExecution extends NotifyingCertificateRepositoryObjectFetcher.ListenerAdapter {
    override def afterPrefetchFailure(uri: URI, result: ValidationResult) {
      update("rsync.prefetch.failure", uri, RsyncRpkiRepositoryObjectFetcher.RSYNC_PREFETCH_VALIDATION_METRIC, result)
    }
    override def afterPrefetchSuccess(uri: URI, result: ValidationResult) {
      update("rsync.prefetch.success", uri, RsyncRpkiRepositoryObjectFetcher.RSYNC_PREFETCH_VALIDATION_METRIC, result)
    }
    override def afterFetchFailure(uri: URI, result: ValidationResult) {
      update("rsync.fetch.file.failure", uri, RsyncRpkiRepositoryObjectFetcher.RSYNC_FETCH_FILE_VALIDATION_METRIC, result)
    }
    override def afterFetchSuccess(uri: URI, obj: CertificateRepositoryObject, result: ValidationResult) {
      update("rsync.fetch.file.success", uri, RsyncRpkiRepositoryObjectFetcher.RSYNC_FETCH_FILE_VALIDATION_METRIC, result)
    }

    private[this] def update(callback: String, uri: URI, name: String, result: ValidationResult) {
      val metric = result.getMetrics(new ValidationLocation(uri)).asScala.find(_.getName == name)
      metric foreach { metric =>
        try {
          val elapsedTime = metric.getValue.toLong
          registry.newTimer(classOf[MeasureRsyncExecution], "%s[%s]" format (name, uri.getHost)).update(elapsedTime, TimeUnit.MILLISECONDS)
        } catch {
          case _: NumberFormatException => // Ignore
        }
      }
    }
  }

  def rsyncMetrics: Seq[Metric] = {
    val now = DateTimeUtils.currentTimeMillis
    registry.allMetrics.asScala.flatMap {
      case (name, timer: Timer) =>
        Vector(
          Metric(name.getName + ".count", timer.count.toString, now),
          Metric(name.getName + ".mean", timer.mean.toString, now),
          Metric(name.getName + ".min", timer.min.toString, now),
          Metric(name.getName + ".max", timer.max.toString, now),
          Metric(name.getName + ".stdDev", timer.stdDev.toString, now),
          Metric(name.getName + ".75p", timer.getSnapshot.get75thPercentile.toString, now),
          Metric(name.getName + ".95p", timer.getSnapshot.get95thPercentile.toString, now),
          Metric(name.getName + ".98p", timer.getSnapshot.get98thPercentile.toString, now),
          Metric(name.getName + ".99p", timer.getSnapshot.get99thPercentile.toString, now),
          Metric(name.getName + ".999p", timer.getSnapshot.get999thPercentile.toString, now),
          Metric(name.getName + ".median", timer.getSnapshot.getMedian.toString, now),
          Metric(name.getName + ".rate.1m", timer.oneMinuteRate.toString, now),
          Metric(name.getName + ".rate.5m", timer.fiveMinuteRate.toString, now),
          Metric(name.getName + ".rate.15m", timer.fifteenMinuteRate.toString, now))
      case _ =>
        Vector.empty
    }.toIndexedSeq
  }

  abstract override def shutdown(): Unit = {
    registry.shutdown()
    super.shutdown()
  }
}

trait ValidationProcessLogger extends ValidationProcess {
  override def objectFetcherListeners = super.objectFetcherListeners :+ ObjectFetcherLogger

  abstract override def validateObjects(certificate: CertificateRepositoryObjectValidationContext) = {
    logger.info("Loaded trust anchor " + trustAnchorLocator.getCaName + " from location " + certificate.getLocation + ", starting validation")
    val objects = super.validateObjects(certificate)
    logger.info("Finished validating " + trustAnchorLocator.getCaName + ", fetched " + objects.size + " valid Objects")
    objects
  }

  abstract override def exceptionHandler = {
    case e: Exception =>
      logger.error("Error while validating trust anchor " + trustAnchorLocator.getCaName + ": " + e, e)
      super.exceptionHandler(e)
  }

  private object ObjectFetcherLogger extends NotifyingCertificateRepositoryObjectFetcher.ListenerAdapter {
    override def afterPrefetchFailure(uri: URI, result: ValidationResult) {
      logger.warn("Failed to prefetch '" + uri + "'")
    }
    override def afterPrefetchSuccess(uri: URI, result: ValidationResult) {
      logger.debug("Prefetched '" + uri + "'")
    }
    override def afterFetchFailure(uri: URI, result: ValidationResult) {
      logger.warn("Failed to validate '" + uri + "': " + result.getFailuresForCurrentLocation.asScala.map(_.toString).mkString(", "))
    }
    override def afterFetchSuccess(uri: URI, obj: CertificateRepositoryObject, result: ValidationResult) {
      logger.debug("Validated OBJECT '" + uri + "'")
    }
  }
}

/**
 * Checks the Validated Objects for inconsistent repositories and reports metrics for this.
 */
trait MeasureInconsistentRepositories extends ValidationProcess {

  private[this] val metricsBuilder = Vector.newBuilder[Metric]

  abstract override def validateObjects(certificate: CertificateRepositoryObjectValidationContext) = {
    val objects = super.validateObjects(certificate)
    extractInconsistencies(objects)
    objects
  }

  private[models] def extractInconsistencies(objects: Map[URI, ValidatedObject]) = {
    val now = DateTimeUtils.currentTimeMillis
    val inconsistencyStats = InconsistentRepositoryChecker.check(objects)

    val totalRepositoriesMetric = Metric("trust.anchor[%s].repositories.total.count" format trustAnchorLocator.getCertificateLocation, inconsistencyStats.size.toString, now)

    metricsBuilder += totalRepositoriesMetric

    val inconsistentRepositories = inconsistencyStats.filter(_._2 == true).keys
    val totalInconsistentRepoMetric = Metric("trust.anchor[%s].repositories.inconsistent.count" format trustAnchorLocator.getCertificateLocation, inconsistentRepositories.size.toString, now)

    metricsBuilder += totalInconsistentRepoMetric
    for (uri <- inconsistentRepositories) {
      val inconsistentRepoMetric = Metric("trust.anchor[%s].repository.is.inconsistent" format trustAnchorLocator.getCertificateLocation, uri.toString, now)
      metricsBuilder += inconsistentRepoMetric
    }
  }

  lazy val inconsistencyMetrics = metricsBuilder.result()

}
