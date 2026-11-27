package com.omega.arabify

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * First-install offline English→Arabic translation from the model package
 * bundled into the APK's assets (assets/arabify-models/en-ar).
 *
 * Omega's decision (2026-11-27): the offline model ships inside the app so
 * translation works on a fresh install with no network. Weights are staged
 * and SHA-256-verified in CI from the pinned Mozilla registry and are never
 * committed to git; [TranslationModelPackageVerifier] re-checks every staged
 * byte against the packaged manifest before the native session is created.
 *
 * Engine-choice policy lives in [OfflineArabicTranslator]: ML Kit stays the
 * default once its Arabic model is on the device; this bundled engine takes
 * over when it is not (fresh install / no network). The reverse direction
 * stays test-only, so only en-ar is packaged.
 */
internal class BundledBergamotTranslator(private val context: Context) {
    private val tag = "BundledBergamot"
    private val lock = Any()
    private var session: Long = 0L
    private var prepareError: String? = null

    // The pinned JNI library ships only in builds that package it (the
    // bergamot workflow and the standard debug APK); touching the object
    // forces System.loadLibrary. Any failure (UnsatisfiedLinkError is an
    // Error, not an Exception) must degrade to "engine unavailable", never
    // crash the translation flow.
    private val nativeAvailable: Boolean by lazy {
        try {
            BergamotNative.toString()
            true
        } catch (error: Throwable) {
            Log.w(tag, "bergamot native library unavailable in this build", error)
            false
        }
    }

    /** True when the APK actually carries the bundled en-ar package. */
    fun isPackaged(): Boolean = try {
        context.assets.list("$ASSET_ROOT/en-ar")?.contains("config.yml") == true
    } catch (error: Exception) {
        Log.w(tag, "asset listing failed", error)
        false
    }

    /** True when this APK can actually serve bundled translations (weights AND native lib). */
    fun isUsable(): Boolean = isPackaged() && nativeAvailable

    /** Translates synchronously on the calling thread; callers dispatch off the main thread. */
    fun translateEnglishToArabic(text: String): String {
        require(text.isNotBlank()) { "النص فارغ." }
        synchronized(lock) {
            try {
                return BergamotNative.nativeTranslate(ensureSession(), text)
            } catch (error: Throwable) {
                // Normalize every failure (including native Errors) so the
                // caller's Exception-based fallback keeps working.
                prepareError = error.message ?: error.javaClass.simpleName
                throw IllegalStateException("bundled translation failed: $prepareError", error)
            }
        }
    }

    private fun ensureSession(): Long {
        session.takeIf { it != 0L }?.let { return it }
        prepareError?.let { throw IllegalStateException("bundled model unavailable: $it") }
        check(nativeAvailable) { "bergamot native library is not packaged in this build" }
        try {
            val root = File(context.filesDir, "bundled-models/en-ar")
            stageAssets(root)
            val verification = TranslationModelPackageVerifier.verify(root, readManifest(root))
            if (!verification.accepted) {
                throw IllegalStateException("bundled model verification refused: ${verification.reason}")
            }
            val handle = BergamotNative.nativeCreate(File(root, "config.yml").absolutePath)
            if (handle == 0L) throw IllegalStateException("native session was not created")
            Log.i(tag, "bundled en-ar model loaded (${verification.totalBytes} verified bytes)")
            session = handle
            return handle
        } catch (error: Throwable) {
            prepareError = error.message ?: error.javaClass.simpleName
            throw IllegalStateException("bundled model unavailable: $prepareError", error)
        }
    }

    /** Copies the packaged asset tree to private storage once (native needs real paths). */
    private fun stageAssets(root: File) {
        val marker = File(root, ".staged")
        if (marker.isFile) return
        root.deleteRecursively()
        check(root.mkdirs()) { "could not create ${root.absolutePath}" }
        val names = context.assets.list("$ASSET_ROOT/en-ar")?.toList().orEmpty()
        check(names.isNotEmpty()) { "APK carries no bundled model assets under $ASSET_ROOT/en-ar" }
        var total = 0L
        for (name in names) {
            context.assets.open("$ASSET_ROOT/en-ar/$name").use { input ->
                File(root, name).outputStream().use { output ->
                    total += input.copyTo(output)
                }
            }
        }
        check(total > 0L) { "bundled model assets copied to zero bytes" }
        marker.writeText("$total")
        Log.i(tag, "staged ${names.size} bundled model files ($total bytes) to private storage")
    }

    private fun readManifest(root: File): TranslationModelManifest {
        val json = JSONObject(File(root, "manifest.json").readText(Charsets.UTF_8))
        val files = buildList {
            val array = json.getJSONArray("files")
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                add(TranslationModelFile(entry.getString("relativePath"), entry.getString("sha256")))
            }
        }
        return TranslationModelManifest(
            packageId = json.getString("packageId"),
            engine = json.getString("engine"),
            engineVersion = json.getString("engineVersion"),
            engineLicense = json.getString("engineLicense"),
            modelVersion = json.getString("modelVersion"),
            modelLicense = json.getString("modelLicense"),
            tokenizerLicense = json.getString("tokenizerLicense"),
            sourceUrl = json.getString("sourceUrl"),
            sourceLanguage = json.getString("sourceLanguage"),
            targetLanguage = json.getString("targetLanguage"),
            files = files,
        )
    }

    fun close() {
        synchronized(lock) {
            if (session != 0L) {
                BergamotNative.nativeDestroy(session)
                session = 0L
            }
        }
    }

    companion object {
        const val ASSET_ROOT = "arabify-models"
    }
}
