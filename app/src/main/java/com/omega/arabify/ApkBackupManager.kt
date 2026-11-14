package com.omega.arabify

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class BackupApk(val name: String, val file: File, val sha256: String, val sizeBytes: Long)
internal data class VerifiedApkBackup(val packageName: String, val appLabel: String, val versionName: String?, val apks: List<BackupApk>)

internal object ApkBackupManager {
    private const val MANIFEST = "manifest.json"
    private const val MAX_APK_BYTES = 1_500L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 3L * 1024L * 1024L * 1024L
    private const val MAX_APK_COUNT = 64
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun createBackup(
        packageName: String,
        appLabel: String,
        versionName: String?,
        apkFiles: List<File>,
        output: File
    ) {
        require(packageName.isNotBlank()) { "اسم حزمة التطبيق غير صالح." }
        require(apkFiles.isNotEmpty() && apkFiles.size <= MAX_APK_COUNT) { "عدد ملفات APK غير صالح." }
        require(apkFiles.all { it.isFile && it.length() in 1..MAX_APK_BYTES }) { "ملف APK مفقود أو يتجاوز الحد المسموح." }
        val total = apkFiles.sumOf { it.length() }
        require(total <= MAX_TOTAL_BYTES) { "الحجم الإجمالي للنسخة الاحتياطية يتجاوز 3GB." }
        val entries = apkFiles.mapIndexed { index, file ->
            val name = if (index == 0) "base.apk" else "splits/split-%02d.apk".format(index)
            BackupApk(name, file, sha256(file), file.length())
        }
        val manifest = JSONObject()
            .put("format", "ARABIFY_APK_BACKUP")
            .put("schemaVersion", 1)
            .put("packageName", packageName)
            .put("appLabel", appLabel)
            .put("versionName", versionName ?: JSONObject.NULL)
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("apks", JSONArray().apply {
                entries.forEach { item ->
                    put(JSONObject()
                        .put("entry", item.name)
                        .put("sizeBytes", item.sizeBytes)
                        .put("sha256", item.sha256))
                }
            })
        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { item ->
                zip.putNextEntry(ZipEntry(item.name))
                FileInputStream(item.file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // Verify the completed archive before reporting success.
        val verificationDir = File(output.parentFile ?: output.absoluteFile.parentFile, "verify-${output.nameWithoutExtension}")
        try {
            val verified = extractAndVerify(output, verificationDir)
            check(verified.packageName == packageName && verified.apks.size == apkFiles.size) {
                "فشل التحقق النهائي من النسخة الاحتياطية."
            }
        } finally {
            verificationDir.deleteRecursively()
        }
    }

    fun extractAndVerify(archive: File, destination: File): VerifiedApkBackup {
        require(archive.isFile) { "ملف النسخة الاحتياطية غير موجود." }
        require(!destination.exists() || (destination.isDirectory && destination.list()?.isEmpty() == true)) {
            "مجلد الاستعادة موجود وغير فارغ."
        }
        require(destination.mkdirs() || destination.isDirectory) { "تعذر تجهيز مجلد الاستعادة." }
        try {
            return ZipFile(archive).use { zip ->
                val zipNames = HashSet<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(zipNames.add(entry.name)) { "مدخل مكرر داخل ملف النسخة الاحتياطية." }
                    require(!entry.isDirectory) { "المجلدات غير مسموحة داخل النسخة الاحتياطية." }
                }
                require(MANIFEST in zipNames) { "ملف النسخة الاحتياطية لا يحتوي على manifest." }
                val manifestEntry = zip.getEntry(MANIFEST)
                val manifestText = zip.getInputStream(manifestEntry).use { input ->
                    BoundedArchiveInput.readAll(input, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                }
                val manifest = JSONObject(manifestText)
                require(manifest.optString("format") == "ARABIFY_APK_BACKUP") { "صيغة النسخة الاحتياطية غير معروفة." }
                require(manifest.optInt("schemaVersion") == 1) { "إصدار النسخة الاحتياطية غير مدعوم." }
                val packageName = manifest.getString("packageName")
                require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "اسم الحزمة غير صالح." }
                val array = manifest.getJSONArray("apks")
                require(array.length() in 1..MAX_APK_COUNT) { "قائمة ملفات APK غير صالحة." }
                var total = 0L
                val expectedEntries = linkedSetOf(MANIFEST)
                val verified = ArrayList<BackupApk>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val entryName = item.getString("entry")
                    require((index == 0 && entryName == "base.apk") || (index > 0 && entryName == "splits/split-%02d.apk".format(index))) {
                        "مسار ملف داخل النسخة الاحتياطية غير مسموح."
                    }
                    require(expectedEntries.add(entryName)) { "ملف مكرر داخل manifest." }
                    val expectedSize = item.getLong("sizeBytes")
                    require(expectedSize in 1..MAX_APK_BYTES) { "حجم APK في manifest غير صالح." }
                    total = Math.addExact(total, expectedSize)
                    require(total <= MAX_TOTAL_BYTES) { "الحجم الإجمالي للنسخة الاحتياطية أكبر من الحد." }
                    val source = zip.getEntry(entryName) ?: error("ملف APK مفقود من النسخة الاحتياطية: $entryName")
                    require(!source.isDirectory) { "مدخل APK غير صالح." }
                    val target = File(destination, entryName)
                    require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "مسار استخراج غير آمن." }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(source).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied = Math.addExact(copied, count.toLong())
                                require(copied <= expectedSize && copied <= MAX_APK_BYTES) { "حجم APK الفعلي تجاوز القيمة المسجلة." }
                                output.write(buffer, 0, count)
                            }
                            require(copied == expectedSize) { "حجم APK لا يطابق manifest." }
                        }
                    }
                    val actualHash = sha256(target)
                    require(item.getString("sha256").matches(Regex("[a-fA-F0-9]{64}"))) { "قيمة SHA-256 في manifest غير صالحة." }
                    require(actualHash.equals(item.getString("sha256"), ignoreCase = true)) {
                        "فشل التحقق من SHA-256 للملف $entryName."
                    }
                    verified += BackupApk(entryName, target, actualHash, expectedSize)
                }
                require(zipNames == expectedEntries) { "ملف ZIP يحتوي على مدخلات غير موثقة في manifest." }
                VerifiedApkBackup(
                    packageName = packageName,
                    appLabel = manifest.optString("appLabel", packageName),
                    versionName = manifest.optString("versionName").takeUnless { it == "null" || it.isBlank() },
                    apks = verified
                )
            }
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }
}
