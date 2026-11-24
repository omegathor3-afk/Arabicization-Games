package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderProtectorTest {
 @Test fun protectsAndRestoresGameTokens() {
  val source = "Hello {player}, score %1\$d and \${coins} <b>now</b>\\n"
  val protected = PlaceholderProtector.protect(source)
  assertEquals(source, PlaceholderProtector.restore(protected.text, protected))
  assertEquals(6, protected.tokens.size)
 }
 @Test fun protectsSquareBracketMarkup() {
  val source = "[red]Warning:[] core [#ff8800]under attack[] {0}"
  val protected = PlaceholderProtector.protect(source)
  assertEquals(source, PlaceholderProtector.restore(protected.text, protected))
  assertEquals(5, protected.tokens.size)
  assertFalse(protected.text.contains("[red]"))
 }
 @Test fun leavesNormalTextAlone() {
  val source = "Welcome to the island"
  val protected = PlaceholderProtector.protect(source)
  assertEquals(source, protected.text)
  assertEquals(emptyList<String>(), protected.tokens)
 }

 @Test fun protectsPrintfWidthPrecisionAndLiteralPercent() {
  val dollar = 36.toChar()
  val source = "Score %1" + dollar + "02d, accuracy %.2f, player %1" + dollar + "s, 100% complete, %% ready"
  val protected = PlaceholderProtector.protect(source)
  val expected = listOf("%1" + dollar + "02d", "%.2f", "%1" + dollar + "s", "%%")
  assertEquals(expected, protected.tokens)
  assertEquals(source, PlaceholderProtector.restore(protected.text, protected))
 }
 @Test fun avoidsCollisionsWithLiteralLegacyMarkerText() {
  val source = "Literal __ARABIFY_TOKEN_0__ and {player}"
  val protected = PlaceholderProtector.protect(source)
  assertTrue(protected.text.contains("__ARABIFY_TOKEN_0__"))
  assertEquals(1, protected.markers.size)
  assertFalse(source.contains(protected.markers.single()))
  assertEquals(source, PlaceholderProtector.restore(protected.text, protected))
 }

}
