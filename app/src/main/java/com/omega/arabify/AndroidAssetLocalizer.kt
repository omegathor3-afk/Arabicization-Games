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
    val lost=protected.tokens.indices.any{!result.contains("__ARABIFY_TOKEN_${it}__")}
    if(lost){skipped++;value}
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
  return text.any{it.isLetter()} && (text.contains(' ')||text.length>=5)
 }
}

internal object AndroidAssetLocalizer {
 private const val MAX_FILE_BYTES=512L*1024L
 private const val MAX_TOTAL_TRANSLATION_BYTES=4L*1024L*1024L
 private val supported=setOf("json","txt","properties","strings")
 private val allowedRoots=listOf("assets/","res/raw/","localization/","strings/")

 suspend fun localize(
  context:Context,inputApk:File,unsignedApk:File,signedApk:File,
  translator:OfflineArabicTranslator,onProgress:(Int,Int)->Unit={_,_->}
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
   while(entries.hasMoreElements()){
    val entry=entries.nextElement()
    if(entry.name.startsWith("lib/")&&entry.name.endsWith(".so",true))hasNativeLibraries=true
    val ext=entry.name.substringAfterLast('.',"").lowercase()
    if(!entry.isDirectory&&supported.contains(ext)&&allowedRoots.any{entry.name.startsWith(it)}&&entry.size in 1..MAX_FILE_BYTES){
     candidates+=entry
    }
   }
   if(hasNativeLibraries&&!extractNativeLibs){
    error("تحتوي اللعبة على مكتبات native غير مستخرجة. إعادة بناء APK تحتاج محاذاة ZIP خاصة قبل التثبيت؛ أوقفنا العملية لحماية الملف بدل إنشاء APK قد يفشل تثبيته.")
   }
   discovered=candidates.size
   candidates.forEachIndexed { index,entry ->
    val bytes=zip.getInputStream(entry).use{it.readBytes()}
    totalBytes+=bytes.size
    if(totalBytes<=MAX_TOTAL_TRANSLATION_BYTES){
     val original=bytes.toString(Charsets.UTF_8)
     val result=AssetTextTransformer.transform(entry.name,original){value->translator.translateEnglishToArabic(value)}
     if(result.translated>0){
      transformed[entry.name]=result.text.toByteArray(Charsets.UTF_8)
      translatedFiles++
     }
     translatedStrings+=result.translated
     skippedStrings+=result.skipped
    }else skippedStrings++
    onProgress(index+1,candidates.size)
   }

   require(translatedStrings>0){"لم يتم العثور على نصوص قابلة للترجمة في ملفات JSON/TXT/Properties/Strings المدعومة."}
   unsignedApk.parentFile?.mkdirs()
   ZipOutputStream(unsignedApk.outputStream().buffered()).use { out ->
    val all=zip.entries()
    while(all.hasMoreElements()){
     val entry=all.nextElement()
     if(isOldSignature(entry.name))continue
     val target=ZipEntry(entry.name).apply{
      time=entry.time
      comment=entry.comment
     }
     out.putNextEntry(target)
     if(!entry.isDirectory){
      val replacement=transformed[entry.name]
      if(replacement!=null)out.write(replacement)
      else zip.getInputStream(entry).use{it.copyTo(out)}
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
