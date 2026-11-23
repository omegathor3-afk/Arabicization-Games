package com.omega.arabify

import java.io.File

/**
 * Rebuilds every member of a split APK set (base + config splits) so the whole
 * set can be re-signed with one key and installed in a single session.
 *
 * Every member is rebuilt through the same ZIP policy even when it holds no
 * translatable text, because every APK in a split set must carry the SAME
 * signer: once one member is re-signed, an untouched sibling that keeps its
 * original signature would break the install. Rebuilding also strips stale
 * META-INF signatures from every member, so the device signing step starts
 * from a clean unsigned set.
 *
 * Translation is tolerant per member; the set fails only when NO member
 * contains translatable text. Signing and the install session stay on the
 * device path (ApkSigningService / ApkRestoreInstaller).
 */
internal object SplitApkSetLocalizer {

    private const val MAX_SET_MEMBERS = 64

    data class MemberReport(
        val sourceName: String,
        val outputApk: File,
        val report: AssetLocalizationReport,
        val transformed: Boolean
    )

    data class SetReport(val members: List<MemberReport>) {
        val anyTransformed: Boolean get() = members.any { it.transformed }
        val stringsTranslated: Int get() = members.sumOf { it.report.stringsTranslated }
        val stringsSkipped: Int get() = members.sumOf { it.report.stringsSkipped }
    }

    suspend fun localizeSet(
        inputApks: List<File>,
        outputDir: File,
        extractNativeLibsResolver: (File) -> Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        tx: suspend (String) -> String
    ): SetReport {
        require(inputApks.isNotEmpty()) { "مجموعة Split فارغة." }
        require(inputApks.size <= MAX_SET_MEMBERS) { "عدد ملفات Split يتجاوز الحد الآمن (64)." }
        val outputRoot = outputDir.absoluteFile
        outputRoot.mkdirs()
        val members = inputApks.mapIndexed { index, apk ->
            val name = if (index == 0) "base.apk" else "split_%02d.apk".format(index)
            val out = File(outputRoot, name)
            require(out.absoluteFile.parentFile == outputRoot) { "مسار إخراج غير آمن." }
            val report = AndroidAssetLocalizer.transformToUnsignedApk(
                apk, out, extractNativeLibsResolver(apk),
                onProgress = onProgress, requireTranslations = false, tx = tx
            )
            MemberReport(apk.name, out, report, report.stringsTranslated > 0)
        }
        require(members.any { it.transformed }) {
            "لم يتم العثور على نصوص قابلة للترجمة في أي ملف من مجموعة Split."
        }
        return SetReport(members)
    }
}
