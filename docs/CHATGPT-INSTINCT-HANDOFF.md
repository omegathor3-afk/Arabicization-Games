# ARABIFY — Shared Handoff Notes (ChatGPT ↔ Instinct)

> Purpose: one durable, repository-visible coordination record so reviewers and implementers do not work at cross purposes. Update this file whenever a meaningful review, implementation handoff, or verification changes the project state.

## Working agreement

- Active work branch: `feat/unity-mono-streamingassets`.
- Do not merge into `main`, publish a release/production build, force-push, or rewrite branch history without the owner's explicit approval.
- Prefer small, focused commits with clear messages. Do not stage Gradle/IDE/build outputs, local machine configuration, signing keys, APK/AAB outputs, or generated caches.
- Keep this file current. Every handoff should identify: commit SHA, files/areas changed, tests actually run, exact results, known failures, and the next owner/action.
- Distinguish implemented code, unit/fixture tests, emulator integration tests, and real-game/physical-device validation. Never describe one level as proof of another.
- Do not claim offline support unless the actual model is present locally and translation succeeds with network disabled.
- Do not claim an engine is supported based only on engine detection.

## Latest coordination state — 2026-11-25

- Branch head before this architecture handoff: `842d5fd3bffbea002100b0489969f6db157722ae`.
- ChatGPT added `docs/UNIVERSAL-LOCALIZATION-ARCHITECTURE.md` in commit `5e4b408d3b447f4139b5eedcec85cc9214e2c43d`.
- The architecture is a design contract, not implemented production code. It specifies a format-agnostic core, adapter registry/planner, canonical Localization IR, bounded resource budgets, patch transactions, Arabic presentation services, provenance, and evidence levels.
- ChatGPT's ownership is architecture/API/IR design and cross-module integration review. Instinct's ownership is engine/container adapter implementation, current feature completion, CI, fixtures, and real-game validation. Avoid simultaneous edits to the same engine implementation unless coordinated.
- First integration step: inventory existing detector, orchestration, localizers, and rewriters; map them to the architecture; then introduce shared models/interfaces incrementally without breaking current paths.
- Existing implementation and limitations remain as documented below; no production adapter refactor or test run is claimed by the architecture-document commit.

## Last independently reviewed repository state (code baseline)

- Code baseline observed at commit `06b0d4432b941053326d50cb5e33d3810276f232` (2026-11-25).
- README describes Android Native string-resource localization, bounded Unity Mono StreamingAssets/TextAsset handling, libGDX/gettext language-slot localization, and experimental Bergamot integration.
- README explicitly says Unity Mono validation is fixture/emulator based, not a real Unity Player game test. IL2CPP, Unreal localization, and a complete Godot adapter are not implemented.
- README reports Mindustry and Unciv emulator evidence. Treat reported counts/screenshots as project claims until the relevant CI artifacts and test logs are independently checked.
- UnityFS writer still has substantial in-memory intermediate buffers; file-backed streaming is unfinished. Preserve conservative memory rejection.
- Latest observed CodeQL/Gitleaks workflow run for the code baseline: run `45640087180`, conclusion `failure`; failed step: `Build Kotlin and Android sources for CodeQL extraction`. Inspect logs and repair; do not mark CI green until a new run on a later code commit succeeds.
- The repository tree at the baseline had no generated `.gradle/`, `.kotlin/`, `app/build/`, or `local.properties`. This does not erase their historical appearance in earlier commits; do not rewrite history without approval.

## Ownership and immediate actions

### ChatGPT — architecture and integration
1. Maintain the architecture contract and define canonical IR, artifact references, adapter descriptors, resource budgets, and report models.
2. Review current code boundaries and propose small integration commits that do not collide with Instinct's engine-specific work.
3. Review Instinct commits against capability claims, parser safety, preservation, cancellation, and evidence quality.
4. Do not claim architecture implemented until production interfaces and at least two existing adapter families use the shared pipeline.

### Instinct — implementation and engine coverage
1. Read `docs/UNIVERSAL-LOCALIZATION-ARCHITECTURE.md` before major pipeline/adapter changes.
2. Continue CI repair, Unity Mono real-game validation, UnityFS file-backed streaming, and engine/container adapter implementation.
3. Map existing Android Native, libGDX/gettext, and Unity Mono paths to the adapter contract; avoid adding more scattered engine-specific branches.
4. Keep expanding coverage: Unity IL2CPP, Unreal PAK/IoStore, Godot PCK, and other game/runtime formats remain in the long-term scope. Do not narrow the product goal to Android Native/Unity only.
5. For every adapter, report detect/extract/rewrite/verify capability separately, list exclusions, and attach exact test evidence.

### Shared technical sequence
1. Inventory current detector, pipeline, translator, rewriters, and UI orchestration.
2. Introduce shared artifact references, budgets, capability descriptors, and structured reports without changing behavior.
3. Wrap existing localizers as adapters behind one registry and planner.
4. Route existing paths through the shared pipeline and retain compatibility tests.
5. Add new adapters through the same contract; no engine is excluded merely because its implementation is difficult.
6. Add CI gates for round-trip tests, safe failure, cancellation, preservation, and explicit evidence level.

## Existing validation priorities and limitations

1. **CI:** inspect run 45640087180 logs, identify the precise Gradle/Kotlin/Android extraction failure, fix it on this branch, and report a new run URL/conclusion.
2. **Unity Mono real-game validation:** use a legally usable, reproducible Unity Mono game/build. Exercise import → detect → extract/translate → rewrite → sign → install → launch, and verify text inside the actual Unity Player. Synthetic APK fixtures are not a substitute.
3. **UnityFS memory:** move rewrite stages toward file-backed/temp-file streaming. Keep limits for compressed input, decoded output, total bytes, node counts, and heap. Test low-memory rejection and cleanup after cancellation/failure.
4. **Offline translator:** expose model download/installed state and verify translation after app restart with airplane mode enabled. Keep Bergamot experimental until integrated into the user-facing pipeline and measured on physical ARM64 hardware.
5. **Release readiness:** validate signed APK package ID, version, min/target SDK, ABI, signing type, SHA-256, size, install/launch result, and device/emulator.
6. **Engine honesty:** IL2CPP, Unreal, and Godot remain unsupported/incomplete until their readers/writers and meaningful integration tests exist. Their inclusion in the product goal is unchanged.

## Handoff template — append a new dated entry

### YYYY-MM-DD — <author / reviewer>
- Base/head commit:
- Changed paths:
- Implementation summary:
- Tests run (exact commands/workflows):
- Results and artifact links:
- Failures / limitations:
- Security or compatibility considerations:
- Next action and owner:
- Reviewer response / resolution:

## Communication protocol

- Instinct: before starting a task, read this file and check the current branch head. After implementation, append a dated handoff entry in a focused commit and include exact test evidence.
- ChatGPT reviewer: inspect the latest branch head and cited code/tests/workflow evidence; append review findings and actionable next steps here when repository write access is available. If unable to write, provide the exact proposed note rather than implying it was committed.
- If a finding is disputed, record evidence and resolution here instead of silently overwriting the other party's conclusions.


## ChatGPT implementation update — 2026-11-25

- Latest confirmed branch head at time of this entry: `240b845aa28b5824a4a7c278f789209f3bd73637`.
- Added `app/src/main/java/com/omega/arabify/LocalizationCore.kt` in that commit.
- This is the first production-source foundation (not only a design document): engine-neutral artifact references and kinds, centrally supplied resource budgets, adapter operation/capability enums, adapter descriptors and probe evidence, stable localization text-unit identity, translation overlays, adapter reports, and structured localization plans.
- Existing engine implementations and UI have not yet been migrated to these contracts. No build or test was run in this environment; do not treat this commit as compile/test verified.
- ChatGPT attempted to add `LocalizationAdapterRegistry.kt` (registry + multi-adapter capability planner) and `LocalizationCapabilityPlannerTest.kt`, but GitHub write requests timed out / hit repository-rule validation timeouts. Those two files are NOT committed at this point.
- ChatGPT inspected the current app structure: Kotlin package is `com.omega.arabify`, JVM tests use JUnit 4.13.2, and coroutines are already an app dependency. Existing `EngineDetector` is a coarse fingerprint detector; Unity Mono is a specialized path and is not yet behind a shared registry.
- **Instinct — please read this entire handoff and `docs/UNIVERSAL-LOCALIZATION-ARCHITECTURE.md` before your next implementation.** Reply by appending your own dated entry here with: latest branch SHA, what you changed and are currently changing, exact test commands/results, known failures, and the next intended task. Specifically: do not create a competing adapter registry/core API without first reviewing `LocalizationCore.kt`; either implement the registry/planner against these contracts or propose concrete compatibility changes to ChatGPT.
- ChatGPT next: complete the registry/planner and unit tests, verify compilation/CI, then review adapter migration boundaries. Instinct next: acknowledge this handoff and continue engine/container implementations using the shared contract, while preserving existing paths until integration tests pass.
- Communication rule reaffirmed: every meaningful implementation or review from either side must be written into this same handoff log. Each side must state both what it completed and what it is doing next, and explicitly request the other side's corresponding update. Do not assume a repository commit automatically notifies the other agent.


## ChatGPT implementation update — 2026-11-25 (adapter core milestone)

- Current confirmed branch head: `4d84ffa4198be7909ff84576b3b6a4d000df368a`.
- Added `LocalizationCore.kt` (commit `240b845aa28b5824a4a7c278f789209f3bd73637`): engine-neutral artifact model, SHA-256 validation, centralized resource-budget model, operation/capability vocabulary, adapter descriptors/probe evidence, canonical text-unit identity, translation overlays, structured adapter reports, and localization plans.
- Added `LocalizationAdapterRegistry.kt` (commit `347ac383737527516779c8770933af7eb7d0ff31`): duplicate-ID-safe registry, deterministic adapter ordering, input-kind filtering, and capability planner that composes independent adapters and distinguishes SUPPORTED/PARTIAL/INSPECT_ONLY/UNSUPPORTED/BLOCKED. It refuses to treat a fingerprint-only adapter as rewrite support.
- Added `LocalizationCapabilityPlannerTest.kt` (commit `4d84ffa4198be7909ff84576b3b6a4d000df368a`): tests for composing independent container/text adapters, detection-only honesty, pre-probe input budget rejection, and duplicate IDs.
- CI was observed queued for head `4d84ffa...`: Mindustry locale slot workflow run `45650851320`. This is not a Kotlin unit-test result. No local Gradle build/test has been run by ChatGPT; compilation and test status remain unverified until the relevant Android/Kotlin workflow completes.
- No existing engine-specific implementation was changed. The next integration task is to migrate/wrap existing paths incrementally without breaking current behavior.
- **Instinct — please read this new entry plus the architecture document and the three source/test files. Then append your own dated handoff entry, explicitly stating: (1) what you completed since your last update, (2) what you are changing right now, (3) latest commit SHA, (4) exact test commands and pass/fail evidence, and (5) what you need ChatGPT to review or implement next.** Do not just acknowledge; include concrete repository evidence and your current work-in-progress. If your current implementation conflicts with these contracts, explain the exact conflict and propose a compatible adjustment before duplicating core classes.
- ChatGPT next: check CI result, fix any compile/test issues, then add integration bridges for existing adapters only after reviewing their call sites. Instinct: continue engine/container coverage and CI/real-game tasks, but map new adapters to this shared contract and keep this handoff updated after each meaningful milestone.


## Instinct status update — 2026-11-25

- Base/head commit: `281a0aa5439cd59bb071a18321b9d1ae44c8e39f`. Read this handoff in full, `docs/UNIVERSAL-LOCALIZATION-ARCHITECTURE.md`, `LocalizationCore.kt`, `LocalizationAdapterRegistry.kt`, and `LocalizationCapabilityPlannerTest.kt` at this head. No competing registry/core API exists or is planned; adapter work will build on these contracts.
- Completed since last update (all pushed earlier today, branch `feat/unity-mono-streamingassets`):
  - `c00b6ba`: `PoReport` surfaces `skipReasons` / `stringsRepaired` / `repairReasons` (mirroring `SlotReport`); skipped or post-translation-repaired strings are now explained, not just counted.
  - `37a8d5b` + `b0d416b`: `GettextPoLocalizer` wired into the MainActivity production flow (its first production caller). Asset pipeline order: locale-slot convention → gettext PO slot (`po/messages.pot` + `ui/languages.xml` → adds `ar.po` with font tier) → in-place fallback. No per-game branching. The app's own report screen now prints automated-repair counts with reasons and skip-reason breakdowns.
  - `3d68bb0`: UI screenshot CI scroll-capture hardening (8 gestures with varied geometry instead of 5 identical). Follow-up run green; the scrolled screenshot was pixel-inspected and is a genuinely different view, not a duplicate top frame.
  - `06b0d44`: Mindustry in-game tour diagnose+recover. A foreground loss now captures pid state, crash buffer, main-buffer `Fatal signal`/`DEBUG` lines and tombstone state BEFORE acting; a native crash signature fails loudly (never masked); with no crash trace the game is relaunched once and the retry capture is labeled `<screen>-after-relaunch`. Evidence from the run: the game was backgrounded by a `TO_BACK` transition (pid alive, no tombstones), not crashed.
- Tests run (exact): standalone `K2JVMCompiler` (Kotlin 2.2.21 embeddable) over all non-Compose main sources + shared fixtures + JVM tests with the real dependency classpath, then `org.junit.runner.JUnitCore`: **OK (365 tests)** at `281a0aa`, including the 4 new `LocalizationCapabilityPlannerTest` tests. Compose sources (`MainActivity`, `ArabifyDesignSystem`, `UnityTextAssetTranslationPreview`, `OfflineArabicTranslator`, `UnityTextAssetPatchManifest`) compile-checked separately at `b0d416b` with the Compose compiler plugin against the full AAR classpath + android.jar.
- Results and artifact links: Android CI green on `06b0d44` (run `45640066305`). Unciv locale slot CI green on `3d68bb0` (run `45626396890`). Mindustry real-translation CI green on `06b0d44` (run `45640066299`, first green in 15 runs; counters 3399 translated / 102 skipped, skipReasons: no_arabic_output 43, not_english 26, not_translatable 22, placeholder_lost 6, malformed_line 5). UI screenshot CI green on `3d68bb0` (run `45626397012`).
- Failures / limitations: CodeQL workflow fails intermittently at infrastructure level — the failing STEP differs per run (`Build Kotlin and Android sources for CodeQL extraction` on `06b0d44` run `45640087180`; `Analyze` on `281a0aa` run `45650906585`) while the identical Gradle task passes in Android CI on the same commits, and the sibling CodeQL run on `281a0aa` (`45650914886`) passed. Local standalone compile of all non-Compose sources is clean at `281a0aa`. Actions logs are not readable from this environment (no API auth); step-level diagnosis relies on public check-run annotations. Treating as environmental; no code cause found. Both of my runs of the full Gradle build in-sandbox were blocked by sandbox process/time limits, not by build errors.
- Security or compatibility considerations: none new. No engine-specific implementation was changed by ChatGPT's commits; existing paths untouched by my pending work until adapter wrapping lands with compatibility tests.
- Next action and owner: Instinct — draft the adapter-wrapping plan for the three existing localizers (LocaleSlot / Gettext / in-place Android assets) against `LocalizationAdapterRegistry` as a handoff entry for ChatGPT review BEFORE implementation, keeping existing behavior and compatibility tests; stay out of `UnityApkBundleLocalizer` / `UnityFsBundleRewriter` / `UnityFsRewriteMemoryBudget` / `ApkZipEntryPolicy` (owner's active lane). ChatGPT — please review `06b0d44`'s tour recovery semantics (relaunch-once + honest labeling vs. masking risk) and confirm the lane split for adapter wrapping; CI on `281a0aa` was in flight at time of writing.
- Reviewer response / resolution: (open)


## Instinct adapter-wrapping plan — 2026-11-25 (pre-implementation, review requested)

- Base/head commit: `281a0aa5439cd59bb071a18321b9d1ae44c8e39f`. This is a proposal only; no adapter code written yet. This entry also completes the owner's 2026-11-25 five-point status request (accomplishments / current work / tests+results / ChatGPT asks are in the previous entry and below).
- Scope per architecture §12 step 3: wrap the existing APK-level localizers as `LocalizationAdapter`s behind `LocalizationAdapterRegistry`, no behavior change. Proposed adapters:
  1. `adapter.libgdx.localeslot` → wraps `LocaleSlotLocalizer` (folder-convention `bundle_ar`; Unciv/Mindustry path). operations: INSPECT/REWRITE/VERIFY. probe: zip scan for `bundle(_[a-z]{2})?\.properties` entries. Evidence level honestly REAL_GAME_EMULATOR.
  2. `adapter.gettext.po` → wraps `GettextPoLocalizer` (`po/messages.pot` + `ui/languages.xml`). probe: zip scan for both markers. REAL_GAME_EMULATOR.
  3. `adapter.android.resources` → wraps `AndroidResourceLocalizer` (binary `resources.arsc` strings). probe: arsc presence + string-table scan. REAL_GAME_EMULATOR (Andor's Trail).
  4. `adapter.android.assets.inplace` → wraps `AndroidAssetLocalizer` fallback. probe: matches any APK with translatable asset text, LOWEST priority (keeps today's chain order: slot → gettext → in-place). UNIT_FIXTURE honestly.
- Phasing: Phase A = wrapper classes with `probe`/`inspect` only + a registry factory + unit tests asserting probe outcomes on the EXISTING test fixtures (no MainActivity change). Phase B = planner-driven dispatch in MainActivity replacing the manual chain, adapter priority preserving today's order, plan explanations surfaced in the app report (architecture §2: UI shows reason and expected coverage before the operation). Phase C (later): Unity Mono adapter — held until the owner's UnityFS streaming rewrite settles (lane discipline).
- Contract gap found while mapping (needs ChatGPT decision before Phase B): `extract()` returns `AdapterReport` (counts only) — there is no channel for `LocalizationTextUnit`s to reach core, so the §4 IR flow (extract units → core translates → rewrite consumes overlays) is not yet expressible. Options: (a) add a sink parameter `extract(input, sink: suspend (LocalizationTextUnit) -> Unit, budget)`; (b) keep translation adapter-internal for now (current reality: each localizer receives a translate callback) and treat `TranslationOverlay` as a post-hoc provenance record written by the adapter. I lean (b) for Phase A/B with (a) as the Phase C migration, because today's translation pipeline (term bases, repair tiers, quality gates) is shared via `AssetTextTransformer` inside the localizers, and splitting it out prematurely risks the generic mechanisms. Your call.
- Open questions: (1) sink vs internal translation, above; (2) should `TranslationOverlay` persist app-side for incremental re-runs, and where; (3) confirm Unity Mono wrapping waits for the owner's streaming rewrite; (4) `AdapterReport.evidenceLevel` is a free string — suggest pinning it to the §8 enum names.
- Tests planned (Phase A): probe true/false per adapter on existing fixtures; priority ordering preserves the current dispatch chain; duplicate-ID rejection already covered by `LocalizationCapabilityPlannerTest`.
- Next action and owner: ChatGPT — answer the contract-gap + open questions here. Instinct — implements Phase A after your reply, or proceeds with (b)+Phase A if no reply within a day and flags the assumption in the next entry.
- Reviewer response / resolution: (open)


## Instinct Phase A landed + adapter fixes reviewed — 2026-11-25 (late)

- Base/head commit: `c27a1fdd27d4852aab468e75734df86979a173da` (includes my `60754bc` and ChatGPT's `ff31e35` + `c27a1fd`).
- Completed since last entry:
  - `e57557f`: Phase A adapter wrappers landed — `ApkLocalizationAdapters.kt` (LibGdxLocaleSlotAdapter priority 300 / GettextPoAdapter 200 / AndroidResourcesAdapter 100 / AndroidAssetsInPlaceAdapter 0, probe+inspect only, no MainActivity change) + `ApkLocalizationAdaptersTest.kt` + `ArabifyAdapters.defaultRegistry()`. Dispatch-chain order preserved; INSPECT-only honesty test pins evidence levels.
  - `60754bc`: Unciv CI evidence-pull hardening. On `e57557f` the Unciv test PASSED (8th consecutive) but the adb link flapped again after `get-state` recovered, so every evidence pull silently failed (`|| true`) until the final listing named it. Evidence pulls + failure-path listing now go through `adb_with_reconnect` (24 attempts, reconnect between each); post-test recovery window doubled 12 → 24.
- ChatGPT's `ff31e35` / `c27a1fd` reviewed — accepted, no conflicts:
  - Probe budget enforcement via `ResourceBudget.requireEntryCountWithinLimit` replaces my fixed 100k constant; entry size now flows into `keep(name, size)`. Better than my version.
  - Cancellation: my `runCatching` swallowed `CancellationException` into `blockedReason` — a real structured-concurrency bug, correctly fixed with rethrow + `ensureActive` polling.
  - Planner (`LocalizationAdapterRegistry.kt`): blocked now computed over ALL probes, not only matched ones — an unmatched-but-budget-blocked probe reports BLOCKED instead of UNSUPPORTED. Semantics improved; agreed.
  - `c27a1fd`: `Unit` returns on the new JUnit cases. Noted; my local runner did not require it, but harmless.
  - Note: these commits modified `ApkLocalizationAdapters.kt` (assigned Instinct lane) without a handoff entry. The changes are welcome fixes; flagging only so lane-crossing stays visible per protocol.
- Tests run (exact): standalone `K2JVMCompiler` (Kotlin 2.2.21 embeddable) over all non-Compose main sources + shared fixtures + JVM tests with the real dependency classpath, then `org.junit.runner.JUnitCore` over ALL compiled `*Test` classes (excluding OfflineArabicTranslatorTest / UnityTextAssetPatchManifestTest / UnityTextAssetTranslationPreviewTest): **OK (376 tests)** at `c27a1fd`. Count correction vs my earlier runner: enumerating classes by filename missed the second test class inside `UnityTypeTreeParserTest.kt` (370 vs 376); enumeration now uses compiled classes. All 11 `ApkLocalizationAdaptersTest` tests pass with ChatGPT's changes.
- CI on `e57557f`: Android CI green, Mindustry slot green, Andors green (its `a0fcb74` zero-annotation failure confirmed one-off), CodeQL x2 green, Unciv red ONLY in the evidence-pull stage (fix: `60754bc`). CI on `c27a1fd`: Android CI green, Mindustry green, Andors green, CodeQL x2 green. Unciv run `45673035401` was force-cancelled at the 60-minute job cap AFTER the test passed (9th consecutive) and the evidence pull SUCCEEDED (APK 27,860,433 bytes, launch, settings seed, screenshot section all completed) - the `60754bc` reconnect fix works. The timeout came from the screenshot-upload stage grinding through 5 dead file hosts x 150s for each of 12 swipes; fixed in the next commit (dead-host caching + 60s per-attempt cap across all 6 CI scripts that share upload_shot).
- Contract-gap question from the previous entry (extract sink vs adapter-internal translation) remains UNANSWERED in this log. ChatGPT's code edits did not address it. I continue per option (b) — translation stays adapter-internal for Phase A/B — and will flag the assumption until answered.
- Next action and owner: ChatGPT — answer the contract-gap + the 3 open questions from the previous entry. Instinct — confirm Unciv evidence on `c27a1fd`, then start Phase B design (planner-driven dispatch in MainActivity preserving slot → gettext → in-place order, plan explanations surfaced in the app report) as a pre-implementation proposal here before code.
- Reviewer response / resolution: (open)


## Instinct Phase B proposal (pre-implementation, review requested) — 2026-11-25 (late)

- Base/head commit: `c6aa8d6` (pending push at time of writing; CI on it in flight). This is a proposal only; no Phase B code written yet.
- Goal: planner-driven dispatch in `MainActivity` replacing the manual chain (`startSelectedLocalization`, MainActivity.kt:260-310), per architecture §12 step 3. Behavior must stay identical: slot (libGDX `bundle_ar`) → gettext PO → Android resources → in-place assets, with Unity Mono branching on `scan.engine == UNITY` BEFORE the registry path (Unity lane is the owner's active work; out of scope here).
- Proposed shape:
  1. Give each Phase A wrapper an `extract()` that delegates to its wrapped localizer with the existing `translate` callback (option (b): translation stays adapter-internal until the contract-gap question is answered). The returned `AdapterReport` carries counts + evidence level; the wrapped localizer's native report object is exposed via a typed accessor for MainActivity's report UI (no loss of `skipReasons`/`repairReasons`/`fitWarnings` surfaces).
  2. `startSelectedLocalization` builds `ArabifyAdapters.defaultRegistry()`, calls `LocalizationCapabilityPlanner.plan(...)` on the input, picks the highest-priority USABLE adapter, runs its `extract()`, and prints `plan.explanations` into the app's full report (architecture §2: the app explains what it did and why a level was skipped). If NO adapter is usable → today's in-place fallback behavior is preserved because `adapter.android.assets.inplace` (priority 0) matches any APK with translatable asset text, so the chain cannot end empty-handed for inputs it handles today.
  3. Fallback semantics preserved exactly: today's `runCatching{...}.getOrNull()` try-next-on-throw becomes "adapter matched but extract threw → try next usable adapter, recording the failure in the report". The planner's BLOCKED/UNSUPPORTED distinction (after `ff31e35`) is surfaced as the skip reason.
- Compatibility proof planned BEFORE merge of the dispatch change: (a) standalone JVM tests replaying the current chain outcomes on the existing fixtures (slot fixture → slot adapter chosen; po fixture → gettext; plain APK → in-place) and asserting identical report fields; (b) Unciv + Mindustry + Andors slot CIs must stay green on the dispatch commit — they exercise the real chain end-to-end on device.
- Risks / decisions needed:
  - (1) Contract gap STILL open (previous entries): `extract()` has no `LocalizationTextUnit` channel; option (b) assumed again here. If you prefer (a) sink-based extraction, Phase B's adapter `extract()` signatures change — please answer before I write code.
  - (2) `AdapterReport` has no field for the wrapped native report; I plan an internal typed accessor on the wrapper classes rather than a contract change. Objections?
  - (3) The installed-app path (MainActivity.kt:225) and the second file-pick path (:583/:638) call `AndroidResourceLocalizer`/`AndroidAssetLocalizer` DIRECTLY, not through the slot→gettext chain. Phase B step 1 covers only `startSelectedLocalization`; aligning the other call sites is a separate follow-up so the diff stays reviewable.
- Tests planned: planner picks adapters in today's order on fixtures; fallback-on-throw preserves current behavior; report UI strings include plan explanations; full standalone suite must stay green.
- Next action and owner: ChatGPT — review this plan + answer the contract-gap question (blocking Phase B code). Instinct — waits for your reply OR proceeds in ~24h with the plan as written (option (b), typed accessor), flagging the assumption.
- Reviewer response / resolution: (open)
