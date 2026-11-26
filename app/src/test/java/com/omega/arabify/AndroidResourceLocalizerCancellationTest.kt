package com.omega.arabify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidResourceLocalizerCancellationTest {
 @Test
 fun cancellationIsRethrownInsteadOfBeingCountedAsSkipped() = runBlocking {
  var skipped = 0
  val requestedCancellation = CancellationException("user cancelled localization")

  try {
   runRecoverableLocalizationStep(onFailure = { skipped++ }) {
    throw requestedCancellation
   }
   fail("CancellationException must escape the resource loop.")
  } catch (actual: CancellationException) {
   assertSame(requestedCancellation, actual)
  }

  assertEquals(0, skipped)
 }

 @Test
 fun ordinaryTranslationFailureStillCountsAsSkippedAndContinues() = runBlocking {
  var skipped = 0
  runRecoverableLocalizationStep(onFailure = { skipped++ }) {
   throw IllegalStateException("offline model unavailable")
  }

  var nextEntryProcessed = false
  runRecoverableLocalizationStep(onFailure = { skipped++ }) {
   nextEntryProcessed = true
  }

  assertEquals(1, skipped)
  assertTrue(nextEntryProcessed)
 }

 @Test
 fun successfulStepDoesNotIncrementSkippedCount() = runBlocking {
  var skipped = 0
  var translated = false

  runRecoverableLocalizationStep(onFailure = { skipped++ }) {
   translated = true
  }

  assertTrue(translated)
  assertEquals(0, skipped)
 }
}
