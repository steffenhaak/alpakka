/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.impl.util

import akka.NotUsed
import akka.stream.scaladsl.Flow

private[bigquery] object ConcatWithHeaders {

  def apply(): Flow[(Seq[String], Seq[Seq[String]]), Seq[String], NotUsed] =
    Flow[(Seq[String], Seq[Seq[String]])].statefulMapConcat(() => {
      var isFirstRun = true

      {
        case (fields, rows) =>
          if (isFirstRun && rows.nonEmpty) {
            isFirstRun = false
            fields :: rows.toList
          } else {
            rows.toList
          }
      }
    })
}
