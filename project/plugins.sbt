addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.32")  // should ensure that assembly jar is stripped from nondeterminism
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.14.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
// addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")
