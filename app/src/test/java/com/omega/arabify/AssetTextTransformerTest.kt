package com.omega.arabify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetTextTransformerTest {
 @Test fun textFileTranslatesLinesAndKeepsCommentsAndPlaceholders()=runBlocking {
  val source="# dialogue\nThe door is open, {player}.\n"
  val result=AssetTextTransformer.transform("assets/dialogue.txt",source){it.replace("The door is open","الباب مفتوح")}
  assertTrue(result.text.contains("# dialogue"))
  assertTrue(result.text.contains("الباب مفتوح, {player}."))
  assertEquals(1,result.translated)
 }

 @Test fun propertiesKeepsKeysAndComments()=runBlocking {
  val source="# menu\nmenu.start=Start Game\nmenu.exit=Exit"
  val result=AssetTextTransformer.transform("assets/lang.properties",source){ "AR:$it" }
  assertTrue(result.text.contains("menu.start=AR:Start Game"))
  assertTrue(result.text.contains("# menu"))
  assertEquals(2,result.translated)
 }

 @Test fun appleStringsKeepsKeysAndSyntax()=runBlocking {
  val source="\"start_button\" = \"Start Game\";\n"
  val result=AssetTextTransformer.transform("assets/en.strings",source){ "ابدأ اللعبة" }
  assertEquals("\"start_button\" = \"ابدأ اللعبة\";",result.text.trim())
  assertEquals(1,result.translated)
 }

 @Test fun lostPlaceholderRejectsTranslation()=runBlocking {
  val source="Hello {player}"
  val result=AssetTextTransformer.transform("assets/dialogue.txt",source){"مرحبا"}
  assertEquals(source,result.text)
  assertEquals(0,result.translated)
  assertEquals(1,result.skipped)
 }
}
