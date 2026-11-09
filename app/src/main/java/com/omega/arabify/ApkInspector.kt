package com.omega.arabify

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipFile

internal data class ApkScanResult(
 val fileName:String,val fileSize:Long,val packageName:String?,val versionName:String?,
 val engine:GameEngine,val entryCount:Int,val dexCount:Int,
 val candidateFiles:List<String>,val extractedTextSamples:List<String>,val notes:List<String>
)
internal object ApkInspector {
 private const val MAX_APK_BYTES=1_500L*1024L*1024L
 private const val MAX_SINGLE_TEXT_BYTES=512L*1024L
 private const val MAX_TOTAL_TEXT_BYTES=3L*1024L*1024L
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
   val candidates=mutableListOf<String>();val samples=mutableListOf<String>();val notes=mutableListOf<String>();val paths=mutableListOf<String>()
   var count=0;var dex=0;var textBytes=0L
   ZipFile(temp).use { zip ->
    val en=zip.entries()
    while(en.hasMoreElements()){
     val e=en.nextElement();if(e.isDirectory)continue
     count++;val path=e.name;paths+=path
     if(path.matches(Regex("(^|.*/)classes([0-9]+)?\\.dex",RegexOption.IGNORE_CASE)))dex++
     val ext=path.substringAfterLast('.',"").lowercase()
     if(ext in extensions && (path.startsWith("assets/")||path.startsWith("res/raw/")||path.startsWith("localization/")||path.startsWith("strings/"))){
      candidates+=path
      val size=e.size
      if(size in 1..MAX_SINGLE_TEXT_BYTES && textBytes+size<=MAX_TOTAL_TEXT_BYTES)try{
       val body=zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use{it.readText()};textBytes+=size
       body.lineSequence().map{it.trim()}.filter{it.length in 3..240&&it.any(Char::isLetter)}.take(8).forEach{line->if(samples.size<60)samples+="$path\n$line"}
      }catch(_:Exception){notes+="تعذر قراءة نص UTF-8 من: $path"}
     }
    }
   }
   val engine=EngineDetector.detect(paths)
   if(paths.any{it=="resources.arsc"})notes+="موارد Android الثنائية موجودة؛ لم يتم فك resources.arsc بعد."
   if(dex>0)notes+="تم العثور على $dex ملف DEX؛ نصوص الكود تحتاج محلل DEX/Smali."
   if(engine==GameEngine.UNITY)notes+="تم اكتشاف Unity؛ النصوص قد تكون في Assets أو Bundles أو ملفات IL2CPP/Mono."
   if(engine==GameEngine.UNREAL)notes+="تم اكتشاف Unreal؛ ملفات PAK/IoStore قد تكون مضغوطة أو مشفرة."
   if(candidates.isEmpty())notes+="لم يتم العثور على ملفات نصية واضحة في المسارات المدعومة."
   notes+="فحص أولي للقراءة فقط؛ ليس تعريبًا ولا يثبت اكتمال النصوص."
   return ApkScanResult(name,copied,info?.packageName,info?.versionName,engine,count,dex,candidates.take(100),samples,notes.distinct())
  }finally{temp.delete()}
 }
}
