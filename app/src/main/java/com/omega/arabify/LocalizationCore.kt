package com.omega.arabify

/** Engine-neutral contracts shared by game/container localization adapters. */
internal enum class ArtifactKind { APK, SPLIT_APK_SET, GAME_DIRECTORY, ARCHIVE, RAW_FILE, UNKNOWN }

internal data class ArtifactRef(
    val id: String,
    val displayName: String,
    val uri: String,
    val kind: ArtifactKind,
    val sizeBytes: Long? = null,
    val sha256: String? = null
) {
    init {
        require(id.isNotBlank() && uri.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0L)
        require(sha256 == null || sha256.matches(Regex("(?i)[0-9a-f]{64}")))
    }
}

/** Hard ceilings are supplied by the orchestrator, never selected by an adapter. */
internal data class ResourceBudget(
    val maxInputBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxExpandedBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxEntries: Int = 100_000,
    val maxNestingDepth: Int = 32,
    val maxTextUnits: Int = 2_000_000,
    val maxTextUnitBytes: Int = 1024 * 1024,
    val maxTemporaryBytes: Long = 4L * 1024 * 1024 * 1024
) {
    init {
        require(maxInputBytes > 0 && maxExpandedBytes > 0 && maxTemporaryBytes > 0)
        require(maxEntries > 0 && maxNestingDepth > 0 && maxTextUnits > 0 && maxTextUnitBytes > 0)
    }
    fun requireInputWithinLimit(bytes: Long) {
        require(bytes >= 0 && bytes <= maxInputBytes) { "Input exceeds configured byte budget." }
    }
    fun requireExpandedWithinLimit(bytes: Long) {
        require(bytes >= 0 && bytes <= maxExpandedBytes) { "Expanded data exceeds configured byte budget." }
    }
    fun requireEntryCountWithinLimit(count: Int) {
        require(count >= 0 && count <= maxEntries) { "Archive entry count exceeds configured budget." }
    }
}

internal enum class AdapterOperation { INSPECT, EXTRACT, REWRITE, VERIFY }
internal enum class CapabilityStatus { SUPPORTED, PARTIAL, INSPECT_ONLY, UNSUPPORTED, BLOCKED }

internal data class AdapterDescriptor(
    val id: String,
    val version: String,
    val displayName: String,
    val inputKinds: Set<ArtifactKind>,
    val outputKinds: Set<ArtifactKind>,
    val operations: Set<AdapterOperation>,
    val formatIds: Set<String> = emptySet(),
    val priority: Int = 0
) {
    init {
        require(id.isNotBlank() && version.isNotBlank() && displayName.isNotBlank())
        require(inputKinds.isNotEmpty())
    }
}

internal data class AdapterProbe(
    val adapterId: String,
    val matched: Boolean,
    val confidence: Int,
    val evidence: List<String> = emptyList(),
    val blockedReason: String? = null
) {
    init {
        require(adapterId.isNotBlank() && confidence in 0..100)
        require(blockedReason == null || blockedReason.isNotBlank())
    }
}

/** Stable source identity; translations are overlays and never replace original text. */
internal data class LocalizationTextUnit(
    val id: String,
    val sourceLocator: String,
    val originalText: String,
    val sourceLanguage: String? = null,
    val context: String? = null,
    val encoding: String? = null,
    val adapterId: String,
    val adapterVersion: String,
    val placeholders: List<String> = emptyList(),
    val tags: List<String> = emptyList()
) {
    init { require(id.isNotBlank() && sourceLocator.isNotBlank() && adapterId.isNotBlank() && adapterVersion.isNotBlank()) }
}

internal data class TranslationOverlay(
    val textUnitId: String,
    val targetLanguage: String,
    val translatedText: String,
    val providerId: String,
    val providerVersion: String,
    val reviewed: Boolean = false
) {
    init { require(textUnitId.isNotBlank() && targetLanguage.isNotBlank() && providerId.isNotBlank() && providerVersion.isNotBlank()) }
}

internal data class AdapterReport(
    val adapterId: String,
    val operation: AdapterOperation,
    val processed: Int,
    val succeeded: Int,
    val skipped: Int,
    val warnings: List<String> = emptyList(),
    val evidenceLevel: String = "UNIT_FIXTURE"
) {
    init {
        require(adapterId.isNotBlank() && processed >= 0 && succeeded >= 0 && skipped >= 0)
        require(succeeded + skipped <= processed)
    }
}

internal data class LocalizationPlan(
    val artifact: ArtifactRef,
    val status: CapabilityStatus,
    val selectedAdapters: List<AdapterDescriptor>,
    val requestedOperations: Set<AdapterOperation>,
    val coveredOperations: Set<AdapterOperation>,
    val missingOperations: Set<AdapterOperation>,
    val explanations: List<String>
)
