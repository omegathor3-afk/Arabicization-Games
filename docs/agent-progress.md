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
8. `91de60a` test(unity): SerializedFile v22 fixtures for the patcher
   (48-byte header with 64-bit fileSize/dataOffset, 32-byte TypeTree
   nodes, i64 object byteStart). Three tests: longer/shorter script patch
   on v22 and 64-bit dataOffset reading. Tests: 252 green.

CI after batch 7: CodeQL (default setup) and CodeQL-and-Gitleaks green on
all new heads; build green.
9. `a34a9b3` fix(unity): real LZMA bundles were being refused. UnityFS
   LZMA blocks are raw LZMA1 + 5-byte props header, not LZMA-alone; the
   decoder now reattaches the size field. Proven with differential tests
   against Unity's official UnityDataTools fixtures (Unity Companion
   License, NOTICE vendored) and a UnityPy 1.25.4 oracle: real Unity
   2021.3 LZMA bundle + real Unity 6 bundle decode, and every SerializedFile
   object matches UnityPy (pathId, classId, byteSize). Tests: 256 green.

## Next up (in order)

- CLOSED fixture gap (`5ae8f19` fix(tests)): the hand-built
  SerializedFile fixtures were missing trailing metadata and a 4-aligned
  dataOffset, so UnityPy (whose object payload reader aligns relative to
  the object start) could not read them. Builders now emit real-layout
  files. Verified in sandbox with UnityPy 1.25.4: both v17 and v22
  fixtures read fully (TextAsset names + Arabic scripts), and the
  rewritten real Unity 6 bundle's injected TextAssets read back correctly.
  A dump test (FixtureDumpForUnityPyTest) writes fixtures to
  build/realFixtures/ so the UnityPy check can be re-run any time.
  Note: the older private builders inside
  UnityTextAssetSerializedPatcherTest.kt remain self-consistent-only
  (they predate the shared builders); migrating them is cosmetic.
10. `6eb61a9` feat(unity): UnityApkBundleLocalizer - APK-level chain:
    find UnityFS entries in an APK, translate every supported TextAsset in
    their SerializedFiles, patch, chain one rewrite per changed node,
    rebuild the APK via ApkZipEntryPolicy (methods/alignment preserved,
    stale signatures dropped). Source APK never modified; a strict-gate
    failure aborts instead of shipping a broken APK. Tests: 260 green.
11. `0509397` + revert `4ec0b65` ci: I added a duplicate
    `arabify-debug-apk` upload step; the workflow ALREADY had one
    ("Upload final debug APK", if: always()). The duplicate made run
    45280230697's tail step fail even though every real step was green
    (unit tests, APK build+verify, emulator real-game test). Reverted;
    the original upload step remains the APK delivery route.

12. `4ec0b65` revert of the duplicate CI step; `4b560db` docs correction.
    Run 45280230697 artifact link sent to the user (arabify-debug-apk).
13. `79f2992` test(unity): rewrite a REAL Unity 2021.3 LZMA-storage
    bundle. The rewriter re-chunks storage as raw/LZ4; verified with our
    parsers AND UnityPy 1.25.4 reads the Arabic TextAsset back from the
    rewritten real bundle. Tests: 261 green.
14. `49eb4dd` test(unity): migrated the last private fixture builder to
    the shared UnityTestFixtures helper. Tests: 261 green.
15. `38f80c3` feat(unity): production wiring - UnityMonoLocalizer.localize
    now runs a UnityFS bundle stage before the loose-asset stage:
    TextAssets inside bundles are translated via a new tolerant variant
    (UnityApkBundleLocalizer.tryLocalizeBundlesInApk, returns null and
    writes nothing when nothing is translatable), then loose assets are
    translated on top of the bundle-localized APK in one signature cycle.
    Games whose text lives ONLY inside bundles are signed from the bundle
    stage and reported; counts merge when both stages translate. Strict
    localizeBundlesInApk behavior unchanged. Tests: 262 green.
    Caveat: the signing fallback path needs AndroidKeyStore, so it is
    covered by the CI emulator test, not JVM tests.

## Next up (in order)

- DONE: artifact URL reported (Actions run 45280230697).
- DONE: APK-level integration of the UnityFS bundle localizer (38f80c3).
- UI wiring for the bundle stage (game picker -> preview -> apply):
  UnityMonoLocalizer.localize is called from MainActivity; the new bundle
  counts already flow into its report, but preview/dry-run of bundled
  TextAssets in the UI is still missing.
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
- UnityPy 1.25.4 is pip-installed in the workspace box; downloaded real
  fixtures live in /downloads/realfixtures/.

## Known honest limits (do not claim otherwise)

- AssetBundle write-back works for NONE/LZ4 containers in tests with
  synthetic fixtures; no real game has been localized end-to-end yet.
- IL2CPP, Unreal, Godot: unsupported.
- The Android UI does not yet expose the patcher/rewriter; they are
  library-level and test-verified only.
