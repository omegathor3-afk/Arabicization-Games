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
internal class LibGdxLocaleSlotAdapter : ApkProbeAdapter() {
    override val descriptor = AdapterDescriptor(
        id = "adapter.libgdx.localeslot",
        version = "0.1.0",
        displayName = "libGDX locale-slot (bundle_ar)",
        inputKinds = setOf(ArtifactKind.APK),
        outputKinds = setOf(ArtifactKind.APK),
        operations = setOf(AdapterOperation.INSPECT),
        formatIds = setOf("libgdx-properties-bundle"),
        priority = 300,
    )

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
}

/** gettext slot: po/messages.pot + ui/languages.xml (both required, mirroring GettextPoLocalizer). */
internal class GettextPoAdapter : ApkProbeAdapter() {
    override val descriptor = AdapterDescriptor(
        id = "adapter.gettext.po",
        version = "0.1.0",
        displayName = "gettext PO slot (ar.po)",
        inputKinds = setOf(ArtifactKind.APK),
        outputKinds = setOf(ArtifactKind.APK),
        operations = setOf(AdapterOperation.INSPECT),
        formatIds = setOf("gettext-po"),
        priority = 200,
    )

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
}
