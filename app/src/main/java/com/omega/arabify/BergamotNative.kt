package com.omega.arabify

/**
 * Thin JNI boundary for the pinned Bergamot runtime.
 *
 * Callers must provide an app-private model configuration whose referenced files
 * have passed TranslationModelPackageVerifier. CI has proven both pinned model-v3.0
 * directions load and translate offline on an API 35 emulator. This class remains
 * outside the user UI until the production installer/lifecycle and physical-device
 * memory, thermal, and translation-quality gates pass.
 */
internal object BergamotNative {
    init {
        System.loadLibrary("arabify_bergamot")
    }

    external fun nativeCreate(modelConfigPath: String): Long
    external fun nativeTranslate(session: Long, input: String): String
    external fun nativeDestroy(session: Long)
}
