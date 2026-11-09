package com.omega.arabify

import android.content.Context
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal enum class UnityRuntimeKind { MONO, IL2CPP, UNKNOWN }

internal object UnityMonoPackageDetector {
    fun detect(paths: Collection<String>): UnityRuntimeKind {
        val normalized = paths.map { "/" + it.replace('\\', '/').trimStart('/').lowercase() }
        val hasIl2Cpp = normalized.any {
            it.endsWith("/libil2cpp.so") ||
                it.endsWith("/global-metadata.dat") ||
                it.contains("/il2cpp_data/")
        }
        if (hasIl2Cpp) return UnityRuntimeKind.IL2CPP
        val hasManagedDirectory = normalized.any {
            it.contains("/assets/bin/data/managed/") && it.endsWith(".dll")
        }
        val hasGameAssembly = normalized.any {
            it.endsWith("/assets/bin/data/managed/assembly-csharp.dll")
        }
        return if (hasManagedDirectory && hasGameAssembly) UnityRuntimeKind.MONO else UnityRuntimeKind.UNKNOWN
    }

    fun detect(apk: File): UnityRuntimeKind {
        require(apk.isFile) { "ملف APK غير موجود." }
        return ZipFile(apk).use { zip ->
            val entries = zip.entries()
            val paths = ArrayList<String>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) paths += entry.name
            }
            detect(paths)
        }
    }
}

/**
 * First Unity Mono adapter: localizes plain-text files shipped in StreamingAssets.
 * Managed DLLs and Unity serialized assets are preserved byte-for-byte. This adapter
 * does not rewrite CIL, resources.assets, or AssetBundles.
 */
internal object UnityMonoLocalizer {
    const val supportedScope =
        "يدعم حاليًا ملفات JSON/TXT/Properties/Strings النصية الواضحة داخل APK لألعاب Unity Mono. لا يغيّر Assembly-CSharp.dll أو resources.assets أو AssetBundles."

    suspend fun localize(
        context: Context,
        inputApk: File,
        unsignedApk: File,
        signedApk: File,
        translator: OfflineArabicTranslator,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): AssetLocalizationReport {
        when (UnityMonoPackageDetector.detect(inputApk)) {
            UnityRuntimeKind.MONO -> Unit
            UnityRuntimeKind.IL2CPP ->
                error("هذه اللعبة تستخدم Unity IL2CPP وليست Mono. لم نعدّل أي ملف؛ دعم IL2CPP مرحلة مستقلة.")
            UnityRuntimeKind.UNKNOWN ->
                error("لم نعثر على Assembly-CSharp.dll داخل assets/bin/Data/Managed؛ لا يمكن تأكيد أن هذه نسخة Unity Mono.")
        }
        val report = AndroidAssetLocalizer.localize(
            context = context,
            inputApk = inputApk,
            unsignedApk = unsignedApk,
            signedApk = signedApk,
            translator = translator,
            onProgress = onProgress,
            allowedPathPrefixes = listOf("assets/bin/Data/StreamingAssets/")
        )
        UnityAssetIntegrityVerifier.verifyUnchanged(inputApk, report.outputApk)
        return report
    }

    internal data class FixtureReport(val stringsTranslated: Int)

    internal suspend fun transformForTest(
        inputApk: File,
        outputApk: File,
        translate: suspend (String) -> String
    ): FixtureReport {
        require(UnityMonoPackageDetector.detect(inputApk) == UnityRuntimeKind.MONO)
        var translated = 0
        ZipFile(inputApk).use { zip ->
            ZipOutputStream(outputApk.outputStream().buffered()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name).apply { time = entry.time })
                    val path = entry.name
                    val supported = path.startsWith("assets/bin/Data/StreamingAssets/") &&
                        path.substringAfterLast('.', "").lowercase() in setOf("json", "txt", "properties", "strings")
                    if (supported && entry.size in 1..(512L * 1024L)) {
                        val source = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val result = AssetTextTransformer.transform(path, source, translate)
                        translated += result.translated
                        out.write(result.text.toByteArray(Charsets.UTF_8))
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
        return FixtureReport(translated)
    }
}
