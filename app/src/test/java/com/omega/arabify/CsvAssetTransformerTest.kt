package com.omega.arabify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvAssetTransformerTest {
 @Test fun csvTranslatesCellsAndKeepsHeaderAndKeyColumn()=runBlocking {
  val source="key,en,notes\nstart_button,Start Game,ui\nexit_button,Exit Game,ui\n"
  val result=AssetTextTransformer.transform("assets/lang/game.csv",source){value->
   when(value){
    "Start Game"->"ابدأ اللعبة"
    "Exit Game"->"اخرج من اللعبة"
    else->error("unexpected translator input: $value")
   }
  }
  assertEquals("key,en,notes\nstart_button,ابدأ اللعبة,ui\nexit_button,اخرج من اللعبة,ui\n",result.text)
  assertEquals(2,result.translated)
 }

 @Test fun csvQuotesTranslationContainingComma()=runBlocking {
  val source="key,en\nwelcome,\"Hello, brave knight\"\n"
  val result=AssetTextTransformer.transform("assets/lang/game.csv",source){"أهلاً، أيها الفارس"}
  assertTrue(result.text.contains("welcome,\"أهلاً، أيها الفارس\""))
  assertEquals(1,result.translated)
  // Column count must stay consistent so the game parser does not break.
  val rows=AssetTextTransformer.parseCsv(result.text)
  assertEquals(2,rows.size)
  assertEquals(2,rows[1].size)
  assertEquals("أهلاً، أيها الفارس",rows[1][1].value)
 }

 @Test fun csvPreservesQuotedCellsAndEscapesInnerQuotes()=runBlocking {
  val source="\"He said \"\"hi\"\"\",Start Game\n"
  val result=AssetTextTransformer.transform("assets/lang/game.csv",source){value->
   if(value=="Start Game")"ابدأ اللعبة" else value
  }
  assertTrue(result.text.startsWith("\"He said \"\"hi\"\"\","))
  assertTrue(result.text.contains("ابدأ اللعبة"))
 }

 @Test fun csvWithoutHeaderTranslatesProseCellsOnly()=runBlocking {
  val source="42,The gate is locked.\n7,NUMERIC_CONSTANT\n"
  val seen=mutableListOf<String>()
  val result=AssetTextTransformer.transform("assets/data/levels.csv",source){value->
   seen+=value
   "البوابة مقفلة."
  }
  assertEquals(listOf("The gate is locked."),seen)
  assertEquals("42,البوابة مقفلة.\n7,NUMERIC_CONSTANT\n",result.text)
  assertEquals(1,result.translated)
 }

 @Test fun csvSkipsSnakeCaseIdentifiers()=runBlocking {
  val source="start_game_button,Press start to play\n"
  val seen=mutableListOf<String>()
  val result=AssetTextTransformer.transform("assets/data/ui.csv",source){value->
   seen+=value
   "اضغط للبدء"
  }
  assertEquals(listOf("Press start to play"),seen)
  assertTrue(result.text.startsWith("start_game_button,"))
  assertEquals(1,result.translated)
 }

 @Test fun csvPlaceholderSurvivesTranslation()=runBlocking {
  val source="key,en\nscore,Score: %d points\n"
  val result=AssetTextTransformer.transform("assets/lang/game.csv",source){"النقاط: __ARABIFY_TOKEN_0__"}
  assertTrue(result.text.contains("النقاط: %d"))
  assertEquals(1,result.translated)
 }

 @Test fun glossaryLabelIsTranslatedWithoutCallingTranslator()=runBlocking {
  val source="Cancel\n"
  val result=AssetTextTransformer.transform("assets/dialogue.txt",source){error("translator must not run for glossary hits")}
  assertEquals("إلغاء\n",result.text)
  assertEquals(1,result.translated)
  assertEquals(0,result.skipped)
 }

 @Test fun glossaryPreservesSurroundingWhitespace()=runBlocking {
  val result=AssetTextTransformer.transform("assets/dialogue.txt","Cancel "){error("translator must not run")}
  assertEquals("إلغاء ",result.text)
  assertEquals(1,result.translated)
 }

 @Test fun nonGlossaryProseStillUsesTranslator()=runBlocking {
  val seen=mutableListOf<String>()
  val result=AssetTextTransformer.transform("assets/dialogue.txt","Cancel the mission now"){value->
   seen+=value
   "ألغِ المهمة الآن"
  }
  assertEquals(listOf("Cancel the mission now"),seen)
  assertEquals("ألغِ المهمة الآن",result.text)
  assertEquals(1,result.translated)
 }

 @Test fun parseCsvHandlesCarriageReturnNewlineAndEmptyCells()=runBlocking {
  val rows=AssetTextTransformer.parseCsv("a,,c\r\nd,e,\r\n")
  assertEquals(2,rows.size)
  assertEquals(listOf("a","","c"),rows[0].map{it.value})
  assertEquals(listOf("d","e",""),rows[1].map{it.value})
  assertFalse(rows[0][0].quoted)
 }
}
