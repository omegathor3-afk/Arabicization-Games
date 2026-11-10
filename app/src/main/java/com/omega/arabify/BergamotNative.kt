package com.omega.arabify

/**
 * Thin JNI boundary for the pinned Bergamot runtime.
 *
 * Callers must provide an app-private model configuration whose referenced files
 * have passed TranslationModelPackageVerifier. This class is intentionally not
 * wired into the UI until model-v3.0 compatibility and device tests pass.
 */
internal object BergamotNative {
    init {
        System.loadLibrary("arabify_bergamot")
    }

    external fun nativeCreate(modelConfigPath: String): Long
    external fun nativeTranslate(session: Long, input: String): String
    external fun nativeDestroy(session: Long)
}
