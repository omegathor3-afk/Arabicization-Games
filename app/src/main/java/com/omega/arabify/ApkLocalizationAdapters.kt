package com.omega.arabify

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Phase A adapter wrappers: expose the existing APK localizers through the
 * shared LocalizationAdapter contract (probe + inspect only) so the registry
 * and capability planner run on real adapters before any dispatch migration.
 * No production dispatch uses these yet; MainActivity keeps its current chain
 * until Phase B. Detection here never claims rewrite support: every wrapper
 * declares INSPECT only, and evidence levels follow the architecture's ladder
 * (a lower level never proves a higher one).
 */

private fun artifactToFile(input: ArtifactRef): File {
    val path = if (input.uri.startsWith("file://")) input.uri.removePrefix("file://") else input.uri
    val file = File(path)
    require(file.isFile) { "الملف غير موجود: ${input.displayName}" }
    return file
}

private data class ApkMarkers(val names: List<String>, val truncated: Boolean)

/**
 * Runtime dependencies a wrapper needs to actually execute its wrapped
 * localizer: a staging directory for the output APK, the app's font bytes
 * loader, and the shared translation callback (term bases, repair tiers and
 * quality gates live behind it). Phase B decision (handoff 2026-11-26):
 * translation stays adapter-internal; sink-based extraction is Phase C.
 */
internal data class AdapterRuntimeDeps(
    val stagingDir: File,
    val fontBytes: (SelectableFont) -> ByteArray,
    val translate: suspend (String) -> String,
)

/**
 * Native-report carrier returned alongside the AdapterReport summary, per
 * the sealed-variant design accepted in the handoff on 2026-11-26: the
 * summary never replaces skipReasons/repairReasons/fitWarnings/font details.
 */
internal sealed class AdapterLocalizationOutput {
    abstract val summary: AdapterReport
    abstract val outputApk: File

    data class LocaleSlot(val report: LocaleSlotLocalizer.SlotReport, override val summary: AdapterReport) : AdapterLocalizationOutput() {
        override val outputApk: File get() = report.outputApk
    }

    data class Gettext(val report: GettextPoLocalizer.PoReport, override val summary: AdapterReport) : AdapterLocalizationOutput() {
        override val outputApk: File get() = report.outputApk
    }
}

private fun AdapterRuntimeDeps.outputFor(input: ArtifactRef, tag: String): File {
    val safe = input.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(stagingDir, "$safe-$tag.apk")
}

private suspend fun scanApk(input: ArtifactRef, budget: ResourceBudget, keep: (String, Long) -> Boolean): ApkMarkers {
    val file = artifactToFile(input)
    budget.requireInputWithinLimit(file.length())
    val coroutineContext = currentCoroutineContext()
    val matches = mutableListOf<String>()
    var truncated = false
    ZipFile(file).use { zip ->
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            count++
            budget.requireEntryCountWithinLimit(count)
            if ((count and 0x3f) == 0) coroutineContext.ensureActive()
            val entry = entries.nextElement()
            if (!entry.isDirectory && keep(entry.name, entry.size)) {
                if (matches.size < 8) matches += entry.name else truncated = true
            }
        }
    }
    coroutineContext.ensureActive()
    return ApkMarkers(matches, truncated)
}

internal abstract class ApkProbeAdapter : LocalizationAdapter {
    override suspend fun probe(input: ArtifactRef, budget: ResourceBudget): AdapterProbe {
        if (input.kind != ArtifactKind.APK && input.kind != ArtifactKind.UNKNOWN) {
            return AdapterProbe(descriptor.id, matched = false, confidence = 0, evidence = listOf("not an APK artifact"))
        }
        currentCoroutineContext().ensureActive()
        return try {
            probeApk(input, budget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AdapterProbe(
                descriptor.id,
                matched = false,
                confidence = 0,
                blockedReason = "probe failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    protected abstract suspend fun probeApk(input: ArtifactRef, budget: ResourceBudget): AdapterProbe
}

/** libGDX folder-convention slot (Unciv, Mindustry): bundle.properties / translations/English.properties. */
internal class LibGdxLocaleSlotAdapter(private val deps: AdapterRuntimeDeps? = null) : ApkProbeAdapter() {
    override val descriptor = AdapterDescriptor(
        id = "adapter.libgdx.localeslot",
        version = "0.1.0",
        displayName = "libGDX locale-slot (bundle_ar)",
        inputKinds = setOf(ArtifactKind.APK),
        outputKinds = setOf(ArtifactKind.APK),
        operations = setOf(AdapterOperation.INSPECT, AdapterOperation.EXTRACT),
        formatIds = setOf("libgdx-properties-bundle"),
        priority = 300,
    )

    /** The most recent extraction's native report, set by [extract]. */
    var lastLocalizationOutput: AdapterLocalizationOutput.LocaleSlot? = null
        private set

    override suspend fun probeApk(input: ArtifactRef, budget: ResourceBudget): AdapterProbe {
        val markers = scanApk(input, budget) { name, _ ->
            // Mirrors LocaleSlotLocalizer.baseBundlePattern / translationFolderPattern.
            name.endsWith("bundle.properties") || name.endsWith("translations/English.properties")
        }
        return AdapterProbe(
            descriptor.id,
            matched = markers.names.isNotEmpty(),
            confidence = if (markers.names.isNotEmpty()) 90 else 0,
            evidence = markers.names + (if (markers.truncated) listOf("(more matches not listed)") else emptyList()),
        )
    }

    override suspend fun inspect(input: ArtifactRef, budget: ResourceBudget): AdapterReport {
        val markers = scanApk(input, budget) { name, _ ->
            name.endsWith("bundle.properties") || name.endsWith("translations/English.properties")
        }
        return AdapterReport(
            descriptor.id, AdapterOperation.INSPECT,
            processed = markers.names.size, succeeded = 0, skipped = 0,
            warnings = listOf("probe-level inventory only; string counts are produced by the localization run"),
            evidenceLevel = "UNIT_FIXTURE",
        )
    }

    override suspend fun extract(input: ArtifactRef, budget: ResourceBudget): AdapterReport {
        val runtime = deps
            ?: throw UnsupportedOperationException("${descriptor.id}: EXTRACT needs runtime deps (staging dir, fonts, translate)")
        val file = artifactToFile(input)
        budget.requireInputWithinLimit(file.length())
        val output = runtime.outputFor(input, "slot")
        val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(
            file, output, LocaleSlotLocalizer.FontInjection(runtime.fontBytes), runtime.translate,
        )
        val summary = AdapterReport(
            descriptor.id, AdapterOperation.EXTRACT,
            processed = report.stringsTranslated + report.stringsSkipped,
            succeeded = report.stringsTranslated,
            skipped = report.stringsSkipped,
            warnings = report.fitWarningSamples.take(3),
            // Honest until the wrapper dispatch path itself runs in emulator CI.
            evidenceLevel = "UNIT_FIXTURE",
        )
        lastLocalizationOutput = AdapterLocalizationOutput.LocaleSlot(report, summary)
        return summary
    }
}

/** gettext slot: po/messages.pot + ui/languages.xml (both required, mirroring GettextPoLocalizer). */
internal class GettextPoAdapter(private val deps: AdapterRuntimeDeps? = null) : ApkProbeAdapter() {
    override val descriptor = AdapterDescriptor(
        id = "adapter.gettext.po",
        version = "0.1.0",
        displayName = "gettext PO slot (ar.po)",
        inputKinds = setOf(ArtifactKind.APK),
        outputKinds = setOf(ArtifactKind.APK),
        operations = setOf(AdapterOperation.INSPECT, AdapterOperation.EXTRACT),
        formatIds = setOf("gettext-po"),
        priority = 200,
    )

    /** The most recent extraction's native report, set by [extract]. */
    var lastLocalizationOutput: AdapterLocalizationOutput.Gettext? = null
        private set

    override suspend fun probeApk(input: ArtifactRef, budget: ResourceBudget): AdapterProbe {
        // Mirrors GettextPoLocalizer.potPattern / languagesXmlPattern.
        val pot = scanApk(input, budget) { name, _ -> name.endsWith("po/messages.pot") }
        val langs = scanApk(input, budget) { name, _ -> name.endsWith("ui/languages.xml") }
        val matched = pot.names.isNotEmpty() && langs.names.isNotEmpty()
        return AdapterProbe(
            descriptor.id,
            matched = matched,
            confidence = if (matched) 90 else 0,
            evidence = pot.names + langs.names,
            blockedReason = if (!matched && pot.names.isNotEmpty()) "po/messages.pot found without ui/languages.xml" else null,
        )
    }

    override suspend fun inspect(input: ArtifactRef, budget: ResourceBudget): AdapterReport {
        val pot = scanApk(input, budget) { name, _ -> name.endsWith("po/messages.pot") || name.endsWith("ui/languages.xml") }
        return AdapterReport(
            descriptor.id, AdapterOperation.INSPECT,
            processed = pot.names.size, succeeded = 0, skipped = 0,
            warnings = listOf("probe-level inventory only; string counts are produced by the localization run"),
            evidenceLevel = "UNIT_FIXTURE",
        )
    }

    override suspend fun extract(input: ArtifactRef, budget: ResourceBudget): AdapterReport {
        val runtime = deps
            ?: throw UnsupportedOperationException("${descriptor.id}: EXTRACT needs runtime deps (staging dir, fonts, translate)")
        val file = artifactToFile(input)
        budget.requireInputWithinLimit(file.length())
        val output = runtime.outputFor(input, "po")
        val report = GettextPoLocalizer.addArabicGettextSlot(
            file, output, GettextPoLocalizer.FontInjection(runtime.fontBytes), runtime.translate,
        )
        val summary = AdapterReport(
            descriptor.id, AdapterOperation.EXTRACT,
            processed = report.stringsTranslated + report.stringsSkipped,
            succeeded = report.stringsTranslated,
            skipped = report.stringsSkipped,
            // Honest until the wrapper dispatch path itself runs in emulator CI.
            evidenceLevel = "UNIT_FIXTURE",
        )
        lastLocalizationOutput = AdapterLocalizationOutput.Gettext(report, summary)
        return summary
    }
}

/** Android native string resources (resources.arsc). Present in nearly every APK, so low confidence. */
internal class AndroidResourcesAdapter : ApkProbeAdapter() {
    override val descriptor = AdapterDescriptor(
        id = "adapter.android.resources",
        version = "0.1.0",
        displayName = "Android string resources (arsc)",
        inputKinds = setOf(ArtifactKind.APK),
        outputKinds = setOf(ArtifactKind.APK),
        operations = setOf(AdapterOperation.INSPECT),
        formatIds = setOf("android-arsc"),
        priority = 100,
    )

    override suspend fun probeApk(input: ArtifactRef, budget: ResourceBudget): AdapterProbe {
        val markers = scanApk(input, budget) { name, _ -> name == "resources.arsc" }
        return AdapterProbe(
            descriptor.id,
            matched = markers.names.isNotEmpty(),
            confidence = if (markers.names.isNotEmpty()) 40 else 0,
            evidence = if (markers.names.isNotEmpty()) listOf("resources.arsc present; string-table contents not parsed at probe level") else emptyList(),
        )
    }

    override suspend fun inspect(input: ArtifactRef, budget: ResourceBudget): AdapterReport {
        val markers = scanApk(input, budget) { name, _ -> name == "resources.arsc" }
        return AdapterReport(
            descriptor.id, AdapterOperation.INSPECT,
            processed = markers.names.size, succeeded = 0, skipped = 0,
            warnings = listOf("arsc presence only; translatable-string counts need the binary table parse, deferred to the localization run"),
            evidenceLevel = "UNIT_FIXTURE",
        )
    }
}

/** In-place asset text fallback (lowest priority; last resort of the dispatch chain). */
internal class AndroidAssetsInPlaceAdapter : ApkProbeAdapter() {
    override val descriptor = AdapterDescriptor(
        id = "adapter.android.assets.inplace",
        version = "0.1.0",
        displayName = "In-place asset text rewrite (fallback)",
        inputKinds = setOf(ArtifactKind.APK),
        outputKinds = setOf(ArtifactKind.APK),
        operations = setOf(AdapterOperation.INSPECT),
        formatIds = setOf("generic-text-assets"),
        priority = 0,
    )

    override suspend fun probeApk(input: ArtifactRef, budget: ResourceBudget): AdapterProbe {
        val markers = scanApk(input, budget) { name, size ->
            AndroidAssetLocalizer.isSupportedTextAsset(name, size)
        }
        return AdapterProbe(
            descriptor.id,
            matched = markers.names.isNotEmpty(),
            confidence = if (markers.names.isNotEmpty()) 60 else 0,
            evidence = markers.names + (if (markers.truncated) listOf("(more matches not listed)") else emptyList()),
        )
    }

    override suspend fun inspect(input: ArtifactRef, budget: ResourceBudget): AdapterReport {
        val markers = scanApk(input, budget) { name, size ->
            AndroidAssetLocalizer.isSupportedTextAsset(name, size)
        }
        val warnings = buildList {
            add("in-place rewrite is the last-resort path; probe lists candidate files only (first 8)")
            if (markers.truncated) add("additional candidate files were omitted from this bounded inventory")
        }
        return AdapterReport(
            descriptor.id, AdapterOperation.INSPECT,
            processed = markers.names.size, succeeded = 0, skipped = 0,
            warnings = warnings,
            evidenceLevel = "UNIT_FIXTURE",
        )
    }
}

/** The app's adapter registry: one wrapper per existing APK localizer, chain order preserved by priority. */
internal object ArabifyAdapters {
    fun defaultRegistry(): LocalizationAdapterRegistry = LocalizationAdapterRegistry(
        listOf(
            LibGdxLocaleSlotAdapter(),
            GettextPoAdapter(),
            AndroidResourcesAdapter(),
            AndroidAssetsInPlaceAdapter(),
        )
    )

    /**
     * Registry wired for real execution: the slot and gettext wrappers can
     * EXTRACT through their wrapped localizers. The resources and in-place
     * wrappers stay INSPECT-only until their Context-dependent executors are
     * wired (Phase B step 1b).
     */
    fun runtimeRegistry(deps: AdapterRuntimeDeps): LocalizationAdapterRegistry = LocalizationAdapterRegistry(
        listOf(
            LibGdxLocaleSlotAdapter(deps),
            GettextPoAdapter(deps),
            AndroidResourcesAdapter(),
            AndroidAssetsInPlaceAdapter(),
        )
    )
}
