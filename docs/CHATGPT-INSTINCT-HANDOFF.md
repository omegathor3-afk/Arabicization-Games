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

## Last independently reviewed repository state

- Branch head observed: `06b0d4432b941053326d50cb5e33d3810276f232` (2026-11-25).
- Current README describes Android Native string-resource localization, bounded Unity Mono StreamingAssets/TextAsset handling, libGDX/gettext language-slot localization, and experimental Bergamot integration.
- README explicitly says Unity Mono validation is fixture/emulator based, not a real Unity Player game test. IL2CPP, Unreal localization, and a complete Godot adapter are not implemented.
- README reports Mindustry and Unciv emulator evidence. Treat reported counts/screenshots as project claims until the relevant CI artifacts and test logs are independently checked.
- UnityFS writer currently uses substantial in-memory byte arrays and documents file-backed streaming as unfinished. Preserve/reinforce conservative memory rejection rather than weakening limits.
- Latest observed CodeQL/Gitleaks workflow run for this head: run `45640087180`, conclusion `failure`; failed step: `Build Kotlin and Android sources for CodeQL extraction`. Investigate the job logs and repair the cause. Do not mark CI green until a new run on the relevant head succeeds.
- The repository tree at this head was checked: generated `.gradle/`, `.kotlin/`, `app/build/`, and `local.properties` were not present in the current tree. This does not erase their historical appearance in earlier commits; do not rewrite history without approval.

## Immediate shared priorities

1. **CI first:** inspect the failing CodeQL job logs for run 45640087180, identify the precise Gradle/Kotlin/Android extraction failure, fix it on this branch, and report the new run URL and conclusion.
2. **Unity Mono real-game validation:** obtain a legally usable, reproducible Unity Mono sample game/build. Run the app's production import → detect → extract/translate → rewrite → sign → install → launch flow. Capture before/after evidence and verify gameplay/menu text in the actual Unity Player. A synthetic APK fixture is not a substitute.
3. **UnityFS memory work:** continue moving rewrite stages toward file-backed/temp-file streaming. Keep explicit limits for compressed input, decoded output, total bytes, node counts, and heap. Test low-memory rejection and cleanup after cancellation/failure.
4. **Offline translator UX:** clearly show model download/installed state and verify translation after app restart with airplane mode enabled. Keep Bergamot labelled experimental until integrated into the user-facing pipeline and measured on a physical ARM64 phone.
5. **Release readiness:** produce and validate a signed installable APK only after CI and core flows pass. Record package ID, version, min/target SDK, ABI, signing type, SHA-256, size, install/launch result, and device/emulator used.
6. **Engine honesty:** keep IL2CPP, Unreal, and Godot labelled unsupported/incomplete until their actual readers/writers and meaningful integration tests exist.

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

- Instinct: before starting a task, read this file and check the current branch head. After implementation, append a dated handoff entry in a focused commit and include the exact test evidence.
- ChatGPT reviewer: inspect the latest branch head and the cited code/tests/workflow evidence; append review findings and actionable next steps here when repository write access is available. If unable to write, provide the exact proposed note to the owner rather than implying it was committed.
- If a finding is disputed, record the evidence and resolution in this file instead of silently overwriting the other party's conclusions.
