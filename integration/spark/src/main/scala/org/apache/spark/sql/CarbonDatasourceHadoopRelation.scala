/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import scala.collection.JavaConverters._

import org.apache.spark.CarbonInputMetrics
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{GetStructField, NamedExpression}
import org.apache.spark.sql.execution.command.management.CarbonInsertIntoCommand
import org.apache.spark.sql.execution.strategy.PushDownHelper
import org.apache.spark.sql.hive.CarbonRelation
import org.apache.spark.sql.optimizer.CarbonFilters
import org.apache.spark.sql.sources.{And, BaseRelation, EqualNullSafe, EqualTo, Filter, InsertableRelation, Or}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CarbonException

import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.index.IndexFilter
import org.apache.carbondata.core.indexstore.PartitionSpec
import org.apache.carbondata.core.metadata.AbsoluteTableIdentifier
import org.apache.carbondata.core.metadata.schema.table.CarbonTable
import org.apache.carbondata.core.metadata.schema.table.column.CarbonColumn
import org.apache.carbondata.core.scan.expression.Expression
import org.apache.carbondata.core.scan.expression.logical.AndExpression
import org.apache.carbondata.hadoop.CarbonProjection
import org.apache.carbondata.spark.rdd.CarbonScanRDD

case class CarbonDatasourceHadoopRelation(
    sparkSession: SparkSession,
    paths: Array[String],
    parameters: Map[String, String],
    tableSchema: Option[StructType])
  extends BaseRelation with InsertableRelation {

  val caseInsensitiveMap: Map[String, String] = parameters.map(f => (f._1.toLowerCase, f._2))
  lazy val identifier: AbsoluteTableIdentifier = AbsoluteTableIdentifier.from(
    FileFactory.getUpdatedFilePath(paths.head),
    CarbonEnv.getDatabaseName(caseInsensitiveMap.get("dbname"))(sparkSession),
    caseInsensitiveMap("tablename"))
  CarbonUtils.updateSessionInfoToCurrentThread(sparkSession)

  @transient lazy val carbonRelation: CarbonRelation =
    CarbonEnv.getInstance(sparkSession).carbonMetaStore.
      createCarbonRelation(parameters, identifier, sparkSession)


  @transient lazy val carbonTable: CarbonTable = carbonRelation.carbonTable

  override def sqlContext: SQLContext = sparkSession.sqlContext

  override def schema: StructType = tableSchema.getOrElse(carbonRelation.schema)

  def getStorageOrdinal(filter: Filter): Int = {
    val leftColumn = filter.references.map(leftRef => carbonTable
      .getCreateOrderColumn
      .asScala
      .find(_.getColName.equals(leftRef))).head.get
    if (leftColumn.isDimension) {
      leftColumn.getOrdinal
    } else {
      leftColumn.getOrdinal + carbonTable.getAllDimensions.size()
    }
  }

  def collectSimilarExpressions(filter: Filter): Seq[(Filter, Int)] = {
    filter match {
      case And(left, right) =>
        collectSimilarExpressions(left) ++ collectSimilarExpressions(right)
      case Or(left, right) => collectSimilarExpressions(left) ++ collectSimilarExpressions(right)
      case others => Seq((others, getStorageOrdinal(others)))
    }
  }

  private def sortFilter(filter: Filter): (Filter, Int) = {
    filter match {
      case and@And(left, right) =>
        val hasOr = and.productIterator.exists(_.isInstanceOf[Or])
        if (hasOr) {
          if (left.references.length == 1 && right.references.length == 1) {
            val leftOrdinal = getStorageOrdinal(left)
            val rightOrdinal = getStorageOrdinal(right)
            if (leftOrdinal > rightOrdinal) {
              (And(right, left), leftOrdinal)
            } else {
              (and, rightOrdinal)
            }
          } else {
            if (left.references.toSeq == right.references.toSeq ||
                right.references.diff(left.references).length == 0) {
              val sorted = sortFilter(left)
              val rightOrdinal = getStorageOrdinal(right)
              if (sorted._2 >= rightOrdinal) {
                (And(right, sorted._1), rightOrdinal)
              } else {
                (And(sorted._1, right), sorted._2)
              }
            } else {
              val leftSorted = sortFilter(left)
              val rightSorted = sortFilter(right)
              if (leftSorted._2 > rightSorted._2) {
                (And(rightSorted._1, leftSorted._1), rightSorted._2)
              } else {
                (And(leftSorted._1, rightSorted._1), leftSorted._2)
              }
            }
          }
        } else {
          val andFilterList = collectSimilarExpressions(and)
          val sortedFilterAndOrdinal = andFilterList.sortBy(_._2)
          (sortedFilterAndOrdinal.map(_._1).reduce(And), sortedFilterAndOrdinal.head._2)
        }
      case or@Or(left, right) =>
        val hasAnd = or.productIterator.exists(_.isInstanceOf[And])
        if (hasAnd) {
          if (left.references.length == 1 && right.references.length == 1) {
            val leftOrdinal = getStorageOrdinal(left)
            val rightOrdinal = getStorageOrdinal(right)
            if (leftOrdinal > rightOrdinal) {
              (Or(right, left), leftOrdinal)
            } else {
              (or, rightOrdinal)
            }
          } else {
            if (left.references.toSeq == right.references.toSeq ||
                right.references.diff(left.references).length== 0) {
              val sorted = sortFilter(left)
              val rightOrdinal = getStorageOrdinal(right)
              if (sorted._2 >= rightOrdinal) {
                (Or(right, sorted._1), rightOrdinal)
              } else {
                (Or(sorted._1, right), sorted._2)
              }
            } else {
              val leftSorted = sortFilter(left)
              val rightSorted = sortFilter(right)
              if (leftSorted._2 > rightSorted._2) {
                (Or(rightSorted._1, leftSorted._1), rightSorted._2)
              } else {
                (Or(leftSorted._1, rightSorted._1), leftSorted._2)
              }
            }
          }
        } else {
          val orFilterList = collectSimilarExpressions(or)
          val sortedFilterAndOrdinal = orFilterList.sortBy(_._2)
          (sortedFilterAndOrdinal.map(_._1).reduce(Or), sortedFilterAndOrdinal.head._2)
        }
      case others => (others, getStorageOrdinal(others))
    }
  }

  def buildScan(requiredColumns: Array[String],
      filterComplex: Seq[org.apache.spark.sql.catalyst.expressions.Expression],
      projects: Seq[NamedExpression],
      filters: Array[Filter],
      partitions: Seq[PartitionSpec]): RDD[InternalRow] = {
    val d = filters.map(sortFilter).sortBy(_._2).map(_._1)
    val filterExpression = d.flatMap { filter =>
      CarbonFilters.createCarbonFilter(schema, filter,
        carbonTable.getTableInfo.getFactTable.getTableProperties.asScala)
    }.reduceOption(new AndExpression(_, _))

    val projection = new CarbonProjection

    // As Filter pushdown for Complex datatype is not supported, if filter is applied on complex
    // column, then Projection pushdown on Complex Columns will not take effect. Hence, check if
    // filter contains Struct Complex Column.
    val complexFilterExists = filterComplex.map(col =>
      col.map(_.isInstanceOf[GetStructField]))

    if (!complexFilterExists.exists(f => f.contains(true))) {
      PushDownHelper.pushDownProjection(requiredColumns, projects, projection)
    } else {
      requiredColumns.foreach(projection.addColumn)
    }

    val inputMetricsStats: CarbonInputMetrics = new CarbonInputMetrics
    new CarbonScanRDD(
      sparkSession,
      projection,
      filterExpression.map(new IndexFilter(carbonTable, _, true)).orNull,
      identifier,
      carbonTable.getTableInfo.serialize(),
      carbonTable.getTableInfo,
      inputMetricsStats,
      partitions)
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = new Array[Filter](0)

  override def toString: String = {
    "CarbonDatasourceHadoopRelation"
  }

  override def sizeInBytes: Long = carbonRelation.sizeInBytes

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    if (carbonRelation.output.size > CarbonCommonConstants.DEFAULT_MAX_NUMBER_OF_COLUMNS) {
      CarbonException.analysisException("Maximum supported column by carbon is: " +
                                        CarbonCommonConstants.DEFAULT_MAX_NUMBER_OF_COLUMNS)
    }
    if (data.logicalPlan.output.size >= carbonRelation.output.size) {
      CarbonInsertIntoCommand(
        databaseNameOp = Some(this.carbonRelation.databaseName),
        tableName = this.carbonRelation.tableName,
        options = scala.collection.immutable
          .Map("fileheader" -> this.tableSchema.get.fields.map(_.name).mkString(",")),
        isOverwriteTable = overwrite,
        logicalPlan = data.logicalPlan,
        tableInfo = this.carbonRelation.carbonTable.getTableInfo).run(sparkSession)
    } else {
      CarbonException.analysisException(
        "Cannot insert into target table because number of columns mismatch")
    }
  }

}
