/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.storage.impl

import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}

import scala.util.Try

object `X-Goog-Encryption-Algorithm` extends ModeledCustomHeaderCompanion[`X-Goog-Encryption-Algorithm`] {
  override val name = "x-goog-encryption-algorithm"
  override def parse(value: String) = Try(new `X-Goog-Encryption-Algorithm`(value))
}

final case class `X-Goog-Encryption-Algorithm`(override val value: String)
    extends ModeledCustomHeader[`X-Goog-Encryption-Algorithm`] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion = `X-Goog-Encryption-Algorithm`
}

object `X-Goog-Encryption-Key` extends ModeledCustomHeaderCompanion[`X-Goog-Encryption-Key`] {
  override val name = "x-goog-encryption-key"
  override def parse(value: String) = Try(new `X-Goog-Encryption-Key`(value))
}

final case class `X-Goog-Encryption-Key`(override val value: String)
    extends ModeledCustomHeader[`X-Goog-Encryption-Key`] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion = `X-Goog-Encryption-Key`
}

object `X-Goog-Encryption-Key-Sha256` extends ModeledCustomHeaderCompanion[`X-Goog-Encryption-Key-Sha256`] {
  override val name = "x-goog-encryption-key-sha256"
  override def parse(value: String) = Try(new `X-Goog-Encryption-Key-Sha256`(value))
}

final case class `X-Goog-Encryption-Key-Sha256`(override val value: String)
    extends ModeledCustomHeader[`X-Goog-Encryption-Key-Sha256`] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion = `X-Goog-Encryption-Key-Sha256`
}

object `X-Goog-Copy-Source-Encryption-Algorithm`
    extends ModeledCustomHeaderCompanion[`X-Goog-Copy-Source-Encryption-Algorithm`] {
  override val name = "x-goog-copy-source-encryption-algorithm"
  override def parse(value: String) = Try(new `X-Goog-Copy-Source-Encryption-Algorithm`(value))
}

final case class `X-Goog-Copy-Source-Encryption-Algorithm`(override val value: String)
    extends ModeledCustomHeader[`X-Goog-Copy-Source-Encryption-Algorithm`] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion = `X-Goog-Copy-Source-Encryption-Algorithm`
}

object `X-Goog-Copy-Source-Encryption-Key` extends ModeledCustomHeaderCompanion[`X-Goog-Copy-Source-Encryption-Key`] {
  override val name = "x-goog-copy-source-encryption-key"
  override def parse(value: String) = Try(new `X-Goog-Copy-Source-Encryption-Key`(value))
}

final case class `X-Goog-Copy-Source-Encryption-Key`(override val value: String)
    extends ModeledCustomHeader[`X-Goog-Copy-Source-Encryption-Key`] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion = `X-Goog-Copy-Source-Encryption-Key`
}

object `X-Goog-Copy-Source-Encryption-Key-Sha256`
    extends ModeledCustomHeaderCompanion[`X-Goog-Copy-Source-Encryption-Key-Sha256`] {
  override val name = "x-goog-copy-source-encryption-key-sha256"
  override def parse(value: String) = Try(new `X-Goog-Copy-Source-Encryption-Key-Sha256`(value))
}

final case class `X-Goog-Copy-Source-Encryption-Key-Sha256`(override val value: String)
    extends ModeledCustomHeader[`X-Goog-Copy-Source-Encryption-Key-Sha256`] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion = `X-Goog-Copy-Source-Encryption-Key-Sha256`
}
