package org.bykn.bosatsu

import cats.Applicative
import cats.data.NonEmptyList
import fastparse.all._
import org.typelevel.paiges.{ Doc, Document }

import Parser.{ Combinators, lowerIdent, upperIdent, maybeSpace }
import cats.implicits._

sealed abstract class Pattern[+N, +T] {
  def mapName[U](fn: N => U): Pattern[U, T] =
    (new Pattern.InvariantPattern(this)).mapStruct[U] { (n, parts) =>
      Pattern.PositionalStruct(fn(n), parts)
    }

  def mapType[U](fn: T => U): Pattern[N, U] =
    this match {
      case Pattern.WildCard => Pattern.WildCard
      case Pattern.Literal(lit) => Pattern.Literal(lit)
      case Pattern.Var(v) => Pattern.Var(v)
      case Pattern.ListPat(items) =>
        Pattern.ListPat(items.map(_.right.map(_.mapType(fn))))
      case Pattern.Annotation(p, tpe) => Pattern.Annotation(p.mapType(fn), fn(tpe))
      case Pattern.PositionalStruct(name, params) =>
        Pattern.PositionalStruct(name, params.map(_.mapType(fn)))
      case Pattern.Union(h, t) => Pattern.Union(h.mapType(fn), t.map(_.mapType(fn)))
    }

  /**
   * List all the names that are bound in Vars inside this pattern
   * in the left to right order they are encountered, without any duplication
   */
  def names: List[String] = {
    @annotation.tailrec
    def loop(stack: List[Pattern[N, T]], seen: Set[String], acc: List[String]): List[String] =
      stack match {
        case Nil => acc.reverse
        case (Pattern.WildCard | Pattern.Literal(_)) :: tail => loop(tail, seen, acc)
        case Pattern.Var(v) :: tail =>
          if (seen(v)) loop(tail, seen, acc)
          else loop(tail, seen + v, v :: acc)
        case Pattern.ListPat(items) :: tail =>
          val globs = items.collect { case Left(Some(glob)) => glob }.filterNot(seen)
          val next = items.collect { case Right(inner) => inner }
          loop(next ::: tail, seen ++ globs, globs reverse_::: acc)
        case Pattern.Annotation(p, _) :: tail => loop(p :: tail, seen, acc)
        case Pattern.PositionalStruct(name, params) :: tail =>
          loop(params ::: tail, seen, acc)
        case Pattern.Union(h, t) :: tail =>
          loop(h :: (t.toList) ::: tail, seen, acc)
      }

    loop(this :: Nil, Set.empty, Nil)
  }

  /**
   * Return the pattern with all the binding names removed
   */
  def unbind: Pattern[N, T] =
    filterVars(Set.empty)

  /**
   * replace all Var names with Wildcard that are not
   * satifying the keep predicate
   */
  def filterVars(keep: String => Boolean): Pattern[N, T] =
    this match {
      case Pattern.WildCard | Pattern.Literal(_) => this
      case p@Pattern.Var(v) =>
        if (keep(v)) p else Pattern.WildCard
      case Pattern.ListPat(items) =>
        Pattern.ListPat(items.map {
          case Left(opt) => Left(opt.filter(keep))
          case Right(p) => Right(p.filterVars(keep))
        })
      case Pattern.Annotation(p, tpe) =>
        Pattern.Annotation(p.filterVars(keep), tpe)
      case Pattern.PositionalStruct(name, params) =>
        Pattern.PositionalStruct(name, params.map(_.filterVars(keep)))
      case Pattern.Union(h, t) =>
        Pattern.Union(h.filterVars(keep), t.map(_.filterVars(keep)))
    }
}

object Pattern {

  implicit class InvariantPattern[N, T](val pat: Pattern[N, T]) extends AnyVal {
    def traverseType[F[_]: Applicative, T1](fn: T => F[T1]): F[Pattern[N, T1]] =
      pat match {
        case Pattern.WildCard => Applicative[F].pure(Pattern.WildCard)
        case Pattern.Literal(lit) => Applicative[F].pure(Pattern.Literal(lit))
        case Pattern.Var(v) => Applicative[F].pure(Pattern.Var(v))
        case Pattern.ListPat(items) =>
          items.traverse {
            case Left(v) => Applicative[F].pure(Left(v): Either[Option[String], Pattern[N, T1]])
            case Right(p) => p.traverseType(fn).map(Right(_): Either[Option[String], Pattern[N, T1]])
          }.map(Pattern.ListPat(_))
        case Pattern.Annotation(p, tpe) =>
          (p.traverseType(fn), fn(tpe)).mapN(Pattern.Annotation(_, _))
        case Pattern.PositionalStruct(name, params) =>
          params.traverse(_.traverseType(fn)).map { ps =>
            Pattern.PositionalStruct(name, ps)
          }
        case Pattern.Union(h, tail) =>
          (h.traverseType(fn), tail.traverse(_.traverseType(fn))).mapN { (h, t) =>
            Pattern.Union(h, t)
          }
      }

    def mapStruct[N1](parts: (N, List[Pattern[N1, T]]) => Pattern[N1, T]): Pattern[N1, T] =
      pat match {
        case Pattern.WildCard => Pattern.WildCard
        case Pattern.Literal(lit) => Pattern.Literal(lit)
        case Pattern.Var(v) => Pattern.Var(v)
        case Pattern.ListPat(items) =>
          val items1 = items.map {
            case Left(v) =>
              Left(v): Either[Option[String], Pattern[N1, T]]
            case Right(p) =>
              val p1 = p.mapStruct(parts)
              Right(p1): Either[Option[String], Pattern[N1, T]]
          }
          Pattern.ListPat(items1)
        case Pattern.Annotation(p, tpe) =>
          Pattern.Annotation(p.mapStruct(parts), tpe)
        case Pattern.PositionalStruct(name, params) =>
          val p1 = params.map(_.mapStruct(parts))
          parts(name, p1)
        case Pattern.Union(h, tail) =>
          Pattern.Union(h.mapStruct(parts), tail.map(_.mapStruct(parts)))
      }
  }

  case object WildCard extends Pattern[Nothing, Nothing]
  case class Literal(toLit: Lit) extends Pattern[Nothing, Nothing]
  case class Var(name: String) extends Pattern[Nothing, Nothing]
  case class ListPat[N, T](parts: List[Either[Option[String], Pattern[N, T]]]) extends Pattern[N, T]
  case class Annotation[N, T](pattern: Pattern[N, T], tpe: T) extends Pattern[N, T]
  case class PositionalStruct[N, T](name: N, params: List[Pattern[N, T]]) extends Pattern[N, T]
  case class Union[N, T](head: Pattern[N, T], rest: NonEmptyList[Pattern[N, T]]) extends Pattern[N, T]

  implicit def patternOrdering[N: Ordering, T: Ordering]: Ordering[Pattern[N, T]] =
    new Ordering[Pattern[N, T]] {
      val ordN = implicitly[Ordering[N]]
      val ordT = implicitly[Ordering[T]]
      val list = ListOrdering.onType(this)
      def eOrd[A, B](ordA: Ordering[A], ordB: Ordering[B]): Ordering[Either[A, B]] =
        new Ordering[Either[A, B]] {
          def compare(a: Either[A, B], b: Either[A, B]) =
            (a, b) match {
              case (Left(_), Right(_)) => -1
              case (Right(_), Left(_)) => 1
              case (Left(a), Left(b)) => ordA.compare(a, b)
              case (Right(a), Right(b)) => ordB.compare(a, b)
            }
        }

      val listE = ListOrdering.onType(eOrd[Option[String], Pattern[N, T]](implicitly, this))

      def compare(a: Pattern[N, T], b: Pattern[N, T]): Int =
        (a, b) match {
          case (WildCard, WildCard) => 0
          case (WildCard, _) => -1
          case (Literal(_), WildCard) => 1
          case (Literal(a), Literal(b)) => Lit.litOrdering.compare(a, b)
          case (Literal(_), _) => -1
          case (Var(_), WildCard | Literal(_)) => 1
          case (Var(a), Var(b)) => a.compareTo(b)
          case (Var(_), _) => -1
          case (ListPat(_), WildCard | Literal(_) | Var(_)) => 1
          case (ListPat(as), ListPat(bs)) => listE.compare(as, bs)
          case (ListPat(_), _) => -1
          case (Annotation(a0, t0), Annotation(a1, t1)) =>
            val c = compare(a0, a1)
            if (c == 0) ordT.compare(t0, t1) else c
          case (Annotation(_, _), PositionalStruct(_, _) | Union(_, _)) => -1
          case (Annotation(_, _), _) => 1
          case (PositionalStruct(n0, a0), PositionalStruct(n1, a1)) =>
            val c = ordN.compare(n0, n1)
            if (c == 0) list.compare(a0, a1) else c
          case (PositionalStruct(_, _), Union(_, _)) => -1
          case (PositionalStruct(_, _), _) => 1
          case (Union(h0, t0), Union(h1, t1)) =>
            list.compare(h0 :: t0.toList, h1 :: t1.toList)
          case (Union(_, _), _) => 1
        }
    }

  implicit lazy val document: Document[Pattern[Option[String], TypeRef]] =
    Document.instance[Pattern[Option[String], TypeRef]] {
      case WildCard => Doc.char('_')
      case Literal(lit) => Document[Lit].document(lit)
      case Var(n) => Doc.text(n)
      case ListPat(items) =>
        Doc.char('[') + Doc.intercalate(Doc.text(", "),
          items.map {
            case Left(None) => Doc.text("*_")
            case Left(Some(glob)) => Doc.char('*') + Doc.text(glob)
            case Right(p) => document.document(p)
          }) + Doc.char(']')
      case Annotation(_, _) =>
        /*
         * We need to know what package we are in and what imports we depend on here.
         * This creates some challenges we need to deal with:
         *   1. how do we make sure we don't have duplicate short names
         *   2. how do we make sure we have imported the names we need
         *   3. at the top level we need parens to distinguish a: Integer from being the rhs of a
         *      case
         */
        ???
      case PositionalStruct(n, Nil) =>
        n match {
          case None => Doc.text("()")
          case Some(nm) => Doc.text(nm)
        }
      case PositionalStruct(None, h :: Nil) =>
        // single item tuples need a comma:
        Doc.char('(') + document.document(h) + Doc.text(",)")
      case PositionalStruct(n, nonEmpty) =>
        val prefix = n match {
          case None => Doc.empty
          case Some(n) => Doc.text(n)
        }
        prefix +
          Doc.char('(') + Doc.intercalate(Doc.text(", "), nonEmpty.map(document.document(_))) + Doc.char(')')
      case Union(head, rest) =>
        def doc(p: Pattern[Option[String], TypeRef]): Doc =
          p match {
            case Annotation(_, _) | Union(_, _) =>
              // if an annotation or union is embedded, we need to put parens for parsing
              // to round trip. Note, we will never parse a nested union, but generators or could
              // code produce one
              Doc.char('(') + document.document(p) + Doc.char(')')
            case nonParen => document.document(nonParen)
          }
        Doc.intercalate(Doc.text(" | "), (head :: rest.toList).map(doc(_)))
    }

  lazy val parser: P[Pattern[Option[String], TypeRef]] = {

    val pwild = P("_").map(_ => WildCard)
    val pvar = lowerIdent.map(Var(_))
    val plit = Lit.parser.map(Literal(_))
    lazy val recurse = P(go(false))

    def go(isTop: Boolean): P[Pattern[Option[String], TypeRef]] = {

      val positional = P(upperIdent ~ (recurse.listN(1).parens).?)
        .map {
          case (n, None) => PositionalStruct(Some(n), Nil)
          case (n, Some(ls)) => PositionalStruct(Some(n), ls)
        }

      val tupleOrParens = recurse.tupleOrParens.map {
        case Left(parens) => parens
        case Right(tup) => PositionalStruct(None, tup)
      }

      val listItem: P[Either[Option[String], Pattern[Option[String], TypeRef]]] = {
        val maybeNamed: P[Option[String]] =
          P("_").map(_ => None) | lowerIdent.map(Some(_))

        P("*" ~ maybeNamed).map(Left(_)) | recurse.map(Right(_))
      }

      val listP = listItem.listSyntax.map(ListPat(_))

      val nonAnnotated = pvar | plit | pwild | tupleOrParens | positional | listP
      // A union can't have an annotation, we need to be inside a parens for that
      val unionOp: P[Pattern[Option[String], TypeRef] => Pattern[Option[String], TypeRef]] = {
        val unionSep = P("|" ~ maybeSpace)
        val one = (unionSep ~ nonAnnotated ~ maybeSpace)
        (one ~ one.rep())
          .map { case (h, tailSeq) =>
            val ne = NonEmptyList(h, tailSeq.toList)

            { pat: Pattern[Option[String], TypeRef] => Union(pat, ne) }
          }
      }
      val typeAnnotOp: P[Pattern[Option[String], TypeRef] => Pattern[Option[String], TypeRef]] = {
        P(":" ~ maybeSpace ~ TypeRef.parser)
          .map { tpe =>
            { pat: Pattern[Option[String], TypeRef] => Annotation(pat, tpe) }
          }
      }

      def maybeOp(opP: P[Pattern[Option[String], TypeRef] => Pattern[Option[String], TypeRef]]): P[Pattern[Option[String], TypeRef]] =
        (nonAnnotated ~ maybeSpace ~ opP.?)
          .map {
            case (p, None) => p
            case (p, Some(op)) => op(p)
          }

      // We only allow type annotation not at the top level, must be inside
      // Struct or parens
      if (isTop) maybeOp(unionOp)
      else maybeOp(unionOp | typeAnnotOp)
    }

    go(true)
  }
}

