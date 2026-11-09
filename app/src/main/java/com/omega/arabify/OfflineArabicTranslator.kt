package com.omega.arabify

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

internal class OfflineArabicTranslator(context: Context) {
 private val translator = Translation.getClient(
  TranslatorOptions.Builder()
   .setSourceLanguage(TranslateLanguage.ENGLISH)
   .setTargetLanguage(TranslateLanguage.ARABIC)
   .build()
 )

 suspend fun prepareForOfflineUse() {
  val conditions = DownloadConditions.Builder().requireWifi().build()
  translator.downloadModelIfNeeded(conditions).await()
 }

 suspend fun translateEnglishToArabic(text: String): String {
  require(text.isNotBlank()) { "النص فارغ." }
  return translator.translate(text).await()
 }

 fun close() = translator.close()
}
