/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.scaladsl.schema

import akka.stream.alpakka.googlecloud.bigquery.model.TableJsonProtocol.{BytesType, RepeatedMode}
import akka.util.ByteString

import scala.collection.Iterable

trait CollectionSchemas extends LowPriorityCollectionSchemas {

  /**
   * Supplies the SchemaWriter for Arrays.
   */
  implicit def arraySchemaWriter[T](implicit writer: SchemaWriter[T]): SchemaWriter[Array[T]] = { (name, mode) =>
    require(mode != RepeatedMode, "A collection cannot be nested inside another collection.")
    writer.write(name, RepeatedMode)
  }

  // Placed here to establish priority over iterableSchemaWriter[Byte]
  implicit val byteStringSchemaWriter: SchemaWriter[ByteString] = new PrimitiveSchemaWriter(BytesType)
}

private[schema] trait LowPriorityCollectionSchemas {

  /**
   * Supplies the SchemaWriter for Iterables.
   */
  implicit def iterableSchemaWriter[T](implicit writer: SchemaWriter[T]): SchemaWriter[Iterable[T]] = { (name, mode) =>
    require(mode != RepeatedMode, "A collection cannot be nested inside another collection.")
    writer.write(name, RepeatedMode)
  }

}
