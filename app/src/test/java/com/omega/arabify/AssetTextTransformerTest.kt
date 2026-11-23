package com.omega.arabify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetTextTransformerTest {
 @Test fun textFileTranslatesLinesAndKeepsCommentsAndPlaceholders()=runBlocking {
  val source="# dialogue\nThe door is open, {player}.\n"
  val result=AssetTextTransformer.transform("assets/dialogue.txt",source){ "الباب مفتوح، __ARABIFY_TOKEN_0__." }
  assertTrue(result.text.contains("# dialogue"))
  assertTrue(result.text.contains("الباب مفتوح، {player}."))
  assertEquals(1,result.translated)
 }

 @Test fun jsonTranslatesValuesKeepsKeysAndRestoresPlaceholders()=runBlocking {
  val source="""{"menu":"Start Game","dialogue":{"welcome":"Hello {player}","url":"https://example.com"}}"""
  val result=AssetTextTransformer.transform("assets/localization/en.json",source) {
   if(it=="Start Game") "ابدأ اللعبة" else "مرحباً __ARABIFY_TOKEN_0__"
  }
  assertTrue(result.text.contains("\"menu\": \"ابدأ اللعبة\""))
  assertTrue(result.text.contains("مرحباً {player}"))
  assertTrue(result.text.contains("https://example.com"))
  assertEquals(2,result.translated)
 }

 @Test fun propertiesKeepsKeysAndComments()=runBlocking {
  val source="# menu\nmenu.start=Start Game\nmenu.exit=Exit"
  val result=AssetTextTransformer.transform("assets/lang.properties",source){ "ابدأ" }
  assertTrue(result.text.contains("menu.start=ابدأ"))
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
 @Test fun duplicatedPlaceholderRejectsTranslation()=runBlocking {
  val source = "Hello {player}"
  val token = "__ARABIFY_TOKEN_0__"
  val result = AssetTextTransformer.transform("assets/dialogue.txt",source) { "مرحبا $token $token" }
  assertEquals(source,result.text)
  assertEquals(0,result.translated)
  assertEquals(1,result.skipped)
 }

 @Test fun nulCharactersArePreservedAndNeverSentToTranslator()=runBlocking {
  val source="Start\u0000Game"
  var translatorCalled=false
  val result=AssetTextTransformer.transform("assets/dialogue.txt",source){
   translatorCalled=true
   "ابدأ اللعبة"
  }
  assertFalse(translatorCalled)
  assertEquals(source,result.text)
  assertEquals(0,result.translated)
  assertEquals(1,result.skipped)
 }


 @Test fun respectsTranslationCandidateLimitAndLeavesRemainingSourceUnchanged()=runBlocking {
  val source="First line\nSecond line"
  var calls=0
  val result=AssetTextTransformer.transform(
   path="assets/limited.txt",
   source=source,
   maxTranslationCandidates=1
  ) {
   calls++
   "سطر مترجم"
  }
  assertEquals(1,calls)
  assertEquals("سطر مترجم\nSecond line",result.text)
  assertEquals(1,result.translated)
  assertEquals(1,result.skipped)
 }

 @Test fun jsonTranslationPropagatesCoroutineCancellationInsteadOfSkippingIt() {
  val observed=runCatching {
   runBlocking {
    AssetTextTransformer.transform("assets/strings.json","""{"label":"Welcome to the castle"}""") {
     throw CancellationException("cancel translation")
    }
   }
  }.exceptionOrNull()
  assertTrue(observed is CancellationException)
 }

 @Test fun preservesLiteralLegacyMarkerTextWhenAnotherPlaceholderIsPresent()=runBlocking {
  val source = "Welcome __ARABIFY_TOKEN_0__ {player}"
  val result = AssetTextTransformer.transform("assets/dialogue.txt", source) { value ->
   value.replace("Welcome", "أهلًا")
  }
  assertEquals("أهلًا __ARABIFY_TOKEN_0__ {player}", result.text)
  assertEquals(1, result.translated)
  assertEquals(0, result.skipped)
 }

}
