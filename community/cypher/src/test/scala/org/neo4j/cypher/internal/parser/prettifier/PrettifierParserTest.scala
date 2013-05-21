/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.cypher.internal.parser.prettifier

import org.neo4j.cypher.internal.parser.ParserTest
import org.junit.Test

class PrettifierParserTest extends PrettifierParser with ParserTest {

  implicit val parserToTest = query

  @Test
  def shouldParseKeywords() {
    // given
    val keyword = "create"

    // when then
    parsing[Seq[SyntaxToken]](keyword)(parserToTest) shouldGive
      Seq(ReservedKeywords(keyword))
  }

  @Test
  def shouldParseIndexAsKeyword() {
    // given
    val keyword = "index"

    // when then
    parsing[Seq[SyntaxToken]](keyword)(parserToTest) shouldGive
      Seq(ExtraKeywords(keyword))
  }

  @Test
  def shouldParseAnyText() {
    // given
    val input = "a-->b"

    // when then
    parsing[Seq[SyntaxToken]](input)(parserToTest) shouldGive
      Seq(AnyText(input))
  }

  @Test
  def shouldParseEscapedText() {
    // given
    val input = "aha!"

    // when then
    parsing[Seq[SyntaxToken]]("\"" + input + "\"")(parserToTest) shouldGive
      Seq(EscapedText(input))
  }

  @Test
  def shouldParseGroupingText() {
    // given
    val input = "(}{)[]"

    // when then
    parsing[Seq[SyntaxToken]](input)(parserToTest) shouldGive
      Seq(OpenGroup("("),
          CloseGroup("}"),
          OpenGroup("{"),
          CloseGroup(")"),
          OpenGroup("["),
          CloseGroup("]"))
  }

  @Test
  def shouldParseComplexExample1() {
    // given
    val input = "match a-->b where b.name = \"aha!\" return a.age"

    // when then
    parsing[Seq[SyntaxToken]](input)(parserToTest) shouldGive
      Seq(ReservedKeywords("match"), AnyText("a-->b"), ReservedKeywords("where"), AnyText("b.name"), AnyText("="),
          EscapedText("aha!"), ReservedKeywords("return"), AnyText("a.age"))
  }

  @Test
  def shouldParseComplexExample2() {
    // given
    val input = "merge n on create set n.age=32"

    // when then
    parsing[Seq[SyntaxToken]](input)(parserToTest) shouldGive
      Seq(ReservedKeywords("merge"), AnyText("n"), ReservedKeywords("on create set"), AnyText("n.age=32"))
  }
}