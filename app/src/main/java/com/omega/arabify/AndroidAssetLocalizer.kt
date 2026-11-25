package com.omega.arabify

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

internal data class AssetLocalizationReport(
 val packageName: String?,
 val filesDiscovered: Int,
 val filesTranslated: Int,
 val stringsTranslated: Int,
 val stringsSkipped: Int,
 val outputApk: File,
 val bundleFilesTranslated: Int? = null,
 val bundleStringsTranslated: Int? = null,
 val fitWarnings: Int = 0,
 val fitWarningSamples: List<String> = emptyList()
)

internal object AssetTextTransformer {

 // Full-coverage phrase composition: strings built entirely from reviewed
 // glossary phrases never reach the MT engine.
 private val phraseTermBase by lazy { PhraseTermBase(ArabicUiGlossary.terms) }

 data class Result(val text:String,val translated:Int,val skipped:Int,val skipReasons:Map<String,Int> = emptyMap(),val repaired:Int = 0,val repairReasons:Map<String,Int> = emptyMap(),val fitWarnings:Int = 0,val fitWarningSamples:List<String> = emptyList())

 suspend fun transform(
  path:String,
  source:String,
  translate:suspend (String)->String
 ):Result = transform(path,source,Int.MAX_VALUE,translate)

  // ML Kit tends to lowercase the marker or inject spaces inside it; map any
  // marker-shaped remnant back to the exact marker the gate expects.
  val mangledMarkerPattern=Regex("(?i)_+\\s*arabify\\s*_?\\s*token\\s*_?\\s*(\\d+)\\s*_+")
  fun recoverMangledMarkers(translated:String,protected:ProtectedText):String {
   var out=translated
   for(match in mangledMarkerPattern.findAll(translated)){
    val index=match.groupValues[1].toIntOrNull() ?: continue
    val marker=protected.markers.getOrNull(index) ?: continue
    out=out.replace(match.value,marker)
   }
   return out
  }

  // Translate the marker-free segments one by one and reassemble with the
  // markers in their original order. Returns null when a segment fails.
  suspend fun translateSegmented(protected:ProtectedText,translate:suspend (String)->String):String? {
   var remaining=protected.text
   val parts=mutableListOf<String>()
   for(marker in protected.markers){
    val at=remaining.indexOf(marker)
    if(at<0) return null
    parts+=remaining.substring(0,at)
    remaining=remaining.substring(at+marker.length)
   }
   parts+=remaining
   val out=StringBuilder()
   for(i in parts.indices){
    val segment=parts[i]
    // Keep the segment's boundary whitespace: translators often collapse it,
    // but it is structural ("Collect %s" must not become "اجمع%s").
    val translated=if(segment.isBlank()) segment else try{
     val core=translate(segment).trim()
     segment.takeWhile{it.isWhitespace()}+core+segment.takeLastWhile{it.isWhitespace()}
    }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){return null}
    out.append(translated)
    if(i<protected.markers.size) out.append(protected.markers[i])
   }
   return out.toString()
  }

 /**
  * Translate a single value through the standard pipeline (translatability
  * check, reviewed glossary, placeholder protection, quality gate) without
  * file-level bookkeeping. Used by game-native locale slot writers that
  * build new language files line by line.
  */
 suspend fun transformValue(
  value:String,
  translate:suspend (String)->String
 ):SingleResult {
  if(!looksTranslatable(value))return SingleResult(value,false,"not_translatable")
  val glossaryHit=ArabicUiGlossary.translateExact(value)
  if(glossaryHit!=null){
   val prefix=value.takeWhile{it.isWhitespace()}
   val suffix=value.takeLastWhile{it.isWhitespace()}
   return SingleResult(prefix+glossaryHit+suffix,true,fitWarning=fitWarningFor(value,glossaryHit))
  }
  val phraseHit=phraseTermBase.compose(value.trim())
  if(phraseHit!=null){
   val prefix=value.takeWhile{it.isWhitespace()}
   val suffix=value.takeLastWhile{it.isWhitespace()}
   return SingleResult(prefix+phraseHit.text+suffix,true,null,"phrase_term_base",fitWarningFor(value,phraseHit.text))
  }
  // Never feed non-English source to the EN->AR engine: a string that is
  // actually French/CJK/etc. would produce garbage attributed to us. Arabic
  // source is already rejected by looksTranslatable above; short English
  // strings (OK, No) still pass through.
  if(SourceLanguageGuard.classify(value)==SourceLanguageGuard.Verdict.NOT_ENGLISH){
   return SingleResult(value,false,"source_language:not_english")
  }
  val protected=PlaceholderProtector.protect(value)
  return try {
   var result=translate(protected.text)
   var quality=TranslationQualityGate.review(value,result,protected.markers)
   var repair:String?=null
   if(!quality.accepted && quality.reason=="placeholder_lost" && protected.markers.isNotEmpty()){
    val recovered=recoverMangledMarkers(result,protected)
    if(recovered!=result){
     result=recovered
     quality=TranslationQualityGate.review(value,result,protected.markers)
     if(quality.accepted)repair="marker_recovered"
    }
   }
   if(!quality.accepted && (quality.reason=="placeholder_lost" || quality.reason=="placeholder_order_changed") && protected.markers.isNotEmpty()){
    val rebuilt=translateSegmented(protected,translate)
    if(rebuilt!=null){
     val reQuality=TranslationQualityGate.review(value,rebuilt,protected.markers)
     if(reQuality.accepted){result=rebuilt;quality=reQuality;repair="segmented_placeholders"}
    }
   }
   if(!quality.accepted)SingleResult(value,false,"quality_gate:"+quality.reason)
   else {
    val restored=PlaceholderProtector.restore(result,protected)
    if(restored.isBlank())SingleResult(value,false,"blank_after_restore")else SingleResult(restored,true,null,repair,fitWarningFor(value,restored))
   }
  }catch(cancelled:CancellationException){throw cancelled}
  catch(_:Exception){SingleResult(value,false,"translator_error")}
 }

 data class SingleResult(val text:String,val translated:Boolean,val reason:String? = null,val repair:String? = null,val fitWarning:String? = null)

 internal fun fitWarningFor(source:String,translation:String):String? {
  val est=TextFitEstimator.estimate(source.trim(),translation.trim())
  if(!est.overflowRisk)return null
  return source.trim().take(40)+" -> "+translation.trim().take(40)+" ("+"%.2f".format(est.widthRatio)+"x)"
 }

 suspend fun transform(
  path:String,
  source:String,
  maxTranslationCandidates:Int,
  translate:suspend (String)->String
 ):Result {
  require(maxTranslationCandidates>=0) { "حد الترجمات غير صالح." }
  var translated=0
  var skipped=0
  var translationCandidates=0
  var repaired=0
  val skipReasons=mutableMapOf<String,Int>()
  val repairReasons=mutableMapOf<String,Int>()
  fun countSkip(reason:String){skipReasons[reason]=(skipReasons[reason] ?: 0)+1}
  fun countRepair(reason:String){repairReasons[reason]=(repairReasons[reason] ?: 0)+1}
  var fitWarnings=0
  val fitWarningSamples=mutableListOf<String>()
  fun countFit(source:String,out:String){
   val warning=fitWarningFor(source,out)
   if(warning!=null){fitWarnings++;if(fitWarningSamples.size<10)fitWarningSamples+=warning}
  }
  suspend fun tx(value:String):String {
   if(!looksTranslatable(value)){skipped++;countSkip("not_translatable");return value}
   if(SourceLanguageGuard.classify(value)==SourceLanguageGuard.Verdict.NOT_ENGLISH){skipped++;countSkip("source_language:not_english");return value}
   val glossaryHit=ArabicUiGlossary.translateExact(value)
   if(glossaryHit!=null){
    translated++
    countFit(value,glossaryHit)
    val prefix=value.takeWhile{it.isWhitespace()}
    val suffix=value.takeLastWhile{it.isWhitespace()}
    return prefix+glossaryHit+suffix
   }
   val phraseHit=phraseTermBase.compose(value.trim())
   if(phraseHit!=null){
    translated++
    countRepair("phrase_term_base")
    countFit(value,phraseHit.text)
    val prefix=value.takeWhile{it.isWhitespace()}
    val suffix=value.takeLastWhile{it.isWhitespace()}
    return prefix+phraseHit.text+suffix
   }
   if(translationCandidates>=maxTranslationCandidates){skipped++;countSkip("candidate_cap");return value}
   translationCandidates++
   val protected=PlaceholderProtector.protect(value)
   return try {
    var result=translate(protected.text)
    val expectedTokens=protected.markers
    var quality=TranslationQualityGate.review(value,result,expectedTokens)
    // Repair instead of reject, tier 1: ML Kit often mangles the marker text
    // itself (lowercases it or inserts spaces). Recover mangled markers in
    // place before giving up on the translation.
    if(!quality.accepted && quality.reason=="placeholder_lost" && expectedTokens.isNotEmpty()){
     val recovered=recoverMangledMarkers(result,protected)
     if(recovered!=result){result=recovered;quality=TranslationQualityGate.review(value,result,expectedTokens)}
     if(quality.accepted){repaired++;countRepair("marker_recovered")}
    }
    // Tier 2: split the protected text at the markers, translate each segment
    // on its own, and reassemble with the markers back in their original
    // positions - placeholder preservation by construction.
    if(!quality.accepted && (quality.reason=="placeholder_lost" || quality.reason=="placeholder_order_changed") && expectedTokens.isNotEmpty()){
     val rebuilt=translateSegmented(protected,translate)
     if(rebuilt!=null){
      val reQuality=TranslationQualityGate.review(value,rebuilt,expectedTokens)
      if(reQuality.accepted){result=rebuilt;quality=reQuality;repaired++;countRepair("segmented_placeholders")}
     }
    }
    if(!quality.accepted){skipped++;countSkip("quality_gate:"+quality.reason);value}
    else {
     val restored=PlaceholderProtector.restore(result,protected)
     if(restored.isBlank()){skipped++;countSkip("blank_after_restore");value}else{translated++;countFit(value,restored);restored}
    }
   }catch(cancelled:CancellationException){throw cancelled}
   catch(_:Exception){skipped++;countSkip("translator_error");value}
  }

  return when(path.substringAfterLast('.',"").lowercase()) {
   "json" -> try {
    val root=source.trim()
    val output=when {
     root.startsWith("{") -> transformObject(JSONObject(root),::tx).toString(2)
     root.startsWith("[") -> transformArray(JSONArray(root),::tx).toString(2)
     else -> source.also{skipped++;countSkip("unsupported_json_root")}
    }
    Result(output,translated,skipped,skipReasons,repaired,repairReasons,fitWarnings,fitWarningSamples)
   }catch(cancelled:CancellationException){throw cancelled}
   catch(_:Exception){Result(source,0,1,mapOf("unparseable" to 1))}
   "properties" -> {
    val output=mutableListOf<String>()
    for(line in source.split("\n")) {
     val trimmed=line.trimStart()
     if(trimmed.isEmpty()||trimmed.startsWith("#")||trimmed.startsWith("!")) output+=line
     else {
      val index=line.indexOfFirst{it=='='||it==':'}
      if(index<0){skipped++;countSkip("malformed_line");output+=line}
      else {
       val prefix=line.substring(0,index+1)
       val value=line.substring(index+1)
       output+=prefix+tx(value)
      }
     }
    }
    Result(output.joinToString("\n"),translated,skipped,skipReasons,repaired,repairReasons,fitWarnings,fitWarningSamples)
   }
   "csv" -> {
    val output=transformCsv(source,::tx){skipped++;countSkip("csv_structure")}
    Result(output,translated,skipped,skipReasons,repaired,repairReasons,fitWarnings,fitWarningSamples)
   }
   "strings" -> {
    val pattern=Regex("^(\\s*\"(?:\\\\.|[^\"])*\"\\s*=\\s*\")(.*?)(\"\\s*;\\s*)$")
    val output=mutableListOf<String>()
    for(line in source.split("\n")) {
     val match=pattern.matchEntire(line)
     if(match==null){if(line.trim().isNotEmpty()&&!line.trim().startsWith("//")){skipped++;countSkip("malformed_line")};output+=line}
     else output+=match.groupValues[1]+tx(match.groupValues[2])+match.groupValues[3]
    }
    Result(output.joinToString("\n"),translated,skipped,skipReasons,repaired,repairReasons,fitWarnings,fitWarningSamples)
   }
   "txt" -> {
    val output=mutableListOf<String>()
    for(line in source.split("\n")) {
     output+=if(line.isBlank()||line.trimStart().startsWith("#"))line else tx(line)
    }
    Result(output.joinToString("\n"),translated,skipped,skipReasons,repaired,repairReasons,fitWarnings,fitWarningSamples)
   }
   else -> Result(source,0,1,mapOf("unsupported_extension" to 1))
  }
 }

 private val csvHeaderNames=setOf(
  "key","id","keys","identifier","code","text","value","string","source","target",
  "translation","en","eng","english","ar","arabic","default","loc","localization"
 )
 private val csvKeyColumnNames=setOf("key","id","keys","identifier","code")
 private val csvHeaderCell=Regex("[A-Za-z][A-Za-z0-9_ ]{0,31}")
 private val csvSnakeIdentifier=Regex("[a-z0-9]+(?:_[a-z0-9]+)+")

 internal data class CsvCell(val start:Int,val end:Int,val quoted:Boolean,val value:String)

 /** RFC4180-style CSV reader that records source spans so untouched content is rebuilt byte-identically. */
 internal fun parseCsv(source:String):List<List<CsvCell>> {
  val rows=mutableListOf<MutableList<CsvCell>>()
  var row=mutableListOf<CsvCell>()
  var i=0
  val n=source.length
  while(i<n){
   val start=i
   var quoted=false
   val value:String
   if(source[i]=='"'){
    quoted=true
    i++
    val sb=StringBuilder()
    while(i<n){
     val c=source[i]
     if(c=='"'){
      if(i+1<n&&source[i+1]=='"'){sb.append('"');i+=2}
      else{i++;break}
     }else{sb.append(c);i++}
    }
    value=sb.toString()
   }else{
    val sb=StringBuilder()
    while(i<n&&source[i]!=','&&source[i]!='\n'&&source[i]!='\r'){sb.append(source[i]);i++}
    value=sb.toString()
   }
   row+=CsvCell(start,i,quoted,value)
   if(i<n&&source[i]==','){i++}
   else{
    if(i<n&&source[i]=='\r')i++
    if(i<n&&source[i]=='\n')i++
    rows+=row
    row=mutableListOf()
   }
  }
  if(row.isNotEmpty())rows+=row
  return rows
 }

 private fun encodeCsvCell(text:String,wasQuoted:Boolean):String {
  val needsQuote=text.any{it=='"'||it==','||it=='\n'||it=='\r'}
  return when {
   needsQuote->"\""+text.replace("\"","\"\"")+"\""
   wasQuoted->"\""+text+"\""
   else->text
  }
 }

 /**
  * Translates CSV cells in place. A first row that looks like a localization
  * header is preserved, key/identifier columns are never touched, and bare
  * snake_case identifiers stay as-is because they are usually keys, not prose.
  */
 private suspend fun transformCsv(
  source:String,
  tx:suspend(String)->String,
  markSkipped:()->Unit
 ):String {
  val rows=parseCsv(source)
  if(rows.isEmpty())return source
  val header:List<String>?=if(rows.size>=2){
   val first=rows[0].map{it.value.trim()}
   if(first.isNotEmpty()&&first.all{csvHeaderCell.matches(it)}&&first.any{it.lowercase(java.util.Locale.ROOT) in csvHeaderNames})first else null
  }else null
  val keyColumns:Set<Int> = header?.mapIndexedNotNull{index,name->if(name.lowercase(java.util.Locale.ROOT) in csvKeyColumnNames)index else null}?.toSet() ?: emptySet()
  val out=StringBuilder(source.length)
  var cursor=0
  rows.forEachIndexed{rowIndex,cells->
   val isHeaderRow=rowIndex==0&&header!=null
   cells.forEachIndexed{colIndex,cell->
    out.append(source,cursor,cell.start)
    val skip=isHeaderRow||colIndex in keyColumns||csvSnakeIdentifier.matches(cell.value.trim())
    if(skip){
     markSkipped()
     out.append(source,cell.start,cell.end)
    }else{
     val translated=tx(cell.value)
     if(translated==cell.value)out.append(source,cell.start,cell.end)
     else out.append(encodeCsvCell(translated,cell.quoted))
    }
    cursor=cell.end
   }
  }
  out.append(source,cursor,source.length)
  return out.toString()
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
  if(text.contains('\u0000'))return false
  return text.any{it.isLetter()} && (text.contains(' ')||text.length>=3)
 }
}

internal class ArchiveEntryNameGuard {
 private val entryNames = HashSet<String>()
 private val normalizedCandidatePaths = HashSet<String>()

 fun register(name: String, normalizedCandidatePath: String? = null): Boolean {
  if (name.isEmpty() || name.indexOf(0.toChar()) >= 0 || !entryNames.add(name)) return false
  return normalizedCandidatePath == null || normalizedCandidatePaths.add(normalizedCandidatePath)
 }
}

internal object AndroidAssetLocalizer {
 private const val MAX_FILE_BYTES=512L*1024L
 private const val MAX_TOTAL_TRANSLATION_BYTES=4L*1024L*1024L
 private const val MAX_CANDIDATE_FILES=2048
 private const val MAX_ARCHIVE_ENTRIES=100_000
 private const val MAX_SINGLE_REBUILT_ENTRY_BYTES=2L*1024L*1024L*1024L
 private const val MAX_TOTAL_REBUILT_BYTES=4L*1024L*1024L*1024L
 private val supported=setOf("json","csv","txt","properties","strings")
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
  val archiveInfo=try{
   @Suppress("DEPRECATION")
   context.packageManager.getPackageArchiveInfo(inputApk.absolutePath,0)
  }catch(_:Exception){null}
  val packageName=archiveInfo?.packageName
  val extractNativeLibs=archiveInfo?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0 } ?: false
  val report=transformToUnsignedApk(inputApk,unsignedApk,extractNativeLibs,allowedPathPrefixes,onProgress,requireTranslations=true){value->
   translator.translateEnglishToArabic(value)
  }
  ApkSigningService.signAndVerify(context,unsignedApk,signedApk)
  return report.copy(packageName=packageName,outputApk=signedApk)
 }

 internal suspend fun transformToUnsignedApk(
  inputApk:File,unsignedApk:File,extractNativeLibs:Boolean,
  allowedPathPrefixes:List<String> = allowedRoots,onProgress:(Int,Int)->Unit={_,_->},
  requireTranslations:Boolean=true,
  tx:suspend (String)->String
 ):AssetLocalizationReport {
  require(inputApk.isFile){"ملف APK الأصلي غير موجود."}
  var discovered=0
  var translatedFiles=0
  var translatedStrings=0
  var skippedStrings=0
  var totalBytes=0L
  val transformed=mutableMapOf<String,ByteArray>()

  ZipFile(inputApk).use { zip ->
   val entries=zip.entries()
   val candidates=mutableListOf<ZipEntry>()
   val archiveEntryNames=ArchiveEntryNameGuard()
   var hasNativeLibraries=false
   var archiveEntryCount=0
   while(entries.hasMoreElements()){
    val entry=entries.nextElement()
    archiveEntryCount++
    require(archiveEntryCount<=MAX_ARCHIVE_ENTRIES){"عدد عناصر APK يتجاوز حد إعادة البناء الآمن (100,000)."}
    val supportedTextEntry=!entry.isDirectory&&isSupportedTextAsset(entry.name,entry.size,allowedPathPrefixes)
    val normalizedCandidatePath=if(supportedTextEntry) normalizeArchivePath(entry.name) else null
    require(archiveEntryNames.register(entry.name,normalizedCandidatePath)){
     "ملف APK يحتوي على اسم ZIP فارغ أو مكرر، أو مسارين نصيين متطابقين بعد تطبيع الفواصل؛ أوقفنا إعادة البناء لتجنب استبدال محتوى غير محدد."
    }
    if(entry.name.startsWith("lib/")&&entry.name.endsWith(".so",true))hasNativeLibraries=true
    if(supportedTextEntry){
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
    val result=AssetTextTransformer.transform(entry.name,original){value->tx(value)}
    if(result.translated>0){
     transformed[entry.name]=result.text.toByteArray(Charsets.UTF_8)
     translatedFiles++
    }
    translatedStrings+=result.translated
    skippedStrings+=result.skipped
    onProgress(index+1,candidates.size)
   }

   if(requireTranslations) require(translatedStrings>0){"لم يتم العثور على نصوص قابلة للترجمة في ملفات JSON/CSV/TXT/Properties/Strings المدعومة."}
   unsignedApk.parentFile?.mkdirs()
   val countingOutput = CountingOutputStream(unsignedApk.outputStream().buffered())
   ZipOutputStream(countingOutput).use { out ->
    val all=zip.entries()
    var copiedArchiveBytes=0L
    var writtenEntries=0
    while(all.hasMoreElements()){
     val entry=all.nextElement()
     writtenEntries++
     require(writtenEntries<=MAX_ARCHIVE_ENTRIES){"عدد عناصر APK تغير أثناء إعادة البناء."}
     if(isOldSignature(entry.name))continue
     val replacement=transformed[entry.name]
     val target=ApkZipEntryPolicy.create(entry,replacement,countingOutput.bytesWritten)
     out.putNextEntry(target)
     if(!entry.isDirectory){
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
  return AssetLocalizationReport(null,discovered,translatedFiles,translatedStrings,skippedStrings,unsignedApk)
 }

 internal fun isOldSignature(path:String):Boolean {
  if(!path.startsWith("META-INF/",true))return false
  val name=path.substringAfterLast('/').uppercase()
  return name.endsWith(".RSA")||name.endsWith(".DSA")||name.endsWith(".EC")||
   name.endsWith(".SF")||name=="MANIFEST.MF"
 }
}
