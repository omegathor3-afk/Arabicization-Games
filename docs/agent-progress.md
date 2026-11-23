# ARABIFY - Agent Progress Log

This file is the resumable state of the autonomous development session on
`feat/unity-mono-streamingassets`. Each batch is a separate tested commit.
Rules in force: never `main`, no merge, no Release, no paid services, no
feature claims without passing tests, no build artifacts in git.

## Done (pushed, oldest first)

1. `be6abac` feat(translations): CSV localization files in the asset text
   transformer (header/key-column/snake_case safety, byte-fidelity rebuild)
   + exact-match ArabicUiGlossary reuse before the offline model.
   Tests: 235 unit tests green locally.
2. `b9a6d48` chore: removed 881 accidentally committed build artifacts from
   tracking; added a full Android .gitignore (incl. keystores and APKs).
   No secrets were ever committed (verified file list: only a sandbox
   sdk.dir path in local.properties). Blobs remain in history; a cleanup
   needs a force-push, which requires an explicit user decision.
3. `0ac3d38` feat(unity): UnityTextAssetSerializedPatcher - writes a
   reviewed Arabic text into one TextAsset object inside a standalone
   SerializedFile (payload rebuild, 4-byte alignment, object-table and
   header patch, full post-write verification incl. byte-compare of all
   other objects). Tests: 241 green, fixture = hand-built SerializedFile
   v17 round-tripped through the strict reader.
4. `a2b9c2a` feat(unity): UnityFsBundleRewriter - replaces one directory
   node inside a UnityFS bundle and rebuilds the container (LZ4/raw
   storage blocks, block-info codec preserved for NONE/LZ4, LZMA refused,
   full decode-back verification). Tests: 246 green.
5. `e883fa3` docs: this progress log.
6. `e24d1d8` fix(unity): replaced the MD5 cache-hint hash with truncated
   SHA-256 after CodeQL flagged MD5 as a high-severity alert. The slot is
   a Unity cache hint that nothing validates; no behavior change.
   CodeQL check-run on this commit: success.
7. `8c60180` feat(unity): UnityTextAssetBundleLocalizer - the first
   end-to-end chain: decode bundle -> extract SerializedFile node ->
   inspect -> read TextAsset -> transform (placeholder protection,
   quality gate, glossary) -> patch SerializedFile -> rewrite bundle ->
   re-decode and verify the result (translated script, sibling objects,
   untouched nodes, source bundle never modified). Library level, strict
   Arabic errors, no UI wiring yet. Integration test drives a hand-built
   UnityFS bundle holding a SerializedFile fixture end to end.
   Tests: 249 green (2 pre-existing conditional skips).

## Next up (in order)

- v22+ SerializedFile fixture tests for the patcher (64-bit header fields).
- Real-game fixture: run extract/preview/patch/rewrite on an actual Unity
  Mono game bundle and compare with UnityPy's output (differential test
  like the existing oracle pattern).
- APK-level integration: locate the UnityFS bundle inside an APK, run the
  localizer, and rebuild a signed debug APK (library + tests first, UI
  wiring after).
- Split APK rebuild support (their issue #2).
- Bergamot offline engine wiring (their issue #6) - currently ML Kit only,
  model downloads once then works offline.
- Font catalog/matching (their issue #5); Arabic shaping review for games
  without proper text layout.
- IL2CPP: research only; not supported and not claimed.

## Environment notes (for the next agent run)

- Clone of this branch with build toolchain lives in the agent sandbox:
  JDK17 at /downloads/toolchain/jdk17, Android SDK 36 at
  /downloads/toolchain/sdk, Gradle 8.13 at /downloads/toolchain/gradle.
  Run tests with:
  `gradle testDebugUnitTest --no-daemon -Dorg.gradle.jvmargs='-Xmx1g'`
  (the sandbox has ~2 GB RAM; kill leftover Gradle daemons if git stalls).
- Pushes use an SSH deploy key (write access) stored only in the sandbox;
  main and Releases are never touched.
- Not verifiable in the sandbox: physical-device behavior, Firebase Test
  Lab instrumented tests, real game compatibility.

## Known honest limits (do not claim otherwise)

- AssetBundle write-back works for NONE/LZ4 containers in tests with
  synthetic fixtures; no real game has been localized end-to-end yet.
- IL2CPP, Unreal, Godot: unsupported.
- The Android UI does not yet expose the patcher/rewriter; they are
  library-level and test-verified only.
