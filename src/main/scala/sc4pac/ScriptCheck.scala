package io.github.memo33
package sc4pac

import zio.{IO, ZIO}
import scdbpf.{LText, Tgi, DbpfType, DbpfEntry, DbpfException, DbpfFile}
import sc4pac.ScdbpfZio.zioExceptionHandler

object ScriptCheck {

  private def fromString(s: String): collection.immutable.ArraySeq[Byte] = s.to(collection.immutable.ArraySeq).map(_.toByte)

  val XA = fromString("XA\u0000\u0000")  // WAV
  val XAI = fromString("XAI\u0000")
  val XAJ = fromString("XAJ\u0000")
  val RIFF = fromString("RIFF")  // "RIFF&\u00f8\u0000\u0000WAVEfmt "
  val FSC = fromString("version 7")  // LEV (TODO not a good indicator, as the file is plain text)
  val HIT = fromString("HIT!")

  def isSoundRelated(raw: DbpfType): Boolean = {
    val data = raw.unsafeArray
    (data.startsWith(XA)
      || data.startsWith(XAI)
      || data.startsWith(XAJ)
      || data.startsWith(RIFF)
      || data.startsWith(FSC)
      || data.startsWith(HIT))
  }

  val embeddedLuaPattern = java.util.regex.Pattern.compile("""#(?<!##)(?:##)*(?!#)(.+?(?<!#)(?:##)*)#(?!#)""", java.util.regex.Pattern.DOTALL)
  val safeLuaPattern = java.util.regex.Pattern.compile("""^[\s\w:.]*$""", java.util.regex.Pattern.DOTALL)
  val literalOnlyLuaPattern = java.util.regex.Pattern.compile("""^\s*(?:['"]|\[\[)[^'"\]]*(?:['"]|\]\])\s*$""")

  def isSafeLua(lua: String): Boolean = {
    if (lua.contains("link_id")) false
    else safeLuaPattern.matcher(lua).matches() || literalOnlyLuaPattern.matcher(lua).matches()
  }

  // Return the first unsafe embedded Lua script, if any.
  def findUnsafeEmbeddedScript(ltext: LText): Option[String] = {
    util.boundary {
      val m = embeddedLuaPattern.matcher(ltext.text)
      while (m.find()) {
        val token = m.group(1)
        if (!isSafeLua(token)) {
          util.boundary.break(Some(token))
        }
      }
      val link_token = "sc4://advice/"
      Option.when(ltext.text.contains(link_token))(link_token)
    }
  }

  enum Score { case Safe, UnsafeScript, UnsafeUnparseable }

  // Entries outside domain are considered Safe.
  // Returns an optional reason for debugging (in case an unsafe embedded Lua in LText was found).
  val classify: PartialFunction[DbpfEntry, IO[DbpfException, (Score, Option[String])]] = {
    case e if e.tgi.matches(Tgi.LText) =>
      ZIO.blocking(e.toBufferedEntry).flatMap { be =>
        if (isSoundRelated(be.content)) {
          ZIO.succeed((Score.UnsafeUnparseable, None))
        } else {
          for {
            ltext <- be.content.convertTo(LText)
            reason = findUnsafeEmbeddedScript(ltext)
          } yield ((if (reason.isDefined) Score.UnsafeScript else Score.Safe), reason)
        }
      }
    case e if e.tgi.matches(Tgi.Lua) =>
      ZIO.succeed((Score.UnsafeScript, None))
  }

  def collectUnsafeTgis(path: os.Path): IO[java.io.IOException, Seq[(Tgi, Option[String])]] = {
    for {
      dbpf       <- ZIO.blocking(DbpfFile.read(path.toIO))
      scoreTasks =  dbpf.entries.collect { case e if classify.isDefinedAt(e) =>
                      classify(e).orElseSucceed((Score.UnsafeUnparseable, None)).map(e.tgi -> _)
                    }: Seq[zio.UIO[(Tgi, (Score, Option[String]))]]
      unsafeTgis <- ZIO.collectAllWithPar(scoreTasks) { case (tgi, (score, reason)) if score != Score.Safe => (tgi, reason) }
    } yield unsafeTgis
  }

}
