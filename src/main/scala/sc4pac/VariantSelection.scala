package io.github.memo33
package sc4pac

import zio.{ZIO, Task, RIO}

import sc4pac.JsonData as JD
import sc4pac.error.Sc4pacMissingVariant

/** Encodes the variant selections from different sources, for an Update run.
  *
  * - `currentSelections`: selections that have been confirmed in this Update
  *   run (these are guaranteed to be what the user wants now)
  * - `initialSelections`: selections at the start of this Update run
  * - `importedSelections`: selections from "imported" package lists (in the
  *   GUI), ordered in descending priority. These can be used as fallback, but
  *   should be confirmed by the user, especially since these might conflict
  *   with `initialSelections`.
  *
  * Finally, after Update completes, `initialSelections ++ currentSelections`
  * will become the new initial variant selections.
  */
class VariantSelection private (
  val currentSelections: Variant,
  val initialSelections: Variant,
  val importedSelections: Map[String, Seq[String]],
) {

  def addSelections(additionalChoices: Seq[(String, String)]): VariantSelection =
    new VariantSelection(
      currentSelections = currentSelections ++ additionalChoices,
      initialSelections = initialSelections,
      importedSelections = importedSelections,
    )

  def buildFinalSelections(): Variant = initialSelections ++ currentSelections

  /** Returns false if there is a conflict between the variant and our present
    * selections. */
  private def isMatchingVariant(variant: Variant): Boolean =
    variant.iterator.forall { (key, value) =>
      currentSelections.get(key) match {
        case Some(selectedValue) => selectedValue == value
        case None => initialSelections.get(key).contains(value) && importedSelections.getOrElse(key, Nil).forall(_ == value)
      }
    }

  def pickVariant(pkgData: JD.Package): Task[(JD.Package, JD.VariantData)] = {
    pkgData.variants.find(vd => isMatchingVariant(vd.variant)) match {
      case Some(vd) => ZIO.succeed((pkgData, vd))
      case None =>
        // Either the variant has not been fully selected yet (user needs to choose),
        // or there is a conflict between a variant and currentSelections
        // (should not happen, but can happen in case of incomplete variants in YAML),
        // or there is a conflict between initialSelections and importedSelections
        // (can happen, so user needs to choose).
        // Finding the exact reason would require setting up a DecisionTree, so
        // isn't done here, but when the exception is handled.
        ZIO.fail(new Sc4pacMissingVariant(
          pkgData,
          s"could not find variant for ${pkgData.toBareDep.orgName} matching [${JD.VariantData.variantString(currentSelections)}]",
        ))
    }
  }

  /** If Left, user confirmation is needed for choosing between (optional)
    * initial selection and (possibly empty) imported selections.
    * If Right, the selection is unambiguous, so no user confirmation needed.
    */
  def getSelectedValue(key: String): Either[(Option[String], Seq[String]), String] =
    currentSelections.get(key).map(Right(_))
      .getOrElse {
        val initialValueOpt: Option[String] = initialSelections.get(key)
        val importedValues: Seq[String] = importedSelections.getOrElse(key, Seq.empty)
        if (initialValueOpt.isDefined && importedValues.forall(_ == initialValueOpt.get)) {  // no conflict with importedValues
          Right(initialValueOpt.get)
        } else {  // no global selection, or conflict
          Left((initialValueOpt, importedValues))
        }
      }

  /** Prompts for missing variant keys, so that the result allows to pick a unique variant of the package. */
  def refineFor(pkgData: JD.Package): RIO[Prompter, VariantSelection] = {
    val mod = pkgData.toBareDep
    lazy val variantInfo = pkgData.upgradeVariantInfo.variantInfo
    import VariantSelection.{DecisionTree, Node, Empty}
    DecisionTree.fromVariants(pkgData.variants.map(_.variant)) match {
      case Left(err) => ZIO.fail(new error.UnsatisfiableVariantConstraints(
        s"Unable to choose variants as the metadata of ${mod.orgName} seems incomplete", err.toString))
      case Right(decisionTree) =>
        type Key = String; type Value = String
        def choose[T](key: Key, choices: Seq[(Value, T)]): RIO[Prompter, (Value, T)] = {
          getSelectedValue(key) match
            case Right(selectedValue) => choices.find(_._1 == selectedValue) match
              case Some(choice) => ZIO.succeed(choice)
              case None => ZIO.fail(new error.UnsatisfiableVariantConstraints(
                s"""None of the variants ${choices.map(_._1).mkString(", ")} of ${mod.orgName} match the configured variant $key=$selectedValue.""",
                s"""The package metadata seems incorrect, but resetting the configured variant in the GUI Dashboard may resolve the problem (CLI command: `sc4pac variant reset "$key"`)."""))
            case Left((initialValueOpt, importedValues)) =>  // variant for key has not been selected or is ambiguous, so choose it interactively
              ZIO.serviceWithZIO[Prompter](_.promptForVariant(
                module = mod,
                variantId = key,
                values = choices.map(_._1),
                info = variantInfo.get(key).getOrElse(JD.VariantInfo.empty),
                previouslySelectedValue = initialValueOpt,
                importedValues = importedValues,
              ).map(selectedValue => choices.find(_._1 == selectedValue).get))  // prompter is guaranteed to return a matching value
        }

        ZIO.iterate(decisionTree, Seq.newBuilder[(Key, Value)])(_._1 != Empty) {
          case (Node(key, choices), builder) => choose(key, choices).map { case (value, subtree) => (subtree, builder += key -> value) }
          case (Empty, builder) => throw new AssertionError
        }.map(_._2.result())
          .map(addSelections)
    }
  }

}

object VariantSelection {
  def apply(currentSelections: Variant, initialSelections: Variant, importedSelections: Seq[Variant]): VariantSelection =
    new VariantSelection(
      currentSelections = currentSelections,
      initialSelections = initialSelections,
      importedSelections = importedSelections.flatMap(_.iterator).groupMap(_._1)(_._2).view.mapValues(_.distinct).toMap,
    )

  sealed trait DecisionTree[+A, +B]
  case class Node[+A, +B](key: A, choices: Seq[(B, DecisionTree[A, B])]) extends DecisionTree[A, B] {
    require(choices.nonEmpty, "decision tree must not have empty choices")
  }
  case object Empty extends DecisionTree[Nothing, Nothing]

  object DecisionTree {
    private class NoCommonKeys(val msg: String) extends scala.util.control.ControlThrowable

    def fromVariants[A, B](variants: Seq[Map[A, B]]): Either[ErrStr, DecisionTree[A, B]] = {

      def helper(variants: Seq[Map[A, B]], remainingKeys: Set[A]): DecisionTree[A, B] = {
        remainingKeys.find(key => variants.forall(_.contains(key))) match
          case None => variants match
            case Seq(singleVariant) => Empty  // if there is just a single variant left, all its keys have already been chosen validly
            case _ => throw new NoCommonKeys(s"Variants do not have a key in common: $variants")  // our choices of keys left an ambiguity
          case Some(key) =>  // this key allows partitioning
            val remainingKeys2 = remainingKeys - key  // strictly smaller, so recursion is well-founded
            val parts: Map[B, Seq[Map[A, B]]] = variants.groupBy(_(key))
            val values: Seq[B] = variants.map(_(key)).distinct  // note that this preserves order
            val choices = values.map { value => value -> helper(parts(value), remainingKeys2) }
            Node(key, choices)
      }

      val allKeys = variants.flatMap(_.keysIterator).toSet
      try Right(helper(variants, allKeys)) catch { case e: NoCommonKeys => Left(e.msg) }
    }
  }

}
