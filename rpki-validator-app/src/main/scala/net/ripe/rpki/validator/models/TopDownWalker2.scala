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

import java.net.URI

import com.sun.tools.javac.comp.Check
import grizzled.slf4j.Logging
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject
import net.ripe.rpki.commons.crypto.crl.{CrlLocator, X509Crl}
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificate
import net.ripe.rpki.commons.validation.ValidationString._
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import net.ripe.rpki.commons.validation._
import net.ripe.rpki.validator.models.validation._
import net.ripe.rpki.validator.store.Storage
import org.apache.commons.lang.Validate
import org.joda.time.Instant

import scala.collection.GenTraversableOnce
import scala.collection.JavaConverters._
import scala.collection.immutable.Iterable

class TopDownWalker2(certificateContext: CertificateRepositoryObjectValidationContext,
                    store: Storage,
                    repoService: RepoService,
                    validationOptions: ValidationOptions,
                    validationStartTime: Instant)(seen: scala.collection.mutable.Set[String])
  extends Logging {

  private object HashUtil extends Hashing

  private val certificateSkiHex: String = HashUtil.stringify(certificateContext.getSubjectKeyIdentifier)
  
  Validate.isTrue(seen.add(certificateSkiHex))
  Validate.isTrue(certificateContext.getCertificate.isObjectIssuer, "certificate must be an object issuer")
  
  private val certContextValidationLocation = new ValidationLocation(certificateContext.getLocation)
  private val certContextValidationResult: ValidationResult = ValidationResult.withLocation(certContextValidationLocation)

  private[models] def preferredFetchLocation: Option[URI] = Option(certificateContext.getRpkiNotifyURI).orElse(Option(certificateContext.getRepositoryURI))

  sealed trait Check {
    def location: ValidationLocation
    def key: String
    def params: Seq[String]
  }

  case class Reject(location: ValidationLocation, key: String, params: String*) extends Check
  case class Warning(location: ValidationLocation, key: String, params: String*) extends Check


  def execute: Map[URI, ValidatedObject] = {
    logger.info(s"Validating ${certificateContext.getLocation}")
    val fetchErrors = preferredFetchLocation.map(prefetch)

    val validatedObjects = Map.newBuilder[URI, ValidatedObject]

    val crlList = fetchCrlsByAKI
    val (objects, checks) = findRecentValidCrl(crlList) match {
      case None =>
        (Seq(), List(Reject(certContextValidationLocation, CRL_REQUIRED, s"No valid CRL found with AKI=$certificateSkiHex")))

      case Some(crl) =>
        val mftList = fetchMftsByAKI
        findRecentValidMft(mftList, crl) match {
          case Some(manifest) =>
            val (ClassifiedObjects(roas, childrenCertificates, _), mftObjectsChecks) = checkManifestObjects(manifest, crl)

            val checks = checkManifestUrlOnCertMatchesLocationInRepo(manifest).toList ++
              mftObjectsChecks ++
              validateAllCrls(crlList) ++
              validateAllMfts(mftList, crl) ++
              validate(roas, crl) ++
              validate(childrenCertificates, crl)

            val validatedChildrenObjects = childrenCertificates.flatMap(stepDown)
            validatedObjects ++= validatedChildrenObjects

            (Seq(), checks)

          case None =>
            (Seq(), List(Warning(certContextValidationLocation, VALIDATOR_CA_SHOULD_HAVE_MANIFEST, certificateSkiHex)))
        }

//        validatedChildrenObjects.map(_._1.toString)
    }

    val validatedObjectMap = validatedObjects.result()


    validatedObjectMap
  }


  def validate[T <: CertificateRepositoryObject](objects: Seq[RepositoryObject[T]], crl: CrlObject): List[Check] = {
    objects.map { o =>
      val location = new ValidationLocation(o.url)
      val result = ValidationResult.withLocation(location)
      o.decoded.validate(o.url, certificateContext, crlLocator(crl), validationOptions, result)
      toChecks(location, result)
    }.flatten.toList
  }


  private def toChecks[T <: CertificateRepositoryObject](location: ValidationLocation, result: ValidationResult): List[Check] = {
    result.getWarnings(location).asScala.map(r => Warning(location, r.getKey, r.getParams: _*)).toList ++
    result.getFailures(location).asScala.map(r => Reject(location, r.getKey, r.getParams: _*)).toList
  }

  private def stepDown(cert: RepositoryObject[X509ResourceCertificate]): Map[URI, ValidatedObject] = {
    val ski: String = HashUtil.stringify(cert.decoded.getSubjectKeyIdentifier)
    if (seen.contains(ski)) {
      logger.error(s"Found circular reference of certificates: from ${certificateContext.getLocation} [$certificateSkiHex] to ${cert.url} [$ski]")
      Map()
    } else {
      val newValidationContext = new CertificateRepositoryObjectValidationContext(new URI(cert.url), cert.decoded)
      val nextLevelWalker = new TopDownWalker2(newValidationContext, store, repoService, validationOptions, validationStartTime)(seen)
      nextLevelWalker.execute
    }
  }

  private def prefetch(uri: URI) = repoService.visitRepo(uri)

  private def validateObject[T <: CertificateRepositoryObject](obj: RepositoryObject[T])(validate: (String, ValidationResult) => Unit) = {
    val location = new ValidationLocation(obj.url)
    val validationResult = ValidationResult.withLocation(location)
    validate(obj.url, validationResult)
    (location, validationResult)
  }

  private def findRecentValidCrl(crlList: Seq[CrlObject]): Option[CrlObject] =
    crlList.sortBy(_.decoded.getNumber.negate).find { crl =>
      val (_, crlValidationResult) = _validateCrl(crl)
      !crlValidationResult.hasFailures
    }

  private def validateAllCrls(crlList: Seq[CrlObject]): List[Check] =
    crlList.map { crl =>
      val (location, crlValidationResult) = _validateCrl(crl)
      toChecks(location, crlValidationResult)
    }.flatten.toList


  private def _validateCrl(crl: CrlObject): (ValidationLocation, ValidationResult) =
    validateObject(crl) { (url, validationResult) =>
      crl.decoded.validate(url, certificateContext, crlLocator(crl), validationOptions, validationResult)
    }

  private def fetchCrlsByAKI: Seq[CrlObject] = store.getCrls(certificateContext.getSubjectKeyIdentifier)
  private def fetchMftsByAKI: Seq[ManifestObject] = store.getManifests(certificateContext.getSubjectKeyIdentifier)

  private def crlLocator(crl :CrlObject) = new CrlLocator {
    override def getCrl(uri: URI, context: CertificateRepositoryObjectValidationContext, result: ValidationResult): X509Crl =
      crl.decoded
  }

  private def findRecentValidMft(mftList: Seq[ManifestObject], crl: CrlObject): Option[ManifestObject] =
    mftList.sortBy(_.decoded.getNumber.negate).find { mft =>
      val (_, mftValidationResult) = _validateMft(crl, mft)
      !mftValidationResult.hasFailures
    }

  private def validateAllMfts(mftList: Seq[ManifestObject], crl: CrlObject): List[Check] =
    mftList.map { mft =>
      val (location, mftValidationResult) = _validateMft(crl, mft)
      toChecks(location, mftValidationResult)
    }.flatten.toList


  def _validateMft(crl: CrlObject, mft: ManifestObject): (ValidationLocation, ValidationResult) = {
    validateObject(mft) { (url, validationResult) =>
      mft.decoded.validate(url, certificateContext, crlLocator(crl), validationOptions, validationResult)
    }
  }

  type FileAndHashEntries = Map[URI, Array[Byte]]

  case class ClassifiedObjects(roas: Seq[RoaObject], certificates: Seq[CertificateObject], crls: Seq[CrlObject])

  private def classify(objects: Seq[RepositoryObject[_]]) = {
    var (roas, certificates, crls) = (List[RoaObject](), List[CertificateObject](), List[CrlObject]())
    val c = objects.foreach {
      case roa: RoaObject => roas = roa :: roas
      case cer: CertificateObject => certificates = cer :: certificates
      case crl: CrlObject => crls = crl :: crls
      case _ =>
    }
    ClassifiedObjects(roas.toSeq, certificates.toSeq, crls.toSeq)
  }


  def checkManifestUrlOnCertMatchesLocationInRepo(manifest: ManifestObject): Option[Warning] = {
    val manifestLocationInCertificate = certificateContext.getManifestURI.toString
    val manifestLocationInRepository = manifest.url
    if (!manifestLocationInRepository.equalsIgnoreCase(manifestLocationInCertificate)) {
      Some(Warning(new ValidationLocation(manifestLocationInRepository),
        VALIDATOR_MANIFEST_LOCATION_MISMATCH, manifestLocationInCertificate, manifestLocationInRepository))
    } else
      None
  }

  def crossCheckCrls(crl: CrlObject, manifestCrlEntries: Seq[CrlObject], validationLocation: ValidationLocation): Option[Warning] = {
    if (manifestCrlEntries.isEmpty) {
      Some(Warning(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, "*.obj"))
    } else if (manifestCrlEntries.size > 1) {
      val crlUris = manifestCrlEntries.map(_.url).mkString(",")
      Some(Warning(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, s"Single CRL expected, found: $crlUris"))
    } else {
      val crlOnMft: CrlObject = manifestCrlEntries.head
      if (crlOnMft.url != crl.url) {
        Some(Warning(validationLocation, VALIDATOR_MANIFEST_CRL_URI_MISMATCH, crlOnMft.url, crl.url))
      } else if (!HashUtil.equals(crl.hash, crlOnMft.hash)) {
        Some(Warning(validationLocation, VALIDATOR_MANIFEST_HASH_MISMATCH, crlOnMft.url, certificateSkiHex))
      } else
        None
    }
  }

  private def checkManifestObjects(manifest: ManifestObject, crlByAki: CrlObject) = {
    val validationLocation = new ValidationLocation(manifest.url)

    val (classified @ ClassifiedObjects(roas, childrenCertificates, crlsOnManifest), warnings) = getManifestObjects(manifest)

    val crlWarning = crossCheckCrls(crlByAki, crlsOnManifest, validationLocation)

    // TODO Implement more checks for other objects on the manifest

    (classified, warnings ++ crlWarning.toList)
  }


  private def getManifestObjects(manifest: ManifestObject) = {
    val repositoryUri = certificateContext.getRepositoryURI
    val validationLocation = new ValidationLocation(manifest.url)
    val manifestEntries: FileAndHashEntries = manifest.decoded.getHashes.entrySet().asScala.map { entry =>
      repositoryUri.resolve(entry.getKey) -> entry.getValue
    }.toMap

    val objectsOrWarnings: Iterable[Either[Warning, RepositoryObject[_]]] = manifestEntries.map { e =>
      val (uri, hash) = e
      val obj = store.getObject(uri)
      val r = obj.toRight(Warning(validationLocation, VALIDATOR_MANIFEST_FILE_NOT_FOUND_BY_AKI, uri.toString, certificateSkiHex))
      r.right.flatMap { o =>
        Either.cond[Warning, RepositoryObject[_]](HashUtil.equals(o.hash, hash), o,
          Warning(validationLocation, VALIDATOR_MANIFEST_LOCATION_MISMATCH, uri.toString, certificateSkiHex))
      }
    }
    val warnings = objectsOrWarnings.collect { case Left(w) => w }
    val objects = objectsOrWarnings.collect { case Right(o) => o }

    (classify(objects.toSeq), warnings.toSeq)
  }


}
