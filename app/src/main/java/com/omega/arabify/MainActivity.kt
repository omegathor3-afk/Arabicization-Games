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
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
  if(uri!=null) {
   busy=true;error=null;result=null
   scope.launch {
    try { result=withContext(Dispatchers.IO){ApkInspector.inspect(context,uri)} }
    catch(e:Exception){error=e.message ?: "حدث خطأ أثناء فحص الملف."}
    finally{busy=false}
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
     Text("الإصدار التجريبي 0.1.0 — لا يدّعي التعريب أو إنشاء APK عربي بعد.",color=Color(0xFF78818D),fontSize=11.sp)
    }
   }
  }
 }
}

@Composable
private fun SectionTitle(value:String){Text(value,color=Color(0xFFD7B77A),fontWeight=FontWeight.Bold,fontSize=16.sp)}
