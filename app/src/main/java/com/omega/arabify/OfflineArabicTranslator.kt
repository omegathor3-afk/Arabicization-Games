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
 private val translationMemory = TranslationMemory()

 /** Translation-memory counters for the activity report. */
 val translationMemoryStats: String
  get() = with(translationMemory) { "TM exact=$exactHits fuzzy=$fuzzyHits miss=$misses size=$size" }

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

 // Reverse direction exists for round-trip verification audits (CI evidence
 // paths), not for the user-facing pipeline.
 private val backTranslator = Translation.getClient(
  TranslatorOptions.Builder()
   .setSourceLanguage(TranslateLanguage.ARABIC)
   .setTargetLanguage(TranslateLanguage.ENGLISH)
   .build()
 )

 suspend fun prepareReverseForOfflineUse(requireWifi: Boolean = true) {
  val conditionsBuilder = DownloadConditions.Builder()
  if (requireWifi) conditionsBuilder.requireWifi()
  backTranslator.downloadModelIfNeeded(conditionsBuilder.build()).await()
 }

 suspend fun translateArabicToEnglish(text: String): String {
  require(text.isNotBlank()) { "النص فارغ." }
  return backTranslator.translate(text).await()
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
  val hit = translationMemory.lookup(text)
  if (hit.kind == TranslationMemory.MatchKind.EXACT) return hit.translation
  if (hit.kind == TranslationMemory.MatchKind.FUZZY &&
      translationMemory.placeholderSignature(text) == translationMemory.placeholderSignature(hit.source)) {
   return hit.translation
  }
  val result = translator.translate(text).await()
  translationMemory.add(text, result)
  return result
 }

 fun close() { translator.close(); backTranslator.close() }
}
