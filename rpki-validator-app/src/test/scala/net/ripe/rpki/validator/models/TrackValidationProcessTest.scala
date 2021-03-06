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

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{BeforeAndAfter, FunSuite}
import scalaz.Failure
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import java.net.URI
import scala.concurrent.stm._
import net.ripe.rpki.validator.util.TrustAnchorLocator
import net.ripe.rpki.validator.config.MemoryImage
import org.mockito.Mockito._
import org.joda.time.DateTime
import java.io.File
import java.util.{Collections, Date}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TrackValidationProcessTest extends FunSuite with ShouldMatchers with BeforeAndAfter {

  class MyTrackValidationProcessTrustAnchor(trustAnchors: Seq[TrustAnchor]) extends MyValidationProcess with TrackValidationProcess {
    override val memoryImage = Ref(MemoryImage(Filters(), Whitelist(), new TrustAnchors(trustAnchors), ValidatedObjects(new TrustAnchors(trustAnchors))))
    override def runProcess() = { super.runProcess() }
  }

  val tal = new TrustAnchorLocator(new File(""), "caName", URI.create("rsync://rpki.ripe.net/root.cer"), "publicKeyInfo", Collections.emptyList())

  test("should fail with no processable trust anchor") {
    val subject = new MyTrackValidationProcessTrustAnchor(Seq.empty[TrustAnchor])

    val result = subject.runProcess()
    result should equal(Failure("Trust anchor not idle or enabled"));
  }

  test("should not process disabled trust anchors") {
    val subject = new MyTrackValidationProcessTrustAnchor(Seq(TrustAnchor(tal, Idle(new DateTime()), false)))

    val result = subject.runProcess()
    result should equal(Failure("Trust anchor not idle or enabled"));
  }

  test("should not process already running trust anchors") {
    val subject = new MyTrackValidationProcessTrustAnchor(Seq(TrustAnchor(tal, Running(""), true)))

    val result = subject.runProcess()
    result should equal(Failure("Trust anchor not idle or enabled"));
  }
}
