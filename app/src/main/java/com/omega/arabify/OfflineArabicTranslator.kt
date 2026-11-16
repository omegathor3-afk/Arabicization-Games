package com.omega.arabify

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

internal fun hasArabicTranslationModel(downloadedLanguages: Set<String>): Boolean =
 TranslateLanguage.ARABIC in downloadedLanguages

internal class OfflineArabicTranslator(context: Context) {
 private val translator = Translation.getClient(
  TranslatorOptions.Builder()
   .setSourceLanguage(TranslateLanguage.ENGLISH)
   .setTargetLanguage(TranslateLanguage.ARABIC)
   .build()
 )

 suspend fun prepareForOfflineUse(requireWifi: Boolean = true) {
  val conditionsBuilder = DownloadConditions.Builder()
  if (requireWifi) conditionsBuilder.requireWifi()
  translator.downloadModelIfNeeded(conditionsBuilder.build()).await()
 }

 suspend fun isArabicModelDownloaded(): Boolean {
  val downloadedLanguages = RemoteModelManager.getInstance()
   .getDownloadedModels(TranslateRemoteModel::class.java)
   .await()
   .map { it.language }
   .toSet()
  return hasArabicTranslationModel(downloadedLanguages)
 }

 suspend fun translateEnglishToArabic(text: String): String {
  require(text.isNotBlank()) { "النص فارغ." }
  return translator.translate(text).await()
 }

 fun close() = translator.close()
}
