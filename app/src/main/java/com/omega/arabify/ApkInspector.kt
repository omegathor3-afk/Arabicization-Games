package com.omega.arabify

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

/** Reads an archive entry with an actual-byte limit, not only its ZIP header size. */
internal object BoundedArchiveInput {
 fun readAll(
  input: InputStream,
  maxBytes: Long,
  cancellationCheck: () -> Unit = {}
 ): ByteArray {
  require(maxBytes in 0..Int.MAX_VALUE.toLong())
  val output = ByteArrayOutputStream(minOf(maxBytes, 16L * 1024L).toInt())
  val buffer = ByteArray(16 * 1024)
  var total = 0L
  while (true) {
   cancellationCheck()
   val count = input.read(buffer)
   if (count < 0) break
   total = Math.addExact(total, count.toLong())
   require(total <= maxBytes) { "Archive entry exceeded its configured byte limit." }
   output.write(buffer, 0, count)
  }
  return output.toByteArray()
 }

 fun declaredSizeOrNull(columnIsNull: Boolean, value: Long): Long? =
  if (columnIsNull || value < 0L) null else value

 fun hasSufficientSpace(
  usableSpace: Long,
  declaredSize: Long?,
  maxBytes: Long,
  reserveBytes: Long
 ): Boolean {
  if (usableSpace < 0L || maxBytes < 0L || reserveBytes < 0L) return false
  val bytesToCopy = declaredSize ?: 0L
  if (bytesToCopy < 0L || bytesToCopy > maxBytes) return false
  val required = try {
   Math.addExact(bytesToCopy, reserveBytes)
  } catch (_: ArithmeticException) {
   return false
  }
  return usableSpace >= required
 }

 fun copy(
  input: InputStream,
  output: java.io.OutputStream,
  maxBytes: Long,
  cancellationCheck: () -> Unit = {}
 ): Long {
  require(maxBytes >= 0L)
  val buffer = ByteArray(64 * 1024)
  var total = 0L
  while (true) {
   cancellationCheck()
   val count = input.read(buffer)
   if (count < 0) break
   total = Math.addExact(total, count.toLong())
   require(total <= maxBytes) { "Archive entry exceeded its configured copy limit." }
   output.write(buffer, 0, count)
  }
  return total
 }
}

internal data class ApkScanResult(
 val fileName:String,val fileSize:Long,val packageName:String?,val versionName:String?,
 val engine:GameEngine,val entryCount:Int,val dexCount:Int,
 val candidateFiles:List<String>,val extractedTextSamples:List<String>,val notes:List<String>,val fontAssessments:List<ArabicFontAssessment>,
 val unityAssetInventory:UnityAssetInventory? = null
)
internal object ApkInspector {
 private const val MAX_APK_BYTES=1_500L*1024L*1024L
 private const val MIN_FREE_SPACE_RESERVE_BYTES=32L*1024L*1024L
 private const val MAX_SINGLE_TEXT_BYTES=512L*1024L
 private const val MAX_TOTAL_TEXT_BYTES=3L*1024L*1024L
 private const val MAX_FONT_BYTES=20L*1024L*1024L
 private const val MAX_ZIP_ENTRIES=100_000
 private const val MAX_PATH_CHARS=4096
 private val extensions=setOf("json","xml","txt","csv","properties","po","strings","yaml","yml","ini")
 fun inspect(
  context:Context,
  uri:Uri,
  cancellationCheck: () -> Unit = {}
 ):ApkScanResult {
  cancellationCheck()
  val resolver=context.contentResolver
  val metadata=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
   if(!c.moveToFirst()) null else {
    val nameIndex=c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val sizeIndex=c.getColumnIndex(OpenableColumns.SIZE)
    val displayName=if(nameIndex>=0) c.getString(nameIndex) else null
    val declaredSize=if(sizeIndex>=0) BoundedArchiveInput.declaredSizeOrNull(c.isNull(sizeIndex),c.getLong(sizeIndex)) else null
    displayName to declaredSize
   }
  }
  val name=metadata?.first?.takeIf { !it.isNullOrBlank() } ?: "selected.apk"
  val declaredSize=metadata?.second
  require(declaredSize == null || declaredSize <= MAX_APK_BYTES) {
   "حجم ملف APK يتجاوز حد الفحص الآمن (1.5 GiB)."
  }
  require(BoundedArchiveInput.hasSufficientSpace(
   context.cacheDir.usableSpace,
   declaredSize,
   MAX_APK_BYTES,
   MIN_FREE_SPACE_RESERVE_BYTES
  )) {
   "المساحة المتاحة في التخزين المؤقت غير كافية لفحص APK بأمان. وفّر مساحة إضافية ثم أعد المحاولة."
  }
  cancellationCheck()
  val temp=File.createTempFile("arabify-input-",".apk",context.cacheDir)
  try {
   val copied=resolver.openInputStream(uri)?.use { input ->
    temp.outputStream().use { output ->
     BoundedArchiveInput.copy(input,output,MAX_APK_BYTES,cancellationCheck)
    }
   } ?: error("تعذر فتح الملف المحدد.")
   require(declaredSize == null || copied == declaredSize) {
    "حجم ملف APK لا يطابق الحجم الذي أعلنه مزوّد الملفات."
   }
   @Suppress("DEPRECATION")
   cancellationCheck()
   val info=try{context.packageManager.getPackageArchiveInfo(temp.absolutePath,0)}catch(_:Exception){null}
   cancellationCheck()
   val candidates=mutableListOf<String>();val samples=mutableListOf<String>();val notes=mutableListOf<String>();val paths=mutableListOf<String>();val fonts=mutableListOf<ArabicFontAssessment>()
   var count=0;var dex=0;var textBytes=0L
   ZipFile(temp).use { zip ->
    val en=zip.entries()
    while(en.hasMoreElements()){
     cancellationCheck()
     val e=en.nextElement();if(e.isDirectory)continue
     count++;require(count<=MAX_ZIP_ENTRIES){"عدد ملفات الأرشيف يتجاوز حد الفحص الآمن (100,000)."};val path=e.name;require(path.length<=MAX_PATH_CHARS){"مسار داخل الأرشيف أطول من الحد الآمن."};paths += path
     if(ArabicFontAnalyzer.isFontPath(path)&&e.size in 1..(20L*1024L*1024L)&&fonts.size<64)try {
      val fontBytes=zip.getInputStream(e).use{BoundedArchiveInput.readAll(it,MAX_FONT_BYTES,cancellationCheck)}
      fonts +=ArabicFontAnalyzer.inspect(path,fontBytes)
     } catch(cancelled:CancellationException){throw cancelled}
     catch(_:Exception){notes+="تعذر تحليل ملف الخط: $path"}
     if(path.matches(Regex("(^|.*/)classes([0-9]+)?\\.dex",RegexOption.IGNORE_CASE)))dex++
     val ext=path.substringAfterLast('.',"").lowercase()
     if(ext in extensions && (path.startsWith("assets/")||path.startsWith("res/raw/")||path.startsWith("localization/")||path.startsWith("strings/"))){
      candidates+=path
      val size=e.size
      if(size in 1..MAX_SINGLE_TEXT_BYTES && textBytes<MAX_TOTAL_TEXT_BYTES)try{
       val budget=minOf(MAX_SINGLE_TEXT_BYTES,MAX_TOTAL_TEXT_BYTES-textBytes)
       val bodyBytes=zip.getInputStream(e).use{BoundedArchiveInput.readAll(it,budget,cancellationCheck)}
       textBytes+=bodyBytes.size
       val body=bodyBytes.toString(Charsets.UTF_8)
       body.lineSequence().map{it.trim()}.filter{it.length in 3..240&&it.any(Char::isLetter)}.take(8).forEach{line->if(samples.size<60)samples+="$path\n$line"}
      }catch(cancelled:CancellationException){throw cancelled}
      catch(_:Exception){notes+="تعذر قراءة نص UTF-8 من: $path (تجاوز حد الحجم أو تلف الملف)"}
     }
    }
   }
   cancellationCheck()
   val engine=EngineDetector.detect(paths)
   val unityInventory=if(engine==GameEngine.UNITY) UnityAssetInventoryScanner.inspect(temp,cancellationCheck=cancellationCheck) else null
   unityInventory?.notes?.forEach { notes += it }
   if(paths.any{it=="resources.arsc"})notes+="موارد Android الثنائية موجودة؛ لم يتم فك resources.arsc بعد."
   if(dex>0)notes+="تم العثور على $dex ملف DEX؛ نصوص الكود تحتاج محلل DEX/Smali."
   if(engine==GameEngine.UNITY)notes+="تم اكتشاف Unity؛ النصوص قد تكون في Assets أو Bundles أو ملفات IL2CPP/Mono."
   if(engine==GameEngine.UNREAL)notes+="تم اكتشاف Unreal؛ ملفات PAK/IoStore قد تكون مضغوطة أو مشفرة."
   if(candidates.isEmpty())notes += "لم يتم العثور على ملفات نصية واضحة في المسارات المدعومة."
   if(fonts.isEmpty())notes+="لم يتم العثور على ملفات TTF/OTF واضحة يمكن فحص تغطيتها العربية."
   else if(fonts.none{it.suitableForArabic})notes+="الخطوط المفحوصة لا تحتوي تغطية عربية كافية؛ يلزم خط عربي بديل مدمج واختبار ربطه بمحرك اللعبة."
   notes+="فحص أولي للقراءة فقط؛ ليس تعريبًا ولا يثبت اكتمال النصوص."
   return ApkScanResult(name,copied,info?.packageName,info?.versionName,engine,count,dex,candidates.take(100),samples,notes.distinct(),ArabicFontAnalyzer.rank(fonts),unityInventory)
  }finally{temp.delete()}
 }
}
