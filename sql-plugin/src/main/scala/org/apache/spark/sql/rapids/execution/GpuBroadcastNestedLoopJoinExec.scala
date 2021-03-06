/*
 * Copyright (c) 2020, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.rapids.execution

import ai.rapids.cudf.{NvtxColor, Table}
import com.nvidia.spark.rapids.{Arm, BaseExprMeta, ConfKeysAndIncompat, GpuBindReferences, GpuColumnVector, GpuExec, GpuExpression, GpuFilter, GpuOverrides, NvtxWithMetrics, RapidsConf, RapidsMeta, SparkPlanMeta}
import com.nvidia.spark.rapids.GpuMetricNames.{NUM_OUTPUT_BATCHES, NUM_OUTPUT_ROWS, TOTAL_TIME}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression}
import org.apache.spark.sql.catalyst.plans.{Cross, ExistenceJoin, FullOuter, Inner, InnerLike, JoinType, LeftExistence, LeftOuter, RightOuter}
import org.apache.spark.sql.catalyst.plans.physical.{BroadcastDistribution, Distribution, IdentityBroadcastMode, UnspecifiedDistribution}
import org.apache.spark.sql.execution.{BinaryExecNode, SparkPlan}
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec
import org.apache.spark.sql.execution.joins.{BroadcastNestedLoopJoinExec, BuildLeft, BuildRight, BuildSide}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.vectorized.ColumnarBatch

class GpuBroadcastNestedLoopJoinMeta(
    join: BroadcastNestedLoopJoinExec,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: ConfKeysAndIncompat)
    extends SparkPlanMeta[BroadcastNestedLoopJoinExec](join, conf, parent, rule) {

  val condition: Option[BaseExprMeta[_]] =
    join.condition.map(GpuOverrides.wrapExpr(_, conf, Some(this)))

  override val childExprs: Seq[BaseExprMeta[_]] = condition.toSeq

  override def tagPlanForGpu(): Unit = {
    join.joinType match {
      case Inner =>
      case Cross =>
      case _ => willNotWorkOnGpu(s"$join.joinType currently is not supported")
    }

    val buildSide = join.buildSide match {
      case BuildLeft => childPlans.head
      case BuildRight => childPlans(1)
    }

    if (!buildSide.canThisBeReplaced) {
      willNotWorkOnGpu("the broadcast for this join must be on the GPU too")
    }

    if (!canThisBeReplaced) {
      buildSide.willNotWorkOnGpu(
        "the GpuBroadcastNestedLoopJoin this feeds is not on the GPU")
    }
  }

  override def convertToGpu(): GpuExec = {
    val left = childPlans.head.convertIfNeeded()
    val right = childPlans(1).convertIfNeeded()
    // The broadcast part of this must be a BroadcastExchangeExec
    val buildSide = join.buildSide match {
      case BuildLeft => left
      case BuildRight => right
    }
    if (!buildSide.isInstanceOf[GpuBroadcastExchangeExec]) {
      throw new IllegalStateException("the broadcast must be on the GPU too")
    }
    GpuBroadcastNestedLoopJoinExec(
      left, right, join.buildSide,
      join.joinType,
      condition.map(_.convertToGpu()),
      conf.gpuTargetBatchSizeBytes)
  }
}

object GpuBroadcastNestedLoopJoinExec extends Arm {
  def innerLikeJoin(
      streamedIter: Iterator[ColumnarBatch],
      builtTable: Table,
      buildSide: BuildSide,
      boundCondition: Option[GpuExpression],
      joinTime: SQLMetric,
      joinOutputRows: SQLMetric,
      numOutputRows: SQLMetric,
      numOutputBatches: SQLMetric,
      filterTime: SQLMetric,
      totalTime: SQLMetric): Iterator[ColumnarBatch] = {
    streamedIter.map { cb =>
      val startTime = System.nanoTime()
      val streamTable = withResource(cb) { cb =>
        GpuColumnVector.from(cb)
      }
      val joined =
        withResource(new NvtxWithMetrics("join", NvtxColor.ORANGE, joinTime)) { _ =>
          val joinedTable = withResource(streamTable) { tab =>
            buildSide match {
              case BuildLeft => builtTable.crossJoin(tab)
              case BuildRight => tab.crossJoin(builtTable)
            }
          }
          withResource(joinedTable) { jt =>
            GpuColumnVector.from(jt)
          }
        }
      joinOutputRows += joined.numRows()
      val ret = if (boundCondition.isDefined) {
        GpuFilter(joined, boundCondition.get, numOutputRows, numOutputBatches, filterTime)
      } else {
        numOutputRows += joined.numRows()
        numOutputBatches += 1
        joined
      }
      totalTime += (System.nanoTime() - startTime)
      ret
    }
  }
}

case class GpuBroadcastNestedLoopJoinExec(
    left: SparkPlan,
    right: SparkPlan,
    buildSide: BuildSide,
    joinType: JoinType,
    condition: Option[Expression],
    targetSize: Long) extends BinaryExecNode with GpuExec {

  override protected def doExecute(): RDD[InternalRow] =
    throw new IllegalStateException("This should only be called from columnar")

  override lazy val additionalMetrics: Map[String, SQLMetric] = Map(
    "buildDataSize" -> SQLMetrics.createSizeMetric(sparkContext, "build side size"),
    "buildTime" -> SQLMetrics.createNanoTimingMetric(sparkContext, "build time"),
    "joinTime" -> SQLMetrics.createNanoTimingMetric(sparkContext, "join time"),
    "joinOutputRows" -> SQLMetrics.createMetric(sparkContext, "join output rows"),
    "filterTime" -> SQLMetrics.createNanoTimingMetric(sparkContext, "filter time"))

  /** BuildRight means the right relation <=> the broadcast relation. */
  private val (streamed, broadcast) = buildSide match {
    case BuildRight => (left, right)
    case BuildLeft => (right, left)
  }

  def broadcastExchange: GpuBroadcastExchangeExec = broadcast match {
    case gpu: GpuBroadcastExchangeExec => gpu
    case reused: ReusedExchangeExec => reused.child.asInstanceOf[GpuBroadcastExchangeExec]
  }

  override def requiredChildDistribution: Seq[Distribution] = buildSide match {
    case BuildLeft =>
      BroadcastDistribution(IdentityBroadcastMode) :: UnspecifiedDistribution :: Nil
    case BuildRight =>
      UnspecifiedDistribution :: BroadcastDistribution(IdentityBroadcastMode) :: Nil
  }

  override def output: Seq[Attribute] = {
    joinType match {
      case _: InnerLike =>
        left.output ++ right.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case FullOuter =>
        left.output.map(_.withNullability(true)) ++ right.output.map(_.withNullability(true))
      case j: ExistenceJoin =>
        left.output :+ j.exists
      case LeftExistence(_) =>
        left.output
      case x =>
        throw new IllegalArgumentException(
          s"BroadcastNestedLoopJoin should not take $x as the JoinType")
    }
  }

  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val numOutputRows = longMetric(NUM_OUTPUT_ROWS)
    val numOutputBatches = longMetric(NUM_OUTPUT_BATCHES)
    val totalTime = longMetric(TOTAL_TIME)
    val joinTime = longMetric("joinTime")
    val filterTime = longMetric("filterTime")
    val joinOutputRows = longMetric("joinOutputRows")

    val boundCondition = condition.map(GpuBindReferences.bindGpuReference(_, output))

    val buildTime = longMetric("buildTime")
    val buildDataSize = longMetric("buildDataSize")

    val broadcastedRelation =
      broadcastExchange.executeColumnarBroadcast[SerializeConcatHostBuffersDeserializeBatch]()

    lazy val builtTable: Table = {
      withResource(new NvtxWithMetrics("build join table", NvtxColor.GREEN, buildTime)) { _ =>
        val ret = GpuColumnVector.from(broadcastedRelation.value.batch)
        // Don't warn for a leak, because we cannot control when we are done with this
        (0 until ret.getNumberOfColumns).foreach( i => {
          val column = ret.getColumn(i)
          column.noWarnLeakExpected()
          buildDataSize += column.getDeviceMemorySize
        })
        ret
      }
    }

    streamed.executeColumnar().mapPartitions { streamedIter =>
      joinType match {
        case _: InnerLike => GpuBroadcastNestedLoopJoinExec.innerLikeJoin(streamedIter,
          builtTable, buildSide, boundCondition,
          joinTime, joinOutputRows, numOutputRows, numOutputBatches, filterTime, totalTime)
        case _ => throw new IllegalArgumentException(s"$joinType + $buildSide is not supported" +
            s" and should be run on the CPU")
      }
    }
  }
}