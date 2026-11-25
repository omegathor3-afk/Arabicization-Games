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
