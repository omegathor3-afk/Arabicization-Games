package com.omega.arabify

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 var installedAppsLoading by remember { mutableStateOf(true) }
 var installedAppsError by remember { mutableStateOf<String?>(null) }
 var installedAppsRefreshKey by remember { mutableIntStateOf(0) }
 var appSearch by remember { mutableStateOf("") }
 var selectedInstalledApp by remember { mutableStateOf<InstalledApp?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var inspectionProgress by remember { mutableStateOf(0 to 0) }
 var modelReady by remember { mutableStateOf(false) }
 var mlKitModelReady by remember { mutableStateOf(false) }
 var bundledEngineAvailable by remember { mutableStateOf(false) }
 var translationInput by remember { mutableStateOf("The gate is locked. Find the ancient key.") }
 var translatedText by remember { mutableStateOf<String?>(null) }
 var translationBusy by remember { mutableStateOf(false) }
 var translationError by remember { mutableStateOf<String?>(null) }
 var repackageError by remember { mutableStateOf<String?>(null) }
 var selectedUri by remember { mutableStateOf<Uri?>(null) }
 var localizeBusy by remember { mutableStateOf(false) }
 var localizationProgress by remember { mutableStateOf(0 to 0) }
 var localizationReport by remember { mutableStateOf<AndroidLocalizationReport?>(null) }
 var generatedApk by remember { mutableStateOf<File?>(null) }
 var localizationError by remember { mutableStateOf<String?>(null) }
 var exportError by remember { mutableStateOf<String?>(null) }
 var showLocalizationChoice by remember { mutableStateOf(false) }
 var assetBusy by remember { mutableStateOf(false) }
 var assetProgress by remember { mutableStateOf(0 to 0) }
 var assetReport by remember { mutableStateOf<AssetLocalizationReport?>(null) }
 var assetError by remember { mutableStateOf<String?>(null) }
 var unityDeepBusy by remember { mutableStateOf(false) }
 var unityDeepProgress by remember { mutableStateOf(0 to 0) }
 var unityDeepReport by remember { mutableStateOf<UnityBundleDeepInspectionReport?>(null) }
 var unityDeepError by remember { mutableStateOf<String?>(null) }
 var backupBusy by remember { mutableStateOf(false) }
 var backupMessage by remember { mutableStateOf<String?>(null) }
 var backupError by remember { mutableStateOf<String?>(null) }
 var restoreBusy by remember { mutableStateOf(false) }
 var restoreMessage by remember { mutableStateOf<String?>(null) }
 var restoreError by remember { mutableStateOf<String?>(null) }
 var inspectionJob by remember { mutableStateOf<Job?>(null) }
 var localizationJob by remember { mutableStateOf<Job?>(null) }
 var assetJob by remember { mutableStateOf<Job?>(null) }
 var unityDeepJob by remember { mutableStateOf<Job?>(null) }

 fun launchTracked(onCreated: (Job) -> Unit, block: suspend CoroutineScope.() -> Unit) {
  val job = scope.launch(start = CoroutineStart.LAZY, block = block)
  onCreated(job)
  job.start()
 }

 LaunchedEffect(context, installedAppsRefreshKey) {
  installedAppsLoading = true
  installedAppsError = null
  try {
   installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
  } catch (cancelled: CancellationException) {
   throw cancelled
  } catch (e: Exception) {
   installedApps = emptyList()
   installedAppsError = "تعذر تحميل قائمة التطبيقات المثبتة (${e.javaClass.simpleName}). أعد المحاولة أو اختر ملف APK يدويًا."
  } finally {
   installedAppsLoading = false
  }
 }
 val clipboardManager=LocalClipboardManager.current
 val offlineTranslator = remember(context) { OfflineArabicTranslator(context) }
 DisposableEffect(offlineTranslator) { onDispose { offlineTranslator.close() } }
 LaunchedEffect(offlineTranslator) {
  val bundledAvailable = try {
   withContext(Dispatchers.IO) { offlineTranslator.isBundledEngineUsable() }
  } catch (cancelled: CancellationException) {
   throw cancelled
  } catch (_: Exception) {
   false
  }
  bundledEngineAvailable = bundledAvailable
  // First-install offline must not wait for ML Kit's optional model query.
  modelReady = bundledAvailable
  try {
   val downloaded = withContext(Dispatchers.IO) { offlineTranslator.isArabicModelDownloaded() }
   mlKitModelReady = downloaded
   modelReady = hasUsableArabicTranslationEngine(downloaded, bundledAvailable)
  } catch (cancelled: CancellationException) {
   throw cancelled
  } catch (e: Exception) {
   mlKitModelReady = false
   modelReady = bundledAvailable
   if (!bundledAvailable) translationError = e.message ?: "تعذر التحقق من حالة نموذج الترجمة."
  }
 }
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
  if(uri!=null) {
   selectedUri=uri;selectedInstalledApp=null;busy=true;error=null;result=null;localizationReport=null;generatedApk=null;unityDeepReport=null;unityDeepError=null
   launchTracked({ inspectionJob=it }) {
    val operationJob=coroutineContext[Job] ?: error("تعذر تتبع فحص APK.")
    try {
     result=withContext(Dispatchers.IO) {
      ApkInspector.inspect(context,uri) { operationJob.ensureActive() }
     }
    } catch (cancelled: CancellationException) {
     error="تم إلغاء فحص APK. لم يتم تعديل الملف الأصلي."
     throw cancelled
    } catch (e: Exception) {
     error=e.message ?: "حدث خطأ أثناء فحص الملف."
    } finally {
     busy=false
     inspectionJob=null
     showLocalizationChoice=result!=null
    }
   }
  }
 }
 val saveZipLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { targetUri:Uri? ->
  val source=generatedApk
  if(targetUri!=null && source!=null) {
   scope.launch {
    try {
     withContext(Dispatchers.IO) {
      context.contentResolver.openOutputStream(targetUri,"wt")?.use { output ->
       source.inputStream().use { input -> input.copyTo(output) }
      } ?: kotlin.error("تعذر إنشاء ملف ZIP في المكان المحدد.")
     }
     exportError=null
    } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { exportError=e.message ?: "تعذر حفظ ملف ZIP." }
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
    } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { exportError=e.message ?: "تعذر حفظ APK." }
   }
  }
 }
 val backupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { targetUri:Uri? ->
  val app=selectedInstalledApp
  if(targetUri!=null && app!=null) {
   backupBusy=true;backupError=null;backupMessage=null
   scope.launch {
    try {
     withContext(Dispatchers.IO) {
      val packageInfo=context.packageManager.getPackageInfo(app.packageName,0)
      val files=listOf(app.apkPath)+app.splitApkPaths
      val archive=File(context.cacheDir,"arabify-backup-${System.currentTimeMillis()}.zip")
      try {
       ApkBackupManager.createBackup(app.packageName,app.label,packageInfo.versionName,files.map(::File),archive)
       context.contentResolver.openOutputStream(targetUri,"wt")?.use { output -> archive.inputStream().use { it.copyTo(output) } }
        ?: error("تعذر حفظ ملف النسخة الاحتياطية.")
      } finally {
       archive.delete()
      }
     }
     backupMessage="تم إنشاء النسخة الاحتياطية والتحقق من SHA-256 لكل ملفات APK."
    } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { backupError=e.message ?: "تعذر إنشاء النسخة الاحتياطية." }
    finally { backupBusy=false }
   }
  }
 }
 val restorePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
  if(uri!=null) {
   restoreBusy=true;restoreError=null;restoreMessage=null
   scope.launch {
    val restoreDirectory=File(context.cacheDir,"arabify-restore-${System.currentTimeMillis()}")
    try {
     val backup=withContext(Dispatchers.IO) {
      val archive=copyUriToCache(context,uri,ApkBackupManager.MAX_ARCHIVE_BYTES)
      try { ApkBackupManager.extractAndVerify(archive,restoreDirectory) }
      finally { archive.delete() }
     }
     withContext(Dispatchers.Main) { ApkRestoreInstaller.install(context,backup) }
     restoreMessage="تم التحقق من الملفات وإرسال مجموعة APK إلى مُثبّت Android. أكّد التثبيت في نافذة النظام."
    } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { restoreError=e.message ?: "تعذرت استعادة النسخة الاحتياطية." }
    finally {
     withContext(NonCancellable + Dispatchers.IO) {
      runCatching { restoreDirectory.deleteRecursively() }
     }
     restoreBusy=false
    }
   }
  }
 }

 fun startSelectedLocalization(forceAsset: Boolean = false) {
  val scan=result ?: return
  val uri=selectedUri ?: return
  if(localizeBusy||assetBusy||unityDeepBusy) return
  if(!modelReady) {
   localizationError="لا يوجد محرك ترجمة جاهز. نزّل نموذج ML Kit عبر اتصال Wi-Fi أو استخدم نسخة تتضمن محرك Bergamot المضمّن."
   assetError="لا يوجد محرك ترجمة جاهز. نزّل نموذج ML Kit عبر اتصال Wi-Fi أو استخدم نسخة تتضمن محرك Bergamot المضمّن."
   showLocalizationChoice=false
   return
  }
  showLocalizationChoice=false
  generatedApk=null
  localizationReport=null
  assetReport=null
  if(scan.engine==GameEngine.ANDROID && !forceAsset) {
   if((selectedInstalledApp?.splitCount ?: 0)>0) {
    localizationError="تعريب مجموعات Split من موارد Android غير مدعوم بعد؛ أوقفنا العملية بأمان."
    return
   }
   localizeBusy=true;localizationError=null;localizationProgress=0 to 0;localizationReport=null
   val stamp=System.currentTimeMillis()
   val unsigned=File(context.cacheDir,"arabify-$stamp-unsigned.apk")
   val signed=File(context.cacheDir,"arabify-$stamp-signed.apk")
   launchTracked({ localizationJob=it }) {
    var input:File?=null
    var completed=false
    try {
     val report=withContext(Dispatchers.IO) {
      input=copyUriToCache(context,uri)
      AndroidResourceLocalizer.localizeEnglishStrings(input!!,unsigned,signed,offlineTranslator,context) { done,total ->
       if(localizationJob?.isActive==true) scope.launch {
        if(localizationJob?.isActive==true) localizationProgress=done to total
       }
      }
     }
     localizationReport=report;generatedApk=report.signedApk;completed=true
    } catch (cancelled: CancellationException) {
     localizationError="تم إلغاء التعريب. بقي APK الأصلي دون تعديل؛ ابدأ العملية من جديد عند الحاجة."
     throw cancelled
    } catch (e: Exception) { localizationError=e.message ?: "تعذر تعريب موارد اللعبة." }
    finally {
     input?.delete()
     unsigned.delete()
     if(!completed) signed.delete()
     localizeBusy=false;localizationJob=null
    }
   }
   return
  }

  val splitApp=selectedInstalledApp?.takeIf { it.splitCount>0 }
  if(splitApp!=null) {
   assetBusy=true;assetError=null;assetReport=null;assetProgress=0 to 0
   val stamp=System.currentTimeMillis()
   val workDir=File(context.cacheDir,"arabify-splitset-$stamp")
   val zipOut=File(context.cacheDir,"arabify-splitset-$stamp.zip")
   launchTracked({ assetJob=it }) {
    var completed=false
    try {
     val report=withContext(Dispatchers.IO) {
      val inputs=listOf(File(splitApp.apkPath))+splitApp.splitApkPaths.map { File(it) }
      val (setReport,signed)=SplitApkSetLocalizer.localizeAndSignSet(context,inputs,workDir,offlineTranslator) { done,total ->
       if(assetJob?.isActive==true) scope.launch {
        if(assetJob?.isActive==true) assetProgress=done to total
       }
      }
      ApkBackupManager.createBackup(splitApp.packageName,splitApp.label,null,signed,zipOut)
      AssetLocalizationReport(
       splitApp.packageName,
       setReport.members.sumOf { it.report.filesDiscovered },
       setReport.members.sumOf { it.report.filesTranslated },
       setReport.stringsTranslated,
       setReport.stringsSkipped,
       zipOut
      )
     }
     assetReport=report;generatedApk=report.outputApk;completed=true
    } catch (cancelled: CancellationException) {
     assetError="تم إلغاء تعريب مجموعة Split. حُذفت الملفات المؤقتة ولم يتغير التطبيق المثبت."
     throw cancelled
    } catch (e: Exception) { assetError=e.message ?: "تعذر تعريب مجموعة Split." }
    finally {
     workDir.deleteRecursively()
     if(!completed) zipOut.delete()
     assetBusy=false;assetJob=null
    }
   }
   return
  }

  assetBusy=true;assetError=null;assetReport=null;assetProgress=0 to 0
  val stamp=System.currentTimeMillis()
  val unsigned=File(context.cacheDir,"arabify-assets-$stamp-unsigned.apk")
  val signed=File(context.cacheDir,"arabify-assets-$stamp-signed.apk")
  val dispatchStage=File(context.cacheDir,"arabify-dispatch-$stamp")
  val dispatchSigned=File(context.cacheDir,"arabify-dispatch-$stamp-signed.apk")
  val bundleStage=File(context.cacheDir,"arabify-assets-$stamp-unsigned-bundles.apk")
  val bundleWork=File(context.cacheDir,"arabify-assets-$stamp-unsigned-bundlework")
  launchTracked({ assetJob=it }) {
   var input:File?=null
   var outputApk:File?=null
   var completed=false
   try {
    val report=withContext(Dispatchers.IO) {
     input=copyUriToCache(context,uri)
     val onProgress:(Int,Int)->Unit = { done,total ->
      if(assetJob?.isActive==true) scope.launch {
       if(assetJob?.isActive==true) assetProgress=done to total
      }
     }
     if(scan.engine==GameEngine.UNITY) {
      UnityMonoLocalizer.localize(context,input!!,unsigned,signed,offlineTranslator,onProgress)
     } else {
      // Planner dispatch runs the same chain (slot -> gettext -> in-place)
      // inside this tracked job, so إلغاء التعريب keeps working. Android
      // resources rewriting stays out of this chain on purpose (it lives in
      // the installed-app and second file-pick paths).
      val dispatchDeps=AdapterRuntimeDeps(
       stagingDir=dispatchStage,
       fontBytes={ f -> context.assets.open(f.assetPath).use { it.readBytes() } },
       translate={ text -> if(text.isBlank()) text else offlineTranslator.translateEnglishToArabic(text) },
       onProgress=onProgress
      )
      val registry=ArabifyAdapters.runtimeChainRegistry(dispatchDeps)
      val artifact=ArtifactRef(id=input!!.name,displayName=scan.fileName,uri=input!!.absolutePath,kind=ArtifactKind.APK,sizeBytes=input!!.length())
      val dispatch=PlannerDispatch(registry).dispatch(artifact,ResourceBudget())
      val adapter=registry.find(dispatch.adapterId) ?: error("المهيئ غير موجود: ${dispatch.adapterId}")
      val locOutput=(adapter as? LibGdxLocaleSlotAdapter)?.lastLocalizationOutput
       ?: (adapter as? GettextPoAdapter)?.lastLocalizationOutput
       ?: (adapter as? AndroidAssetsInPlaceAdapter)?.lastLocalizationOutput
       ?: error("المسار ${dispatch.adapterId} لم ينتج ملف APK.")
      ApkSigningService.signAndVerify(context,locOutput.outputApk,dispatchSigned)
      when(locOutput) {
       is AdapterLocalizationOutput.LocaleSlot -> AssetLocalizationReport(
        scan.packageName,1,1,locOutput.report.stringsTranslated,locOutput.report.stringsSkipped,dispatchSigned,
        fitWarnings=locOutput.report.fitWarnings,fitWarningSamples=locOutput.report.fitWarningSamples,
        stringsRepaired=locOutput.report.stringsRepaired,skipReasons=locOutput.report.skipReasons,
        repairReasons=locOutput.report.repairReasons
       )
       is AdapterLocalizationOutput.Gettext -> AssetLocalizationReport(
        scan.packageName,1,1,locOutput.report.stringsTranslated,locOutput.report.stringsSkipped,dispatchSigned,
        stringsRepaired=locOutput.report.stringsRepaired,skipReasons=locOutput.report.skipReasons,
        repairReasons=locOutput.report.repairReasons
       )
       is AdapterLocalizationOutput.AssetsInPlace -> locOutput.report.copy(packageName=scan.packageName,outputApk=dispatchSigned)
       is AdapterLocalizationOutput.AndroidResources -> error("موارد Android ليست جزءًا من سلسلة الملفات.")
      }
     }
    }
    assetReport=report;generatedApk=report.outputApk;outputApk=report.outputApk;completed=true
   } catch (cancelled: CancellationException) {
    assetError="تم إلغاء التعريب. حُذفت الملفات المؤقتة ولم يتم استبدال اللعبة الأصلية."
    throw cancelled
   } catch (noAdapter: LocalizationDispatchException) {
    assetError="تعذر تعريب ملفات النصوص بأي مسار مدعوم."
   } catch (e: Exception) { assetError=e.message ?: "تعذر تعريب ملفات النصوص." }
   finally {
    input?.delete()
    listOf(unsigned,bundleStage).forEach { it.delete() }
    dispatchStage.deleteRecursively()
    listOf(signed,dispatchSigned).filter { it != outputApk }.forEach { it.delete() }
    bundleWork.deleteRecursively()
    assetBusy=false;assetJob=null
   }
  }
 }
 val activityReport=buildString {
  appendLine("ARABIFY — التقرير الكامل")
  appendLine("التطبيق: ${selectedInstalledApp?.label ?: result?.fileName ?: "غير محدد"}")
  appendLine("الحزمة: ${result?.packageName ?: selectedInstalledApp?.packageName ?: "غير معروفة"}")
  appendLine("المحرك: ${result?.engine?.displayName ?: "لم يتم تحديده"}")
  result?.let { scan ->
   appendLine("الإصدار: ${scan.versionName ?: "غير معروف"}")
   appendLine("الحجم: ${scan.fileSize} bytes | ZIP: ${scan.entryCount} | DEX: ${scan.dexCount}")
   appendLine("الملفات المرشحة:")
   scan.candidateFiles.forEach { appendLine(it) }
   appendLine("عينات النصوص:")
   scan.extractedTextSamples.forEach { appendLine(it) }
   appendLine("الخطوط:")
   scan.fontAssessments.forEach { appendLine("${it.path} — ${it.coveragePercent}% — shaping=${it.hasArabicShapingTables}") }
   appendLine("ملاحظات الفحص:")
   scan.notes.forEach { appendLine(it) }
   scan.unityAssetInventory?.let { appendLine(it.report) }
   unityDeepReport?.let { deep ->
    appendLine(deep.summary)
    deep.items.forEach { item ->
     appendLine(item.path + " — " + item.status)
     item.details.forEach { appendLine("  " + it) }
    }
    deep.textAssetPreviews.forEachIndexed { index, preview ->
     appendLine("Unity TextAsset #${index + 1}: ${preview.name} — ${preview.serializedFilePath} — PathID ${preview.pathId}")
     preview.pathName?.let { appendLine("Path: " + it) }
     appendLine(preview.scriptPreview)
     if(preview.truncated) appendLine("المعاينة مختصرة؛ النص الكامل أطول من الحد المعروض.")
    }
    deep.notes.forEach { appendLine("ملاحظة فحص UnityFS: " + it) }
   }
   unityDeepError?.let { appendLine("خطأ فحص UnityFS: " + it) }
  }
  appendLine("محرك الترجمة: ${offlineTranslator.lastEngine ?: if(mlKitModelReady) "ML Kit (نموذج مُنزّل)" else if(bundledEngineAvailable) "Bergamot المضمّن (متاح لهذا ABI)" else "غير جاهز"}")
  translatedText?.let { appendLine("الترجمة التجريبية: $it") }
  translationError?.let { appendLine("خطأ الترجمة: $it") }
  localizationReport?.let { appendLine("موارد Android: ${it.translatedStrings}/${it.discoveredStrings} نصًا") }
  localizationError?.let { appendLine("خطأ تعريب Android: $it") }
  assetReport?.let { report ->
   appendLine("ملفات النصوص: ${report.filesTranslated}/${report.filesDiscovered}؛ مترجم ${report.stringsTranslated}؛ متروك ${report.stringsSkipped}")
   if(report.stringsRepaired>0) {
    appendLine("إصلاحات آلية بعد الترجمة: ${report.stringsRepaired} (" + report.repairReasons.entries.joinToString("، ") { it.key + ": " + it.value } + ")")
   }
   if(report.skipReasons.isNotEmpty()) {
    appendLine("أسباب ترك بعض النصوص: " + report.skipReasons.entries.joinToString("، ") { it.key + ": " + it.value })
   }
   if(report.fitWarnings>0) {
    appendLine("تحذيرات احتمال تجاوز العرض (تقدير آلي وليس قياس خط فعلي): ${report.fitWarnings}")
    report.fitWarningSamples.forEach { appendLine("  " + it) }
   }
  }
  assetError?.let { appendLine("خطأ تعريب الملفات: $it") }
  if(localizeBusy) appendLine("حالة التعريب: ${localizationProgress.first}/${localizationProgress.second}")
  if(assetBusy) appendLine("حالة تعريب الملفات: ${assetProgress.first}/${assetProgress.second}")
  error?.let { appendLine("خطأ الفحص: $it") }
  exportError?.let { appendLine("خطأ التصدير: $it") }
 }
 ArabifyTheme {

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
  if(showLocalizationChoice && result!=null) AlertDialog(
   onDismissRequest={showLocalizationChoice=false},
   title={Text("هل تريد بدء تعريب التطبيق؟")},
   text={Text("تم اختيار ${selectedInstalledApp?.label ?: result?.fileName}. يمكنك بدء التعريب الآن أو المتابعة دون تعديل. لن يتم تغيير التطبيق الأصلي.")},
   confirmButton={TextButton(onClick={startSelectedLocalization()},enabled=!busy&&!localizeBusy&&!assetBusy&&!unityDeepBusy){Text("ابدأ التعريب")}},
   dismissButton={TextButton(onClick={showLocalizationChoice=false}){Text("ليس الآن")}}
  )
  Surface(Modifier.fillMaxSize(),color=ArabifyPalette.canvas) {
   Box(Modifier.fillMaxSize()) {
    HextechBackdrop()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp, vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    item {
     HextechPanel(containerColor=Color.Transparent, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().background(ArabifyGradients.hero).padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("OMEGA DEVELOPMENT LAB",color=ArabifyPalette.textMuted,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp,fontFamily=ArabifyFontFamily)
       Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text("ARABIFY",style=TextStyle(fontFamily=ArabifyFontFamily,fontSize=30.sp,fontWeight=FontWeight.Black,letterSpacing=3.sp,brush=rememberGoldShimmerBrush()))
        Text("LOCAL ENGINE  ·  0.2",color=ArabifyPalette.primary,fontSize=10.sp,fontWeight=FontWeight.Bold,fontFamily=ArabifyFontFamily)
       }
       GoldDivider()
       Text("خلّي ألعابك تتكلم عربي",color=ArabifyPalette.textPrimary,fontSize=23.sp,fontWeight=FontWeight.Black,fontFamily=ArabifyFontFamily)
       Text("محرك لفحص الألعاب والتطبيقات، واكتشاف النصوص والخطوط، وتجربة التعريب بأمان على جهازك.",color=ArabifyPalette.textSecondary,fontSize=13.sp,lineHeight=20.sp,fontFamily=ArabifyFontFamily)
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.clip(CutCornerShape(8.dp)).background(ArabifyGradients.neonSoft).border(1.dp,ArabifyPalette.primary.copy(alpha=0.5f),CutCornerShape(8.dp)).padding(horizontal=10.dp,vertical=4.dp)) {
         Text("خصوصية محلية",color=ArabifyPalette.primary,fontSize=10.sp,fontWeight=FontWeight.SemiBold,fontFamily=ArabifyFontFamily)
        }
        Box(Modifier.clip(CutCornerShape(8.dp)).background(ArabifyPalette.surfaceRaised).border(1.dp,ArabifyPalette.outline,CutCornerShape(8.dp)).padding(horizontal=10.dp,vertical=4.dp)) {
         Text("فحص قبل التعديل",color=ArabifyPalette.textSecondary,fontSize=10.sp,fontFamily=ArabifyFontFamily)
        }
       }
      }
     }
    }
    item {
     HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("اختر من تطبيقات وألعاب هاتفك",color=ArabifyPalette.textPrimary,fontSize=19.sp,fontWeight=FontWeight.Bold)
       Text("تظهر هنا التطبيقات المثبتة التي لها أيقونة تشغيل. اختر تطبيقًا لقراءة ملف التثبيت الأساسي محليًا.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       OutlinedTextField(value=appSearch,onValueChange={appSearch=it},label={Text("ابحث عن لعبة أو تطبيق")},modifier=Modifier.fillMaxWidth(),singleLine=true)
       val visibleApps=installedApps.filter { it.label.contains(appSearch,true) || it.packageName.contains(appSearch,true) }
       when (val contentState = installedAppsContentState(
        isLoading = installedAppsLoading,
        error = installedAppsError,
        totalCount = installedApps.size,
        visibleCount = visibleApps.size
       )) {
        InstalledAppsContentState.Loading -> Row(
         Modifier.fillMaxWidth().padding(vertical=8.dp),
         verticalAlignment=Alignment.CenterVertically,
         horizontalArrangement=Arrangement.spacedBy(10.dp)
        ) {
         CircularProgressIndicator(
          modifier=Modifier.size(20.dp).semantics { contentDescription = "جارٍ تحميل التطبيقات المثبتة" },
          strokeWidth=2.dp
         )
         Text("جارٍ البحث عن التطبيقات والألعاب المثبتة…",color=ArabifyPalette.info,fontSize=12.sp)
        }
        is InstalledAppsContentState.Error -> Column(
         Modifier.fillMaxWidth().padding(vertical=6.dp),
         verticalArrangement=Arrangement.spacedBy(8.dp)
        ) {
         Text(contentState.message,color=ArabifyPalette.danger,fontSize=12.sp)
         OutlinedButton(onClick={ installedAppsRefreshKey++ },enabled=!busy) {
          Text("إعادة المحاولة")
         }
        }
        is InstalledAppsContentState.Empty -> Column(
         Modifier.fillMaxWidth().padding(vertical=6.dp),
         verticalArrangement=Arrangement.spacedBy(8.dp)
        ) {
         val message = when(contentState.reason) {
          InstalledAppsEmptyReason.NO_LAUNCHER_APPS -> "لم يتم العثور على تطبيقات لها أيقونة تشغيل. يمكنك اختيار ملف APK يدويًا."
          InstalledAppsEmptyReason.NO_SEARCH_MATCHES -> "لا توجد نتائج تطابق البحث. جرّب اسمًا أو رقم حزمة مختلفًا."
         }
         Text(message,color=ArabifyPalette.textMuted,fontSize=12.sp)
         if(contentState.reason==InstalledAppsEmptyReason.NO_LAUNCHER_APPS) {
          OutlinedButton(onClick={ installedAppsRefreshKey++ },enabled=!busy) { Text("إعادة فحص التطبيقات") }
         }
        }
        is InstalledAppsContentState.Results -> {
         Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
          Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
           Text("التطبيقات المتاحة",color=ArabifyPalette.textSecondary,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
           Text("${contentState.count} نتيجة",color=ArabifyPalette.primary,fontSize=11.sp,fontWeight=FontWeight.Bold)
          }
          TextButton(onClick={ installedAppsRefreshKey++ },enabled=!busy) { Text("تحديث القائمة") }
         }
         LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
          items(visibleApps, key={it.packageName}) { app ->
          val rowSelected=selectedInstalledApp?.packageName==app.packageName
          Row(Modifier.fillMaxWidth().clip(CutCornerShape(10.dp)).background(if(rowSelected) ArabifyPalette.surfaceAccent else ArabifyPalette.surfaceRaised).border(1.dp,if(rowSelected) ArabifyPalette.outlineNeon else ArabifyPalette.outline,CutCornerShape(10.dp)).padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
           val appIcon=rememberInstalledAppIcon(context,app.packageName)
           Box(Modifier.size(44.dp).clip(CutCornerShape(10.dp)).background(ArabifyPalette.canvas).border(1.dp,ArabifyPalette.outlineNeon.copy(alpha=0.7f),CutCornerShape(10.dp)).padding(4.dp)) {
            if (appIcon != null) {
             Image(bitmap=appIcon,contentDescription="أيقونة التطبيق",modifier=Modifier.fillMaxSize().clip(CutCornerShape(6.dp)))
            } else {
             Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
              Text(app.label.take(1).uppercase(),color=ArabifyPalette.primary,fontWeight=FontWeight.Bold,fontSize=18.sp)
             }
            }
           }
           Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(app.label,color=ArabifyPalette.textPrimary,fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(app.packageName,color=ArabifyPalette.textMuted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(app.splitCount>0) Text("حزمة أساسية + ${app.splitCount} ملفات Split إضافية",color=ArabifyPalette.warning,fontSize=10.sp)
           }
           OutlinedButton(onClick={
            selectedInstalledApp=app
            unityDeepReport=null;unityDeepError=null
            selectedUri=Uri.fromFile(File(app.apkPath))
            busy=true;inspectionProgress=0 to (1+app.splitCount);error=null;result=null;localizationReport=null;generatedApk=null
            launchTracked({ inspectionJob=it }) {
             val operationJob=coroutineContext[Job] ?: error("تعذر تتبع فحص التطبيق المثبت.")
             try {
              result=withContext(Dispatchers.IO) {
               inspectInstalledPackage(
                context,
                app,
                onProgress={done,total -> scope.launch {
                 if(inspectionJob?.isActive==true) inspectionProgress=done to total
                }},
                cancellationCheck={operationJob.ensureActive()}
               )
              }
             } catch (cancelled: CancellationException) {
              error="تم إلغاء فحص التطبيق المثبت. لم يتم تعديل أي ملف."
              throw cancelled
             } catch (e: Exception) {
              error=e.message ?: "تعذر فحص التطبيق المثبت."
             } finally {
              busy=false
              inspectionJob=null
              showLocalizationChoice=result!=null
             }
            }
           },enabled=!busy,shape=CutCornerShape(10.dp),
           colors=ButtonDefaults.outlinedButtonColors(
            containerColor=if(rowSelected) ArabifyPalette.surfaceAccent else Color.Transparent,
            contentColor=ArabifyPalette.primary),
           border=BorderStroke(1.dp,if(rowSelected) ArabifyPalette.primary else ArabifyPalette.outline),
           modifier=Modifier.semantics { contentDescription = "اختيار ${app.label} ${app.packageName}" }) { Text(if(rowSelected) "محدد" else "اختيار") }
          }
         }
         }
        }
       }
       if(busy && selectedInstalledApp!=null) {
        val fraction=if(inspectionProgress.second>0) inspectionProgress.first.toFloat()/inspectionProgress.second else 0f
        LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
        Text("فحص ملفات التثبيت: ${inspectionProgress.first} من ${inspectionProgress.second}",color=ArabifyPalette.info,fontSize=11.sp)
        TextButton(onClick={ inspectionJob?.cancel(CancellationException("ألغى المستخدم فحص التطبيق المثبت.")) }) { Text("إلغاء الفحص") }
       }
       Text("يتم فحص ملفات APK الأساسية وملفات Split الإضافية كلٌ على حدة. تعريب نصوص مجموعات Split متاح من مسار ملفات النصوص: تُعاد كتابة كل الملفات وتُوقَّع بمفتاح واحد وتُصدَّر كملف ZIP واحد يُثبَّت من قسم الاستعادة.",color=ArabifyPalette.warning,fontSize=11.sp)
      }
     }
    }
    if(selectedInstalledApp!=null) item {
     HextechPanel(containerColor=ArabifyPalette.surfaceAccent, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
       Text("نسخة احتياطية آمنة للعبة المختارة",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
       Text("سيتم حفظ APK الأساسي وكل ملفات Split في ملف ZIP واحد، مع manifest يحتوي SHA-256 والحجم لكل ملف. يتم فحص النسخة بعد إنشائها وقبل السماح باستعادتها.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       ArabifyCtaButton(if(backupBusy) "جارٍ إنشاء النسخة الاحتياطية..." else "إنشاء وحفظ نسخة احتياطية",onClick={backupLauncher.launch("arabify-${selectedInstalledApp!!.packageName}-backup.zip")},enabled=!backupBusy)
       backupMessage?.let { Text(it,color=ArabifyPalette.success,fontSize=12.sp) }
       backupError?.let { Text(it,color=ArabifyPalette.danger,fontSize=12.sp) }
      }
     }
    }
    item {
     HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
       Text("استعادة نسخة APK احتياطية",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
       Text("اختر ملف ARABIFY ZIP. سيتحقق التطبيق من manifest والأحجام وSHA-256، ثم يرسل كل ملفات APK إلى مُثبّت Android في جلسة واحدة، بما فيها ملفات Split.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       ArabifyCtaButton(if(restoreBusy) "جارٍ التحقق والاستعادة..." else "اختيار نسخة احتياطية واستعادتها",onClick={
        if(!context.packageManager.canRequestPackageInstalls()) {
         context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else restorePicker.launch(arrayOf("application/zip","application/octet-stream"))
       },enabled=!restoreBusy)
       restoreMessage?.let { Text(it,color=ArabifyPalette.success,fontSize=12.sp) }
       restoreError?.let { Text(it,color=ArabifyPalette.danger,fontSize=12.sp) }
      }
     }
    }
    item {
     HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("محرك الترجمة المحلي",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
       Text("تتضمن نسخة الهاتف ذات مكتبة ARM64 محرك Bergamot ووزنه العربي/الإنجليزي داخل APK؛ لذلك يمكن أن تبدأ الترجمة من أول تثبيت دون تنزيل. عند توفر نموذج ML Kit محليًا يُفضَّل، ويمكن تنزيله اختياريًا. لا يعني توفر المحرك أن كل ملفات اللعبة مدعومة.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       Text(if(bundledEngineAvailable) "محرك Bergamot المضمّن متاح لهذا المعالج." else "محرك Bergamot المضمّن غير متاح في هذه البنية/ABI؛ يلزم نموذج ML Kit مُنزّل.",color=if(modelReady) ArabifyPalette.success else ArabifyPalette.warningText,fontSize=11.sp)
       ArabifyCtaButton(if(mlKitModelReady) "نموذج ML Kit مُنزّل" else "تنزيل ML Kit الاختياري",onClick={
        translationBusy=true;translationError=null
        scope.launch {
         try {
          withContext(Dispatchers.IO){offlineTranslator.prepareForOfflineUse()}
          mlKitModelReady=true
          modelReady=true
         }
         catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception){translationError=e.message ?: "تعذر تجهيز نموذج ML Kit."}
         finally{translationBusy=false}
        }
       },enabled=!translationBusy&&!mlKitModelReady)
       OutlinedTextField(value=translationInput,onValueChange={translationInput=it},label={Text("اختبر المحرك بجملة قصيرة")},modifier=Modifier.fillMaxWidth())
       ArabifyCtaButton("اختبار الترجمة المحلية",onClick={
        translationBusy=true;translationError=null;translatedText=null
        scope.launch {
         try { translatedText=withContext(Dispatchers.IO){offlineTranslator.translateEnglishToArabic(translationInput)} }
         catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception){translationError=e.message ?: "فشلت الترجمة."}
         finally{translationBusy=false}
        }
       },enabled=modelReady&&!translationBusy&&translationInput.isNotBlank())
       if(translationBusy) CircularProgressIndicator()
       translatedText?.let{Text(it,color=ArabifyPalette.accent,fontSize=17.sp)}
       translationError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
      }
     }
    }
    item {
     HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("أو اختر ملف APK يدويًا",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
       Text("هذا الخيار احتياطي للتطبيقات التي لا تظهر في القائمة أو لملف APK خارجي.",color=ArabifyPalette.textSecondary,fontSize=13.sp)
       ArabifyCtaButton("اختيار ملف APK",onClick={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream"))},enabled=!busy)
       if(busy && selectedInstalledApp==null) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        CircularProgressIndicator()
        Text("جارٍ فحص بنية الملف...")
        TextButton(onClick={ inspectionJob?.cancel(CancellationException("ألغى المستخدم فحص ملف APK.")) }) { Text("إلغاء الفحص") }
       }
       error?.let{Text(it,color=ArabifyPalette.danger)}
      }
     }
    }
    result?.let { scan ->
     item{SectionTitle("نتيجة الفحص")}
     item {
      HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
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
      HextechPanel(containerColor=ArabifyPalette.surfaceAccent, contentPadding=PaddingValues(0.dp)) {
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
       HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
         Text("تعريب موارد Android",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text("هذا المسار التجريبي يضيف موارد string عربية إلى resources.arsc. لا يشمل النصوص المخبأة داخل DEX أو محركات الألعاب.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
         Text("يجب تنزيل نموذج الترجمة أولًا. التوقيع الجديد يعني أن تثبيت النسخة المعربة بدل الأصلية قد يتطلب إزالة النسخة الأصلية، وقد يؤدي ذلك لفقد بياناتها.",color=ArabifyPalette.warningText,fontSize=12.sp)
         ArabifyCtaButton(if(localizeBusy) "جارٍ التعريب..." else "ابدأ تعريب موارد اللعبة",onClick={startSelectedLocalization()},enabled=modelReady&&!localizeBusy&&!assetBusy&&!unityDeepBusy&&selectedUri!=null&&(selectedInstalledApp?.splitCount ?: 0)==0)
         if(localizeBusy) {
          val fraction=if(localizationProgress.second>0) localizationProgress.first.toFloat()/localizationProgress.second.toFloat() else 0f
          LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
          Text(if(localizationProgress.second>0) "تعريب الموارد: ${localizationProgress.first} من ${localizationProgress.second} (${(fraction*100).toInt()}%)" else "تهيئة وفحص موارد التطبيق…",color=Color.White,fontSize=12.sp)
          TextButton(onClick={ localizationJob?.cancel(CancellationException("ألغى المستخدم تعريب موارد Android.")) }) { Text("إلغاء التعريب") }
         }
         localizationReport?.let { report ->
          Text("اكتمل إنشاء الموارد العربية: ${report.translatedStrings} نصًا من ${report.discoveredStrings}.",color=ArabifyPalette.success,fontSize=13.sp)
          ArabifyCtaButton("الخطوة الأخيرة: حفظ APK المعرب",onClick={saveLauncher.launch("arabify_arabic.apk")})
         }
         localizationError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
         exportError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
        }
       }
      }
     }
     if(scan.candidateFiles.isNotEmpty()) {
      item {
       HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
         Text("تعريب ملفات النصوص داخل اللعبة",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text("يعالج هذا المسار ملفات JSON وTXT وProperties وStrings الموجودة داخل assets أو res/raw أو مجلدات localization/strings. يحافظ على مفاتيح JSON وخصائص الإعدادات والمتغيرات المعروفة.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
         Text("لا يترجم ملفات Unreal أو ملفات Unity الثنائية خارج النطاق الموضح. لا تعتبر النتيجة تعريبًا كاملًا للعبة.",color=ArabifyPalette.warningText,fontSize=12.sp)
         if(scan.engine==GameEngine.UNITY) Text(UnityMonoLocalizer.supportedScope,color=ArabifyPalette.warningText,fontSize=12.sp)
         ArabifyCtaButton(if(assetBusy) "جارِ تعريب ملفات النصوص..." else if((selectedInstalledApp?.splitCount ?: 0)>0) "تعريب مجموعة Split وإنشاء ZIP" else "تعريب ملفات النصوص وإنشاء APK",onClick={startSelectedLocalization(forceAsset = true)},enabled=modelReady&&!assetBusy&&!localizeBusy&&!unityDeepBusy&&selectedUri!=null)
         if(assetBusy) {
          val fraction=if(assetProgress.second>0) assetProgress.first.toFloat()/assetProgress.second.toFloat() else 0f
          LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
          Text(if(assetProgress.second>0) "تعريب الملفات: ${assetProgress.first} من ${assetProgress.second} (${(fraction*100).toInt()}%)" else "استخراج الملفات وتحليل النصوص…",color=Color.White,fontSize=12.sp)
          TextButton(onClick={ assetJob?.cancel(CancellationException("ألغى المستخدم تعريب ملفات اللعبة.")) }) { Text("إلغاء التعريب") }
         }
         assetReport?.let { report ->
          Text("الملفات: ${report.filesTranslated} / ${report.filesDiscovered} • النصوص المترجمة: ${report.stringsTranslated} • المتروكة: ${report.stringsSkipped}",color=ArabifyPalette.success,fontSize=13.sp)
          if((report.bundleStringsTranslated ?: 0) > 0) Text("منها داخل حزم UnityFS: ${report.bundleFilesTranslated} ملف نصي، ${report.bundleStringsTranslated} نصًا مترجمًا.",color=ArabifyPalette.success,fontSize=11.sp)
          if(report.outputApk.extension=="zip") {
           ArabifyCtaButton("الخطوة الأخيرة: حفظ ZIP مجموعة Split المعربة",onClick={saveZipLauncher.launch("arabify_splitset_arabic.zip")})
           Text("ثبّت المجموعة من قسم الاستعادة أدناه؛ سيُرسل كل ملفات APK إلى مُثبّت Android في جلسة واحدة.",color=ArabifyPalette.textSecondary,fontSize=11.sp)
          } else {
           ArabifyCtaButton("الخطوة الأخيرة: حفظ APK بعد التعريب",onClick={saveLauncher.launch("arabify_assets_arabic.apk")})
           val repackagePkg=report.packageName
           if(!repackagePkg.isNullOrBlank()&&report.outputApk.isFile) {
            val repackageBlocked by produceState<Boolean?>(initialValue=null,repackagePkg,report.outputApk.absolutePath) {
             value=withContext(Dispatchers.IO){runCatching{RepackageInstallHelper.installedOriginalBlocksInstall(context,repackagePkg,report.outputApk)}.getOrDefault(false)}
            }
            if(repackageBlocked==true) {
             Text("اللعبة الأصلية مثبتة بتوقيعها الخاص، وأندرويد يرفض النسخة المعربة كتحديث لاختلاف التوقيع. احذف اللعبة الأصلية أولًا ثم ثبّت النسخة المعربة. تنبيه: حذف اللعبة يحذف بياناتها المحفوظة محليًا.",color=ArabifyPalette.warningText,fontSize=12.sp)
             ArabifyCtaButton("الخطوة ١: حذف اللعبة الأصلية (بموافقتك في نافذة النظام)",onClick={repackageError=null;runCatching{context.startActivity(RepackageInstallHelper.uninstallIntent(repackagePkg))}.onFailure{repackageError=it.message}})
            }
            ArabifyCtaButton(if(repackageBlocked==true) "الخطوة ٢: تثبيت النسخة المعربة" else "تثبيت النسخة المعربة",onClick={
             repackageError=null
             runCatching{RepackageInstallHelper.install(context,report.outputApk,repackagePkg)}.onFailure{repackageError=it.message ?: "تعذر إرسال الملف إلى مثبّت أندرويد."}
            })
            Text("التثبيت يتم بموافقتك في نافذة مثبّت أندرويد. النسخة المعربة موقعة بمفتاح ARABIFY وليس بمفتاح اللعبة الأصلية.",color=ArabifyPalette.textSecondary,fontSize=11.sp)
            repackageError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
           }
          }
         }
         assetError?.let{Text(it,color=ArabifyPalette.danger,fontSize=12.sp)}
        }
       }
      }
     }
     scan.unityAssetInventory?.let { inventory ->
      item {
       HextechPanel(containerColor=ArabifyPalette.surface, contentPadding=PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
         Text("جرد موارد Unity — قراءة فقط",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
         Text(inventory.report,color=ArabifyPalette.textBody,fontSize=12.sp)
         Text("الفحص الحالي يقرأ SerializedFile metadata ويفحص TextAsset (class ID 49) فقط عندما يتوفر TypeTree كامل مطابق، لعدّ نصوص UTF-8 للعرض. لا يكتب داخل object payloads أو AssetBundles أو DLL.",color=ArabifyPalette.warningText,fontSize=12.sp)
         inventory.notes.forEach { note ->
          Text("• " + note,color=ArabifyPalette.warningText,fontSize=10.sp)
         }
         if (inventory.bundleFiles > 0) {
          Text("فحص UnityFS العميق اختياري: يفك ضغط الحزم المدعومة، ويستخرج CAB nodes إلى مجلد مؤقت، ويفهرس metadata، ثم يتحقق قراءةً فقط من TextAsset ذات المخطط المعروف. لا يكتب داخل APK ولا يغيّر ملفات اللعبة.",color=ArabifyPalette.textSecondary,fontSize=11.sp)
          Button(
           onClick={
            val uri=selectedUri
            if(uri!=null) {
             unityDeepBusy=true;unityDeepError=null;unityDeepReport=null;unityDeepProgress=0 to inventory.bundleFiles
             launchTracked({ unityDeepJob=it }) {
              val operationJob=coroutineContext[Job] ?: error("تعذر تتبع فحص UnityFS.")
              try {
               val report=withContext(Dispatchers.IO) {
                val input=copyUriToCache(context,uri)
                try {
                 UnityBundleDeepInspector.inspect(
                  input,inventory,context.cacheDir,
                  onProgress={done,total -> if(unityDeepJob?.isActive==true) scope.launch {
                   if(unityDeepJob?.isActive==true) unityDeepProgress=done to total
                  }},
                  cancellationCheck={operationJob.ensureActive()}
                 )
                } finally { input.delete() }
               }
               unityDeepReport=report
              } catch (cancelled: CancellationException) {
               unityDeepError="تم إلغاء فحص UnityFS. لم يتم تعديل أي أصل."
               throw cancelled
              } catch (e: Exception) {
               unityDeepError=e.message ?: "تعذر فحص UnityFS بأمان."
              } finally {
               unityDeepBusy=false
               unityDeepJob=null
              }
             }
            }
           },
           enabled=!unityDeepBusy&&!busy&&!localizeBusy&&!assetBusy&&selectedUri!=null&&(selectedInstalledApp?.splitCount ?: 0)==0,
           modifier=Modifier.fillMaxWidth()
          ) { Text(if(unityDeepBusy) "جارٍ فحص UnityFS واستخراج CAB..." else "فحص UnityFS واستخراج CAB — قراءة فقط") }
          if(unityDeepBusy) {
           val fraction=if(unityDeepProgress.second>0) unityDeepProgress.first.toFloat()/unityDeepProgress.second else 0f
           LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
           Text("فحص الحزم: " + unityDeepProgress.first + " من " + unityDeepProgress.second,color=ArabifyPalette.info,fontSize=11.sp)
           TextButton(onClick={ unityDeepJob?.cancel(CancellationException("ألغى المستخدم فحص UnityFS.")) }) { Text("إلغاء الفحص") }
          }
          unityDeepReport?.let { deep ->
           Text(deep.summary,color=ArabifyPalette.success,fontSize=12.sp)
           deep.items.forEach { item ->
            Text(item.path + "\n" + item.status,color=ArabifyPalette.textBody,fontSize=10.sp)
            item.details.forEach { detail -> Text("• " + detail,color=ArabifyPalette.textSecondary,fontSize=10.sp) }
           }
           if(deep.textAssetPreviews.isNotEmpty()) {
            Text("نصوص Unity المكتشفة — معاينة للقراءة فقط",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=13.sp)
            deep.textAssetPreviews.forEach { preview ->
             HextechPanel(containerColor=ArabifyPalette.surfaceElevated, contentPadding=PaddingValues(0.dp)) {
              Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
               Text(preview.name.ifBlank { "TextAsset" },color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
               Text(preview.serializedFilePath + " • PathID " + preview.pathId,color=ArabifyPalette.textMuted,fontSize=9.sp)
               preview.pathName?.let { Text("المسار داخل اللعبة: " + it,color=ArabifyPalette.textSecondary,fontSize=10.sp) }
               Text(preview.scriptPreview,color=ArabifyPalette.textBody,fontSize=11.sp,lineHeight=17.sp)
               if(preview.truncated) Text("تم اختصار المعاينة لحماية الذاكرة؛ لم يُعدّل النص الأصلي.",color=ArabifyPalette.warningText,fontSize=10.sp)
               TextButton(onClick={clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(preview.scriptPreview))}) {
                Text("نسخ المعاينة")
               }
               UnityTextAssetTranslationPreview(
                preview = preview,
                translator = offlineTranslator,
                modelReady = modelReady
               )
              }
             }
            }
           }
           deep.notes.forEach { note -> Text("• " + note,color=ArabifyPalette.warningText,fontSize=10.sp) }
          }
          unityDeepError?.let { Text(it,color=ArabifyPalette.danger,fontSize=11.sp) }
          if((selectedInstalledApp?.splitCount ?: 0)>0) {
           Text("فحص UnityFS العميق لملفات Split غير مفعّل بعد؛ لم يتم فك أي ملف.",color=ArabifyPalette.warningText,fontSize=10.sp)
          }
         }
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
         if(inventory.totalUnityFiles > 12 || inventory.scanTruncated) {
          val shown=minOf(12,inventory.items.size)
          Text("عدد عناصر Unity المكتشفة: ${inventory.totalUnityFiles}؛ المعروض: أول ${shown}." + if(inventory.scanTruncated) " الجرد جزئي؛ لم تُفحص بقية إدخالات ZIP." else "",color=ArabifyPalette.textMuted,fontSize=10.sp)
         }
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
    if(result!=null || error!=null || localizationError!=null || assetError!=null) item {
     HextechPanel(containerColor=ArabifyPalette.surfaceAccent, contentPadding=PaddingValues(0.dp)) {
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
       Text("التقرير الكامل — جاهز للنسخ والإرسال",color=ArabifyPalette.accent,fontWeight=FontWeight.Bold,fontSize=17.sp)
       Text("يجمع بيانات التطبيق ونتائج الفحص والخطوط والنصوص والملاحظات وأخطاء ونتائج التعريب في نص واحد.",color=ArabifyPalette.textSecondary,fontSize=12.sp)
       OutlinedTextField(value=activityReport,onValueChange={},readOnly=true,modifier=Modifier.fillMaxWidth(),minLines=8,maxLines=18,label={Text("كل تفاصيل العملية")})
       ArabifyCtaButton("نسخ التقرير الكامل",onClick={clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(activityReport))})
      }
     }
    }
    item {
     Spacer(Modifier.height(8.dp))
     Text("الإصدار 0.2.0 — خيار بدء التعريب وتقرير موحد قابل للنسخ.",color=ArabifyPalette.textDisabled,fontSize=11.sp)
    }
    }
   }
  }
  }
 }
}

@Composable
private fun SectionTitle(value:String){HextechSectionHeader(value)}

private const val MAX_APK_IMPORT_BYTES = 1_500L * 1024L * 1024L
private const val CACHE_RESERVE_BYTES = 16L * 1024L * 1024L

private suspend fun copyUriToCache(
 context:Context,
 uri:Uri,
 maxBytes:Long = MAX_APK_IMPORT_BYTES
):File {
 val operationJob=currentCoroutineContext()[Job]
 require(maxBytes > 0L) { "حد حجم الملف غير صالح." }
 val declaredSize = try {
  context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { cursor ->
   if(cursor.moveToFirst()) {
    BoundedArchiveInput.declaredSizeOrNull(cursor.isNull(0),cursor.getLong(0))
   } else null
  }
 } catch(_:Exception) { null }
 require(declaredSize == null || declaredSize <= maxBytes) {
  "حجم الملف يتجاوز الحد المسموح."
 }
 val availableBytes = (context.cacheDir.usableSpace - CACHE_RESERVE_BYTES).coerceAtLeast(0L)
 val copyLimit = minOf(maxBytes,availableBytes)
 require(copyLimit > 0L) { "مساحة التخزين المؤقت غير كافية لاستيراد الملف بأمان." }
 require(declaredSize == null || declaredSize <= copyLimit) {
  "المساحة الحرة في التخزين المؤقت أقل من حجم الملف."
 }
 val file = File.createTempFile("arabify-import-",".bin",context.cacheDir)
 try {
  val copied = context.contentResolver.openInputStream(uri)?.use { input ->
   file.outputStream().buffered().use { output ->
    val buffer=ByteArray(64*1024)
    var total=0L
    while(true) {
     operationJob?.ensureActive()
     val count=input.read(buffer)
     if(count<0) break
     total=Math.addExact(total,count.toLong())
     require(total<=copyLimit) { "Archive entry exceeded its configured copy limit." }
     output.write(buffer,0,count)
    }
    total
   }
  } ?: error("تعذر قراءة الملف المحدد.")
  require(declaredSize == null || copied == declaredSize) {
   "حجم الملف تغير أو لم يكتمل أثناء النسخ."
  }
  return file
 } catch(error:Exception) {
  file.delete()
  throw error
 }
}



@Composable
private fun rememberInstalledAppIcon(context: Context, packageName: String): androidx.compose.ui.graphics.ImageBitmap? {
 val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, packageName) {
  value = withContext(Dispatchers.IO) {
   try {
    drawableToBitmap(context.packageManager.getApplicationIcon(packageName)).asImageBitmap()
   } catch (cancelled: CancellationException) {
    throw cancelled
   } catch (_: Exception) {
    null
   }
  }
 }
 return icon
}

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
 val launcherIntent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
 // Launcher filters commonly omit CATEGORY_DEFAULT, so MATCH_DEFAULT_ONLY would hide valid games.
 val candidates=pm.queryIntentActivities(launcherIntent,0)
  .mapNotNull { resolved ->
   val info=resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
   val packageName=info.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
   val apkPath=info.sourceDir?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
   LauncherAppCandidate(
    label=pm.getApplicationLabel(info).toString(),
    packageName=packageName,
    apkPath=apkPath,
    splitApkPaths=info.splitSourceDirs?.toList() ?: emptyList()
   )
  }
 return InstalledAppDiscovery.fromCandidates(candidates)
}


private fun inspectInstalledPackage(
 context:Context,
 app:InstalledApp,
 onProgress:(Int,Int)->Unit={_,_->},
 cancellationCheck:()->Unit={}
):ApkScanResult {
 cancellationCheck()
 val files=listOf(app.apkPath)+app.splitApkPaths
 val scans=files.mapIndexed { index,path ->
  cancellationCheck()
  val scan=ApkInspector.inspect(context,Uri.fromFile(File(path)),cancellationCheck)
  cancellationCheck()
  onProgress(index+1,files.size)
  if(index==0) scan else scan.copy(fileName="split-$index-${File(path).name}")
 }
 val base=scans.first()
 if(scans.size==1) return base
 val allFonts=scans.flatMap { it.fontAssessments }
 val allCandidates=scans.flatMap { scan -> scan.candidateFiles.map { "${scan.fileName}:$it" } }.distinct()
 val allSamples=scans.flatMap { it.extractedTextSamples }.take(100)
 val allNotes=scans.flatMap { it.notes }.distinct().toMutableList()
 allNotes += "تم فحص APK الأساسي و${scans.size-1} ملفات Split إضافية للقراءة فقط. تعريب نصوص المجموعة وتصديرها كملف ZIP موقّع متاح من مسار ملفات النصوص."
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
