/*
 * Copyright 2019 ABSA Group Limited
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

package za.co.absa.spline.harvester

import java.util.UUID

import za.co.absa.spline.harvester.ModelConstants.OperationParams._
import za.co.absa.spline.harvester.ModelConstants.OperationExtras._
import za.co.absa.spline.model.expr.{Alias, AttrRef, Expression, Generic}
import za.co.absa.spline.producer.model.{DataOperation, OperationLike}

import scala.collection.mutable


case class AttributeDependencies(attributes: Map[UUID, Set[UUID]], operations: Map[UUID, Set[Int]])

object AttributeDependencySolver {

  def resolveDependencies(dataOperations: Seq[DataOperation], ioOperations: Seq[OperationLike]):
      AttributeDependencies = {

    val attributeDependencies = dataOperations
      .map(resolveDependencies)
      .reduce(mergeDependencies)

    val operationDependencies = resolveAttributeOperationDependencies(
      dataOperations ++ ioOperations,
      attributeDependencies)

    AttributeDependencies(attributeDependencies, operationDependencies)
  }

  private def resolveDependencies(op: DataOperation): Map[UUID, Set[UUID]] =
    op.extra(Name) match {
      case "Project" => resolveExpressionList(op.params(Transformations), op.schema)
      case "Aggregate" => resolveExpressionList(op.params(Aggregations), op.schema)
      case "Generate" => resolveGenerator(op)
      case _ => Map.empty
    }

  private def resolveExpressionList(exprList: Any, maybeSchema: Option[Any]): Map[UUID, Set[UUID]] = {

    val schema = maybeSchema.get.asInstanceOf[Seq[UUID]]

    exprList
      .asInstanceOf[Some[Seq[Expression]]]
      .get
      .zip(schema)
      .map{ case (expr, attrId) => attrId -> toAttrDependencies(expr) }
      .toMap
  }

  private def resolveGenerator(op: DataOperation): Map[UUID, Set[UUID]] = {

    val expression = op.params("generator").asInstanceOf[Some[Generic]].get
    val dependencies = toAttrDependencies(expression)

    val keyId = op.params("generatorOutput")
      .asInstanceOf[Some[Seq[AttrRef]]]
      .get.head.refId

    Map(keyId -> dependencies)
  }

  private def toAttrDependencies(expr: Expression): Set[UUID] = expr match {
    case AttrRef(refId)                        => Set(refId)
    case Alias(_, child)                       => toAttrDependencies(child)
    case e: Expression if e.children.nonEmpty  => e.children.map(toAttrDependencies).reduce(_ union _)
    case _                                     => Set.empty
  }

  def mergeDependencies(acc: Map[UUID, Set[UUID]], newDependencies:  Map[UUID, Set[UUID]]): Map[UUID, Set[UUID]] =
    newDependencies.foldLeft(acc) { case (acc, (newKey, newValue)) =>

      // add old dependencies to the new dependencies when they contain one of old keys
      val addToNewValue = acc.flatMap {
        case (k, v)  if newValue(k) => v
        case _                      => Nil
      }

      val updatedNewValue = newValue.union(addToNewValue.toSet)

      // add new dependencies to all dependencies that contains the new key
      val updatedAcc = acc.map {
        case (k, v) if v(newKey) => k -> v.union(updatedNewValue)
        case (k, v)              => k -> v
      }

      updatedAcc + (newKey -> updatedNewValue)
    }

  def schemaOf(op: OperationLike): Seq[UUID] = {

    val maybeSchema = op.schema.asInstanceOf[Option[Seq[UUID]]]
    maybeSchema.getOrElse(Seq.empty)
  }

  def schemaMapOf(operations: Seq[OperationLike]): Map[Int, Set[UUID]] = {

    val operationMap = operations.map(op => op.id -> op).toMap

    def transitiveInputSchemaOf(op: OperationLike): Seq[UUID] = {
      if (op.childIds.isEmpty) {
        Seq.empty
      } else {
        val inputOp = operationMap(op.childIds.head)
        val maybeSchema = inputOp.schema.asInstanceOf[Option[Seq[UUID]]]

        maybeSchema.getOrElse(transitiveInputSchemaOf(inputOp))
      }
    }

    operations.map { op =>
      val outputSchema = schemaOf(op)
      val inputSchema = transitiveInputSchemaOf(op)

      op.id -> (outputSchema ++ inputSchema).toSet
    }.toMap
  }

  def resolveAttributeOperationDependencies(operations: Seq[OperationLike], attributeDependencies: Map[UUID, Set[UUID]]):
      Map[UUID, Set[Int]] = {

    val schemaMap = schemaMapOf(operations)
    val attributeOperationMap = mutable.Map[UUID, Set[Int]]()

    operations.foreach { op =>
      schemaMap(op.id).foreach { attr =>
        val opSet = attributeOperationMap.getOrElse(attr, Set.empty)
        attributeOperationMap.update(attr, opSet + op.id)
      }
    }

    val operationDependencies = attributeOperationMap.keys.map { attributeId =>
      val dependentAttributes = attributeDependencies.getOrElse(attributeId, Set.empty) + attributeId

      val dependentOperations = dependentAttributes
        .flatMap(attributeOperationMap.get)
        .fold(Set.empty)(_ union _)

      attributeId -> dependentOperations
    }

    operationDependencies.toMap
  }
}
