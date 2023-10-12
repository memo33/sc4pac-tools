package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import Sc4pac.DecisionTree as DT
import Sc4pac.{Node, Empty}

class VariantsSpec extends AnyWordSpec with Matchers {

  "Variants" should {
    "be picked per decision tree" in {

      val b12 = Node("b", Seq("b1" -> Empty, "b2" -> Empty))
      val c12 = Node("c", Seq("c1" -> Empty, "c2" -> Empty))

      // the simplest case
      DT.fromVariants(Seq[Variant](
        Map("b" -> "b1"),
        Map("b" -> "b2")
      )) shouldBe Right(b12)

      // the all-combinations case: order is irrelevant
      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a1", "b" -> "b2"),
        Map("a" -> "a2", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> b12)))

      // choose a first, then b
      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a1", "b" -> "b2"),
        Map("a" -> "a2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> Empty)))

      // choose a first, then b or c
      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a1", "b" -> "b2"),
        Map("a" -> "a2", "c" -> "c1"),
        Map("a" -> "a2", "c" -> "c2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> c12)))

      // choose a first, then b or c
      DT.fromVariants(Seq[Variant](
        Map("b" -> "b1", "a" -> "a1"),
        Map("b" -> "b2", "a" -> "a1"),
        Map("c" -> "c1", "a" -> "a2"),
        Map("c" -> "c2", "a" -> "a2")
      )) shouldBe Right(Node("a", Seq("a1" -> b12, "a2" -> c12)))


      // a first, then b, then c
      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a2", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b2", "c" -> "c1"),
        Map("a" -> "a2", "b" -> "b2", "c" -> "c2"),
        Map("a" -> "a2", "b" -> "b3")
      )) shouldBe Right(Node("a", Seq("a1" -> Empty, "a2" -> Node("b", Seq("b1" -> Empty, "b2" -> c12, "b3" -> Empty)))))

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
      DT.fromVariants(Seq[Variant](
        Map.empty[String, String]
      )) shouldBe Right(Empty)

      DT.fromVariants(Seq.empty[Variant]) shouldBe a [Left[?, ?]]  // (debatable) Left since it does not allow to choose any variant
    }

    "be allowed single choices" in {
      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1")
      )) shouldBe Right(Node("a", Seq("a1" -> Empty)))

      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1", "b" -> "b1")
      )) should (be (Right(Node("a", Seq("a1" -> Node("b", Seq("b1" -> Empty)))))) or
                 be (Right(Node("b", Seq("b1" -> Node("a", Seq("a1" -> Empty)))))))
    }

    "not be allowed to be incomplete" in {
      // disallowed since incomplete
      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1"),
        Map("b" -> "b2"),
        Map("b" -> "b2")
      )) shouldBe a [Left[?, ?]]

      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b2")
      )) shouldBe a [Left[?, ?]]

      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a2", "b" -> "b1")
      )) shouldBe a [Left[?, ?]]

      DT.fromVariants(Seq[Variant](
        Map("a" -> "a1"),
        Map("a" -> "a1", "b" -> "b1"),
        Map("a" -> "a2")
      )) shouldBe a [Left[?, ?]]
    }
  }

}
