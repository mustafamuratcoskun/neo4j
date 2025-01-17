/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.cypher.internal.v4_0.ast

import org.neo4j.cypher.internal.v4_0.ast.semantics.SemanticCheckResult.{error, success}
import org.neo4j.cypher.internal.v4_0.ast.semantics.{Scope, SemanticAnalysisTooling, SemanticCheckResult, SemanticCheckable, SemanticExpressionCheck, SemanticPatternCheck, SemanticState, _}
import org.neo4j.cypher.internal.v4_0.expressions.Expression.SemanticContext
import org.neo4j.cypher.internal.v4_0.expressions.functions.{Distance, Exists}
import org.neo4j.cypher.internal.v4_0.expressions.{functions, _}
import org.neo4j.cypher.internal.v4_0.util.Foldable._
import org.neo4j.cypher.internal.v4_0.util._
import org.neo4j.cypher.internal.v4_0.util.helpers.StringHelper.RichString
import org.neo4j.cypher.internal.v4_0.util.symbols._

sealed trait Clause extends ASTNode with SemanticCheckable {
  def name: String

  def returnColumns: List[LogicalVariable] =
    throw new IllegalStateException("This clause is not allowed as a last clause and hence does not declare return columns")
}

sealed trait UpdateClause extends Clause with SemanticAnalysisTooling {
  override def returnColumns: List[LogicalVariable] = List.empty
}

case class LoadCSV(
                    withHeaders: Boolean,
                    urlString: Expression,
                    variable: Variable,
                    fieldTerminator: Option[StringLiteral]
                  )(val position: InputPosition) extends Clause with SemanticAnalysisTooling {
  override def name: String = "LOAD CSV"

  override def semanticCheck: SemanticCheck =
    SemanticExpressionCheck.simple(urlString) chain
      expectType(CTString.covariant, urlString) chain
      checkFieldTerminator chain
      typeCheck

  private def checkFieldTerminator: SemanticCheck = {
    fieldTerminator match {
      case Some(literal) if literal.value.length != 1 =>
        error("CSV field terminator can only be one character wide", literal.position)
      case _ => success
    }
  }

  private def typeCheck: SemanticCheck = {
    val typ = if (withHeaders)
      CTMap
    else
      CTList(CTString)

    declareVariable(variable, typ)
  }
}

case class InputDataStream(variables: Seq[Variable])(val position: InputPosition) extends Clause with SemanticAnalysisTooling {

  override def name: String = "INPUT DATA STREAM"

  override def semanticCheck: SemanticCheck =
    variables.foldSemanticCheck(v => declareVariable(v, types(v)))
}

sealed trait MultipleGraphClause extends Clause with SemanticAnalysisTooling {

  override def semanticCheck: SemanticCheck =
    requireFeatureSupport(s"The `$name` clause", SemanticFeature.MultipleGraphs, position)
}

sealed trait GraphSelection extends Clause with SemanticAnalysisTooling {

  def expression: Expression

  override def semanticCheck: SemanticCheck =
    checkGraphReference chain
      whenState(_.features(SemanticFeature.ExpressionsInViewInvocations))(
        thenBranch = checkGraphReferenceExpressions,
        elseBranch = checkGraphReferenceRecursive
      )

  private def checkGraphReference: SemanticCheck =
    GraphReference.checkNotEmpty(graphReference, expression.position)

  private def checkGraphReferenceRecursive: SemanticCheck =
    graphReference.foldSemanticCheck(_.semanticCheck)

  private def checkGraphReferenceExpressions: SemanticCheck =
    graphReference.foldSemanticCheck {
      case ViewRef(_, arguments)        => checkExpressions(arguments)
      case GraphRefParameter(parameter) => checkExpressions(Seq(parameter))
      case _                            => success
    }

  private def checkExpressions(expressions: Seq[Expression]): SemanticCheck =
    whenState(_.features(SemanticFeature.ExpressionsInViewInvocations))(
      expressions.foldSemanticCheck(expr =>
        SemanticExpressionCheck.check(Expression.SemanticContext.Results, expr)
      )
    )

  def graphReference: Option[GraphReference] =
    GraphReference.from(expression)
}

final case class FromGraph(expression: Expression)(val position: InputPosition) extends GraphSelection {
  override def name = "FROM GRAPH"

  override def semanticCheck: SemanticCheck =
    requireFeatureSupport(s"The `$name` clause", SemanticFeature.FromGraphSelector, position) chain
      super.semanticCheck
}

final case class UseGraph(expression: Expression)(val position: InputPosition) extends GraphSelection {
  override def name = "USE GRAPH"

  override def semanticCheck: SemanticCheck =
    requireFeatureSupport(s"The `$name` clause", SemanticFeature.UseGraphSelector, position) chain
      super.semanticCheck
}

object GraphReference extends SemanticAnalysisTooling {
  def from(expression: Expression): Option[GraphReference] = {

    def fqn(expr: Expression): Option[List[String]] = expr match {
      case p: Property           => fqn(p.map).map(_ :+ p.propertyKey.name)
      case v: Variable           => Some(List(v.name))
      case f: FunctionInvocation => Some(f.namespace.parts :+ f.functionName.name)
      case _                     => None
    }

    (expression, fqn(expression)) match {
      case (f: FunctionInvocation, Some(name)) => Some(ViewRef(CatalogName(name), f.args)(f.position))
      case (p: Parameter, _)                   => Some(GraphRefParameter(p)(p.position))
      case (e, Some(name))                     => Some(GraphRef(CatalogName(name))(e.position))
      case _                                   => None
    }
  }

  def checkNotEmpty(gr: Option[GraphReference], pos: InputPosition): SemanticCheck =
    when(gr.isEmpty)(error("Invalid graph reference", pos))
}

sealed trait GraphReference extends ASTNode with SemanticCheckable {
  override def semanticCheck: SemanticCheck = success
}

final case class GraphRef(name: CatalogName)(val position: InputPosition) extends GraphReference

final case class ViewRef(name: CatalogName, arguments: Seq[Expression])(val position: InputPosition) extends GraphReference with SemanticAnalysisTooling {
  override def semanticCheck: SemanticCheck =
    arguments.zip(argumentsAsGraphReferences).foldSemanticCheck { case (arg, gr) =>
      GraphReference.checkNotEmpty(gr, arg.position)
    }

  def argumentsAsGraphReferences: Seq[Option[GraphReference]] =
    arguments.map(GraphReference.from)
}

final case class GraphRefParameter(parameter: Parameter)(val position: InputPosition) extends GraphReference

final case class Clone(items: List[ReturnItem])
  (val position: InputPosition) extends MultipleGraphClause with SemanticAnalysisTooling {

  override def name: String = "CLONE"

  override def semanticCheck: SemanticCheck =
    super.semanticCheck chain
      items.semanticCheck chain
      declareVariables

  private def declareVariables: SemanticCheck = state => {
    items.foldSemanticCheck {
      case AliasedReturnItem(expression, alias) => declareVariable(alias, state.typeTable(expression).actual)
      case _ => success
    }(state)
  }
}

case class CreateInConstruct(pattern: Pattern)
  (val position: InputPosition) extends MultipleGraphClause with SingleRelTypeCheck {

  override def name = "CREATE"

  override def semanticCheck: SemanticCheck =
    super.semanticCheck chain
      checkNewRelTypes chain
      SemanticPatternCheck.check(Pattern.SemanticContext.Construct, pattern)

  /**
    * Checks if a relationship in new is cloned or newly created.
    * If it is cloned, no type needs to be specified.
    * Otherwise it has to have exactly one type.
    *
    * @return
    */
  private def checkNewRelTypes: SemanticCheck = state => {
    pattern.patternParts.foldLeft(success) {
      case (acc, EveryPath(relChain: RelationshipChain)) => acc chain checkNewRelTypes(relChain, state)
      case (acc, _) => acc
    }(state)
  }

  private def checkNewRelTypes(patternElement: PatternElement, state: SemanticState): SemanticCheck = {
    patternElement match {
      case RelationshipChain(element, rel, _) =>
        checkNewRelTypes(rel, state) chain checkNewRelTypes(element, state)
      case _ => success
    }
  }

  private def checkNewRelTypes(rel: RelationshipPattern, state: SemanticState): SemanticCheck = {
    def isCloned(rel: RelationshipPattern, state: SemanticState): Boolean =
      rel.variable.isDefined && state.symbol(rel.variable.get.name).isDefined

    def isCopy(rel: RelationshipPattern): Boolean =
      rel.baseRel.isDefined

    if (rel.types.isEmpty && (isCloned(rel, state) || isCopy(rel))) success else checkRelTypes(rel)
  }
}

trait SingleRelTypeCheck {
  self: Clause =>

  protected def checkRelTypes(pattern: Pattern): SemanticCheck =
    pattern.patternParts.foldSemanticCheck {
      case EveryPath(element) => checkRelTypes(element)
      case _ => success
    }

  private def checkRelTypes(patternElement: PatternElement): SemanticCheck = {
    patternElement match {
      case RelationshipChain(element, rel, _) =>
        checkRelTypes(rel) chain checkRelTypes(element)
      case _ => success
    }
  }

  protected def checkRelTypes(rel: RelationshipPattern): SemanticCheck = {
    if (rel.types.size != 1) {
      if (rel.types.size > 1) {
        SemanticError(s"A single relationship type must be specified for ${self.name}", rel.position)
      } else {
        SemanticError(s"Exactly one relationship type must be specified for ${self.name}. Did you forget to prefix your relationship type with a ':'?", rel.position)
      }
    } else success
  }
}

final case class ConstructGraph(
  clones: List[Clone] = List.empty,
  news: List[CreateInConstruct] = List.empty,
  on: List[CatalogName] = List.empty,
  sets: List[SetClause] = List.empty
)(val position: InputPosition) extends MultipleGraphClause {

  override def name = "CONSTRUCT"

  override def semanticCheck: SemanticCheck =
    super.semanticCheck chain
      clones.semanticCheck chain
      checkDuplicatedRelationships chain
      checkModificationOfClonedEntities chain
      checkBaseNodes chain
      news.semanticCheck chain
      sets.semanticCheck

  private def checkDuplicatedRelationships: SemanticCheck = state => {
    val relationshipVars = news.flatMap(_.pattern.patternParts).collect {
      case EveryPath(element) => collectRelationshipVars(element)
    }.flatten

    relationshipVars
      .groupBy(_.name)
      .map(pair => pair._2.head -> pair._2.size)
      .filterKeys(v => state.symbol(v.name).isEmpty) // do not consider relationships which are cloned
      .filter(x => x._2 > 1)
      .keySet
      .foldSemanticCheck { v => error(s"Relationship `${v.name}` can only be declared once", v.position) }(state)
  }

  def checkModificationOfClonedEntities: SemanticCheck = {
    news.flatMap(_.pattern.patternParts).foldSemanticCheck {
      case EveryPath(element) => checkModificationOfClonedEntities(element)
      case _ => success
    }
  }

  private def checkModificationOfClonedEntities(element: PatternElement): SemanticCheck = state => {
    element match {
      case NodePattern(Some(v), labels, properties, _) if state.symbol(v.name).isDefined && (labels.nonEmpty || properties.isDefined) =>
        error("Modification of a cloned node is not allowed. Use COPY OF to manipulate the node", element.position)(state)

      case RelationshipChain(e, rel, node) =>
        val checks = checkModificationOfClonedEntities(e) chain checkModificationOfClonedEntities(node) chain (
                                                                                                              if (rel.variable.isDefined && state.symbol(rel.variable.get.name).isDefined && (rel.types.nonEmpty || rel.properties.isDefined)) {
                                                                                                                error("Modification of a cloned relationship is not allowed. Use COPY OF to manipulate the relationship", rel.position)
                                                                                                              } else success
                                                                                                              )
        checks(state)

      case _ => success(state)
    }
  }

  private def checkBaseNodes: SemanticCheck = {
    val nodeToBaseMapping = news.flatMap(_.pattern.patternParts).collect {
      case EveryPath(element) => nodeToBaseNodeMapping(element)
    }.flatten

    nodeToBaseMapping
      .groupBy(_._1.name)
      .map { case (_, values) => values.head._1 -> values.map(_._2) }
      .filter(x => x._2.size > 1)
      .foldSemanticCheck {
        case (v, bases) =>
          error(s"Node ${v.name} cannot inherit from multiple bases ${bases.map(_.name).mkString(", ")}", v.position)
      }
  }

  private def collectRelationshipVars(patternElement: PatternElement): Seq[LogicalVariable] = patternElement match {
    case RelationshipChain(element, rel, _) if rel.variable.isDefined => collectRelationshipVars(element) :+ rel.variable.get
    case RelationshipChain(element, _, _) => collectRelationshipVars(element)
    case _ => Seq.empty
  }

  private def nodeToBaseNodeMapping(patternElement: PatternElement): Seq[(LogicalVariable, LogicalVariable)] = patternElement match {
    case RelationshipChain(element, _, node) => nodeToBaseNodeMapping(element) ++ nodeToBaseNodeMapping(node)
    case NodePattern(Some(v), _, _, Some(base)) => Seq(v -> base)
    case _ => Seq.empty

  }
}

final case class ReturnGraph(graphName: Option[CatalogName])(val position: InputPosition) extends MultipleGraphClause {

  override def name = "RETURN GRAPH"

  override def semanticCheck: SemanticCheck =
    super.semanticCheck
}

case class Start(items: Seq[StartItem], where: Option[Where])(val position: InputPosition) extends Clause {
  override def name = "START"

  override def semanticCheck: SemanticCheck = (state: SemanticState) => {

    val query = rewrittenQuery
    val newState = state.addNotification(DeprecatedStartNotification(position, query))
    SemanticCheckResult(newState, Seq(SemanticError(
      s"""START is deprecated, use: `$query` instead.
       """.stripMargin, position)))
  }

  private def rewrittenQuery: String = {
    val rewritten = items.map {
      case AllNodes(variable) => s"MATCH (${variable.asCanonicalStringVal})"
      case AllRelationships(variable) => s"MATCH ()-[${variable.asCanonicalStringVal}]->()"
      case NodeByIds(variable, ids) =>
        if (ids.size == 1) s"MATCH (${variable.asCanonicalStringVal}) WHERE id(${variable.asCanonicalStringVal}) = ${ids.head.asCanonicalStringVal}"
        else s"MATCH (${variable.asCanonicalStringVal}) WHERE id(${variable.asCanonicalStringVal}) IN ${ids.map(_.asCanonicalStringVal).mkString("[", ", ", "]")}"
      case RelationshipByIds(variable, ids) =>
        if (ids.size == 1) s"MATCH ()-[${variable.asCanonicalStringVal}]->() WHERE id(${variable.asCanonicalStringVal}) = ${ids.head.asCanonicalStringVal}"
        else s"MATCH ()-[${variable.asCanonicalStringVal}]->() WHERE id(${variable.asCanonicalStringVal}) IN ${ids.map(_.asCanonicalStringVal).mkString("[", ", ", "]")}"
      case NodeByParameter(variable, parameter) => s"MATCH (${variable.asCanonicalStringVal}) WHERE id(${variable.asCanonicalStringVal}) IN ${parameter.asCanonicalStringVal}"
      case RelationshipByParameter(variable, parameter) => s"MATCH ()-[${variable.asCanonicalStringVal}]->() WHERE id(${variable.asCanonicalStringVal}) IN ${parameter.asCanonicalStringVal}"
    }

    rewritten.mkString(" ")
  }

}

case class Match(
                  optional: Boolean,
                  pattern: Pattern,
                  hints: Seq[UsingHint],
                  where: Option[Where]
                )(val position: InputPosition) extends Clause with SemanticAnalysisTooling {
  override def name = "MATCH"

  override def semanticCheck: SemanticCheck =
    SemanticPatternCheck.check(Pattern.SemanticContext.Match, pattern) chain
      hints.semanticCheck chain
      uniqueHints chain
      where.semanticCheck chain
      checkHints chain
      checkForCartesianProducts

  private def uniqueHints: SemanticCheck = {
    val errors = hints.groupBy(_.variables.toIndexedSeq).collect {
      case pair@(variables, identHints) if identHints.size > 1 =>
        SemanticError("Multiple hints for same variable are not supported", variables.head.position, identHints.map(_.position): _*)
    }.toVector

    state: SemanticState => semantics.SemanticCheckResult(state, errors)
  }

  private def checkForCartesianProducts: SemanticCheck = (state: SemanticState) => {
    import connectedComponents._
    val cc = connectedComponents(pattern.patternParts)
    //if we have multiple connected components we will have
    //a cartesian product
    val newState = cc.drop(1).foldLeft(state) { (innerState, component) =>
      innerState.addNotification(CartesianProductNotification(position, component.variables.map(_.name)))
    }

    semantics.SemanticCheckResult(newState, Seq.empty)
  }

  private def checkHints: SemanticCheck = {
    val error: Option[SemanticCheck] = hints.collectFirst {
      case hint@UsingIndexHint(Variable(variable), LabelName(labelName), properties, _)
        if !containsLabelPredicate(variable, labelName) =>
        SemanticError(
          """|Cannot use index hint in this context.
             | Must use label on node that hint is referring to.""".stripLinesAndMargins, hint.position)
      case hint@UsingIndexHint(Variable(variable), LabelName(labelName), properties, _)
        if !containsPropertyPredicates(variable, properties) =>
        SemanticError(
          """|Cannot use index hint in this context.
             | Index hints are only supported for the following predicates in WHERE
             | (either directly or as part of a top-level AND or OR):
             | equality comparison, inequality (range) comparison, STARTS WITH,
             | IN condition or checking property existence.
             | The comparison cannot be performed between two property values.
             | Note that the label and property comparison must be specified on a
             | non-optional node""".stripLinesAndMargins, hint.position)
      case hint@UsingScanHint(Variable(variable), LabelName(labelName))
        if !containsLabelPredicate(variable, labelName) =>
        SemanticError(
          """|Cannot use label scan hint in this context.
             | Label scan hints require using a simple label test in WHERE (either directly or as part of a
             | top-level AND). Note that the label must be specified on a non-optional node""".stripLinesAndMargins, hint.position)
      case hint@UsingJoinHint(_)
        if pattern.length == 0 =>
        SemanticError("Cannot use join hint for single node pattern.", hint.position)
    }
    error.getOrElse(success)
  }

  private def containsPropertyPredicates(variable: String, propertiesInHint: Seq[PropertyKeyName]): Boolean = {
    val propertiesInPredicates: Seq[String] = (where match {
      case Some(w) => w.treeFold(Seq.empty[String]) {
        case Equals(Property(Variable(id), PropertyKeyName(name)), other) if id == variable && applicable(other) =>
          acc => (acc :+ name, None)
        case Equals(other, Property(Variable(id), PropertyKeyName(name))) if id == variable && applicable(other) =>
          acc => (acc :+ name, None)
        case In(Property(Variable(id), PropertyKeyName(name)), _) if id == variable =>
          acc => (acc :+ name, None)
        case predicate@FunctionInvocation(_, _, _, IndexedSeq(Property(Variable(id), PropertyKeyName(name))))
          if id == variable && predicate.function == Exists =>
          acc => (acc :+ name, None)
        case IsNotNull(Property(Variable(id), PropertyKeyName(name))) if id == variable =>
          acc => (acc :+ name, None)
        case StartsWith(Property(Variable(id), PropertyKeyName(name)), _) if id == variable =>
          acc => (acc :+ name, None)
        case EndsWith(Property(Variable(id), PropertyKeyName(name)), _) if id == variable =>
          acc => (acc :+ name, None)
        case Contains(Property(Variable(id), PropertyKeyName(name)), _) if id == variable =>
          acc => (acc :+ name, None)
        case expr: InequalityExpression =>
          acc =>
            val newAcc: Seq[String] = Seq(expr.lhs, expr.rhs).foldLeft(acc) { (acc, expr) =>
              expr match {
                case Property(Variable(id), PropertyKeyName(name)) if id == variable =>
                  acc :+ name
                case FunctionInvocation(Namespace(List()), FunctionName(Distance.name), _, Seq(Property(Variable(id), PropertyKeyName(name)), _)) if id == variable =>
                  acc :+ name
                case _ =>
                  acc
              }
            }
            (newAcc, None)
        case _: Where | _: And | _: Ands | _: Set[_] | _: Or | _: Ors =>
          acc => (acc, Some(identity))
        case _ =>
          acc => (acc, None)
      }
      case None => Seq.empty
    }) ++ pattern.treeFold(Seq.empty[String]) {
      case NodePattern(Some(Variable(id)), _, Some(MapExpression(prop)), _) if variable == id =>
        acc => (acc ++ prop.map(_._1.name), None)
    }

    propertiesInHint.forall(p => propertiesInPredicates.contains(p.name))
  }

  /*
   * Checks validity of the other side, X, of expressions such as
   *  USING INDEX ON n:Label(prop) WHERE n.prop = X (or X = n.prop)
   *
   * Returns true if X is a valid expression in this context, otherwise false.
   */
  private def applicable(other: Expression) = {
    other match {
      case f: FunctionInvocation => f.function != functions.Id
      case _ => true
    }
  }

  private def containsLabelPredicate(variable: String, label: String): Boolean = {
    var labels = pattern.fold(Seq.empty[String]) {
      case NodePattern(Some(Variable(id)), nodeLabels, _, _) if variable == id =>
        list => list ++ nodeLabels.map(_.name)
    }
    labels = where match {
      case Some(innerWhere) => innerWhere.treeFold(labels) {
        case HasLabels(Variable(id), predicateLabels) if id == variable =>
          acc => (acc ++ predicateLabels.map(_.name), None)
        case _: Where | _: And | _: Ands | _: Set[_] =>
          acc => (acc, Some(identity))
        case _ =>
          acc => (acc, None)
      }
      case None => labels
    }
    labels.contains(label)
  }
}

case class Merge(pattern: Pattern, actions: Seq[MergeAction], where: Option[Where] = None)(val position: InputPosition)
  extends UpdateClause with SingleRelTypeCheck {

  override def name = "MERGE"

  override def semanticCheck: SemanticCheck =
    SemanticPatternCheck.check(Pattern.SemanticContext.Merge, pattern) chain
      actions.semanticCheck chain
      checkRelTypes(pattern)
}

case class Create(pattern: Pattern)(val position: InputPosition) extends UpdateClause with SingleRelTypeCheck {
  override def name = "CREATE"

  override def semanticCheck: SemanticCheck =
    SemanticPatternCheck.check(Pattern.SemanticContext.Create, pattern) chain
      checkRelTypes(pattern)
}

case class CreateUnique(pattern: Pattern)(val position: InputPosition) extends UpdateClause {
  override def name = "CREATE UNIQUE"

  override def semanticCheck =
    SemanticError("CREATE UNIQUE is no longer supported. Please use MERGE instead", position)

}

case class SetClause(items: Seq[SetItem])(val position: InputPosition) extends UpdateClause {
  override def name = "SET"

  override def semanticCheck: SemanticCheck = items.semanticCheck
}

case class Delete(expressions: Seq[Expression], forced: Boolean)(val position: InputPosition) extends UpdateClause {
  override def name = "DELETE"

  override def semanticCheck: SemanticCheck =
    SemanticExpressionCheck.simple(expressions) chain
      warnAboutDeletingLabels chain
      expectType(CTNode.covariant | CTRelationship.covariant | CTPath.covariant, expressions)

  private def warnAboutDeletingLabels =
    expressions.filter(_.isInstanceOf[HasLabels]) map {
      e => SemanticError("DELETE doesn't support removing labels from a node. Try REMOVE.", e.position)
    }
}

case class Remove(items: Seq[RemoveItem])(val position: InputPosition) extends UpdateClause {
  override def name = "REMOVE"

  override def semanticCheck: SemanticCheck = items.semanticCheck
}

case class Foreach(
                    variable: Variable,
                    expression: Expression,
                    updates: Seq[Clause]
                  )(val position: InputPosition) extends UpdateClause {
  override def name = "FOREACH"

  override def semanticCheck: SemanticCheck =
    SemanticExpressionCheck.simple(expression) chain
      expectType(CTList(CTAny).covariant, expression) chain
      updates.filter(!_.isInstanceOf[UpdateClause]).map(c => SemanticError(s"Invalid use of ${c.name} inside FOREACH", c.position)) ifOkChain
      withScopedState {
        val possibleInnerTypes: TypeGenerator = types(expression)(_).unwrapLists
        declareVariable(variable, possibleInnerTypes) chain updates.semanticCheck
      }
}

case class Unwind(
                   expression: Expression,
                   variable: Variable
                 )(val position: InputPosition) extends Clause with SemanticAnalysisTooling {
  override def name = "UNWIND"

  override def semanticCheck: SemanticCheck =
    SemanticExpressionCheck.check(SemanticContext.Results, expression) chain
      expectType(CTList(CTAny).covariant | CTAny.covariant, expression) ifOkChain {
      val possibleInnerTypes: TypeGenerator = types(expression)(_).unwrapPotentialLists
      declareVariable(variable, possibleInnerTypes)
    }
}

abstract class CallClause extends Clause {
  override def name = "CALL"

  def returnColumns: List[LogicalVariable]

  def containsNoUpdates: Boolean
}

case class UnresolvedCall(procedureNamespace: Namespace,
                          procedureName: ProcedureName,
                          // None: No arguments given
                          declaredArguments: Option[Seq[Expression]] = None,
                          // None: No results declared  (i.e. no "YIELD" part)
                          declaredResult: Option[ProcedureResult] = None
                         )(val position: InputPosition) extends CallClause {

  override def returnColumns: List[LogicalVariable] =
    declaredResult.map(_.items.map(_.variable).toList).getOrElse(List.empty)

  override def semanticCheck: SemanticCheck = {
    val argumentCheck = declaredArguments.map(
      SemanticExpressionCheck.check(SemanticContext.Results, _)).getOrElse(success)
    val resultsCheck = declaredResult.map(_.semanticCheck).getOrElse(success)
    val invalidExpressionsCheck = declaredArguments.map(_.map {
      case arg if arg.containsAggregate =>
        error(_: SemanticState,
          SemanticError(
            """Procedure call cannot take an aggregating function as argument, please add a 'WITH' to your statement.
              |For example:
              |    MATCH (n:Person) WITH collect(n.name) AS names CALL proc(names) YIELD value RETURN value""".stripMargin, position))
      case _ => success
    }.foldLeft(success)(_ chain _)).getOrElse(success)

    argumentCheck chain resultsCheck chain invalidExpressionsCheck
  }

  //At this stage we are not sure whether or not the procedure
  // contains updates, so let's err on the side of caution
  override def containsNoUpdates = false
}

sealed trait HorizonClause extends Clause with SemanticAnalysisTooling {
  def semanticCheckContinuation(previousScope: Scope): SemanticCheck
}

object ProjectionClause {

  def unapply(arg: ProjectionClause): Option[(Boolean, ReturnItemsDef, Option[OrderBy], Option[Skip], Option[Limit], Option[Where])] = {
    arg match {
      case With(distinct, ri, orderBy, skip, limit, where) => Some((distinct, ri, orderBy, skip, limit, where))
      case Return(distinct, ri, orderBy, skip, limit, _) => Some((distinct, ri, orderBy, skip, limit, None))
    }
  }
}

sealed trait ProjectionClause extends HorizonClause {
  def distinct: Boolean

  def returnItems: ReturnItemsDef

  def orderBy: Option[OrderBy]

  def where: Option[Where]

  def skip: Option[Skip]

  def limit: Option[Limit]

  final def isWith: Boolean = !isReturn

  def isReturn: Boolean = false

  def copyProjection(distinct: Boolean = this.distinct,
           returnItems: ReturnItemsDef = this.returnItems,
           orderBy: Option[OrderBy] = this.orderBy,
           skip: Option[Skip] = this.skip,
           limit: Option[Limit] = this.limit,
           where: Option[Where] = this.where): ProjectionClause = {
    this match {
      case w:With => w.copy(distinct, returnItems, orderBy, skip, limit, where)(this.position)
      case r:Return => r.copy(distinct, returnItems, orderBy, skip, limit, r.excludedNames)(this.position)
    }
  }

  /**
    * @return copy of this ProjectionClause with new return items
    */
  def withReturnItems(items: Seq[ReturnItem]): ProjectionClause

  override def semanticCheck: SemanticCheck =
    returnItems.semanticCheck

  override def semanticCheckContinuation(previousScope: Scope): SemanticCheck = {
    val declareAllTheThings = (s: SemanticState) => {

      // ORDER BY and WHERE after a WITH have a special scope such that they can see both variables from before the WITH and from after
      // Since the current scoping system does not allow that in a nice way, we run the semantic check for these two twice
      // In the first run we construct a scope with all necessary variables defined to find errors in these subclauses
      val specialReturnItems = createSpecialReturnItems(previousScope, s)
      val specialCheckForOrderByAndWhere =
        specialReturnItems.declareVariables(previousScope) chain
          orderBy.semanticCheck chain
          checkSkip chain
          checkLimit chain
          where.semanticCheck

      val orderByAndWhereResult = specialCheckForOrderByAndWhere(s)

      val orderByAndWhereErrorMapper = warnOnAccessToRetrictedVariableInOrderByOrWhere(previousScope.symbolNames) _
      val orderByAndWhereErrors = orderByAndWhereResult.errors.map(orderByAndWhereErrorMapper)

      // In the second run we want to make sure to declare the return items, and register the use of variables in the ORDER BY and WHERE.
      // This will be executed with the scope which as valid after the WITH. Therefore this second check can lead to false errors, which we ignore.
      val actualResult = (returnItems.declareVariables(previousScope) chain ignoreErrors(orderBy.semanticCheck chain where.semanticCheck)) (s)

      // We fix symbol positions by merging with the return items from the extended scope
      val fixedOrderByResult = specialReturnItems match {
        case ReturnItems(star, items) if star =>
          val orderByAndWhereScope = orderByAndWhereResult.state.currentScope.scope
          val definedHere = items.map(_.name).toSet
          actualResult.copy(actualResult.state.mergeSymbolPositionsFromScope(orderByAndWhereScope, definedHere))
        case _ =>
          actualResult
      }
      // Finally we need to combine:
      // The orderByAndWhereResult has the correct type table since it was allowed to see stuff from the previous scope.
      // The fixedOrderByResult has the correct scope.
      // We keep errors from both results.
      fixedOrderByResult.copy(state = fixedOrderByResult.state.copy(typeTable = orderByAndWhereResult.state.typeTable),
                              errors = fixedOrderByResult.errors ++ orderByAndWhereErrors)
    }
    declareAllTheThings
  }

  /**
    * If you access a previously defined variable in a WITH/RETURN with DISTINCT or aggregation, that is not OK. Example:
    * MATCH (a) RETURN sum(a.age) ORDER BY a.name
    *
    * This method takes the "Variable not defined" errors we get from the semantic analysis and provides a more helpful
    * error message
    * @param previousScopeVars all variables defined in the previous scope.
    * @param error the error
    * @return an error with a possibly better error message
    */
  private def warnOnAccessToRetrictedVariableInOrderByOrWhere(previousScopeVars: Set[String])(error: SemanticErrorDef): SemanticErrorDef = {
    previousScopeVars.collectFirst {
      case name if error.msg.equals(s"Variable `$name` not defined") => error.withMsg(
        s"In a WITH/RETURN with DISTINCT or an aggregation, it is not possible to access variables declared before the WITH/RETURN: $name")
    }.getOrElse(error)
  }

  private def createSpecialReturnItems(previousScope: Scope, s: SemanticState): ReturnItemsDef = {
    // ORDER BY lives in this special scope that has access to things in scope before the RETURN/WITH clause,
    // but also to the variables introduced by RETURN/WITH. This is most easily done by turning
    // RETURN a, b, c => RETURN *, a, b, c

    // Except when we are doing DISTINCT or aggregation, in which case we only see the scope introduced by the
    // projecting clause
    val includePreviousScope = !(returnItems.containsAggregate || distinct)
    val specialReturnItems = returnItems.withExisting(includePreviousScope)
    specialReturnItems
  }

  // use an empty state when checking skip & limit, as these have entirely isolated context
  private def checkSkip: SemanticState => Seq[SemanticErrorDef] =
    _ => skip.semanticCheck(SemanticState.clean).errors

  private def checkLimit: SemanticState => Seq[SemanticErrorDef] =
    _ => limit.semanticCheck(SemanticState.clean).errors

  private def ignoreErrors(inner: SemanticCheck): SemanticCheck =
    s => {
      // Make sure not to declare variables just to suppress errors since they are ignored anyways
      val innerState = s.copy(declareVariablesToSuppressDuplicateErrors = false)
      val innerResultState = inner.apply(innerState).state
      // Switch back to previous declaration behavior
      success(innerResultState.copy(declareVariablesToSuppressDuplicateErrors = s.declareVariablesToSuppressDuplicateErrors))
    }

  def verifyOrderByAggregationUse(fail: (String, InputPosition) => Nothing): Unit = {
    val aggregationInProjection = returnItems.containsAggregate
    val aggregationInOrderBy = orderBy.exists(_.sortItems.map(_.expression).exists(containsAggregate))
    if (!aggregationInProjection && aggregationInOrderBy)
      fail(s"Cannot use aggregation in ORDER BY if there are no aggregate expressions in the preceding $name", position)
  }
}

object With {
  def apply(returnItems: ReturnItemsDef)(pos: InputPosition): With =
    With(distinct = false, returnItems, None, None, None, None)(pos)
}

case class With(distinct: Boolean,
                returnItems: ReturnItemsDef,
                orderBy: Option[OrderBy],
                skip: Option[Skip],
                limit: Option[Limit],
                where: Option[Where])(val position: InputPosition) extends ProjectionClause {

  override def name = "WITH"

  override def semanticCheck: SemanticCheck =
    super.semanticCheck chain
      checkAliasedReturnItems chain
      SemanticPatternCheck.checkValidPropertyKeyNamesInReturnItems(returnItems, this.position)

  override def withReturnItems(items: Seq[ReturnItem]): With =
    this.copy(returnItems = ReturnItems(returnItems.includeExisting, items)(returnItems.position))(this.position)

  private def checkAliasedReturnItems: SemanticState => Seq[SemanticError] = state => returnItems match {
    case li: ReturnItems => li.items.filter(_.alias.isEmpty).map(i => SemanticError("Expression in WITH must be aliased (use AS)", i.position))
    case _ => Seq()
  }
}

object Return {
  def apply(returnItems: ReturnItemsDef)(pos: InputPosition): Return =
    Return(distinct = false, returnItems, None, None, None)(pos)
}

case class Return(distinct: Boolean,
                  returnItems: ReturnItemsDef,
                  orderBy: Option[OrderBy],
                  skip: Option[Skip],
                  limit: Option[Limit],
                  excludedNames: Set[String] = Set.empty)(val position: InputPosition) extends ProjectionClause {

  override def name = "RETURN"

  override def isReturn: Boolean = true

  override def where: Option[Where] = None

  override def returnColumns: List[LogicalVariable] = returnItems.items.flatMap(_.alias).toList

  override def semanticCheck: SemanticCheck =
    super.semanticCheck chain
      checkVariableScope chain
      SemanticPatternCheck.checkValidPropertyKeyNamesInReturnItems(returnItems, this.position)

  override def withReturnItems(items: Seq[ReturnItem]): Return =
    this.copy(returnItems = ReturnItems(returnItems.includeExisting, items)(returnItems.position))(this.position)

  def withReturnItems(returnItems: ReturnItemsDef): Return =
    this.copy(returnItems = returnItems)(this.position)


  private def checkVariableScope: SemanticState => Seq[SemanticError] = s =>
    returnItems match {
      case ReturnItems(star, _) if star && s.currentScope.isEmpty =>
        Seq(SemanticError("RETURN * is not allowed when there are no variables in scope", position))
      case _ =>
        Seq.empty
    }
}

case class SubQuery(part: QueryPart)(val position: InputPosition) extends HorizonClause with SemanticAnalysisTooling {

  override def name: String = "CALL"

  override def semanticCheck: SemanticCheck =
      checkSubquery

  def checkSubquery: SemanticCheck = { outer: SemanticState =>
    // Create empty scope under root
    val empty: SemanticState = outer.newBaseScope
    // Check inner query. Allow it to import from outer scope
    val inner: SemanticCheckResult = part.semanticCheckInSubqueryContext(outer)(empty)
    val output: Scope = part.finalScope(inner.state.currentScope.scope)

    // Keep working from the latest state
    val after: SemanticState = inner.state
      // but jump back to scope tree of outer
      .copy(currentScope = outer.currentScope)
      // Copy in the scope tree from inner query (needed for Namespacer)
      .insertSiblingScope(inner.state.currentScope.scope)
      // Import variables from scope before subquery
      .newSiblingScope
      .importValuesFromScope(outer.currentScope.scope)

    // Declare variables that are in output from subquery
    val merged: SemanticCheckResult =
      declareVariables(output.valueSymbolTable.values)(after)

    // Keep errors from inner check and from variable declarations
    SemanticCheckResult(merged.state, inner.errors ++ merged.errors)
  }

  override def semanticCheckContinuation(previousScope: Scope): SemanticCheck = { s =>
    SemanticCheckResult(s.importValuesFromScope(previousScope), Vector())
  }
}
