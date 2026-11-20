package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderProtectorTest {
 @Test fun protectsAndRestoresGameTokens() {
  val source = "Hello {player}, score %1\$d and \${coins} <b>now</b>\\n"
  val protected = PlaceholderProtector.protect(source)
  assertEquals(source, PlaceholderProtector.restore(protected.text, protected.tokens))
  assertEquals(6, protected.tokens.size)
 }
 @Test fun leavesNormalTextAlone() {
  val source = "Welcome to the island"
  val protected = PlaceholderProtector.protect(source)
  assertEquals(source, protected.text)
  assertEquals(emptyList<String>(), protected.tokens)
 }

 @Test fun protectsPrintfWidthPrecisionAndLiteralPercent() {
  val source = "Score %1" + '
 + "02d, accuracy %.2f, player %1" + '
 + "s, 100% complete, %% ready"
  val protected = PlaceholderProtector.protect(source)
  val expected = listOf("%1" + '
 + "02d", "%.2f", "%1" + '
 + "s", "%%")
  assertEquals(expected, protected.tokens)
  assertEquals(source, PlaceholderProtector.restore(protected.text, protected.tokens))
 }
}
