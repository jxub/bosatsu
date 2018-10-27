package org.bykn.bosatsu

import cats.data.NonEmptyList
import Parser.Combinators
import fastparse.all._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.FunSuite
import org.scalatest.prop.PropertyChecks.{ forAll, PropertyCheckConfiguration }
import org.typelevel.paiges.{Doc, Document}

import Parser.Indy

import Generators.shrinkDecl

class ParserTest extends FunSuite {
  // This is so we can make Declarations without the region
  private[this] implicit val emptyRegion: Region = Region(0, 0)

  implicit val generatorDrivenConfig =
    //PropertyCheckConfiguration(minSuccessful = 500)
    PropertyCheckConfiguration(minSuccessful = 50)
    //PropertyCheckConfiguration(minSuccessful = 5)

  def region(s0: String, idx: Int): String =
    if (s0.isEmpty) s"empty string, idx = $idx"
    else if (s0.length == idx) {
      val s = s0 + "*"
      ("...(" + s.drop(idx - 20).take(20) + ")...")
    }
    else {
      val s = s0.updated(idx, '*')
      ("...(" + s.drop(idx - 20).take(20) + ")...")
    }

  def firstDiff(s1: String, s2: String): String =
    if (s1 == s2) ""
    else if (s1.isEmpty) s2
    else if (s2.isEmpty) s1
    else if (s1(0) == s2(0)) firstDiff(s1.tail, s2.tail)
    else s"${s1(0).toInt}: ${s1.take(20)}... != ${s2(0).toInt}: ${s2.take(20)}..."

  def parseTest[T](p: Parser[T], str: String, expected: T, exidx: Int) =
    p.parse(str) match {
      case Parsed.Success(t, idx) =>
        assert(t == expected)
        assert(idx == exidx)
      case Parsed.Failure(exp, idx, extra) =>
        fail(s"failed to parse: $str: $exp at $idx in region ${region(str, idx)} with trace: ${extra.traced.trace}")
    }

  def parseTestAll[T](p: Parser[T], str: String, expected: T) =
    parseTest(p, str, expected, str.length)

  def roundTrip[T: Document](p: Parser[T], str: String, lax: Boolean = false) =
    p.parse(str) match {
      case Parsed.Success(t, idx) =>
        if (!lax) {
          assert(idx == str.length, s"parsed: $t from: $str")
        }
        val tstr = Document[T].document(t).render(80)
        p.parse(tstr) match {
          case Parsed.Success(t1, _) =>
            assert(t1 == t)
          case Parsed.Failure(exp, idx, extra) =>
            val diff = firstDiff(str, tstr)
            fail(s"Diff: $diff.\nfailed to reparse: $tstr: $exp at $idx in region ${region(tstr, idx)} with trace: ${extra.traced.trace}")
        }
      case Parsed.Failure(exp, idx, extra) =>
        fail(s"failed to parse: $str: $exp at $idx in region ${region(str, idx)} with trace: ${extra.traced.trace}")
    }

  def law[T: Document](p: Parser[T])(t: T) =
    parseTestAll(p, Document[T].document(t).render(80), t)

  def expectFail[T](p: Parser[T], str: String, atIdx: Int) =
    p.parse(str) match {
      case Parsed.Success(t, idx) => fail(s"parsed $t to: $idx")
      case Parsed.Failure(_, idx, _) =>
        assert(idx == atIdx)
    }

  test("we can parse integers") {
    forAll { b: BigInt =>
      parseTestAll(Parser.integerString, b.toString, b.toString)
    }
  }

  test("we can parse quoted strings") {
    val qstr = for {
      qchar <- Gen.oneOf('\'', '"')
      qstr = qchar.toString
      str <- Arbitrary.arbitrary[String].filter { s => !s.contains('\u0000') }
    } yield (qstr + Parser.escape(Set(qchar), str) + qstr, str, qchar)

    forAll(qstr) { case (quoted, str, char) =>
      parseTestAll(Parser.escapedString(char), quoted, str)
    }

    parseTestAll(Parser.escapedString('\''), "''", "")
    parseTestAll(Parser.escapedString('"'), "\"\"", "")
    parseTestAll(Parser.escapedString('\''), "'foo\tbar'", "foo\tbar")
  }

  test("we can parse lists") {
    forAll { (ls: List[Long], spaceCnt0: Int) =>
      val spaceCount = spaceCnt0 & 7
      val str0 = ls.toString
      val str = str0.flatMap {
        case ',' => "," + (" " * spaceCount)
        case c => c.toString
      }
      parseTestAll(Parser.integerString.list.wrappedSpace("List(", ")"),
        str,
        ls.map(_.toString))
    }
  }

  test("we can parse blocks") {
    val indy = Indy.block(Indy.lift(P("if foo")), Indy.lift(P("bar")))
    val p = indy.run("")
    parseTestAll(p, "if foo: bar", ((), OptIndent.same(())))
    parseTestAll(p, "if foo:\n\tbar", ((), OptIndent.paddedIndented(1, 4, ())))
    parseTestAll(p, "if foo:\n    bar", ((), OptIndent.paddedIndented(1, 4, ())))
    parseTestAll(p, "if foo:\n  bar", ((), OptIndent.paddedIndented(1, 2, ())))

    import Indy.IndyMethods
    val repeated = indy.nonEmptyList(Indy.lift(Parser.toEOL))

    val single = ((), OptIndent.notSame(Padding(1, Indented(2, ()))))
    parseTestAll(repeated.run(""), "if foo:\n  bar\nif foo:\n  bar",
      NonEmptyList.of(single, single))

    // we can nest blocks
    parseTestAll(Indy.block(Indy.lift(P("nest")), indy)(""), "nest: if foo: bar",
      ((), OptIndent.same(((), OptIndent.same(())))))
    parseTestAll(Indy.block(Indy.lift(P("nest")), indy)(""), "nest:\n  if foo: bar",
      ((), OptIndent.paddedIndented(1, 2, ((), OptIndent.same(())))))
    parseTestAll(Indy.block(Indy.lift(P("nest")), indy)(""), "nest:\n  if foo:\n    bar",
      ((), OptIndent.paddedIndented(1, 2, ((), OptIndent.paddedIndented(1, 2, ())))))

    val simpleBlock = Indy.block(Indy.lift(Parser.lowerIdent ~ Parser.maybeSpace), Indy.lift(Parser.lowerIdent))
      .nonEmptyList(Indy.toEOLIndent)

    val sbRes = NonEmptyList.of(("x1", OptIndent.paddedIndented(1, 2, "x2")),
        ("y1", OptIndent.paddedIndented(1, 3, "y2")))
    parseTestAll(simpleBlock(""), "x1:\n  x2\ny1:\n   y2", sbRes)

    parseTestAll(Indy.block(Indy.lift(Parser.lowerIdent), simpleBlock)(""),
      "block:\n  x1:\n    x2\n  y1:\n     y2",
      ("block", OptIndent.paddedIndented(1, 2, sbRes)))
  }

  test("we can parse TypeRefs") {
    parseTestAll(TypeRef.parser, "foo", TypeRef.TypeVar("foo"))
    parseTestAll(TypeRef.parser, "Foo", TypeRef.TypeName("Foo"))

    parseTestAll(TypeRef.parser, "forall a. a", TypeRef.TypeLambda(NonEmptyList.of(TypeRef.TypeVar("a")), TypeRef.TypeVar("a")))
    parseTestAll(TypeRef.parser, "forall a, b. f[a] -> f[b]",
      TypeRef.TypeLambda(NonEmptyList.of(TypeRef.TypeVar("a"), TypeRef.TypeVar("b")),
        TypeRef.TypeArrow(
          TypeRef.TypeApply(TypeRef.TypeVar("f"), NonEmptyList.of(TypeRef.TypeVar("a"))),
          TypeRef.TypeApply(TypeRef.TypeVar("f"), NonEmptyList.of(TypeRef.TypeVar("b"))))))
    roundTrip(TypeRef.parser, "forall a, b. f[a] -> f[b]")
    roundTrip(TypeRef.parser, "(forall a, b. f[a]) -> f[b]")
    roundTrip(TypeRef.parser, "(forall a, b. f[a])[Int]") // apply a type

    parseTestAll(TypeRef.parser, "Foo -> Bar", TypeRef.TypeArrow(TypeRef.TypeName("Foo"), TypeRef.TypeName("Bar")))
    parseTestAll(TypeRef.parser, "Foo -> Bar -> baz",
      TypeRef.TypeArrow(TypeRef.TypeName("Foo"), TypeRef.TypeArrow(TypeRef.TypeName("Bar"), TypeRef.TypeVar("baz"))))
    parseTestAll(TypeRef.parser, "(Foo -> Bar) -> baz",
      TypeRef.TypeArrow(TypeRef.TypeArrow(TypeRef.TypeName("Foo"), TypeRef.TypeName("Bar")), TypeRef.TypeVar("baz")))
    parseTestAll(TypeRef.parser, "Foo[Bar]", TypeRef.TypeApply(TypeRef.TypeName("Foo"), NonEmptyList.of(TypeRef.TypeName("Bar"))))

    forAll(Generators.typeRefGen) { tref =>
      parseTestAll(TypeRef.parser, tref.toDoc.render(80), tref)
    }
  }

  test("we can parse python style list expressions") {
    val pident = Parser.lowerIdent
    implicit val stringDoc: Document[String] = Document.instance[String](Doc.text(_))

    roundTrip(ListLang.parser(pident), "[a]")
    roundTrip(ListLang.parser(pident), "[]")
    roundTrip(ListLang.parser(pident), "[a , b]")
    roundTrip(ListLang.parser(pident), "[a , b]")
    roundTrip(ListLang.parser(pident), "[a , *b]")
    roundTrip(ListLang.parser(pident), "[a ,\n*b,\n c]")
    roundTrip(ListLang.parser(pident), "[x for y in z]")
    roundTrip(ListLang.parser(pident), "[x for y in z if w]")

    roundTrip(ListLang.SpliceOrItem.parser(pident), "a")
    roundTrip(ListLang.SpliceOrItem.parser(pident), "*a")
  }

  test("we can parse comments") {
    val gen = Generators.commentGen(Generators.padding(Generators.genDeclaration(0), 1))
    forAll(gen) { comment =>
      parseTestAll(CommentStatement.parser(Parser.Indy.lift(Padding.parser(Declaration.parser("")))).run(""),
        Document[CommentStatement[Padding[Declaration]]].document(comment).render(80),
        comment)
    }

    val parensComment = """(#foo
#bar

1)"""
    parseTestAll(
      Declaration.parser(""),
      parensComment,
      Declaration.Parens(Declaration.Comment(
        CommentStatement(NonEmptyList.of("foo", "bar"),
          Padding(1, Declaration.LiteralInt("1"))))))
  }

  test("we can parse Declaration.LiteralInt") {
    forAll { bi: BigInt =>
      val litInt = Declaration.LiteralInt(bi.toString)
      parseTestAll(Declaration.literalIntP, litInt.toDoc.render(80), litInt)
    }
  }

  test("we can parse DefStatement") {
    val indDoc = Document[Indented[Declaration]]

    forAll(Generators.defGen(Generators.optIndent(Generators.genDeclaration(0)))) { defn =>
      parseTestAll[DefStatement[OptIndent[Declaration]]](
        DefStatement.parser(Parser.maybeSpace ~ OptIndent.indy(Declaration.parser).run("")),
        Document[DefStatement[OptIndent[Declaration]]].document(defn).render(80),
        defn)
    }

    val defWithComment = """def foo(a):
  # comment here
  a
foo"""
    parseTestAll(
      Declaration.parser(""),
      defWithComment,
      Declaration.DefFn(DefStatement("foo", NonEmptyList.of(("a", None)), None,
        (OptIndent.paddedIndented(1, 2, Declaration.Comment(CommentStatement(NonEmptyList.of(" comment here"),
          Padding(0, Declaration.Var("a"))))),
         Padding(0, Declaration.Var("foo"))))))

    roundTrip(Declaration.parser(""), defWithComment)

    // Here is a pretty brutal randomly generated case
    roundTrip(Declaration.parser(""),
"""def uwr(dw: h6lmZhgg) -> forall lnNR. Z5syis -> Mhgm:
  -349743008

foo""")

  }

  test("we can parse BindingStatement") {
    parseTestAll(Declaration.parser(""),
      """foo = 5

5""",
    Declaration.Binding(BindingStatement("foo", Declaration.LiteralInt("5"),
      Padding(1, Declaration.LiteralInt("5")))))
  }

  test("we can parse any Apply") {
    import Declaration._

    parseTestAll(parser(""),
      "x(f)",
      Apply(Var("x"), NonEmptyList.of(Var("f")), false))

    parseTestAll(parser(""),
      "f.x",
      Apply(Var("x"), NonEmptyList.of(Var("f")), true))

    parseTestAll(parser(""),
      "f(foo).x",
      Apply(Var("x"), NonEmptyList.of(Apply(Var("f"), NonEmptyList.of(Var("foo")), false)), true))

    parseTestAll(parser(""),
      "f.foo(x)", // foo(f, x)
      Apply(Var("foo"), NonEmptyList.of(Var("f"), Var("x")), true))

    parseTestAll(parser(""),
      "(\\x -> x)(f)",
      Apply(Parens(Lambda(NonEmptyList.of("x"), Var("x"))), NonEmptyList.of(Var("f")), false))

    parseTestAll(parser(""),
      "((\\x -> x)(f))",
      Parens(Apply(Parens(Lambda(NonEmptyList.of("x"), Var("x"))), NonEmptyList.of(Var("f")), false)))

    val expected = Apply(Parens(Parens(Lambda(NonEmptyList.of("x"), Var("x")))), NonEmptyList.of(Var("f")), false)
    parseTestAll(parser(""),
      "((\\x -> x))(f)",
      expected)

    parseTestAll(parser(""),
      expected.toDoc.render(80),
      expected)

  }

  test("we can parse bind") {
    import Declaration._

    parseTestAll(parser(""),
      """x = 4
x""",
    Binding(BindingStatement("x", LiteralInt("4"), Padding(0, Var("x")))))

    parseTestAll(parser(""),
      """x = foo(4)

x""",
    Binding(BindingStatement("x", Apply(Var("foo"), NonEmptyList.of(LiteralInt("4")), false), Padding(1, Var("x")))))

    parseTestAll(parser(""),
      """x = foo(4)
# x is really great
x""",
    Binding(BindingStatement("x",Apply(Var("foo"),NonEmptyList.of(LiteralInt("4")), false),Padding(0,Comment(CommentStatement(NonEmptyList.of(" x is really great"),Padding(0,Var("x"))))))))

  }

  test("we can parse if") {
    import Declaration._

    roundTrip[Declaration](ifElseP(Parser.Indy.lift(varP))(""),
      """if w:
      x
else:
      y""")
    roundTrip(parser(""),
      """if eq_Int(x, 3):
      x
else:
      y""")

    roundTrip(parser(""),
      """if eq_Int(x, 3):
      x
elif foo:
   z
else:
      y""")

    roundTrip[Declaration](ifElseP(Parser.Indy.lift(varP))(""),
      """if w: x
else: y""")
    roundTrip(parser(""),
      """if eq_Int(x, 3): x
else: y""")

    roundTrip(parser(""),
      """if eq_Int(x, 3): x
elif foo:
      z
else: y""")
  }

  test("we can parse a match") {
    roundTrip[Declaration](Declaration.matchP(Parser.Indy.lift(Declaration.varP))(""),
"""match x:
  y:
    z
  w:
    r""")
    roundTrip(Declaration.parser(""),
"""match 1:
  Foo(a, b):
    a.plus(b)
  Bar:
    42""")

    roundTrip(Declaration.parser(""),
"""match 1:
  Foo(a, b):
    a.plus(b)
  Bar:
    match x:
      True:
        100
      False:
        99""")

    roundTrip(Declaration.parser(""),
"""foo(1, match 2:
  Foo:

    foo
  Bar:

    # this is the bar case
    bar, 100)""")

    roundTrip(Declaration.parser(""),
"""if match 2:
  Foo:

    foo
  Bar:

    # this is the bar case
    bar:
  1
else:
  2""")

    roundTrip(Declaration.parser(""),
"""if True:
  match 1:
    Foo(f):
      1
else:
  100""")

    roundTrip(Declaration.parser(""),
"""match x:
  Bar(_, _):
    10""")

    roundTrip(Declaration.parser(""),
"""match x:
  Bar(_, _):
      if True: 0
      else: 10""")

    roundTrip(Declaration.parser(""),
"""match x:
  Bar(_, _):
      if True: 0
      else: 10""")
  }

  test("we can parse declaration lists") {
    roundTrip(Declaration.parser(""), "[]")
    roundTrip(Declaration.parser(""), "[1]")
    roundTrip(Declaration.parser(""), "[1, 2, 3]")
    roundTrip(Declaration.parser(""), "[1, *x, 3]")
    roundTrip(Declaration.parser(""), "[Foo(a, b), *acc]")
    roundTrip(ListLang.parser(Declaration.parser("")), "[foo(a, b)]")
    roundTrip(ListLang.SpliceOrItem.parser(Declaration.parser("")), "a")
    roundTrip(ListLang.SpliceOrItem.parser(Declaration.parser("")), "foo(a, b)")
    roundTrip(ListLang.SpliceOrItem.parser(Declaration.parser("")), "*foo(a, b)")
    roundTrip(Declaration.parser(""), "[x for y in [1, 2]]")
    roundTrip(Declaration.parser(""), "[x for y in [1, 2] if foo]")
  }

  test("we can parse any Declaration") {
    forAll(Generators.genDeclaration(5))(law(Declaration.parser("")))
  }

  test("we can parse any Statement") {
    forAll(Generators.genStatement(5))(law(Statement.parser))

    roundTrip(Statement.parser,
"""#
def foo(x): x""")

    roundTrip(Statement.parser,
"""#
def foo(x):
  x""")

    roundTrip(Statement.parser,
"""# header
y = if eq_Int(x, 2):
  True
else:
  False

def foo(x: Integer, y: String) -> String:
  toString(x).append_str(y)

# here is a lambda
fn = \x, y -> x.plus(y)

x = ( foo )

""")

    roundTrip(Statement.parser,
"""# header
def foo(x: forall f. f[a] -> f[b], y: a) -> b:
  x(y)

# here is a lambda
fn = \x, y -> x.plus(y)

x = ( foo )

""")

    roundTrip(Statement.parser,
"""# MONADS!!!!
struct Monad(pure: forall a. a -> f[a], flatMap: forall a, b. f[a] -> (a -> f[b]) -> f[b])
""")

    roundTrip(Statement.parser, """enum Option: None, Some(a)""")

    roundTrip(Statement.parser,
"""enum Option:
  None
  Some(a)""")

    roundTrip(Statement.parser,
"""enum Option:
  None, Some(a)""")
  }

  test("Any statement may append a newline and continue to parse") {
    forAll(Generators.genStatement(5)) {
      case Statement.EndOfFile => ()
      case s =>
        val str = Document[Statement].document(s).render(80) + "\n"
        roundTrip(Statement.parser, str)
    }
  }

  test("Any statement ending in a newline may have it removed and continue to parse") {
    forAll(Generators.genStatement(5)) { s =>
      val str = Document[Statement].document(s).render(80)

      roundTrip(Statement.parser, str.reverse.dropWhile(_ == '\n').reverse)
    }
  }

  test("Any declaration may append any whitespace and optionally a comma and parse") {
    forAll(Generators.genDeclaration(5), Gen.listOf(Gen.oneOf(' ', '\t')).map(_.mkString), Gen.oneOf(true, false)) {
      case (s, ws, comma) =>
        val str = Document[Declaration].document(s).render(80) + ws + (if (comma) "," else "")
        roundTrip(Declaration.parser(""), str, lax = true)
    }
  }

  test("parse external defs") {
    roundTrip(Statement.parser,
"""# header
external def foo -> String
""")
    roundTrip(Statement.parser,
"""# header
external def foo(i: Integer) -> String
""")
    roundTrip(Statement.parser,
"""# header
external def foo(i: Integer, b: a) -> String

external def foo2(i: Integer, b: a) -> String
""")
  }

  test("we can parse any package") {
    roundTrip(Package.parser,
"""
package Foo/Bar
import Baz [Bippy]
export [foo]

foo = 1
""")

    forAll(Generators.packageGen(5))(law(Package.parser))
  }

  test("we can parse Externals") {
    parseTestAll(Externals.parser,
"""
Foo/Bar flatMap scala org.bykn.bosatsu.Std.flatMap
Foo/Bar fold scala org.bykn.bosatsu.Std.fold
""",
   Externals.empty
     .add(PackageName.parse("Foo/Bar").get, "flatMap", FfiCall.ScalaCall("org.bykn.bosatsu.Std.flatMap"))
     .add(PackageName.parse("Foo/Bar").get, "fold", FfiCall.ScalaCall("org.bykn.bosatsu.Std.fold")))
  }
}
