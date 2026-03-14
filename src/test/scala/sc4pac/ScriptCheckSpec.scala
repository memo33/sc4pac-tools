package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.github.memo33.scdbpf.LText

class ScriptCheckSpec extends AnyWordSpec with Matchers {

  def getLuaEmbeds(text: String): Seq[String] =
    Seq.unfold(ScriptCheck.embeddedLuaPattern.matcher(text))
      (m => Option.when(m.find())(m.group(1), m))

  "ScriptCheck" should {
    "correctly detect embedded Lua in LText" in {
      getLuaEmbeds("a ## b").shouldBe(Seq.empty)
      getLuaEmbeds("a # 40 + 2 # c").shouldBe(Seq(" 40 + 2 "))
      getLuaEmbeds("#40 + 2#").shouldBe(Seq("40 + 2"))
      getLuaEmbeds("a # 1 # b # 2 # c").shouldBe(Seq(" 1 ", " 2 "))
      getLuaEmbeds("a # 1 # b # 2").shouldBe(Seq(" 1 "))
      getLuaEmbeds("a ## b #1# b ## c #2# d ## e").shouldBe(Seq("1", "2"))
      getLuaEmbeds("a #1 + 2 ## 3 + 4# b ## c #5 + 6").shouldBe(Seq("1 + 2 ## 3 + 4"))
      getLuaEmbeds("#(40+\n2)#").shouldBe(Seq("(40+\n2)"))
      getLuaEmbeds("#(40+\r\n2)#").shouldBe(Seq("(40+\r\n2)"))
      getLuaEmbeds("##40 + 2##").shouldBe(Seq.empty)
      getLuaEmbeds("\\#40 + 2\\#").shouldBe(Seq("40 + 2\\"))
      getLuaEmbeds("#40 + 2 ## 40 + 3#").shouldBe(Seq("40 + 2 ## 40 + 3"))
      getLuaEmbeds("#40 + 2").shouldBe(Seq.empty)
      getLuaEmbeds("# 'ab#cd' #").shouldBe(Seq(" 'ab"))
      getLuaEmbeds("# 'ab##cd' #").shouldBe(Seq(" 'ab##cd' "))
      getLuaEmbeds("# 'ab\\#cd' #").shouldBe(Seq(" 'ab\\"))
      getLuaEmbeds("###40 + 2###abc").shouldBe(Seq("40 + 2##"))
      getLuaEmbeds("###40 + 2#abc").shouldBe(Seq("40 + 2"))
      getLuaEmbeds("### 40 + 2 ### 40 + 3 # 40 + 4 #").shouldBe(Seq(" 40 + 2 ##", " 40 + 4 "))
      getLuaEmbeds("### 40 + 2 ## 40 + 3 # 40 + 4 #").shouldBe(Seq(" 40 + 2 ## 40 + 3 "))
      getLuaEmbeds("### 40 + 2 ## 1 ### 40 + 3 # 40 + 4 #").shouldBe(Seq(" 40 + 2 ## 1 ##", " 40 + 4 "))
      getLuaEmbeds("####40 + 2####").shouldBe(Seq.empty)
      getLuaEmbeds("####40 + 2#####5#").shouldBe(Seq("5"))
      getLuaEmbeds("#40 + 2####40 + 3###").shouldBe(Seq("40 + 2####40 + 3##"))
    }

    "not treat unsafe Lua as safe" in {
      Seq(
        "link_id",
        " link_id ",
        "(link_id)",
        "sit_link_id",
        "functioncall()",
        "functioncall(42)",
        "functioncall'xy'",
        "functioncall 'xy'",
        "functioncall\"xy\"",
        "functioncall \"xy\"",
        "functioncall [[xy]]",
        "functioncall{x,y}",
        "x = 42",
        "[abc]",
        """ "abc" .. "def" """,  // not supported
        "1 + 2 + 3",  // not supported
      ).foreach(s => withClue(s) {
        ScriptCheck.isSafeLua(s).shouldBe(false)
      })
    }

    "only treat known-as-safe Lua as safe" in {
      Seq(
        "tuning_constants.MAYOR_HOUSE_POP",
        "city",
        " City ",
        "m:49cac341",
        "d:27812852",
        "game.g_arrest_count",
        "game . g_date",
        "building_initial_cost",
        """ "abc" """,
        """ "\n" """,
        """ '\n' """,
        """[[abc]]""",
      ).foreach(s => withClue(s) {
        ScriptCheck.isSafeLua(s).shouldBe(true)
      })
    }

    "not allow link_id injection" in {
      Seq(
        "#link_id#game.tool_plop_building(building_tool_types.MAYORS_STATUE4);game.expire_advice()",
        "#link_id#game..",
        "sc4://advice/000..",
      ).foreach(s => withClue(s) {
        ScriptCheck.findUnsafeEmbeddedScript(LText(s)).shouldBe(Symbol("isDefined"))
      })
    }
  }
}
