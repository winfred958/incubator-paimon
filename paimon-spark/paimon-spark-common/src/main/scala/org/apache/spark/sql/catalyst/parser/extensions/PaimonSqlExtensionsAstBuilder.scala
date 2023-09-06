/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.parser.extensions

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.{ParseTree, TerminalNode}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.parser.extensions.PaimonParserUtils.withOrigin
import org.apache.spark.sql.catalyst.parser.extensions.PaimonSqlExtensionsParser._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.trees.{CurrentOrigin, Origin}

import scala.collection.JavaConverters._

/* This file is based on source code from the Iceberg Project (http://iceberg.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * The AST Builder provides an implementation of [[PaimonSqlExtensionsBaseVisitor]], which can be
 * extended to create a visitor which only needs to handle a subset of the available methods.
 *
 * <p>Most of the content of this class is referenced from Iceberg's IcebergSqlExtensionsAstBuilder.
 *
 * @param delegate
 *   The extension parser.
 */
class PaimonSqlExtensionsAstBuilder(delegate: ParserInterface)
  extends PaimonSqlExtensionsBaseVisitor[AnyRef]
  with Logging {

  /** Creates a single statement of extension statements. */
  override def visitSingleStatement(ctx: SingleStatementContext): LogicalPlan = withOrigin(ctx) {
    visit(ctx.statement).asInstanceOf[LogicalPlan]
  }

  /** Creates a [[CallStatement]] for a stored procedure call. */
  override def visitCall(ctx: CallContext): CallStatement = withOrigin(ctx) {
    val name = toSeq(ctx.multipartIdentifier.parts).map(_.getText)
    val args = toSeq(ctx.callArgument).map(typedVisit[CallArgument])
    CallStatement(name, args)
  }

  /** Creates a positional argument in a stored procedure call. */
  override def visitPositionalArgument(ctx: PositionalArgumentContext): CallArgument =
    withOrigin(ctx) {
      val expression = typedVisit[Expression](ctx.expression)
      PositionalArgument(expression)
    }

  /** Creates a named argument in a stored procedure call. */
  override def visitNamedArgument(ctx: NamedArgumentContext): CallArgument = withOrigin(ctx) {
    val name = ctx.identifier.getText
    val expression = typedVisit[Expression](ctx.expression)
    NamedArgument(name, expression)
  }

  /** Creates a [[Expression]] in a positional and named argument. */
  override def visitExpression(ctx: ExpressionContext): Expression = {
    // reconstruct the SQL string and parse it using the main Spark parser
    // while we can avoid the logic to build Spark expressions, we still have to parse them
    // we cannot call ctx.getText directly since it will not render spaces correctly
    // that's why we need to recurse down the tree in reconstructSqlString
    val sqlString = reconstructSqlString(ctx)
    delegate.parseExpression(sqlString)
  }

  /** Returns a multi-part identifier as Seq[String]. */
  override def visitMultipartIdentifier(ctx: MultipartIdentifierContext): Seq[String] =
    withOrigin(ctx) {
      ctx.parts.asScala.map(_.getText)
    }

  private def toBuffer[T](list: java.util.List[T]) = list.asScala

  private def toSeq[T](list: java.util.List[T]) = toBuffer(list)

  private def reconstructSqlString(ctx: ParserRuleContext): String = {
    toBuffer(ctx.children)
      .map {
        case c: ParserRuleContext => reconstructSqlString(c)
        case t: TerminalNode => t.getText
      }
      .mkString(" ")
  }

  private def typedVisit[T](ctx: ParseTree): T =
    ctx.accept(this).asInstanceOf[T]
}

/* Partially copied from Apache Spark's Parser to avoid dependency on Spark Internals */
object PaimonParserUtils {

  private[sql] def withOrigin[T](ctx: ParserRuleContext)(f: => T): T = {
    val current = CurrentOrigin.get
    CurrentOrigin.set(position(ctx.getStart))
    try {
      f
    } finally {
      CurrentOrigin.set(current)
    }
  }

  private[sql] def position(token: Token): Origin = {
    val opt = Option(token)
    Origin(opt.map(_.getLine), opt.map(_.getCharPositionInLine))
  }

  /** Gets the command which created the token. */
  private[sql] def command(ctx: ParserRuleContext): String = {
    val stream = ctx.getStart.getInputStream
    stream.getText(Interval.of(0, stream.size() - 1))
  }
}
