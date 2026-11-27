package com.omega.arabify

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * First-install offline English→Arabic translation from the model package
 * bundled into the APK's assets (assets/arabify-models/en-ar).
 *
 * The APK is the source of truth. BundledModelPackageStager copies only
 * manifest-listed files, verifies their SHA-256 values (including config.yml),
 * and promotes a complete package transactionally. A failed copy or damaged
 * staged package can be retried without deleting the last verified package.
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

    // UnsatisfiedLinkError is a LinkageError, not an Exception. Catch that
    // specific family but never swallow fatal VM errors such as OutOfMemoryError.
    private val nativeAvailable: Boolean by lazy {
        try {
            BergamotNative.toString()
            true
        } catch (error: LinkageError) {
            Log.w(tag, "bergamot native library unavailable in this build", error)
            false
        } catch (error: Exception) {
            Log.w(tag, "bergamot native initialization failed", error)
            false
        }
    }

    /** True when this APK carries the bundled en-ar model package. */
    fun isPackaged(): Boolean = try {
        context.assets.list("$ASSET_ROOT/en-ar")?.contains("config.yml") == true &&
            context.assets.list("$ASSET_ROOT/en-ar")?.contains("manifest.json") == true
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
            } catch (error: LinkageError) {
                throw IllegalStateException("bundled translation native library failed", error)
            } catch (error: Exception) {
                throw IllegalStateException("bundled translation failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }
    }

    private fun ensureSession(): Long {
        session.takeIf { it != 0L }?.let { return it }
        check(nativeAvailable) { "bergamot native library is not packaged in this build" }

        try {
            val manifestBytes = readAssetBounded("$ASSET_ROOT/en-ar/manifest.json")
            val manifest = readManifest(manifestBytes)
            val root = File(context.filesDir, "bundled-models/en-ar")
            val verification = BundledModelPackageStager.stageOrReuse(
                root = root,
                manifestJson = manifestBytes,
                manifest = manifest,
                openAsset = { relativePath -> context.assets.open("$ASSET_ROOT/en-ar/$relativePath") },
            )
            if (!verification.accepted) {
                throw IllegalStateException("bundled model verification refused: ${verification.reason}")
            }

            val handle = BergamotNative.nativeCreate(File(root, "config.yml").absolutePath)
            if (handle == 0L) throw IllegalStateException("native session was not created")
            Log.i(tag, "bundled en-ar model loaded (${verification.totalBytes} verified bytes)")
            session = handle
            return handle
        } catch (error: LinkageError) {
            throw IllegalStateException("bundled model native initialization failed", error)
        } catch (error: Exception) {
            // Do not cache a failed preparation permanently: a transient
            // storage error can be retried after space is freed, and a corrupt
            // staged directory is repaired by the transactional stager.
            throw IllegalStateException(
                "bundled model unavailable: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private fun readAssetBounded(path: String): ByteArray {
        context.assets.open(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(MANIFEST_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > MAX_MANIFEST_BYTES - total) {
                    throw IOException("bundled_model_manifest_size_limit_exceeded")
                }
                output.write(buffer, 0, count)
                total += count
            }
            if (total == 0) throw IOException("bundled_model_manifest_empty")
            return output.toByteArray()
        }
    }

    private fun readManifest(bytes: ByteArray): TranslationModelManifest {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
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
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MANIFEST_BUFFER_BYTES = 8192
    }
}
