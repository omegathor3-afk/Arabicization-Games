package com.omega.arabify

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Reads an archive entry with an actual-byte limit, not only its ZIP header size. */
internal object BoundedArchiveInput {
 fun readAll(input: InputStream, maxBytes: Long): ByteArray {
  require(maxBytes in 0..Int.MAX_VALUE.toLong())
  val output = ByteArrayOutputStream(minOf(maxBytes, 16L * 1024L).toInt())
  val buffer = ByteArray(16 * 1024)
  var total = 0L
  while (true) {
   val count = input.read(buffer)
   if (count < 0) break
   total = Math.addExact(total, count.toLong())
   require(total <= maxBytes) { "Archive entry exceeded its configured byte limit." }
   output.write(buffer, 0, count)
  }
  return output.toByteArray()
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
 private const val MAX_SINGLE_TEXT_BYTES=512L*1024L
 private const val MAX_TOTAL_TEXT_BYTES=3L*1024L*1024L
 private const val MAX_FONT_BYTES=20L*1024L*1024L
 private const val MAX_ZIP_ENTRIES=100_000
 private const val MAX_PATH_CHARS=4096
 private val extensions=setOf("json","xml","txt","csv","properties","po","strings","yaml","yml","ini")
 fun inspect(context:Context,uri:Uri):ApkScanResult {
  val resolver=context.contentResolver
  val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: "selected.apk"
  val temp=File.createTempFile("arabify-input-",".apk",context.cacheDir)
  try {
   var copied=0L
   resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
    val buffer=ByteArray(65536)
    while(true){val n=input.read(buffer);if(n<0)break;copied+=n;require(copied<=MAX_APK_BYTES){"حجم الملف أكبر من الحد الحالي (1.5GB)."};output.write(buffer,0,n)}
   }} ?: error("تعذر فتح الملف المحدد.")
   @Suppress("DEPRECATION")
   val info=try{context.packageManager.getPackageArchiveInfo(temp.absolutePath,0)}catch(_:Exception){null}
   val candidates=mutableListOf<String>();val samples=mutableListOf<String>();val notes=mutableListOf<String>();val paths=mutableListOf<String>();val fonts=mutableListOf<ArabicFontAssessment>()
   var count=0;var dex=0;var textBytes=0L
   ZipFile(temp).use { zip ->
    val en=zip.entries()
    while(en.hasMoreElements()){
     val e=en.nextElement();if(e.isDirectory)continue
     count++;require(count<=MAX_ZIP_ENTRIES){"عدد ملفات الأرشيف يتجاوز حد الفحص الآمن (100,000)."};val path=e.name;require(path.length<=MAX_PATH_CHARS){"مسار داخل الأرشيف أطول من الحد الآمن."};paths += path
     if(ArabicFontAnalyzer.isFontPath(path)&&e.size in 1..(20L*1024L*1024L)&&fonts.size<64)try {
      val fontBytes=zip.getInputStream(e).use{BoundedArchiveInput.readAll(it,MAX_FONT_BYTES)}
      fonts +=ArabicFontAnalyzer.inspect(path,fontBytes)
     } catch(_:Exception){notes+="تعذر تحليل ملف الخط: $path"}
     if(path.matches(Regex("(^|.*/)classes([0-9]+)?\\.dex",RegexOption.IGNORE_CASE)))dex++
     val ext=path.substringAfterLast('.',"").lowercase()
     if(ext in extensions && (path.startsWith("assets/")||path.startsWith("res/raw/")||path.startsWith("localization/")||path.startsWith("strings/"))){
      candidates+=path
      val size=e.size
      if(size in 1..MAX_SINGLE_TEXT_BYTES && textBytes<MAX_TOTAL_TEXT_BYTES)try{
       val budget=minOf(MAX_SINGLE_TEXT_BYTES,MAX_TOTAL_TEXT_BYTES-textBytes)
       val bodyBytes=zip.getInputStream(e).use{BoundedArchiveInput.readAll(it,budget)}
       textBytes+=bodyBytes.size
       val body=bodyBytes.toString(Charsets.UTF_8)
       body.lineSequence().map{it.trim()}.filter{it.length in 3..240&&it.any(Char::isLetter)}.take(8).forEach{line->if(samples.size<60)samples+="$path\n$line"}
      }catch(_:Exception){notes+="تعذر قراءة نص UTF-8 من: $path (تجاوز حد الحجم أو تلف الملف)"}
     }
    }
   }
   val engine=EngineDetector.detect(paths)
   val unityInventory=if(engine==GameEngine.UNITY) UnityAssetInventoryScanner.inspect(temp) else null
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
