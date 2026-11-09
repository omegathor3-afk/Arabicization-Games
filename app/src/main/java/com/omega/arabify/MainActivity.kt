package com.omega.arabify

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { ArabifyApp(applicationContext) }
 }
}

@Composable
private fun ArabifyApp(context: Context) {
 val scope=rememberCoroutineScope()
 var result by remember { mutableStateOf<ApkScanResult?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var modelReady by remember { mutableStateOf(false) }
 var translationInput by remember { mutableStateOf("The gate is locked. Find the ancient key.") }
 var translatedText by remember { mutableStateOf<String?>(null) }
 var translationBusy by remember { mutableStateOf(false) }
 var translationError by remember { mutableStateOf<String?>(null) }
 var selectedUri by remember { mutableStateOf<Uri?>(null) }
 var localizeBusy by remember { mutableStateOf(false) }
 var localizationProgress by remember { mutableStateOf(0 to 0) }
 var localizationReport by remember { mutableStateOf<AndroidLocalizationReport?>(null) }
 var generatedApk by remember { mutableStateOf<File?>(null) }
 var localizationError by remember { mutableStateOf<String?>(null) }
 var exportError by remember { mutableStateOf<String?>(null) }
 val offlineTranslator = remember(context) { OfflineArabicTranslator(context) }
 DisposableEffect(offlineTranslator) { onDispose { offlineTranslator.close() } }
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
  if(uri!=null) {
   selectedUri=uri;busy=true;error=null;result=null;localizationReport=null;generatedApk=null
   scope.launch {
    try { result=withContext(Dispatchers.IO){ApkInspector.inspect(context,uri)} }
    catch(e:Exception){error=e.message ?: "حدث خطأ أثناء فحص الملف."}
    finally{busy=false}
   }
  }
 }
 val saveLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { targetUri:Uri? ->
  val source=generatedApk
  if(targetUri!=null && source!=null) {
   scope.launch {
    try {
     withContext(Dispatchers.IO) {
      context.contentResolver.openOutputStream(targetUri,"wt")?.use { output ->
       source.inputStream().use { input -> input.copyTo(output) }
      } ?: kotlin.error("تعذر إنشاء ملف APK في المكان المحدد.")
     }
     exportError=null
    } catch(e:Exception) { exportError=e.message ?: "تعذر حفظ APK." }
   }
  }
 }
 MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFD7B77A),secondary=Color(0xFF8FA69A),background=Color(0xFF111318),surface=Color(0xFF1B1F26))) {
  Surface(Modifier.fillMaxSize(),color=Color(0xFF111318)) {
   LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item {
     Text("ARABIFY",color=Color(0xFFD7B77A),fontSize=30.sp,fontWeight=FontWeight.Black)
     Text("محرك تعريب الألعاب والتطبيقات",color=Color(0xFFE8E1D5),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
     Text("تحليل محلي • خصوصية • دعم محركات متعددة",color=Color(0xFF9CA5B1),fontSize=12.sp)
    }
    item {
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF202630)),shape=RoundedCornerShape(20.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("مترجم محلي إلى العربية",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
       Text("حمّل نموذج اللغة مرة واحدة عبر Wi-Fi؛ بعد اكتمال تنزيله تتم الترجمة على الجهاز دون إرسال النصوص إلى خادم.",color=Color(0xFFB9C1CB),fontSize=12.sp)
       Button(onClick={
        translationBusy=true;translationError=null
        scope.launch {
         try { withContext(Dispatchers.IO){offlineTranslator.prepareForOfflineUse()};modelReady=true }
         catch(e:Exception){translationError=e.message ?: "تعذر تجهيز نموذج الترجمة."}
         finally{translationBusy=false}
        }
       },enabled=!translationBusy,modifier=Modifier.fillMaxWidth()){Text(if(modelReady) "نموذج العربية جاهز على الجهاز" else "تنزيل نموذج الترجمة")}
       OutlinedTextField(value=translationInput,onValueChange={translationInput=it},label={Text("نص إنجليزي للتجربة")},modifier=Modifier.fillMaxWidth())
       Button(onClick={
        translationBusy=true;translationError=null;translatedText=null
        scope.launch {
         try { translatedText=withContext(Dispatchers.IO){offlineTranslator.translateEnglishToArabic(translationInput)} }
         catch(e:Exception){translationError=e.message ?: "فشلت الترجمة."}
         finally{translationBusy=false}
        }
       },enabled=modelReady&&!translationBusy&&translationInput.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("ترجمة إلى العربية")}
       if(translationBusy) CircularProgressIndicator()
       translatedText?.let{Text(it,color=Color(0xFFE7D5AF),fontSize=17.sp)}
       translationError?.let{Text(it,color=Color(0xFFFF9B91),fontSize=12.sp)}
      }
     }
    }
    item {
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF202630)),shape=RoundedCornerShape(20.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("ابدأ بملف اللعبة",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
       Text("اختر APK لفحص بنيته واكتشاف المحرك والملفات النصية المحتملة. الملف لا يُرفع إلى أي خادم.",color=Color(0xFFB9C1CB),fontSize=13.sp)
       Button(onClick={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("اختيار ملف APK")}
       if(busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){CircularProgressIndicator();Text("جارٍ فحص بنية الملف...")}
       error?.let{Text(it,color=Color(0xFFFF9B91))}
      }
     }
    }
    result?.let { scan ->
     item{SectionTitle("نتيجة الفحص")}
     item {
      Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF1B2927))) {
       Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(scan.fileName,color=Color.White,fontWeight=FontWeight.Bold)
        Text("المحرك: ${scan.engine.displayName}",color=Color(0xFFD7B77A))
        Text("الحزمة: ${scan.packageName ?: "غير مستخرجة"}",color=Color(0xFFD4D9DF),fontSize=12.sp)
        Text("الإصدار: ${scan.versionName ?: "غير معروف"}",color=Color(0xFFD4D9DF),fontSize=12.sp)
        Text("الحجم: ${scan.fileSize/(1024*1024)} MB • عناصر ZIP: ${scan.entryCount} • ملفات DEX: ${scan.dexCount}",color=Color(0xFFD4D9DF),fontSize=12.sp)
       }
      }
     }
     if(scan.engine==GameEngine.ANDROID) {
      item {
       Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF26312D)),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
         Text("تعريب موارد Android",color=Color(0xFFE7D5AF),fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text("هذا المسار التجريبي يضيف موارد string عربية إلى resources.arsc. لا يشمل النصوص المخبأة داخل DEX أو محركات الألعاب.",color=Color(0xFFD0D7D2),fontSize=12.sp)
         Text("يجب تنزيل نموذج الترجمة أولًا. التوقيع الجديد يعني أن تثبيت النسخة المعربة بدل الأصلية قد يتطلب إزالة النسخة الأصلية، وقد يؤدي ذلك لفقد بياناتها.",color=Color(0xFFE5C58C),fontSize=12.sp)
         Button(onClick={
          val uri=selectedUri
          if(uri!=null) {
           localizeBusy=true;localizationError=null;localizationProgress=0 to 0;localizationReport=null
           scope.launch {
            try {
             val report=withContext(Dispatchers.IO) {
              val input=copyUriToCache(context,uri)
              val unsigned=File(context.cacheDir,"arabify-unsigned-${System.currentTimeMillis()}.apk")
              val signed=File(context.cacheDir,"arabify-arabic-${System.currentTimeMillis()}.apk")
              AndroidResourceLocalizer.localizeEnglishStrings(input,unsigned,signed,offlineTranslator,context) { done,total ->
               scope.launch { localizationProgress=done to total }
              }
             }
             localizationReport=report
             generatedApk=report.signedApk
            } catch(e:Exception) { localizationError=e.message ?: "تعذر تعريب موارد اللعبة." }
            finally { localizeBusy=false }
           }
          }
         },enabled=modelReady&&!localizeBusy&&selectedUri!=null,modifier=Modifier.fillMaxWidth()) {
          Text(if(localizeBusy) "جارٍ التعريب..." else "ابدأ تعريب موارد اللعبة")
         }
         if(localizeBusy) {
          CircularProgressIndicator()
          Text("تمت معالجة ${localizationProgress.first} من ${localizationProgress.second}",color=Color.White,fontSize=12.sp)
         }
         localizationReport?.let { report ->
          Text("اكتمل إنشاء الموارد العربية: ${report.translatedStrings} نصًا من ${report.discoveredStrings}.",color=Color(0xFFB8E4C8),fontSize=13.sp)
          Button(onClick={saveLauncher.launch("arabify_arabic.apk")},modifier=Modifier.fillMaxWidth()){Text("حفظ APK المعرب")}
         }
         localizationError?.let{Text(it,color=Color(0xFFFF9B91),fontSize=12.sp)}
         exportError?.let{Text(it,color=Color(0xFFFF9B91),fontSize=12.sp)}
        }
       }
      }
     }
     item{SectionTitle("ملفات نصية محتملة (${scan.candidateFiles.size})")}
     if(scan.candidateFiles.isEmpty()) item{Text("لم يتم العثور على ملفات نصية واضحة في المسارات المدعومة.",color=Color(0xFFB9C1CB))}
     items(scan.candidateFiles){path->Text("• $path",color=Color(0xFFD4D9DF),fontSize=12.sp)}
     item{SectionTitle("عينات نصية")}
     if(scan.extractedTextSamples.isEmpty()) item{Text("لا توجد عينات نصية قابلة للقراءة في هذه المرحلة.",color=Color(0xFFB9C1CB))}
     items(scan.extractedTextSamples){sample->Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF202630))){Text(sample,Modifier.fillMaxWidth().padding(12.dp),color=Color(0xFFE1E5E9),fontSize=11.sp)}}
     item{SectionTitle("ملاحظات وحدود الفحص")}
     items(scan.notes){note->Text("• $note",color=Color(0xFFE5C58C),fontSize=12.sp)}
    }
    item {
     Spacer(Modifier.height(8.dp))
     Text("الإصدار 0.2.0 — تعريب تجريبي لموارد string في تطبيقات Android الأصلية.",color=Color(0xFF78818D),fontSize=11.sp)
    }
   }
  }
 }
}

@Composable
private fun SectionTitle(value:String){Text(value,color=Color(0xFFD7B77A),fontWeight=FontWeight.Bold,fontSize=16.sp)}

private fun copyUriToCache(context:Context,uri:Uri):File {
 val file=File(context.cacheDir,"arabify-import-${System.currentTimeMillis()}.apk")
 context.contentResolver.openInputStream(uri)?.use { input ->
  file.outputStream().use { output -> input.copyTo(output) }
 } ?: error("تعذر قراءة ملف اللعبة.")
 return file
}
