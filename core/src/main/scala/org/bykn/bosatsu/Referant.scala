package org.bykn.bosatsu

import cats.data.NonEmptyList

import rankn.TypeEnv

/**
 * A Referant is something that can be exported or imported after resolving
 * Before resolving, imports and exports are just names.
 */
sealed abstract class Referant[+A]
object Referant {
  case class Value(scheme: rankn.Type) extends Referant[Nothing]
  case class DefinedT[A](dtype: rankn.DefinedType[A]) extends Referant[A]
  case class Constructor[A](name: ConstructorName, dtype: rankn.DefinedType[A], params: List[(ParamName, rankn.Type)], consValue: rankn.Type) extends Referant[A]

  private def imported[A, B, C](imps: List[Import[A, NonEmptyList[Referant[C]]]])(fn: PartialFunction[Referant[C], B]): Map[String, B] =
    imps.foldLeft(Map.empty[String, B]) { (m0, imp) =>
      m0 ++ Import.locals(imp)(fn)
    }

  def importedTypes[A, B](imps: List[Import[A, NonEmptyList[Referant[B]]]]): Map[String, (PackageName, String)] =
    imported(imps) {
      case Referant.DefinedT(dt) => (dt.packageName, dt.name.asString)
    }

  /**
   * These are all the imported items that may be used in a match
   */
  def importedConsNames[A, B](imps: List[Import[A, NonEmptyList[Referant[B]]]]): Map[String, (PackageName, ConstructorName)] =
    imported(imps) {
      case Referant.Constructor(cn, dt, _, _) => (dt.packageName, cn)
    }
  /**
   * There are all the imported values, including the constructor functions
   */
  def importedValues[A, B](imps: List[Import[A, NonEmptyList[Referant[B]]]]): Map[String, rankn.Type] =
    imported(imps) {
      case Referant.Value(t) => t
      case Referant.Constructor(_, _, _, t) => t
    }
  /**
   * Fully qualified original names
   */
  def fullyQualifiedImportedValues[A, B](imps: List[Import[A, NonEmptyList[Referant[B]]]])(nameOf: A => PackageName): Map[(PackageName, String), rankn.Type] =
    imps.iterator.flatMap { item =>
      val pn = nameOf(item.pack)
      item.items.toList.iterator.flatMap { i =>
        val orig = i.originalName
        val key = (pn, orig)
        i.tag.toList.iterator.collect {
          case Referant.Value(t) => (key, t)
          case Referant.Constructor(_, _, _, t) => (key, t)
        }
      }
    }
    .toMap

  def typeConstructors[A, B](
    imps: List[Import[A, NonEmptyList[Referant[B]]]]):
      Map[(PackageName, ConstructorName), (List[rankn.Type.Var], List[rankn.Type], rankn.Type.Const.Defined)] = {
    val refs: Iterator[Referant[B]] = imps.iterator.flatMap(_.items.toList.iterator.flatMap(_.tag.toList))
    refs.collect { case Constructor(cn, dt, params, _) =>
      ((dt.packageName, cn), (dt.typeParams, params.map(_._2), dt.toTypeConst))
    }
    .toMap
  }

  /**
   * Build the TypeEnv view of the given imports
   */
  def importedTypeEnv[A, B](inps: List[Import[A, NonEmptyList[Referant[B]]]])(nameOf: A => PackageName): TypeEnv[B] =
    inps.foldLeft((TypeEnv.empty): TypeEnv[B]) {
      case (te, imps) =>
        val pack = nameOf(imps.pack)
        imps.items.foldLeft(te) { (te, imp) =>
          val nm = imp.localName
          imp.tag.foldLeft(te) {
            case (te1, Referant.Value(t)) =>
              te1.addExternalValue(pack, nm, t)
            case (te1, Referant.Constructor(n, dt, params, v)) =>
              te1.addConstructor(pack, n, params, dt, v)
            case (te1, Referant.DefinedT(dt)) =>
              te1.addDefinedType(dt)
          }
        }
    }
}
