package com.omega.arabify

import android.content.Context
import android.content.pm.ApplicationInfo
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
                apk, out,
                onProgress = onProgress, requireTranslations = false, tx = tx
            )
            MemberReport(apk.name, out, report, report.stringsTranslated > 0)
        }
        require(members.any { it.transformed }) {
            "لم يتم العثور على نصوص قابلة للترجمة في أي ملف من مجموعة Split."
        }
        return SetReport(members)
    }

    /**
     * Device-side orchestration: rebuild the whole set tolerantly, then sign
     * every member with the persisted app key. Native libraries need no flag
     * resolution: DEFLATED .so entries keep their install-time extraction
     * semantics and STORED entries are re-aligned by ApkZipEntryPolicy, so a
     * rebuilt member installs exactly like its original.
     */
    suspend fun localizeAndSignSet(
        context: Context,
        inputApks: List<File>,
        workDir: File,
        translator: OfflineArabicTranslator,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<SetReport, List<File>> {
        val setReport = localizeSet(
            inputApks, File(workDir, "unsigned"), onProgress
        ) { value -> translator.translateEnglishToArabic(value) }
        val signed = signSet(context, setReport, File(workDir, "signed"))
        return setReport to signed
    }

    /**
     * Signs every rebuilt member with the app key. The key is persisted in
     * AndroidKeyStore, so all members share one signer - the hard requirement
     * for installing a split set. Device-only (AndroidKeyStore); covered by
     * the CI emulator path, not JVM tests.
     */
    fun signSet(context: Context, setReport: SetReport, signedDir: File): List<File> {
        signedDir.mkdirs()
        return setReport.members.map { member ->
            val signed = File(signedDir, member.outputApk.name)
            ApkSigningService.signAndVerify(context, member.outputApk, signed)
            signed
        }
    }
}
