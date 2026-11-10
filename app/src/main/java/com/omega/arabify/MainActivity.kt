package com.omega.arabify

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
 var appSearch by remember { mutableStateOf("") }
 var selectedInstalledApp by remember { mutableStateOf<InstalledApp?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var inspectionProgress by remember { mutableStateOf(0 to 0) }
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
 var assetBusy by remember { mutableStateOf(false) }
 var assetProgress by remember { mutableStateOf(0 to 0) }
 var assetReport by remember { mutableStateOf<AssetLocalizationReport?>(null) }
 var assetError by remember { mutableStateOf<String?>(null) }
 LaunchedEffect(context) { installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) } }
 val offlineTranslator = remember(context) { OfflineArabicTranslator(context) }
 DisposableEffect(offlineTranslator) { onDispose { offlineTranslator.close() } }
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
  if(uri!=null) {
   selectedUri=uri;selectedInstalledApp=null;busy=true;error=null;result=null;localizationReport=null;generatedApk=null
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
 ArabifyTheme {
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
  Surface(Modifier.fillMaxSize(),color=ArabifyPalette.canvas) {
   LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp, vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    item {
     Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surfaceRaised),shape=RoundedCornerShape(28.dp),border=BorderStroke(1.dp,ArabifyPalette.outline)) {
      Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
       Text("OMEGA DEVELOPMENT LAB",color=ArabifyPalette.textMuted,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
       Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text("ARABIFY",color=ArabifyPalette.primary,fontSize=28.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp)
        Text("LOCAL ENGINE  ·  0.2",color=ArabifyPalette.accent,fontSize=10.sp,fontWeight=FontWeight.Bold)
       }
       Text("خلّي ألعابك تتكلم عربي",color=ArabifyPalette.textPrimary,fontSize=21.sp,fontWeight=FontWeight.Bold)
       Text("محرك لفحص الألعاب والتطبيقات، واكتشاف النصوص والخطوط، وتجربة التعريب بأمان على جهازك.",color=ArabifyPalette.textSecondary,fontSize=13.sp,lineHeight=20.sp)
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("خصوصية محلية",color=ArabifyPalette.primary,fontSize=10.sp,fontWeight=FontWeight.SemiBold)
        Text("•",color=ArabifyPalette.accent)
        Text("فحص قبل التعديل",color=ArabifyPalette.textSecondary,fontSize=10.sp)
       }
      }
     }
    }
    item {
     Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,ArabifyPalette.outline)) {
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("اختر من تطبيقات وألعاب هاتفك",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
       Text("تظهر هنا التطبيقات المثبتة التي لها أيقونة تشغيل. اختر تطبيقًا لقراءة ملف التثبيت الأساسي محليًا.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       OutlinedTextField(value=appSearch,onValueChange={appSearch=it},label={Text("ابحث عن لعبة أو تطبيق")},modifier=Modifier.fillMaxWidth(),singleLine=true)
       val visibleApps=installedApps.filter { it.label.contains(appSearch,true) || it.packageName.contains(appSearch,true) }
       Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text("التطبيقات المتاحة",color=ArabifyPalette.textSecondary,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
        Text("${visibleApps.size} نتيجة",color=ArabifyPalette.primary,fontSize=11.sp,fontWeight=FontWeight.Bold)
       }
       if(visibleApps.isEmpty()) Text("لا توجد نتائج. تأكد من تثبيت التطبيق أو استخدم اختيار APK اليدوي.",color=ArabifyPalette.textMuted,fontSize=12.sp)
       visibleApps.forEach { app ->
        Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
         val appIcon=remember(app.packageName) { drawableToBitmap(context.packageManager.getApplicationIcon(app.packageName)).asImageBitmap() }
         Image(bitmap=appIcon,contentDescription="أيقونة التطبيق",modifier=Modifier.height(44.dp).fillMaxWidth(0.13f))
         Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
          Text(app.label,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
          Text(app.packageName,color=ArabifyPalette.textMuted,fontSize=10.sp)
          if(app.splitCount>0) Text("حزمة أساسية + ${app.splitCount} ملفات Split إضافية",color=ArabifyPalette.warning,fontSize=10.sp)
         }
         Button(onClick={
          selectedInstalledApp=app
          selectedUri=Uri.fromFile(File(app.apkPath))
          busy=true;inspectionProgress=0 to (1+app.splitCount);error=null;result=null;localizationReport=null;generatedApk=null
          scope.launch {
           try { result=withContext(Dispatchers.IO){inspectInstalledPackage(context,app){done,total -> scope.launch { inspectionProgress=done to total }}} }
           catch(e:Exception){error=e.message ?: "تعذر فحص التطبيق المثبت."}
           finally{busy=false}
          }
         },enabled=!busy,modifier=Modifier.semantics { contentDescription = "اختيار ${app.label} ${app.packageName}" }) { Text(if(selectedInstalledApp?.packageName==app.packageName) "محدد" else "اختيار") }
        }
       }
       if(busy && selectedInstalledApp!=null) {
        val fraction=if(inspectionProgress.second>0) inspectionProgress.first.toFloat()/inspectionProgress.second else 0f
        LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
        Text("فحص ملفات التثبيت: ${inspectionProgress.first} من ${inspectionProgress.second}",color=ArabifyPalette.info,fontSize=11.sp)
       }
       Text("يتم فحص ملفات APK الأساسية وملفات Split الإضافية كلٌ على حدة. إعادة بناء وتوقيع مجموعة Split كحزمة قابلة للتثبيت ما زالت غير مفعّلة؛ لذلك يتوقف التصدير الآمن لهذه الألعاب بدل إنتاج تثبيت ناقص.",color=ArabifyPalette.warning,fontSize=11.sp)
      }
     }
    }
    item {
     Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,ArabifyPalette.outline)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("محرك الترجمة المحلي",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
       Text("تنبيه بوضوح: النسخة الحالية تستخدم نموذج ML Kit يتم تنزيله عند الطلب، وليس حزمة عربية/إنجليزية مضمّنة بالكامل. بعد التنزيل تعمل الترجمة النصية دون اتصال، لكن ذلك لا يعني تعريب كل ملفات اللعبة تلقائيًا.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       Button(onClick={
        translationBusy=true;translationError=null
        scope.launch {
         try { withContext(Dispatchers.IO){offlineTranslator.prepareForOfflineUse()};modelReady=true }
         catch(e:Exception){translationError=e.message ?: "تعذر تجهيز نموذج الترجمة."}
         finally{translationBusy=false}
        }
       },enabled=!translationBusy,modifier=Modifier.fillMaxWidth()){Text(if(modelReady) "النموذج الحالي جاهز" else "تنزيل نموذج الترجمة (مرة واحدة)")}
       OutlinedTextField(value=translationInput,onValueChange={translationInput=it},label={Text("اختبر المحرك بجملة قصيرة")},modifier=Modifier.fillMaxWidth())
       Button(onClick={
        translationBusy=true;translationError=null;translatedText=null
        scope.launch {
         try { translatedText=withContext(Dispatchers.IO){offlineTranslator.translateEnglishToArabic(translationInput)} }
         catch(e:Exception){translationError=e.message ?: "فشلت الترجمة."}
         finally{translationBusy=false}
        }
       },enabled=modelReady&&!translationBusy&&translationInput.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("اختبار الترجمة المحلية")}
       if(translationBusy) CircularProgressIndicator()
       translatedText?.let{Text(it,color=ArabifyPalette.accent,fontSize=17.sp)}
       translationError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
      }
     }
    }
    item {
     Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),shape=RoundedCornerShape(20.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("أو اختر ملف APK يدويًا",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
       Text("هذا الخيار احتياطي للتطبيقات التي لا تظهر في القائمة أو لملف APK خارجي.",color=ArabifyPalette.textSecondary,fontSize=13.sp)
       Button(onClick={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("اختيار ملف APK")}
       if(busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){CircularProgressIndicator();Text("جارٍ فحص بنية الملف...")}
       error?.let{Text(it,color=ArabifyPalette.danger)}
      }
     }
    }
    result?.let { scan ->
     item{SectionTitle("نتيجة الفحص")}
     item {
      Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),border=BorderStroke(1.dp,ArabifyPalette.outline)) {
       Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(scan.fileName,color=Color.White,fontWeight=FontWeight.Bold)
        Text("المحرك: ${scan.engine.displayName}",color=ArabifyPalette.accent)
        Text("الحزمة: ${scan.packageName ?: "غير مستخرجة"}",color=ArabifyPalette.textBody,fontSize=12.sp)
        Text("الإصدار: ${scan.versionName ?: "غير معروف"}",color=ArabifyPalette.textBody,fontSize=12.sp)
        Text("الحجم: ${scan.fileSize/(1024*1024)} MB • عناصر ZIP: ${scan.entryCount} • ملفات DEX: ${scan.dexCount}",color=ArabifyPalette.textBody,fontSize=12.sp)
       }
      }
     }
     item {
      Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surfaceAccent),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,ArabifyPalette.outline)) {
       Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("فحص الخطوط ودعم العربية",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
        Text("يفحص ملفات TTF/OTF المضمنة، ويقيس تغطية الحروف العربية، ويتحقق من وجود سجلات arab في جدولي OpenType GSUB وGPOS قبل ترشيح الخط. الاسم والامتداد وحدهما لا يكفيان.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
        if(scan.fontAssessments.isEmpty()) {
         Text("لم يُعثر على خطوط TTF/OTF قابلة للفحص داخل APK.",color=ArabifyPalette.warningText,fontSize=12.sp)
        } else {
         val best=scan.fontAssessments.first()
         Text("أفضل خط موجود: ${best.path}",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
         Text("تغطية الحروف العربية: ${best.mappedArabicLetters}/${best.totalArabicLetters} (${best.coveragePercent}%)",color=if(best.suitableForArabic) ArabifyPalette.success else ArabifyPalette.warningText,fontSize=12.sp)
         Text("جداول تشكيل OpenType: ${if(best.hasArabicShapingTables) "تم العثور على arab في GSUB وGPOS" else "غير مكتملة أو غير موجودة"}",color=if(best.hasArabicShapingTables) ArabifyPalette.success else ArabifyPalette.warningText,fontSize=12.sp)
         scan.fontAssessments.take(8).forEach { font ->
          val status = when {
           font.suitableForArabic -> "تغطية وتشكيل مرشحان"
           font.hasArabicShapingTables -> "تشكيل موجود لكن التغطية ناقصة"
           else -> "تشكيل عربي غير مؤكد"
          }
          Text("• ${font.path} — ${font.coveragePercent}% · $status",color=ArabifyPalette.textBody,fontSize=11.sp)
         }
        }
        Text("تنبيه: هذه المرحلة ترشح الخطوط فقط؛ لا تضيف الخط إلى اللعبة ولا تبدل مراجعها تلقائيًا. وجود GSUB/GPOS لا يغني عن اختبار تشكيل العربية وRTL داخل محرك اللعبة الفعلي.",color=ArabifyPalette.warningText,fontSize=12.sp)
       }
      }
     }
     if(scan.engine==GameEngine.ANDROID) {
      item {
       Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
         Text("تعريب موارد Android",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text("هذا المسار التجريبي يضيف موارد string عربية إلى resources.arsc. لا يشمل النصوص المخبأة داخل DEX أو محركات الألعاب.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
         Text("يجب تنزيل نموذج الترجمة أولًا. التوقيع الجديد يعني أن تثبيت النسخة المعربة بدل الأصلية قد يتطلب إزالة النسخة الأصلية، وقد يؤدي ذلك لفقد بياناتها.",color=ArabifyPalette.warningText,fontSize=12.sp)
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
         },enabled=modelReady&&!localizeBusy&&selectedUri!=null&&(selectedInstalledApp?.splitCount ?: 0)==0,modifier=Modifier.fillMaxWidth()) {
          Text(if(localizeBusy) "جارٍ التعريب..." else "ابدأ تعريب موارد اللعبة")
         }
         if(localizeBusy) {
          val fraction=if(localizationProgress.second>0) localizationProgress.first.toFloat()/localizationProgress.second.toFloat() else 0f
          LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
          Text(if(localizationProgress.second>0) "تعريب الموارد: ${localizationProgress.first} من ${localizationProgress.second} (${(fraction*100).toInt()}%)" else "تهيئة وفحص موارد التطبيق…",color=Color.White,fontSize=12.sp)
         }
         localizationReport?.let { report ->
          Text("اكتمل إنشاء الموارد العربية: ${report.translatedStrings} نصًا من ${report.discoveredStrings}.",color=ArabifyPalette.success,fontSize=13.sp)
          Button(onClick={saveLauncher.launch("arabify_arabic.apk")},modifier=Modifier.fillMaxWidth()){Text("حفظ APK المعرب")}
         }
         localizationError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
         exportError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
        }
       }
      }
     }
     if(scan.candidateFiles.isNotEmpty()) {
      item {
       Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
         Text("تعريب ملفات النصوص داخل اللعبة",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text("يعالج هذا المسار ملفات JSON وTXT وProperties وStrings الموجودة داخل assets أو res/raw أو مجلدات localization/strings. يحافظ على مفاتيح JSON وخصائص الإعدادات والمتغيرات المعروفة.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
         Text("لن يترجم ملفات ثنائية أو XML أو ملفات Unity/Unreal المشفرة. لا تعتبر النتيجة تعريبًا كاملًا للعبة.",color=ArabifyPalette.warningText,fontSize=12.sp)
         if(scan.engine==GameEngine.UNITY) Text(UnityMonoLocalizer.supportedScope,color=ArabifyPalette.warningText,fontSize=12.sp)
         Button(onClick={
          val uri=selectedUri
          if(uri!=null) {
           assetBusy=true;assetError=null;assetReport=null;assetProgress=0 to 0
           scope.launch {
            try {
             val report=withContext(Dispatchers.IO) {
              val input=copyUriToCache(context,uri)
              val stamp=System.currentTimeMillis()
              if (result?.engine == GameEngine.UNITY) {
               UnityMonoLocalizer.localize(
                context = context,
                inputApk = input,
                unsignedApk = File(context.cacheDir,"arabify-assets-$stamp-unsigned.apk"),
                signedApk = File(context.cacheDir,"arabify-assets-$stamp-signed.apk"),
                translator = offlineTranslator,
                onProgress = { done: Int, total: Int ->
                 scope.launch { assetProgress=done to total }
                }
               )
              } else {
               AndroidAssetLocalizer.localize(
                context = context,
                inputApk = input,
                unsignedApk = File(context.cacheDir,"arabify-assets-$stamp-unsigned.apk"),
                signedApk = File(context.cacheDir,"arabify-assets-$stamp-signed.apk"),
                translator = offlineTranslator,
                onProgress = { done: Int, total: Int ->
                 scope.launch { assetProgress=done to total }
                }
               )
              }
             }
             assetReport=report
             generatedApk=report.outputApk
            } catch(e:Exception) { assetError=e.message ?: "تعذر تعريب ملفات النصوص." }
            finally { assetBusy=false }
           }
          }
         },enabled=modelReady&&!assetBusy&&selectedUri!=null&&(selectedInstalledApp?.splitCount ?: 0)==0,modifier=Modifier.fillMaxWidth()) {
          Text(if(assetBusy) "جارٍ تعريب ملفات النصوص..." else "تعريب ملفات النصوص وإنشاء APK")
         }
         if(assetBusy) {
          val fraction=if(assetProgress.second>0) assetProgress.first.toFloat()/assetProgress.second.toFloat() else 0f
          LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
          Text(if(assetProgress.second>0) "تعريب الملفات: ${assetProgress.first} من ${assetProgress.second} (${(fraction*100).toInt()}%)" else "استخراج الملفات وتحليل النصوص…",color=Color.White,fontSize=12.sp)
         }
         assetReport?.let { report ->
          Text("الملفات: ${report.filesTranslated} / ${report.filesDiscovered} • النصوص المترجمة: ${report.stringsTranslated} • المتروكة: ${report.stringsSkipped}",color=ArabifyPalette.success,fontSize=13.sp)
          Button(onClick={saveLauncher.launch("arabify_assets_arabic.apk")},modifier=Modifier.fillMaxWidth()){Text("حفظ APK بعد تعريب الملفات")}
         }
         assetError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
        }
       }
      }
     }
     scan.unityAssetInventory?.let { inventory ->
      item {
       Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surface),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
         Text("جرد موارد Unity — قراءة فقط",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text(inventory.report,color=ArabifyPalette.textBody,fontSize=12.sp)
         Text("الفحص الحالي يقرأ بيانات SerializedFile الوصفية وجداول الأنواع والكائنات ضمن حدود ذاكرة، ويظل للقراءة فقط. لم يتم تعديل object payloads أو AssetBundles أو DLL.",color=ArabifyPalette.warningText,fontSize=12.sp)
         inventory.items.take(12).forEach { asset ->
          val status = when(asset.support) {
           UnityAssetSupport.TEXT_CANDIDATE_READ_ONLY -> "مرشح نصي — غير معدل"
           UnityAssetSupport.HEADER_PARSED_READ_ONLY -> "ترويسة مقروءة — غير معدل"
           UnityAssetSupport.BUNDLE_HEADER_ONLY_READ_ONLY -> "توقيع Bundle فقط — غير معدل"
           UnityAssetSupport.PRESERVE_AS_EXTERNAL_DATA -> "بيانات جانبية — محفوظة"
           UnityAssetSupport.PRESERVE_UNSUPPORTED -> "غير مدعوم — محفوظ كما هو"
          }
          Text("• ${asset.path} (${asset.sizeBytes} bytes)\n$status\n${asset.detail}",color=ArabifyPalette.textBody,fontSize=10.sp)
         }
         if(inventory.items.size > 12) Text("يتم عرض أول 12 عنصرًا من أصل ${inventory.items.size}.",color=Color(0xFF9CA5B1),fontSize=10.sp)
        }
       }
      }
     }
     item{SectionTitle("ملفات نصية محتملة (${scan.candidateFiles.size})")}
     if(scan.candidateFiles.isEmpty()) item{Text("لم يتم العثور على ملفات نصية واضحة في المسارات المدعومة.",color=ArabifyPalette.textSecondary)}
     items(scan.candidateFiles){path->Text("• $path",color=ArabifyPalette.textBody,fontSize=12.sp)}
     item{SectionTitle("عينات نصية")}
     if(scan.extractedTextSamples.isEmpty()) item{Text("لا توجد عينات نصية قابلة للقراءة في هذه المرحلة.",color=ArabifyPalette.textSecondary)}
     items(scan.extractedTextSamples){sample->Card(colors=CardDefaults.cardColors(containerColor=ArabifyPalette.surfaceElevated)){Text(sample,Modifier.fillMaxWidth().padding(12.dp),color=ArabifyPalette.textOnCard,fontSize=11.sp)}}
     item{SectionTitle("ملاحظات وحدود الفحص")}
     items(scan.notes){note->Text("• $note",color=ArabifyPalette.warningText,fontSize=12.sp)}
    }
    item {
     Spacer(Modifier.height(8.dp))
     Text("الإصدار 0.2.0 — تعريب تجريبي لموارد string في تطبيقات Android الأصلية.",color=ArabifyPalette.textDisabled,fontSize=11.sp)
    }
   }
  }
  }
 }
}

@Composable
private fun SectionTitle(value:String){Text(value,color=ArabifyPalette.primary,fontWeight=FontWeight.Bold,fontSize=16.sp)}

private fun copyUriToCache(context:Context,uri:Uri):File {
 val file=File(context.cacheDir,"arabify-import-${System.currentTimeMillis()}.apk")
 context.contentResolver.openInputStream(uri)?.use { input ->
  file.outputStream().use { output -> input.copyTo(output) }
 } ?: error("تعذر قراءة ملف اللعبة.")
 return file
}


internal data class InstalledApp(val label:String,val packageName:String,val apkPath:String,val splitApkPaths:List<String>) { val splitCount:Int get()=splitApkPaths.size }

private fun drawableToBitmap(drawable:Drawable):Bitmap {
 val width=drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(192)
 val height=drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(192)
 val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
 val canvas=Canvas(bitmap)
 drawable.setBounds(0,0,width,height)
 drawable.draw(canvas)
 return bitmap
}

@Suppress("DEPRECATION")
private fun loadInstalledApps(context:Context):List<InstalledApp> {
 val pm=context.packageManager
 return pm.getInstalledApplications(PackageManager.GET_META_DATA)
  .filter { pm.getLaunchIntentForPackage(it.packageName)!=null && it.sourceDir!=null }
  .map { info -> InstalledApp(pm.getApplicationLabel(info).toString(),info.packageName,info.sourceDir,info.splitSourceDirs?.toList() ?: emptyList()) }
  .sortedBy { it.label.lowercase() }
}


private fun inspectInstalledPackage(context:Context, app:InstalledApp,onProgress:(Int,Int)->Unit={_,_->}):ApkScanResult {
 val files=listOf(app.apkPath)+app.splitApkPaths
 val scans=files.mapIndexed { index,path ->
  val scan=ApkInspector.inspect(context,Uri.fromFile(File(path)))
  onProgress(index+1,files.size)
  if(index==0) scan else scan.copy(fileName="split-$index-${File(path).name}")
 }
 val base=scans.first()
 if(scans.size==1) return base
 val allFonts=scans.flatMap { it.fontAssessments }
 val allCandidates=scans.flatMap { scan -> scan.candidateFiles.map { "${scan.fileName}:$it" } }.distinct()
 val allSamples=scans.flatMap { it.extractedTextSamples }.take(100)
 val allNotes=scans.flatMap { it.notes }.distinct().toMutableList()
 allNotes += "تم فحص APK الأساسي و${scans.size-1} ملفات Split إضافية للقراءة فقط. لم يتم بعد إعادة بناء مجموعة Split وتوقيعها كحزمة واحدة؛ التصدير معطل لهذه الحالة."
 return base.copy(
  fileName=app.label,
  fileSize=scans.sumOf { it.fileSize },
  entryCount=scans.sumOf { it.entryCount },
  dexCount=scans.sumOf { it.dexCount },
  candidateFiles=allCandidates.take(200),
  extractedTextSamples=allSamples,
  notes=allNotes,
  fontAssessments=ArabicFontAnalyzer.rank(allFonts)
 )
}
