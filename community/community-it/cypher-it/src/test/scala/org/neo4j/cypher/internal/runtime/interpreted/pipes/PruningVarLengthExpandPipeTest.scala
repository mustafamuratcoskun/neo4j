/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import org.neo4j.cypher.GraphDatabaseFunSuite
import org.neo4j.cypher.internal.runtime.interpreted.ExecutionContextHelper._
import org.neo4j.cypher.internal.runtime.interpreted.QueryStateHelper.withQueryState
import org.neo4j.cypher.internal.runtime.interpreted.ValueComparisonHelper.beEquivalentTo
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{Literal, Property, Variable}
import org.neo4j.cypher.internal.runtime.interpreted.commands.predicates.{Equals, Predicate, True}
import org.neo4j.cypher.internal.runtime.interpreted.commands.values.UnresolvedProperty
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PruningVarLengthExpandPipeTest.createVarLengthPredicate
import org.neo4j.cypher.internal.runtime.{ExecutionContext, MapExecutionContext}
import org.neo4j.cypher.internal.v4_0.expressions.SemanticDirection
import org.neo4j.graphdb.Node
import org.neo4j.internal.kernel.api.security.LoginContext
import org.neo4j.kernel.api.KernelTransaction.Type
import org.neo4j.kernel.impl.query.{QuerySubscriber, QuerySubscriberAdapter}
import org.neo4j.kernel.impl.util.ValueUtils._
import org.neo4j.values.AnyValue
import org.neo4j.values.virtual.{NodeValue, RelationshipValue}

import scala.collection.immutable.IndexedSeq
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class PruningVarLengthExpandPipeTest extends GraphDatabaseFunSuite {
  val types = RelationshipTypes(Array.empty[String])

  test("node without any relationships produces empty result") {
    val n1 = createNode()
    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val pipeUnderTest = createPipe(src, 1, 2, SemanticDirection.OUTGOING)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState) shouldBe empty
      })
    }
  }

  test("node with a single relationships produces a single output node") {
    val n1 = createNode()
    val n2 = createNode()
    relate(n1, n2)
    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val pipeUnderTest = createPipe(src, 1, 2, SemanticDirection.OUTGOING)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        val toList: Seq[ExecutionContext] = pipeUnderTest.createResults(queryState).toList
        toList should beEquivalentTo(List(Map("from" -> n1, "to" -> n2)))
      })
    }
  }

  test("node with a single relationships produces a two output nodes if minLength is zero") {
    val n1 = createNode()
    val n2 = createNode()
    relate(n1, n2)
    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val pipeUnderTest = createPipe(src, 0, 2, SemanticDirection.OUTGOING)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList should beEquivalentTo(List(
          Map("from" -> n1, "to" -> n2),
          Map("from" -> n1, "to" -> n1)
        ))
      })
    }
  }

  test("long path, take the middle of it") {

    val nodes = (0 to 10) map (_ => createNode())

    nodes.tail.foldLeft(nodes.head) {
      case (x: Node, y: Node) =>
        relate(x, y)
        y
    }

    val src = new FakePipe(Iterator(Map("from" -> nodes.head)))
    val min = 3
    val max = 5
    val pipeUnderTest = createPipe(src, min, max, SemanticDirection.OUTGOING)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).map(_.getByName("to")).toSet should equal(
          nodes.slice(min, max + 1).map(fromNodeEntity).toSet // Slice is excluding the end, whereas ()-[*3..5]->() is including
        )
      })
    }
  }

  test("self-loop in path is OK") {
    /*
    The only path in the graph starting from n1 that is two relationships long includes a self loop.
    (n1)-[0]->(n1)-[1]->(n2) or (n1)-[1]->(n2)-[1]->(n1)
     */
    val n1 = createNode()
    val n2 = createNode()
    relate(n1, n2)
    relate(n1, n1)

    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val pipeUnderTest = createPipe(src, 2, 2, SemanticDirection.BOTH)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList should beEquivalentTo(
          List(
            Map("from" -> n1, "to" -> n2)
          )
        )
      })
    }
  }

  test("fixed length with shortcut") {
    /*
    n1 - ---- - n2 - n3 - n4
       - n1_2 -

    Even though n1-n2 is traversed first, the length 4 path over n1_2 should be found
     */
    val n1 = createNode()
    val n2 = createNode()
    val n3 = createNode()
    val n4 = createNode()
    val n1_2 = createNode()

    relate(n1, n1_2)
    relate(n1_2, n2)

    relate(n1, n2)
    relate(n2, n3)
    relate(n3, n4)

    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val pipeUnderTest = createPipe(src, 4, 4, SemanticDirection.BOTH)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList should beEquivalentTo(
          List(
            Map("from" -> fromNodeEntity(n1), "to" -> fromNodeEntity(n4))
          )
        )
      })
    }
  }

  test("fixed length with longer shortcut") {
    /*
    n1 - ----- - ----- - n2 - n3 - n4
       - n1_2a - n1_2b -

    Even though n1-n2 is traversed first, the length 4 path over n1_2 should be found
     */
    val n1 = createNode()
    val n2 = createNode()
    val n3 = createNode()
    val n4 = createNode()
    val n1_2a = createNode()
    val n1_2b = createNode()

    relate(n1, n1_2a)
    relate(n1_2a, n1_2b)
    relate(n1_2b, n2)

    relate(n1, n2)
    relate(n2, n3)
    relate(n3, n4)

    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val pipeUnderTest = createPipe(src, 5, 5, SemanticDirection.BOTH)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList should beEquivalentTo(
          List(
            Map("from" -> fromNodeEntity(n1), "to" -> fromNodeEntity(n4))
        ))
      })
    }
  }

  test("var-length with relationship predicate") {
    /*
    (n1)-[0 {k:1}]->(n2)-[1]->(n3)
    MATCH (n1)-[r*1..2 {k:1}]-(n) RETURN DISTINCT n
     */

    val n1 = createNode()
    val n2 = createNode()
    val n3 = createNode()
    relate(n1, n2, "k" -> 1)
    relate(n2, n3, "k" -> 2)

    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val predicate = Equals(Property(Variable("r"), UnresolvedProperty("k")), Literal(1))
    val pipeUnderTest = createPipe(src, 1, 2, SemanticDirection.BOTH, predicate, True())

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList should beEquivalentTo(
          List(
            Map("from" -> n1, "to" -> n2)
        ))
      })
    }
  }

  test("var-length with node predicate") {
    /*
    (n1)-[0]->(n2 {k:1})-[1]->(n3)
    MATCH p = (n1)-[r*1..2]-(n) WHERE all(n IN nodes(p) | n.k=1) RETURN DISTINCT n
     */

    val n1 = createNode()
    val n2 = createNode("k" -> 1)
    val n3 = createNode()
    relate(n1, n2)
    relate(n2, n3)

    val src = new FakePipe(Iterator(Map("from" -> n1)))
    val predicate = Equals(Property(Variable("to"), UnresolvedProperty("k")), Literal(1))
    val pipeUnderTest = createPipe(src, 1, 2, SemanticDirection.BOTH, True(), predicate)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList should beEquivalentTo(
          List(
            Map("from" -> n1, "to" -> n2)
          )
        )
      })
    }
  }

  test("two ways to get to the same node - one inside and one outside the max") {
    /*
    (x)-[1]->()-[2]->()-[3]->()-[4]->(y)-[4]->(z)
      \                              ^
       \----------------------------/

     */
    val nodes = (0 to 5) map (_ => createNode())

    nodes.tail.foldLeft(nodes.head) {
      case (x: Node, y: Node) =>
        relate(x, y)
        y
    }

    val n1 = nodes.head
    val n4 = nodes(4)
    val n5 = nodes(5)
    relate(n1, n4)

    val src = new FakePipe(Iterator(Map("from" -> nodes.head)))
    val pipeUnderTest = createPipe(src, 1, 4, SemanticDirection.OUTGOING)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).map(_.toMap).toSet should equal(nodes.tail.map(n => Map("from" -> fromNodeEntity(n1), "to" -> fromNodeEntity(n))).toSet)
      })
    }
  }

  test("multiple start nodes") {
    val nodes = (0 to 10) map (_ => createNode())
    val nodeValues = nodes.map(fromNodeEntity)
    nodes.tail.foldLeft(nodes.head) {
      case (x: Node, y: Node) =>
        relate(x, y)
        y
    }

    val src = new FakePipe(Iterator(Map("from" -> nodes(1)), Map("from" -> nodes(5))))
    val pipeUnderTest = createPipe(src, 1, 4, SemanticDirection.OUTGOING)

    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).map(_.toMap).toSet should equal(Set(
          Map("from" -> nodeValues(1), "to" -> nodeValues(2)),
          Map("from" -> nodeValues(1), "to" -> nodeValues(3)),
          Map("from" -> nodeValues(1), "to" -> nodeValues(4)),
          Map("from" -> nodeValues(1), "to" -> nodeValues(5)),
          Map("from" -> nodeValues(5), "to" -> nodeValues(6)),
          Map("from" -> nodeValues(5), "to" -> nodeValues(7)),
          Map("from" -> nodeValues(5), "to" -> nodeValues(8)),
          Map("from" -> nodeValues(5), "to" -> nodeValues(9))
        ))
      })
    }
  }

  test("should register owning pipe") {
    val src = new FakePipe(Iterator.empty)
    val pred1 = True()
    val pred2 = True()
    val pipeUnderTest = createPipe(src, 1, 2, SemanticDirection.OUTGOING, pred1, pred2)

    pipeUnderTest.filteringStep.predicateExpressions.foreach(_.owningPipe should equal(pipeUnderTest))
    pred1.owningPipe should equal(pipeUnderTest)
    pred2.owningPipe should equal(pipeUnderTest)
  }

  private def setUpGraph(seed: Long, POPULATION: Int, friendCount: Int = 50): IndexedSeq[Node] = {
    val r = new Random(seed)

    var tx = graph.beginTransaction(Type.`implicit`, LoginContext.AUTH_DISABLED)
    var count = 0

    def checkAndSwitch(): Unit = {
      count += 1
      if (count == 1000) {
        tx.commit()
        tx = graph.beginTransaction(Type.`implicit`, LoginContext.AUTH_DISABLED)
        count = 0
      }
    }

    val nodes = (0 to POPULATION) map { _ =>
      checkAndSwitch()
      tx.createNode()
    }

    for {
      n1 <- nodes
      friends = r.nextInt(friendCount)
      friendIdx <- 0 to friends
      n2 = nodes(r.nextInt(POPULATION))
    } {
      checkAndSwitch()
      tx.getNodeById(n1.getId).createRelationshipTo(n2, REL)
    }

    tx.commit()

    nodes
  }

  private def testNode(startNode: Node, r: Random): Unit = {
    val min = r.nextInt(3)
    val max = min + 1 + r.nextInt(3)
    val sourcePipe = new FakePipe(Iterator(Map("from" -> startNode)))
    val sourcePipe2 = new FakePipe(Iterator(Map("from" -> startNode)))
    val pipeUnderTest = createPipe(sourcePipe, min, max, SemanticDirection.BOTH)
    val pipe = VarLengthExpandPipe(sourcePipe2, "from", "r", "to", SemanticDirection.BOTH, SemanticDirection.BOTH, types, min, Some(max), nodeInScope = false)()
    val columns = Array("from", "to")
    val comparison = ProduceResultsPipe(DistinctPipe(pipe, Array(DistinctPipe.GroupingCol("from", Variable("from")), DistinctPipe.GroupingCol("to", Variable("to"))))(), columns)()

    val distinctExpand = graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        pipeUnderTest.createResults(queryState).toList
      })
    }

    val records = ArrayBuffer.empty[ExecutionContext]
    val subscriber: QuerySubscriber = new QuerySubscriberAdapter {
      private var record: mutable.Map[String, AnyValue] = mutable.Map.empty
      private var currentOffset = -1

      override def onRecord(): Unit = currentOffset = 0

      override def onField(value: AnyValue): Unit = {
        try {
          record.put(columns(currentOffset), value)
        } finally {
          currentOffset += 1
        }
      }
      override def onRecordCompleted(): Unit = {
        currentOffset = -1
        records.append(new MapExecutionContext(record))
        record =  mutable.Map.empty
      }
    }
    graph.withTx { tx =>
      withQueryState(graph, tx, Array.empty, { queryState =>
        comparison.createResults(queryState).toList
      }, subscriber)
    }

    val oldThing = records.toSet
    val newThing = distinctExpand.toSet
    if (oldThing != newThing) {
      val missingFromNew = oldThing -- newThing
      val shouldNotBeInNew = newThing -- oldThing
      var message =
        s"""startNode: $startNode
            |size of old result ${oldThing.size}
            |size of new result ${newThing.size}
            |min $min
            |max $max
            |""".stripMargin
      if(missingFromNew.nonEmpty) {
        message += s"missing from new result: ${missingFromNew.mkString(",")}\n"
      }
      if(shouldNotBeInNew.nonEmpty) {
        message += s"should not be in new result: ${shouldNotBeInNew.mkString(",")}\n"
      }

      fail(message)
    }
  }

  test("random and compare") {
    // runs DistinctVarExpand and VarExpand side-by-side and checks that the reachable nodes are the same
    val POPULATION: Int = 1 * 1000
    val seed = System.currentTimeMillis()
    val nodes = setUpGraph(seed, POPULATION, 20)
    val r = new Random

    val start = System.currentTimeMillis()

    while(System.currentTimeMillis() - start < 10000) {
      withClue("seed used: " + seed) {
        testNode(nodes(r.nextInt(POPULATION)), r)
      }
    }
  }

  private def createPipe(src: FakePipe, min: Int, max: Int, outgoing: SemanticDirection) = {
    PruningVarLengthExpandPipe(src, "from", "to", types, outgoing, min, max)()
  }

  private def createPipe(src: FakePipe,
                         min: Int,
                         max: Int,
                         outgoing: SemanticDirection,
                         relationshipPredicate: Predicate,
                         nodePredicate: Predicate) = {
    val filteringStep = createVarLengthPredicate(nodePredicate, relationshipPredicate)
    PruningVarLengthExpandPipe(src, "from", "to", types, outgoing, min, max, filteringStep)()
  }
}

object PruningVarLengthExpandPipeTest {
  def createVarLengthPredicate(nodePredicate: Predicate, relationshipPredicate: Predicate): VarLengthPredicate = {
    new VarLengthPredicate {
      override def filterNode(row: ExecutionContext, state: QueryState)(node: NodeValue): Boolean = {
        val cp = row.copyWith("to", node)
        val result = nodePredicate.isTrue(cp, state)
        result
      }

      override def filterRelationship(row: ExecutionContext, state: QueryState)(rel: RelationshipValue): Boolean = {
        val cp = row.copyWith("r", rel)
        val result = relationshipPredicate.isTrue(cp, state)
        result
      }

      override def predicateExpressions: Seq[Predicate] = Seq(nodePredicate, relationshipPredicate)
    }
  }
}
