/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.client

import akka.annotation.InternalApi

@InternalApi
private[bigquery] object GoogleEndpoints {

  val bigQueryV2Url: String = "https://www.googleapis.com/bigquery/v2"

  def queryUrl(projectId: String): String =
    s"$bigQueryV2Url/projects/$projectId/queries"

  def tableListUrl(projectId: String, dataset: String): String =
    s"$bigQueryV2Url/projects/$projectId/datasets/$dataset/tables"

  def fieldListUrl(projectId: String, dataset: String, tableName: String): String =
    s"$bigQueryV2Url/projects/$projectId/datasets/$dataset/tables/$tableName"

  def insertIntoUrl(projectId: String, dataset: String, tableName: String): String =
    s"$bigQueryV2Url/projects/$projectId/datasets/$dataset/tables/$tableName/insertAll"

  def createTableUrl(projectId: String, dataset: String): String =
    s"$bigQueryV2Url/projects/$projectId/datasets/$dataset/tables"

  def deleteTableUrl(projectId: String, dataset: String, tableName: String): String =
    s"$bigQueryV2Url/projects/$projectId/datasets/$dataset/tables/$tableName"

  def testConnectionUrl(projectId: String, dataset: String): String =
    s"$bigQueryV2Url/projects/$projectId/datasets/$dataset"

  def cancellationUrl(projectId: String, jobId: String): String =
    s"$bigQueryV2Url/projects/$projectId/jobs/$jobId/cancel"
}
