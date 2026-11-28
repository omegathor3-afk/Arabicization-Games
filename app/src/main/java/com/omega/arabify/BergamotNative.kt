package com.omega.arabify

/**
 * Thin JNI boundary for the pinned Bergamot runtime.
 *
 * Callers must provide an app-private model configuration whose referenced files
 * have passed TranslationModelPackageVerifier. OfflineArabicTranslator uses this
 * JNI boundary when the ML Kit Arabic model is absent and the current ABI includes
 * libarabify_bergamot. The production path stages and verifies the bundled en-ar
 * package before creating a native session. Physical ARM64 memory/thermal behavior
 * and broad game-dialogue quality remain unverified.
 */
internal object BergamotNative {
    init {
        System.loadLibrary("arabify_bergamot")
    }

    external fun nativeCreate(modelConfigPath: String): Long
    external fun nativeTranslate(session: Long, input: String): String
    external fun nativeDestroy(session: Long)
}
