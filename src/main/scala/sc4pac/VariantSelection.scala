package io.github.memo33
package sc4pac

import zio.{ZIO, Task}

import sc4pac.JsonData as JD
import sc4pac.error.Sc4pacMissingVariant

/** Encodes the variant selections from different sources, for an Update run.
  *
  * - `currentSelections`: selections that have been confirmed in this Update
  *   run (these are guaranteed to be what the user wants now)
  * - `initialGlobalSelections`: selections at the start of this Update run
  * - `importedSelections`: selections from "imported" package lists (in the
  *   GUI), ordered in descending priority. These can be used as fallback, but
  *   should be confirmed by the user, especially since these might conflict
  *   with `initialGlobalSelections`.
  *
  * After Update completes, `initialGlobalSelections ++ currentSelections` will
  * become the new global variant selections.
  */
class VariantSelection private (
  val currentSelections: Variant,
  val initialGlobalSelections: Variant,
  val importedSelections: Map[String, Seq[String]],
) {

  def addSelections(additionalChoices: Seq[(String, String)]): VariantSelection =
    new VariantSelection(
      currentSelections = currentSelections ++ additionalChoices,
      initialGlobalSelections = initialGlobalSelections,
      importedSelections = importedSelections,
    )

  def buildResultingSelections(): Variant = initialGlobalSelections ++ currentSelections

  /** Returns false if there is a conflict between the variant and our present
    * selections. */
  private def isMatchingVariant(variant: Variant): Boolean =
    variant.iterator.forall { (key, value) =>
      currentSelections.get(key) match {
        case Some(selectedValue) => selectedValue == value
        case None => initialGlobalSelections.get(key).contains(value) && importedSelections.getOrElse(key, Nil).forall(_ == value)
      }
    }

  def pickVariant(pkgData: JD.Package): Task[(JD.Package, JD.VariantData)] = {
    pkgData.variants.find(vd => isMatchingVariant(vd.variant)) match {
      case Some(vd) => ZIO.succeed((pkgData, vd))
      case None =>
        // Either the variant has not been fully selected yet (user needs to choose),
        // or there is a conflict between a variant and currentSelections
        // (should not happen, but can happen in case of incomplete variants in YAML),
        // or there is a conflict between initialGlobalSelections and importedSelections
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
    * global selection and (possibly empty) imported selections.
    * If Right, the selection is unambiguous, so no user confirmation needed.
    */
  def getSelectedValue(key: String): Either[(Option[String], Seq[String]), String] =
    currentSelections.get(key).map(Right(_))
      .getOrElse {
        val globalValueOpt: Option[String] = initialGlobalSelections.get(key)
        val importedValues: Seq[String] = importedSelections.getOrElse(key, Seq.empty)
        if (globalValueOpt.isDefined && importedValues.forall(_ == globalValueOpt.get)) {  // no conflict with importedValues
          Right(globalValueOpt.get)
        } else {  // no global selection, or conflict
          Left((globalValueOpt, importedValues))
        }
      }

}

object VariantSelection {
  def apply(currentSelections: Variant, initialGlobalSelections: Variant, importedSelections: Seq[Variant]): VariantSelection =
    new VariantSelection(
      currentSelections = currentSelections,
      initialGlobalSelections = initialGlobalSelections,
      importedSelections = importedSelections.flatMap(_.iterator).groupMap(_._1)(_._2).view.mapValues(_.distinct).toMap,
    )
}
