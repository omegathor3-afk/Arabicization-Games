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
}
