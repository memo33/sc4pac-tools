package io.github.memo33
package sc4pac

import zio.{ZIO, Task, RIO}

import sc4pac.JsonData as JD
import sc4pac.error.Sc4pacMissingVariant
import VariantSelection.{DecisionTree, Node, Leaf}

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
    if (additionalChoices.isEmpty) this
    else new VariantSelection(
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

  /** Pick a variant from package variants array, but also refine it so that any
    * conditional variants are fully selected as well. */
  def pickVariant(pkgData: JD.Package): Task[JD.VariantData] = {
    pkgData.variants.find(vd => isMatchingVariant(vd.variant)) match {
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
      case Some(vd) =>  // step 1: found ordinary variant
        val condVariantIds = vd.conditionalVariantsIterator.flatMap(_.iterator).map(_._1).toSet  // order does not matter here
        ZIO.foreach(condVariantIds) { variantId =>
          getSelectedValue(variantId) match
            case Left(_) =>
              ZIO.fail(new Sc4pacMissingVariant(
                pkgData,
                s"could not find variant for ${pkgData.toBareDep.orgName} matching [${JD.VariantData.variantString(currentSelections)}] and $variantId=None",
              ))
            case Right(value) => ZIO.succeed(variantId -> value)
        }.map { conditionalSelections =>  // step 2: no unselected variants exist anymore, so we can select any matching "conditional" variants and store this in `vd`
          vd.copy(
            variant = vd.variant ++ conditionalSelections,  // we keep all the variant IDs from conditions (even if some combination is not needed)
          )
        }
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

  /** Obtain module from prefix of variantId. */
  def packageLocalVariantToModule(variantId: String): Option[BareModule] = {
    val i1 = variantId.indexOf(":")
    if (i1 == -1) {
      None
    } else {
      val i2 = variantId.indexOf(":", i1 + 1)
      if (i2 == -1) {
        None
      } else {
        Sc4pac.parseModule(variantId.substring(0, i2)).toOption
      }
    }
  }

  /** If `variantId` is not local to `mod`, fetch package JSON of actual package
    * this variant is local to (if any).
    */
  def fetchFallbackVariantInfo(variantId: String, mod: BareModule): RIO[ResolutionContext, Option[JD.VariantInfo]] = {
    val orgName = mod.orgName
    if (variantId.startsWith(orgName) && variantId.startsWith(":", orgName.length)) {
      // no fallback needed, as variantId is prefixed by mod
      ZIO.succeed(None)
    } else {
      packageLocalVariantToModule(variantId) match
        case Some(mod2) =>
          Find.concreteVersion(mod2, Constants.versionLatestRelease)
            .flatMap(Find.packageData[JD.Package](mod2, _))
            .map(_.flatMap(_._1.upgradeVariantInfo.variantInfo.get(variantId)))
        case None => ZIO.succeed(None)
    }
  }

  private def getVariantInfoOrFallback(variantId: Key, mod: BareModule, variantInfos: => Map[String, JD.VariantInfo]) =
    ZIO.succeed(variantInfos.get(variantId))
      .someOrElseZIO(fetchFallbackVariantInfo(variantId, mod).someOrElse(JD.VariantInfo.empty))

  private type Key = String
  private type Value = String
  /** Choose a variant for the given key; prompt if necessary. */
  private def choose[T](key: Key, choices: Seq[(Value, T)], mod: BareModule, variantInfos: => Map[String, JD.VariantInfo]): RIO[Prompter & ResolutionContext, (Value, T)] = {
    getSelectedValue(key) match
      case Right(selectedValue) => choices.find(_._1 == selectedValue) match
        case Some(choice) => ZIO.succeed(choice)
        case None => ZIO.fail(new error.UnsatisfiableVariantConstraints(
          s"""None of the variants ${choices.map(_._1).mkString(", ")} of ${mod.orgName} match the configured variant $key=$selectedValue.""",
          s"""The package metadata seems incorrect, but resetting the configured variant in the GUI Dashboard may resolve the problem (CLI command: `sc4pac variant reset "$key"`)."""))
      case Left((initialValueOpt, importedValues)) =>  // variant for key has not been selected or is ambiguous, so choose it interactively
        for {
          variantInfo    <- getVariantInfoOrFallback(key, mod, variantInfos)
          selectedValue  <- ZIO.serviceWithZIO[Prompter](_.promptForVariant(api.PromptMessage.ChooseVariant(
                              mod,
                              variantId = key,
                              choices = choices.map(_._1),
                              info = variantInfo,
                              previouslySelectedValue = initialValueOpt,
                              importedValues = importedValues,
                            )))
        } yield choices.find(_._1 == selectedValue).get  // prompter is guaranteed to return a matching value
  }

  /** Select all variants by following a path in the decision tree; prompt if necessary. */
  private def selectionsFromTree[C](decisionTree: DecisionTree[Key, Value, C], mod: BareModule, variantInfos: => Map[String, JD.VariantInfo]): RIO[Prompter & ResolutionContext, (C, VariantSelection)] = {
    ZIO.iterate(decisionTree, Seq.newBuilder[(Key, Value)])(!_._1.isLeaf) {
      case (Node(key, choices), builder) => choose(key, choices, mod, variantInfos).map { case (value, subtree) => (subtree, builder += key -> value) }
      case (Leaf(_), builder) => throw new AssertionError
    }.map {
      case (Leaf(data), builder) => (data, addSelections(builder.result()))
      case (Node(_, _), _) => throw new AssertionError
    }
  }

  /** Prompts for missing variant keys, so that the result allows to pick a unique variant of the package. */
  def refineFor(pkgData: JD.Package): RIO[Prompter & ResolutionContext, VariantSelection] = {
    val mod = pkgData.toBareDep
    lazy val variantInfos = pkgData.upgradeVariantInfo.variantInfo
    DecisionTree.fromVariants(pkgData.variants.map(_.variant).zipWithIndex) match {
      case Left(err) => ZIO.fail(new error.UnsatisfiableVariantConstraints(
        s"Unable to choose variants as the metadata of ${mod.orgName} seems incomplete", err.toString))
      case Right(decisionTree) =>
        // 2-step process: first choose ordinary variant, then conditional variant
        for {
          (variantIdx, tmpSelection) <- selectionsFromTree(decisionTree, mod, variantInfos)
          conditionalDecisionTree    =  DecisionTree.fromVariantsCartesian(pkgData.variants(variantIdx).conditionalVariantsIterator)
          ((), finalSelection)       <- tmpSelection.selectionsFromTree(conditionalDecisionTree, mod, variantInfos)
        } yield finalSelection
    }
  }

  def selectMessageFor(pkgData: JD.Package, variantId: Key): RIO[ResolutionContext, Option[api.PromptMessage.ChooseVariant]] = {
    val mod = pkgData.toBareDep
    ZIO.foreach(JD.Package.buildVariantChoices(pkgData.variants).find(_.variantId == variantId)) { vc =>
      for {
        variantInfo <- getVariantInfoOrFallback(variantId, mod, pkgData.upgradeVariantInfo.variantInfo)
        (initialValueOpt, importedValues) = getSelectedValue(variantId).map(Some(_) -> Nil).merge
      } yield api.PromptMessage.ChooseVariant(
        mod,
        variantId = variantId,
        choices = vc.choices,
        info = variantInfo,
        previouslySelectedValue = initialValueOpt,
        importedValues = importedValues,
      )
    }
  }

}

object VariantSelection {
  def apply(currentSelections: Variant, initialSelections: Variant, importedSelections: Seq[Variant] = Seq.empty): VariantSelection =
    new VariantSelection(
      currentSelections = currentSelections,
      initialSelections = initialSelections,
      importedSelections = importedSelections.flatMap(_.iterator).groupMap(_._1)(_._2).view.mapValues(_.distinct).toMap,
    )

  sealed trait DecisionTree[+A, +B, C] {
    def isLeaf: Boolean = this.isInstanceOf[Leaf[C]]
  }
  case class Node[+A, +B, C](key: A, choices: Seq[(B, DecisionTree[A, B, C])]) extends DecisionTree[A, B, C] {
    require(choices.nonEmpty, "decision tree must not have empty choices")
  }
  case class Leaf[C](data: C) extends DecisionTree[Nothing, Nothing, C]

  object DecisionTree {
    private class NoCommonKeys(val msg: String) extends scala.util.control.ControlThrowable

    def fromVariants[A, B, C](variants: Seq[(Map[A, B], C)]): Either[ErrStr, DecisionTree[A, B, C]] = {

      def helper(variants: Seq[(Map[A, B], C)], remainingKeys: Set[A]): DecisionTree[A, B, C] = {
        remainingKeys.find(key => variants.forall(_._1.contains(key))) match
          case None => variants match
            case Seq(singleVariant) => Leaf(singleVariant._2)  // if there is just a single variant left, all its keys have already been chosen validly
            case _ => throw new NoCommonKeys(s"Variants do not have a key in common: $variants")  // our choices of keys left an ambiguity
          case Some(key) =>  // this key allows partitioning
            val remainingKeys2 = remainingKeys - key  // strictly smaller, so recursion is well-founded
            val parts: Map[B, Seq[(Map[A, B], C)]] = variants.groupBy(_._1(key))
            val values: Seq[B] = variants.map(_._1(key)).distinct  // note that this preserves order
            val choices = values.map { value => value -> helper(parts(value), remainingKeys2) }
            Node(key, choices)
      }

      val allKeys = variants.flatMap(_._1.keysIterator).toSet
      try Right(helper(variants, allKeys)) catch { case e: NoCommonKeys => Left(e.msg) }
    }

    /** The difference here is that `variants` might not have a key in common, in
      * which case the different variants are combined.
      * For simplicity, we assume that all variant IDs are orthogonal to each
      * other, so order does not matter, but we need to choose all of them (even
      * if occasionally some choices wouldn't matter).
      * Conceptually, for each node, every child is the same, so the result is a
      * sequential structure.
      */
    def fromVariantsCartesian[A, B](variants: IterableOnce[Map[A, B]]): DecisionTree[A, B, Unit] = {
      val entries: Seq[(A, B)] = variants.iterator.flatMap(_.iterator).toSeq  // collects all possible keys and values (possibly with duplicates)
      val valuesById = entries.groupMap(_._1)(_._2)
      entries.map(_._1).distinct  // order-preserving
        .foldRight(Leaf(()): DecisionTree[A, B, Unit]) { (key, subtree) =>
          Node(key, choices = valuesById(key).distinct.map(_ -> subtree))  // each child subtree is identical (avoids combinatorial explosion)
        }
    }
  }

  /** Generate combinations that cover all pairs of choices. */
  private[sc4pac] def pairwiseTestingCombinations(variantChoices: Seq[JD.VariantChoice]): Seq[Variant] = {
    if (variantChoices.isEmpty) {
      Seq(Map.empty)
    } else if (variantChoices.lengthCompare(1) == 0) {  // one variant, so pick all choices
      val vc = variantChoices.head
      vc.choices.map(value => Map(vc.variantId -> value))
    } else {
      import io.github.pavelicii.allpairs4j.{AllPairs, Parameter}
      val builder = AllPairs.AllPairsBuilder()
      for (vc <- variantChoices) {
        builder.withParameter(new Parameter(vc.variantId, vc.choices*))
      }
      val allPairs = builder.build()
      import scala.jdk.CollectionConverters.*
      allPairs.iterator.asScala.map(_.asScala.toMap.asInstanceOf[Map[String, String]]).toSeq
    }
  }

  /** Generate combinations that
    * - fully cover all regular choices, and
    * - cover all pairs of conditional choices
    * (or, if quick, only first and last variant). */
  def generateTestingVariants(pkgData: JD.Package, quick: Boolean): Seq[Variant] = {
    if (pkgData.variantChoices.isEmpty) {
      Seq(Map.empty)
    } else if (quick) {
      val first = pkgData.variantChoices.map(vc => (vc.variantId, vc.choices.head)).toMap
      val last = pkgData.variantChoices.map(vc => (vc.variantId, vc.choices.last)).toMap
      Seq(first, last)
    } else {
      pkgData.variants.flatMap { variantData =>
        // all regular variants + pairwise testing combinations of conditional variants
        val conditionalVariantChoices = pkgData.variantChoices.filter(vc => !variantData.variant.contains(vc.variantId))
        pairwiseTestingCombinations(conditionalVariantChoices).map(_ ++ variantData.variant)
      }
    }
  }

}
