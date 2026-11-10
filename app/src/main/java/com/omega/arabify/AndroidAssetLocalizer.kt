package com.omega.arabify

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class AssetLocalizationReport(
 val packageName: String?,
 val filesDiscovered: Int,
 val filesTranslated: Int,
 val stringsTranslated: Int,
 val stringsSkipped: Int,
 val outputApk: File
)

internal object AssetTextTransformer {
 data class Result(val text:String,val translated:Int,val skipped:Int)

 suspend fun transform(path:String, source:String, translate:suspend (String)->String):Result {
  var translated=0
  var skipped=0
  suspend fun tx(value:String):String {
   if(!looksTranslatable(value)){skipped++;return value}
   val protected=PlaceholderProtector.protect(value)
   return try {
    val result=translate(protected.text)
    val expectedTokens=protected.tokens.indices.map { "__ARABIFY_TOKEN_${it}__" }
    val quality=TranslationQualityGate.review(value,result,expectedTokens)
    if(!quality.accepted){skipped++;value}
    else {
     val restored=PlaceholderProtector.restore(result,protected.tokens)
     if(restored.isBlank()){skipped++;value}else{translated++;restored}
    }
   }catch(_:Exception){skipped++;value}
  }

  return when(path.substringAfterLast('.',"").lowercase()) {
   "json" -> try {
    val root=source.trim()
    val output=when {
     root.startsWith("{") -> transformObject(JSONObject(root),::tx).toString(2)
     root.startsWith("[") -> transformArray(JSONArray(root),::tx).toString(2)
     else -> source.also{skipped++}
    }
    Result(output,translated,skipped)
   }catch(_:Exception){Result(source,0,1)}
   "properties" -> {
    val output=mutableListOf<String>()
    for(line in source.split("\n")) {
     val trimmed=line.trimStart()
     if(trimmed.isEmpty()||trimmed.startsWith("#")||trimmed.startsWith("!")) output+=line
     else {
      val index=line.indexOfFirst{it=='='||it==':'}
      if(index<0){skipped++;output+=line}
      else {
       val prefix=line.substring(0,index+1)
       val value=line.substring(index+1)
       output+=prefix+tx(value)
      }
     }
    }
    Result(output.joinToString("\n"),translated,skipped)
   }
   "strings" -> {
    val pattern=Regex("^(\\s*\"(?:\\\\.|[^\"])*\"\\s*=\\s*\")(.*?)(\"\\s*;\\s*)$")
    val output=mutableListOf<String>()
    for(line in source.split("\n")) {
     val match=pattern.matchEntire(line)
     if(match==null){if(line.trim().isNotEmpty()&&!line.trim().startsWith("//"))skipped++;output+=line}
     else output+=match.groupValues[1]+tx(match.groupValues[2])+match.groupValues[3]
    }
    Result(output.joinToString("\n"),translated,skipped)
   }
   "txt" -> {
    val output=mutableListOf<String>()
    for(line in source.split("\n")) {
     output+=if(line.isBlank()||line.trimStart().startsWith("#"))line else tx(line)
    }
    Result(output.joinToString("\n"),translated,skipped)
   }
   else -> Result(source,0,1)
  }
 }

 private suspend fun transformObject(obj:JSONObject,tx:suspend(String)->String):JSONObject {
  val keys=obj.keys()
  val all=mutableListOf<String>()
  while(keys.hasNext())all+=keys.next()
  for(key in all){
   when(val value=obj.opt(key)){
    is String -> obj.put(key,tx(value))
    is JSONObject -> obj.put(key,transformObject(value,tx))
    is JSONArray -> obj.put(key,transformArray(value,tx))
   }
  }
  return obj
 }

 private suspend fun transformArray(array:JSONArray,tx:suspend(String)->String):JSONArray {
  for(i in 0 until array.length()){
   when(val value=array.opt(i)){
    is String -> array.put(i,tx(value))
    is JSONObject -> array.put(i,transformObject(value,tx))
    is JSONArray -> array.put(i,transformArray(value,tx))
   }
  }
  return array
 }

 private fun looksTranslatable(value:String):Boolean {
  val text=value.trim()
  if(text.length<3||text.length>1200)return false
  if(text.any{it in '\u0600'..'\u06FF'})return false
  if(text.startsWith("http://")||text.startsWith("https://")||text.contains("@@"))return false
  if(text.matches(Regex("[0-9\\s.,+\\-/%:]+")))return false
  if(text.matches(Regex("[A-Z0-9_./:-]{3,}")))return false
  if(text.contains("\\u0000"))return false
  return text.any{it.isLetter()} && (text.contains(' ')||text.length>=3)
 }
}

internal object AndroidAssetLocalizer {
 private const val MAX_FILE_BYTES=512L*1024L
 private const val MAX_TOTAL_TRANSLATION_BYTES=4L*1024L*1024L
 private const val MAX_CANDIDATE_FILES=2048
 private const val MAX_ARCHIVE_ENTRIES=100_000
 private const val MAX_SINGLE_REBUILT_ENTRY_BYTES=2L*1024L*1024L*1024L
 private const val MAX_TOTAL_REBUILT_BYTES=4L*1024L*1024L*1024L
 private val supported=setOf("json","txt","properties","strings")
 private val allowedRoots=listOf("assets/","res/raw/","localization/","strings/")

 internal fun isSupportedTextAsset(
  path:String,
  size:Long,
  allowedPathPrefixes:List<String> = allowedRoots
 ):Boolean {
  val safePath = normalizeArchivePath(path) ?: return false
  val safeRoots = allowedPathPrefixes.mapNotNull(::normalizeAllowedRoot)
  val extension=safePath.substringAfterLast('.', "").lowercase()
  return supported.contains(extension) &&
   safeRoots.any { safePath.startsWith(it) } &&
   size in 1..MAX_FILE_BYTES
 }

 /**
  * ZIP entry names must remain relative and may not contain traversal or
  * ambiguous empty path segments. Backslashes are normalized before checking
  * because archives from third-party tooling are not always consistent.
  */
 private fun normalizeArchivePath(path:String):String? {
  if(path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return null
  if(path.contains('\u0000')) return null
  val normalized=path.replace('\\','/')
  val segments=normalized.split('/')
  if(segments.any { it.isEmpty() || it=="." || it==".." }) return null
  if(segments.first().contains(':')) return null
  return segments.joinToString("/")
 }

 private fun normalizeAllowedRoot(root:String):String? {
  if(root.isBlank() || root.startsWith('/') || root.startsWith('\\')) return null
  val normalized=root.replace('\\','/').trimEnd('/')
  val segments=normalized.split('/')
  if(segments.any { it.isEmpty() || it=="." || it==".." }) return null
  if(segments.first().contains(':')) return null
  return "$normalized/"
 }

 suspend fun localize(
  context:Context,inputApk:File,unsignedApk:File,signedApk:File,
  translator:OfflineArabicTranslator,onProgress:(Int,Int)->Unit={_,_->},
  allowedPathPrefixes:List<String> = allowedRoots
 ):AssetLocalizationReport {
  require(inputApk.isFile){"ملف APK الأصلي غير موجود."}
  val archiveInfo=try{
   @Suppress("DEPRECATION")
   context.packageManager.getPackageArchiveInfo(inputApk.absolutePath,0)
  }catch(_:Exception){null}
  val packageName=archiveInfo?.packageName
  val extractNativeLibs=archiveInfo?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0 } ?: false
  var discovered=0
  var translatedFiles=0
  var translatedStrings=0
  var skippedStrings=0
  var totalBytes=0L
  val transformed=mutableMapOf<String,ByteArray>()

  ZipFile(inputApk).use { zip ->
   val entries=zip.entries()
   val candidates=mutableListOf<ZipEntry>()
   var hasNativeLibraries=false
   var archiveEntryCount=0
   while(entries.hasMoreElements()){
    val entry=entries.nextElement()
    archiveEntryCount++
    require(archiveEntryCount<=MAX_ARCHIVE_ENTRIES){"عدد عناصر APK يتجاوز حد إعادة البناء الآمن (100,000)."}
    if(entry.name.startsWith("lib/")&&entry.name.endsWith(".so",true))hasNativeLibraries=true
    if(!entry.isDirectory&&isSupportedTextAsset(entry.name,entry.size,allowedPathPrefixes)){
     candidates+=entry
     require(candidates.size<=MAX_CANDIDATE_FILES){"عدد ملفات النصوص المرشحة يتجاوز حد المعالجة الآمن (2,048)."}
    }
   }
   if(hasNativeLibraries&&!extractNativeLibs){
    error("تحتوي اللعبة على مكتبات native غير مستخرجة. إعادة بناء APK تحتاج محاذاة ZIP خاصة قبل التثبيت؛ أوقفنا العملية لحماية الملف بدل إنشاء APK قد يفشل تثبيته.")
   }
   discovered=candidates.size
   candidates.forEachIndexed { index,entry ->
    val remaining=MAX_TOTAL_TRANSLATION_BYTES-totalBytes
    if(remaining<=0L || entry.size>minOf(MAX_FILE_BYTES,remaining)){
     skippedStrings++
     onProgress(index+1,candidates.size)
     return@forEachIndexed
    }
    val bytes=zip.getInputStream(entry).use{
     BoundedArchiveInput.readAll(it,minOf(MAX_FILE_BYTES,remaining))
    }
    require(entry.size==bytes.size.toLong()){
     "حجم الملف الفعلي لا يطابق بيانات ZIP: ${entry.name}"
    }
    totalBytes+=bytes.size
    val original=bytes.toString(Charsets.UTF_8)
    val result=AssetTextTransformer.transform(entry.name,original){value->translator.translateEnglishToArabic(value)}
    if(result.translated>0){
     transformed[entry.name]=result.text.toByteArray(Charsets.UTF_8)
     translatedFiles++
    }
    translatedStrings+=result.translated
    skippedStrings+=result.skipped
    onProgress(index+1,candidates.size)
   }

   require(translatedStrings>0){"لم يتم العثور على نصوص قابلة للترجمة في ملفات JSON/TXT/Properties/Strings المدعومة."}
   unsignedApk.parentFile?.mkdirs()
   ZipOutputStream(unsignedApk.outputStream().buffered()).use { out ->
    val all=zip.entries()
    var copiedArchiveBytes=0L
    var writtenEntries=0
    while(all.hasMoreElements()){
     val entry=all.nextElement()
     writtenEntries++
     require(writtenEntries<=MAX_ARCHIVE_ENTRIES){"عدد عناصر APK تغير أثناء إعادة البناء."}
     if(isOldSignature(entry.name))continue
     val target=ZipEntry(entry.name).apply{
      time=entry.time
      comment=entry.comment
     }
     out.putNextEntry(target)
     if(!entry.isDirectory){
      val replacement=transformed[entry.name]
      val remaining=MAX_TOTAL_REBUILT_BYTES-copiedArchiveBytes
      require(remaining>=0L){"الحجم المفكوك للأرشيف تجاوز حد إعادة البناء الآمن."}
      val entryBudget=minOf(MAX_SINGLE_REBUILT_ENTRY_BYTES,remaining)
      if(replacement!=null){
       require(replacement.size.toLong()<=entryBudget){"النص المعرب تجاوز حد حجم الملف الآمن."}
       out.write(replacement)
       copiedArchiveBytes=Math.addExact(copiedArchiveBytes,replacement.size.toLong())
      } else {
       val copied=zip.getInputStream(entry).use{BoundedArchiveInput.copy(it,out,entryBudget)}
       require(entry.size<0L || copied==entry.size){
        "حجم عنصر ZIP الفعلي لا يطابق بياناته: ${entry.name}"
       }
       copiedArchiveBytes=Math.addExact(copiedArchiveBytes,copied)
      }
     }
     out.closeEntry()
    }
   }
  }
  ApkSigningService.signAndVerify(context,unsignedApk,signedApk)
  return AssetLocalizationReport(packageName,discovered,translatedFiles,translatedStrings,skippedStrings,signedApk)
 }

 private fun isOldSignature(path:String):Boolean {
  if(!path.startsWith("META-INF/",true))return false
  val name=path.substringAfterLast('/').uppercase()
  return name.endsWith(".RSA")||name.endsWith(".DSA")||name.endsWith(".EC")||
   name.endsWith(".SF")||name=="MANIFEST.MF"
 }
}
