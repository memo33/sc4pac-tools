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

  def hasUnsafeEmbeddedScripts(ltext: LText): Boolean = {
    util.boundary {
      val m = embeddedLuaPattern.matcher(ltext.text)
      while (m.find()) {
        if (!isSafeLua(m.group(1))) {
          util.boundary.break(true)
        }
      }
      ltext.text.contains("sc4://advice/")
    }
  }

  enum Score { case Safe, UnsafeScript, UnsafeUnparseable }

  // entries outside domain are considered Safe
  val classify: PartialFunction[DbpfEntry, IO[DbpfException, Score]] = {
    case e if e.tgi.matches(Tgi.LText) =>
      ZIO.blocking(e.toBufferedEntry).flatMap { be =>
        if (isSoundRelated(be.content)) {
          ZIO.succeed(Score.UnsafeUnparseable)
        } else {
          for {
            ltext <- be.content.convertTo(LText)
          } yield if (hasUnsafeEmbeddedScripts(ltext)) Score.UnsafeScript else Score.Safe
        }
      }
    case e if e.tgi.matches(Tgi.Lua) =>
      ZIO.succeed(Score.UnsafeScript)
  }

  def collectUnsafeTgis(path: os.Path): IO[java.io.IOException, Seq[Tgi]] = {
    for {
      dbpf       <- DbpfFile.read(path.toIO)
      scoreTasks =  dbpf.entries.collect { case e if classify.isDefinedAt(e) =>
                      classify(e).orElseSucceed(Score.UnsafeUnparseable).map(e.tgi -> _)
                    }: Seq[zio.UIO[(Tgi, Score)]]
      unsafeTgis <- ZIO.collectAllWithPar(scoreTasks) { case (tgi, score) if score != Score.Safe => tgi }
    } yield unsafeTgis
  }

}
