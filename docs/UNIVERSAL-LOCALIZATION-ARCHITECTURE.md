# ARABIFY — Universal Localization Architecture

Status: architecture decision record / implementation contract  
Target: Android-first, offline-capable game localization across engines, runtimes, packaging formats, and custom game pipelines.

## Decision

ARABIFY will use a format-agnostic localization core with independently versioned adapters. It will not be a collection of engine-name checks followed by one hard-coded patcher. The core owns orchestration, safety, translation, Arabic text processing, provenance, validation, and output packaging. Adapters own the technical knowledge required to inspect and safely rewrite a specific game/container/runtime format.

The product goal remains broad: support any game that can be localized through a technically accessible and legally permitted path. Universal is the long-term coverage goal, not a claim that every protected, encrypted, server-driven, or inaccessible game can already be modified.

## Non-negotiable architecture rules

1. Detection is not support. An adapter separately reports what it detected, can extract, can rewrite, and has actually validated.
2. No engine monopoly. The pipeline accepts adapters for engines, archives, resource tables, script/data formats, and custom game-specific layouts. A game may require a chain of adapters.
3. Read-only first. Inspect and produce a plan before mutation. Never overwrite the original input.
4. Lossless by default. Preserve unknown files, metadata, compression, alignment, ordering, and all unmodified bytes. If preservation cannot be proven, reject the rewrite or require explicit acknowledgement.
5. Bounded resources. Every parser declares limits for input bytes, expanded bytes, entry count, nesting/depth, string count/length, CPU/time, and temporary storage. Enforce limits while streaming.
6. Composable pipeline. Extraction, translation, Arabic shaping/RTL, font handling, rewriting, signing, and validation are independent stages with explicit inputs/outputs.
7. Evidence-backed capabilities. Each adapter publishes fixture, emulator, real-game, and physical-device validation separately.
8. Extensible without core edits. Adding a format should normally mean adding an adapter module and tests, not changing orchestration.
9. Offline is measured. Translation engines expose model state and network requirements; offline claims require successful post-restart tests with networking disabled.
10. No DRM or anti-cheat bypass. Protected formats are unsupported unless the user supplies a lawful key or officially accessible source.

## Layer model

~~~text
APK / split package / game folder / archive
                 |
          Intake + Snapshot
                 |
       Fingerprint / Detection
                 |
       Capability & Risk Planner
                 |
      Adapter Chain (read-only scan)
                 |
       Canonical Localization IR
                 |
      Translation + QA + Glossary
                 |
     Arabic RTL / Shaping / Fonts
                 |
        Adapter Chain (rewrite)
                 |
       Repack / Sign / Validate
                 |
       Installable output + report
~~~

## 1. Intake and immutable snapshot

Accept APK, split APK sets, XAPK/APKS where safely unpackable, game directories, archives, and user-provided extracted data. Record source identifier, size, SHA-256, package/game metadata, import timestamp, bounded archive inventory, immutable originals, and a workspace manifest mapping derived artifacts to their source and adapter.

Use Android SAF document URIs and app-private temporary files. Never assume a writable shared path. Temporary files must be cancellable and cleaned after success, failure, or process restart.

## 2. Fingerprinting and capability planner

Detection returns evidence, not one engine string. Multiple fingerprints may coexist (for example, Unity + ZIP + SQLite + JSON). The planner selects a compatible adapter chain using declared capabilities and risk.

The planner returns one of:
- SUPPORTED: complete read + rewrite path exists for the required resources and output target.
- PARTIAL: some resources can be localized; unsupported resources are listed.
- INSPECT_ONLY: content can be inspected/extracted but not safely rewritten.
- UNSUPPORTED: no safe adapter path.
- BLOCKED: encryption/protection or resource limits prevent processing.

The UI shows the reason and expected coverage before a long operation starts.

## 3. Adapter contract

Every adapter declares a stable ID/version, supported format/runtime versions, license/provenance, fingerprints/confidence, accepted/emitted artifact types, inspect/extract/rewrite/verify operations, resource classes/exclusions, preservation guarantees, hard resource limits, cancellation behavior, fixture corpus, and validation level.

Illustrative Kotlin contract (not yet claimed to exist in source):

~~~kotlin
interface LocalizationAdapter {
    val descriptor: AdapterDescriptor
    suspend fun probe(input: ArtifactRef, budget: ResourceBudget): ProbeResult
    suspend fun inspect(input: ArtifactRef, budget: ResourceBudget): InspectionReport
    suspend fun extract(input: ArtifactRef, sink: LocalizationSink, budget: ResourceBudget): ExtractionReport
    suspend fun rewrite(input: ArtifactRef, plan: RewritePlan, budget: ResourceBudget): RewriteResult
    suspend fun verify(original: ArtifactRef, rewritten: ArtifactRef, plan: RewritePlan, budget: ResourceBudget): VerificationReport
}
~~~

Adapters must be deterministic where the format permits. Malformed records are reported with source location and reason; they are never silently skipped.

## 4. Canonical Localization IR

All adapters map discovered text into a common intermediate representation, independent of engine:
- stable TextUnit ID and source locator (archive path, file offset, object ID, table/key, script symbol, scene/resource ID);
- original bytes and decoded text, encoding, locale, context, neighboring strings;
- placeholders/tags/plural metadata and formatting constraints;
- source adapter ID/version and confidence;
- extraction warnings and mutability classification;
- translation status, selected translation, reviewer edits, and QA results.

Source text is immutable. Translations are overlays keyed by stable IDs; translated text never becomes the next source baseline. Duplicate strings remain distinct when context differs.

## 5. Translation and language services

The core selects installed local models and optional user-authorized providers. Engines expose language pairs, model size, memory estimate, quality metadata, offline state, and cancellation. Translation is batched with bounded memory and a persistent glossary/cache.

Quality gates preserve placeholders, markup, escape sequences, variables, format strings, and control tokens; detect empty/unchanged/suspicious output; preserve plural/context metadata; allow glossary overrides and user corrections; and record model/version/provenance. Do not assume English source language: language identification is confidence-scored and user-overridable.

## 6. Arabic rendering services

Translation and display correctness are separate stages. A TextPresentationProfile selected by the adapter decides whether to preserve logical Unicode, use runtime-native RTL, shape Arabic presentation forms, reorder bidi text, or combine techniques. Never shape twice.

Font strategy is adapter-specific: inspect embedded font coverage and shaping tables; prefer an existing Arabic-capable font; otherwise inject/merge a redistributable Arabic font only where format/runtime permit; update font atlases only through a dedicated adapter with round-trip validation; never replace icon glyphs or silently alter unrelated metrics.

Validation includes Arabic/Latin mixed strings, punctuation, numerals, diacritics, placeholders, menus, dialogue, and narrow UI controls.

## 7. Rewrite transaction and preservation

Rewriters produce a patch plan before writing. Each patch declares source locator, expected original hash/bytes, replacement, and allowed mutation range. Apply changes into a new workspace/output, never the original.

Required properties: precondition checks; overlapping-patch detection; streaming copy of untouched ranges where possible; temporary-file quotas and free-space checks; atomic finalization only after validation; cancellation-safe cleanup; rollback by discarding output. Preserve unknown archive entries and metadata. If signing invalidates the original signature, explain the new signing identity and possible app-data loss on reinstall.

## 8. Validation and evidence

Every output passes structural parse/round-trip, unchanged-byte comparison for untouched regions where feasible, semantic verification of translated values, package/signature/manifest/ABI checks, and runtime launch/visual checks where available.

Evidence levels:
- UNIT_FIXTURE
- FORMAT_ROUND_TRIP
- DEVICE_FIXTURE
- REAL_GAME_EMULATOR
- REAL_GAME_PHYSICAL_DEVICE

A lower level never proves a higher one. Reports include input/output hashes, adapter versions, found/translatable/translated/skipped/rewritten counts, warnings, tests, runtime/device, and artifacts.

## 9. Adapter families and coverage roadmap

Adapters are grouped by format responsibility, not one mutually exclusive engine enum:

| Family | Examples | Scope |
|---|---|---|
| Package/container | ZIP/APK, split APK, UnityFS, PAK, IoStore | inventory, safe extraction, rewrite, repack |
| Engine resource | Unity Mono/IL2CPP, Unreal, Godot PCK, RPG Maker, Ren'Py, GameMaker | engine-specific resource layouts |
| Localization format | Android resources, gettext, properties, JSON, CSV, XML, PO/MO, YAML, SQLite | parse, preserve schema, write |
| Runtime/code | DEX, JVM bytecode, .NET/CIL, native metadata, scripts | only where lawful and safely reversible |
| Text/media | fonts, atlases, subtitles, textures, audio metadata | dedicated transforms and validation |
| Game-specific | per-game adapters and profiles | exceptional layouts and compatibility fixes |

Initial targets remain Unity Mono, Unity IL2CPP, Unreal PAK/IoStore, Godot PCK, and common script/RPG engines, but this list is not hard-coded. New adapters can be contributed independently.

## 10. Parallel ownership split

### ChatGPT — architecture and integration
- Own this architecture contract and canonical IR/adapter API design.
- Review cross-module boundaries, capability semantics, safety budgets, and integration compatibility.
- Avoid editing engine-specific implementations while Instinct is actively implementing them unless a coordinated handoff assigns the same task.
- Review Instinct commits and record concrete findings in the shared handoff.

### Instinct — implementation
- Continue engine/container adapters, existing feature completion, CI, fixtures, and real-game validation.
- Map new engine-specific paths to this adapter contract or document an exception before adding scattered orchestration branches.
- Report adapter capabilities separately from detection and include exact tests/evidence.

## 11. Definition of architecture completion

This document alone does not mean the architecture is implemented. Completion requires:
- canonical IR and adapter interfaces in production source;
- at least two materially different adapter families using the same orchestration path;
- capability planning generated from adapter descriptors, not scattered engine conditionals;
- rewrite plans, resource budgets, provenance, and validation reports enforced by core;
- a third-party/game-specific adapter can be added without changing core;
- automated tests prove safe failure, cancellation, preservation, and deterministic planning.

## 12. Immediate integration sequence

1. Inventory current detector, pipeline, translator, rewriters, and UI orchestration; map each to these layers.
2. Introduce canonical artifact references, resource budgets, capability descriptors, and report models without changing current behavior.
3. Wrap existing Android Native, libGDX/gettext, and Unity Mono paths as adapters behind one registry.
4. Route current processing through the planner and shared pipeline; retain compatibility tests.
5. Add another engine adapter only after the shared contract is exercised by existing adapters.
6. Add CI checks that reject rewrite-capable adapters without round-trip tests and an explicit evidence level.

## Current repository caveat

The application already has useful specialized localizers and parsers, but major formats remain unsupported and detection is not rewriting. The first engineering task is a controlled migration of existing paths into the shared architecture—not a rewrite-from-scratch and not a false claim of universal support.
