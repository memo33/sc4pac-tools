package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import VariantSelection.DecisionTree as DT
import VariantSelection.{Node, Leaf}

class VariantsSpec extends AnyWordSpec with Matchers {
  val leaf = Leaf(())

  def fromVariantsUnit[A, B](variants: Seq[Map[A, B]]): Either[ErrStr, DT[A, B, Unit]] =
    DT.fromVariants(variants.map(_ -> ()))

  "Variants" should {
    "be picked per decision tree" in {

      val b12 = Node("b", Seq("b1" -> leaf, "b2" -> leaf))
      val c12 = Node("c", Seq("c1" -> leaf, "c2" -> leaf))

      // the simplest case
      fromVariantsUnit(Seq[Variant](
        Map("b" -> "b1"),
        Map("b" -> "b2")
      )) shouldBe Right(b12)

      // the all-combinations case: order is irrelevant
      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a1", "b" -> "b2"),
        Map("a" -> "a2", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> b12)))

      // choose a first, then b
      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a1", "b" -> "b2"),
        Map("a" -> "a2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> leaf)))

      // choose a first, then b or c
      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a1", "b" -> "b2"),
        Map("a" -> "a2", "c" -> "c1"),
        Map("a" -> "a2", "c" -> "c2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> c12)))

      // choose a first, then b or c
      fromVariantsUnit(Seq[Variant](
        Map("b" -> "b1", "a" -> "a1"),
        Map("b" -> "b2", "a" -> "a1"),
        Map("c" -> "c1", "a" -> "a2"),
        Map("c" -> "c2", "a" -> "a2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> c12)))


      // a first, then b, then c
      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a2", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b2", "c" -> "c1"),
        Map("a" -> "a2", "b" -> "b2", "c" -> "c2"),
        Map("a" -> "a2", "b" -> "b3")
      )) shouldBe Right(Node("a", Seq("a1" -> leaf, "a2" -> Node("b", Seq("b1" -> leaf, "b2" -> c12, "b3" -> leaf)))))

      // this is less clear, but "a first, then b" seems desirable as "b first" would make a redundant
      // --> this is left unspecified to discourage this usage
      // Seq(
      //   Map("a" -> "a1", "b" -> "b1"),
      //   Map("a" -> "a1", "b" -> "b2"),
      //   Map("a" -> "a2", "b" -> "b3"),
      //   Map("a" -> "a2", "b" -> "b4")
      // )

      // order irrelevant(?)
      // --> unspecified
      // Seq(
      //   Map("a" -> "a1", "b" -> "b1"),
      //   Map("a" -> "a1", "b" -> "b2"),
      //   Map("a" -> "a2", "b" -> "b3"),
      //   Map("a" -> "a2", "b" -> "b4"),
      //   Map("a" -> "a3", "b" -> "b1"),
      //   Map("a" -> "a3", "b" -> "b2"),
      //   Map("a" -> "a4", "b" -> "b3"),
      //   Map("a" -> "a4", "b" -> "b4")
      // )
    }

    "be allowed to be empty" in {
      fromVariantsUnit(Seq[Variant](
        Map.empty[String, String]
      )) shouldBe Right(leaf)

      fromVariantsUnit(Seq.empty[Variant]) shouldBe a [Left[?, ?]]  // (debatable) Left since it does not allow to choose any variant
    }

    "be allowed single choices" in {
      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1")
      )) shouldBe Right(Node("a", Seq("a1" -> leaf)))

      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1")
      )) should (be (Right(Node("a", Seq("a1" -> Node("b", Seq("b1" -> leaf)))))) or
                 be (Right(Node("b", Seq("b1" -> Node("a", Seq("a1" -> leaf)))))))
    }

    "not be allowed to be incomplete" in {
      // disallowed since incomplete
      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1"),
        Map("b" -> "b2"),
        Map("b" -> "b2")
      )) shouldBe a [Left[?, ?]]

      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b2")
      )) shouldBe a [Left[?, ?]]

      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b1")
      )) shouldBe a [Left[?, ?]]

      fromVariantsUnit(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a2")
      )) shouldBe a [Left[?, ?]]
    }
  }

}
