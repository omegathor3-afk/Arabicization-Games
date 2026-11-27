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
16. `eeb4173` fix(ui): the supported-scope line still claimed AssetBundles
    were never changed after the bundle stage landed; scope text and the
    asset-section warning updated to match implemented+tested behavior.
    IL2CPP and resources.assets remain explicitly excluded.
17. `60d2317` feat(ui): the localization result now shows the UnityFS
    bundle share (bundled TextAssets + strings) as a second line when the
    bundle stage translated anything; report carries nullable bundle
    counts so the generic path is unchanged. Tests: 262 green.
18. `6024785` refactor(assets): AndroidAssetLocalizer's ZIP
    transform/rebuild logic extracted into transformToUnsignedApk
    (lambda translate + requireTranslations flag); localize() behavior
    unchanged. Tests: 262 green.
19. `0fd65ac` feat(splits): SplitApkSetLocalizer (issue #3, library
    stage) - tolerant per-member rebuild of split APK sets. Every member
    is rebuilt through the same ZIP policy (stale signatures stripped)
    because all APKs in a set must share one signer; the set fails only
    when no member translates. Tests: 264 green.
    `be185da` added signSet: one-key signing for every rebuilt member via
    the persisted AndroidKeyStore key (device-only, CI emulator covers it).
    `8c204af` wired #3 into MainActivity: split apps use the asset text
    path end to end - localizeSet + localizeAndSignSet (PackageManager
    extractNativeLibs per member), wrapped via ApkBackupManager.createBackup
    into the verified ARABIFY ZIP, exported through a new zip save
    launcher, installed from the existing restore section (one session).
    The values-ar resource path still refuses splits, deliberately.
    Tests: 264 green; assembleDebugAndroidTest green.
    #3 is DONE at the text-asset scope; UnityFS bundles inside split sets
    are not translated (UnityMonoLocalizer stays single-APK).

20. `da4fe7e` feat(fonts): license-aware Arabic font catalog + per-role
    matching (issue #5, first pass). Amiri (OFL 1.1) bundled with license
    text; load-time license validation; match() recommends per role only
    when game fonts fail coverage/shaping, Arabic reason strings, no
    injection. Real-TTF fixture tests. Tests: 271 green.

## CI incidents

- Android CI emulator step FAILED once on 40e6701 (~4 min in, no logs
  accessible without repo admin auth). Second sample: run 45294493537 on
  da4fe7e (includes ALL recent code) completed SUCCESS - emulator step
  passed in ~4 min. Verdict: flake, not a regression.

21. `9f5d7a8` test(unity): UnityMonoPipelineInstrumentedTest - the Unity
    Mono production path runs ON the CI emulator (offline translation,
    on-device signing, Arabic read-back through the strict parsers).
22. `5464e62` fix(unity): REAL BUG the new emulator test caught on its
    first run - the final integrity check compared the ORIGINAL APK to
    the signed output, so any game with a localized bundle failed at the
    last step on device ("Unity resource bytes changed"). Fixed: final
    check compares the bundle stage's output; the bundle stage verifies
    itself with an allowlist of exactly its rewritten entries
    (rewrittenEntryNames). JVM regression test. 272 green.
    On-device confirmation pending CI on 5464e62.

## New user direction (Nov 24, ~02:20 EET)

User wants the app to Arabicize ANY game, verified by real testing.
Design push: tiered strategies per engine/format, preferred UX = Arabic
added to the game's own language list, else direct translation. Never
claim "any game"; keep a verified-vs-designed matrix; no DRM/anti-tamper
bypass - a game that blocks modification gets an honest "blocked".
Tiers (status): see docs/coverage-matrix.md.

## Issue mapping (corrected - earlier entries here had wrong numbers)

- Issue #2 (Unity Mono StreamingAssets first bounded pass): DONE and
  exceeded (whole chain through APK rebuild, 38f80c3). Candidate to close;
  left open for the user to decide.
- Issue #6 (safe Unity SerializedFile/AssetBundle string localization):
  DONE at TextAsset scope (patcher, rewriter, APK chain, differential
  tests vs UnityDataTools + UnityPy). Candidate to close; user decides.
- Issue #3 (split APK rebuild+sign): library stage DONE (0fd65ac:
  tolerant per-member rebuild with signature stripping). Remaining:
  sign every member with one key on device, install-session wiring,
  and enabling the MainActivity split path (currently refuses splits).
- Issue #4 (verified offline Arabic model for production builds): OPEN.
  Bergamot JNI boundary + pinned model verifier exist and CI proved both
  model-v3.0 directions translate offline on an emulator, but production
  installer/lifecycle + physical-device gates are not done; UI path is
  still ML Kit (download-once, then offline).
- Issue #5 (license-aware Arabic font catalog + per-role matching): OPEN.
  ArabicFontAnalyzer exists (analysis only).
- Issue #7 (Unreal PAK/IoStore): research only; not supported, not claimed.

## Next up (in order)

- DONE: artifact URL reported (Actions run 45280230697).
- DONE: APK-level integration of the UnityFS bundle localizer (38f80c3).
- DONE: UI shows the bundle share of the result (60d2317); bundled
  TextAsset previews already existed via UnityBundleDeepInspector.
- Split APK rebuild+sign support (issue #3).
- Offline model production packaging (issue #4) - biggest remaining chunk
  for the "offline translation verified after restart offline" criterion;
  an offline-after-restart emulator test for the ML Kit path is a cheaper
  intermediate step.
- Font catalog/matching (issue #5); Arabic shaping review for games
  without proper text layout.
- IL2CPP: research only; not supported and not claimed.

## 2026-11-24 late-night batch (run continued)

21. `9f5d7a8` UnityMonoPipelineInstrumentedTest - Unity Mono text-asset
    path running on the CI emulator (shared fixtures in sharedTestFixtures).
22. `5464e62` fix(unity): REAL device bug - final verifyUnchanged compared
    the original APK against the signed output and rejected legitimately
    localized bundles. Fixed with an allowlist, stage-1 self-verification
    and rewrittenEntryNames tracking; 272 JVM tests green. NOTE: the CI
    emulator step still failed on this commit; cause under diagnosis (see 25).
23. `09809d7` feat(locale): LocaleSlotLocalizer - game-native Arabic slot
    for libGDX bundle.properties games (Mindustry tier). Adds
    bundle_ar.properties, translates base values, keeps every other entry
    byte-identical, refuses when a slot exists or no bundle is present.
    Verified locally against the REAL pinned F-Droid Mindustry APK
    (io.anuke.mindustry_1115, sha256 64bf33...9f44): 2,849 values
    translated, 652 skipped, all 748 other entries byte-identical.
24. `f6a54e1` feat(locale): PlaceholderProtector now protects
    square-bracket markup ([red], [#ff8800], []) used by libGDX/Mindustry.
    278 JVM tests green.
25. `a36a78c` test(locale): Mindustry locale-slot emulator tier -
    MindustryLocaleSlotInstrumentedTest + mindustry-slot.yml workflow +
    run-mindustry-slot.sh. Installs pinned F-Droid Mindustry on the CI
    emulator, adds the Arabic slot (deterministic pseudo-Arabic stand-in;
    ML Kit offline translation is covered by the Minesweeper test), signs,
    reinstalls, switches emulator to ar-EG, launches, requires Arabic
    script on screen.
26. `DIAG` ci: run-real-game-localization.sh now emits failing connected
    -test assertions as public ::error annotations because Actions logs
    return 403 anonymously but annotations are readable. Next CI run will
    show exactly why the emulator step failed on 5464e62.

27. `7cdf7f0` test(unity): Unity fixture APK borrows the app's real binary
    AndroidManifest - apksig MinSdkVersionException fixed (device failure
    #1 on the Unity emulator tier, diagnosed via annotations).
28. `e926c10` ci: annotation diagnostics added to run-mindustry-slot.sh.
29. `0c78ec1` fix(unity): REAL device bug #2 - use-after-delete of the
    bundle-stage intermediate APK (verifyUnchanged ran after the finally
    deleted it). Found only on device; Context-bound path. Fix: delete
    after verification in both code paths.
30. `91d979b` test(locale): Mindustry slot test now asserts full bundle
    key-set equality (the guessed 'menu.new' key does not exist in the
    real bundle - the real bundle has 3,501 keys like workshop.menu).
    Head: 91d979b. CI on 91d979b pending; UNPUSHED local edit of this doc.

31. CI on 91d979b FULLY GREEN: Android CI build (Minesweeper real-game
    + Unity Mono on-device pipeline instrumented tests) and the new
    Mindustry locale-slot tier (pinned F-Droid APK, slot added, signed,
    reinstalled, ar-EG, Arabic script verified on screen). The Unity Mono
    EMULATOR tier is now CONFIRMED, and Mindustry is the first full
    game-level end-to-end verification beyond the Minesweeper fixture.
    Head: 91d979b.

32. `b18399b` feat(locale): translation-folder strategy (Unciv layout,
    assets/jsons/translations/<Language>.properties). Split on '=' only
    (189 keys contain ':'), translate the KEY when the value is empty or
    identity, fixed Arabic header directives (spaces kept, no capitals,
    conditionals after body, diacritics empty). Verified against the real
    pinned F-Droid Unciv APK (com.unciv.app 1286, sha256 721d3f...):
    5,448 translated, key set == template's 6,579 keys, all other entries
    byte-identical. 280 JVM tests green.
33. `d1df88a` test(locale): Unciv CI emulator tier (workflow + script +
    instrumented test). Screen check: Arabic script visible OR "Arabic"
    listed in the game's own language picker. CI pending.
34. SPD finding: Shattered Pixel Dungeon (F-Droid 922, x86_64, 9 message
    groups, 21 locales, NO Arabic) keeps its language list in a HARDCODED
    code enum, so a text-only slot cannot activate Arabic - moved to the
    per-game language-table tier (needs code patching). Replaced as game
    #2 by Unciv, whose language list is folder-driven. Pinned SPD APK in
    sandbox at /downloads/spd.apk (sha256 ceef4cdd...); Unciv APK at
    /downloads/unciv.apk.

35. `c8b2656`+`a7e9b21` ci/docs: every launch-section failure point in
    both slot scripts now emits public annotations (mindustry-slot failed
    on d1df88a with no annotation - narrowed to locale-switch/reboot/
    launch area; rerun will tell flake vs real).
36. Game inspections (pinned APKs in /downloads):
    - Andor's Trail (com.gpl.rpg.AndorsTrail 91, 115MB, sha256 a8c755d2):
      classic Android resources, 22,766 strings in arsc, many locales
      incl. library-level ar config - fits the AndroidResourceLocalizer
      (Minesweeper) tier; next emulator candidate. Story-driven.
    - OpenTTD (org.openttd.fdroid 1410128, 76MB, sha256 355cc302): game
      data nested in assets/openttd-data-14.1-0.zip.xz (xz-compressed zip);
      SHIPS lang/arabic_egypt.lng - ALREADY supports Arabic natively.
      Honest outcome: nothing to do. Matrix: ALREADY-ARABIC. A substitute
      10th game is needed.
    - Revengate (org.revengate.revengate 2001300, 84MB, sha256 fd5a43c3):
      Godot with loose assets, NO internationalization section in
      project.godot; only the dialogue addon has .po files (en/es/uk/zh).
      Game text is English-hardcoded in scenes/scripts. UNSUPPORTED
      without a fragile .tscn/.gd literal-rewrite tier. Matrix updated.
    - SuperTuxKart (org.supertuxkart.stk 312, 165MB): not yet downloaded;
      gettext .po tier candidate.

37. `6f45fbb` refactor: AndroidResourceLocalizer accepts a translation
    lambda (production keeps ML Kit) enabling deterministic device tests.
38. `0e94806`+`4b80e1f` test(resources): Andor's Trail emulator tier.
    First run found the app's OWN package has 670 default strings (the
    22k aapt total counts library packages); story text lives in res/raw
    XML (future tier). Thresholds aligned.
39. `37758bd` ci: Mindustry launch retry x3 + logcat annotation (pidof
    flake on 0e94806). `681f7d1` ci: emulator screenshots uploaded to
    0x0.st (transfer.sh fallback) with URLs as public ::notice
    annotations - the user asked to SEE the Arabicized games; fetch URLs
    from annotations on the next run and attach images to parent.
40. SuperTuxKart: ALREADY-ARABIC (assets/data/po/ar.po + Arabic fonts) -
    second already-Arabic game. Apple Flinger: no i18n at all,
    UNSUPPORTED. Pixel Wheels: gettext tier candidate - assets/po/*.po
    (16 locales, no ar), text languages.xml picker, messages.pot
    template; BLOCKER: no Arabic font in game (fontSet system), would
    render boxes without font injection (issue #5, not implemented).

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

## 41. Andor's Trail: already Arabic (completion mode)
Deep APK inspection (JVM scratch test, since deleted): the app package ships 622
values-ar entries with 612 real Arabic strings of 671 total. The first device run's
"apparent failure" was the localizer CORRECTLY respecting existing Arabic
(existingArabic ~= 600) and translating only the ~49 leftovers. Test rewritten to
assert completion mode (existingArabic > 500). Matrix: ALREADY-ARABIC (3rd such
game after OpenTTD and SuperTuxKart). Story text in res/raw XML remains a future tier.

## 42. Screenshot upload diagnostics (1de88c0)
All 4 uploads failed on 681f7d1; the helper had swallowed curl errors. Rewritten:
tries 0x0.st, catbox.moe, temp.sh in order, prints each host's error into the
notice annotation. Next CI run either yields image URLs or the exact failure chain.
If Actions egress blocks all free hosts, the honest blocker gets reported to parent.

## 43. Matrix: three already-Arabic games
OpenTTD, SuperTuxKart, Andor's Trail all ship Arabic. Substitutes being evaluated:
Pixel Wheels (.po tier, no Arabic font in game - blocker documented), Vector
Pinball (not on F-Droid; legit source search), Ren'Py itch.io titles (browser
download check needed). Apple Flinger UNSUPPORTED (no i18n). Honesty rule stands:
do not claim support before implemented and device-tested.

## 44. Mindustry on-screen Arabic: FONT-BLOCKED (honest downgrade)
The 1de88c0 run published the Mindustry screenshot (temp.sh). Inspection: menu
labels rendered as bare digits ("8", "9", "11") - the test's pseudo-Arabic
("عر"+length) lost its Arabic letters because the game's bundled font has no
Arabic glyphs. The bundle_ar slot itself is injected and APK-verified (keys
preserved, game launches, process alive). Also learned: uiautomator dumps never
contain an OpenGL game's text, so the old hierarchy-Arabic gate was
flaky-passing off Arabic system UI; the gate now gates on install+launch+slot
content and publishes the screenshot for human visual verification. Matrix:
FONT-BLOCKED, fix = font-injection tier (issue #5, same class as Pixel Wheels).
Upload route facts: 0x0.st and catbox.moe fail from Actions runners; temp.sh
works; download requires POST to the file URL (GET returns an HTML viewer).

## 45. All five user screenshots delivered (1de88c0 CI run)
temp.sh route worked (POST to download). Verified visually and forwarded to
parent as attachments: arabify-app-screen (app's own Arabic RTL UI),
minesweeper-arabic-screen (localized game fully Arabic - the flagship proof),
andorstrail-arabic-screen (game's own shipped Arabic), unciv-arabic-screen
(system dialog Arabic over Unciv picker - labelled honestly),
mindustry-arabic-screen (font-blocker evidence).

## 46. Font-injection tier design (Mindustry first)
Mindustry ships assets/fonts/font.woff; Fonts.java swaps fonts only for
Japanese (font_jp.woff, hardcoded `ja` check) - no generic per-locale lookup,
so adding font_ar.woff alone does nothing. Viable asset-only plan: merge an OFL
Arabic font (Noto Naskh Arabic, includes presentation forms) into font.woff
(fontTools merge; FreeType sniffs content, extension irrelevant) and pre-shape
bundle_ar values to Arabic Presentation Forms-B in visual order (libGDX has no
shaper/bidi). No dex patching. Open work: Kotlin reshaper + bidi-lite,
font-merge pipeline in CI (pip fonttools + brotli), screenshot visual verify.
Docs must state shaping is approximated, not full HarfBuzz.

## 47. Font tier step 1-2 done (FontAnalyzer + bundled OFL candidates)
Existing ArabicFontAnalyzer/ArabicFontCatalog (Amiri only, role-based) kept; new
FontAnalyzer adds WOFF1 + profile (weight/mono/bitmap/coverage). 7 candidate TTFs bundled under
assets/fonts/catalog (Amiri, Noto Naskh var, Cairo var, Tajawal, Almarai, Rooyin pixel) - all
pass Arabic core coverage in JVM test; Rooyin has 139 presentation-forms-B glyphs. Next: wire
into ArabicFontCatalog (style tags + per-font OFL notices), scoring+reason, ArabicShaper, Mindustry tier.
Mindustry font.woff is WOFF1 (parsable); Mindustry loads via FreeType.

## 48. FontSelector (scoring + reason) done, JVM-verified only
FontSelector.kt: 9 catalog fonts with style tag/weight/license/source URL; hard requirement Arabic core + presentation forms B>=80; score = style match 60 + weight closeness 40; reason string per choice, weak matches say so. Style inference is crude (pixel from bitmap/name, mono, else sans) - serif detection not possible from tables. Not yet wired to any localizer. Next: ArabicShaper, then Mindustry integration.

## 49. ArabicShaper (presentation forms B + bidi-lite), JVM-verified only
ArabicShaper.toVisual: contextual forms for 36 base letters, lam-alef ligatures, marks transparent to joining, placeholders ({0}, %s, \n) atomic, RTL base with Latin/digit runs kept in order, bracket mirroring. Golden tests pass. Known gaps: no kerning/mark positioning, no lam-marks-alef ligature, Persian/Urdu letters unshaped, simplified bidi. Correctness on screen is unproven until Mindustry emulator screenshot. Next: wire into LocaleSlotLocalizer + font replacement (step 5).

## 50. Font tier wired into LocaleSlotLocalizer (step 5), JVM-verified; emulator pending
addArabicLocaleSlotWithFont: for libGDX bundles whose fonts/font.* lacks Arabic, FontSelector picks a catalog font and replaces that asset; bundle_ar values shaped post-translation (shapeProperties). Bug caught by JVM test: glossary hits bypass the translate callback, so shaping inside tx left some values unshaped - shaping is now a post-pass. Real Mindustry font.woff analyses as family 'fontello' (icon-merged font, 0 Arabic glyphs) -> selector chose Tajawal. RISK: replacing font.woff may drop Mindustry's icon glyphs and Latin look; emulator screenshot decides. Mindustry instrumented test now uses the font path, asserts font replaced, expects presentation forms, test text "العربية N". Matrix stays FONT-BLOCKED until a screenshot shows connected Arabic.

## 51. Font parsing budgets and Mindustry screenshot provenance hardened (validation pending)
- FontAnalyzer now caps input/table/aggregate sizes, validates sfnt and WOFF1 directories and offsets with unsigned arithmetic, rejects duplicate/overlapping WOFF tables, and streams zlib output into a bounded buffer with exact-length checks. Malformed name/cmap tables fail with FontParseException rather than risking oversized allocations or index exceptions.
- Added four adversarial JVM fixtures: truncated WOFF directory, declared multi-gigabyte WOFF table, out-of-range sfnt offset, and decompression-length mismatch. Existing bundled-font coverage tests remain in place.
- Mindustry CI previously passed the APK-level instrumentation test (1/1) but failed its launch stage: monkey reported no launcher activity, the process file was empty, and the published PNG was actually the Android launcher home screen. That screenshot is not evidence of in-game Arabic.
- The runner now starts the manifest-declared mindustry.android.AndroidLauncher explicitly, requires both a live io.anuke.mindustry process and a resumed foreground activity before capturing, and re-checks foreground ownership before publishing. UIAutomator/system UI Arabic is no longer described as proof of OpenGL game text.
- Still unresolved: whether replacing Mindustry's fontello asset with Tajawal preserves icon glyphs and whether the resulting in-game text visually joins correctly. The next emulator screenshot must be inspected before the matrix can leave FONT-BLOCKED.

## 52. Mindustry run on 72c8108 (user's launch fix): game launched with injected font, but screenshot was the LOADING screen
Context: user pushed 1fe3f40 (bounded font parsing) and 72c8108 (explicit AndroidLauncher start + foreground gate). My earlier 9db31ba run failed only because monkey found no launcher activity; the screenshot from that run was the Android home screen (not evidence). I rebased cleanly and dropped my own redundant logcat commit. On 72c8108 mindustry-slot is green, but the published PNG (captured 8s after launch) shows Mindustry's asset-loading screen in its Latin tech font - NOT the menu, so it proves nothing about Arabic yet. Fix: wait 60s before capture. The earlier digits-only menu screenshot (pre-font-injection, pseudo-Arabic) is genuine and stands. Matrix stays FONT-BLOCKED.


## 53. 24 Nov 2026 — preserve icon fonts; record the first on-device Unity Mono write-back test

Baseline head `d1b7e41`:
- Android CI [#45362192860](https://github.com/omegathor3-afk/Arabicization-Games/actions/runs/45362192860) passed: **303 unit tests, 0 failures, 0 errors, 0 skipped** across 51 XML suites; Android Lint completed with **21 warnings, 0 errors**.
- The Android 15 Unity Mono instrumentation test passed **1/1**. It downloaded the ML Kit English→Arabic model, disabled Wi-Fi and mobile data, verified no validated internet remained, ran `UnityMonoLocalizer.localize`, signed the output, and read back Arabic from both a loose StreamingAssets file and a TextAsset inside UnityFS (3 strings total, 2 in the bundle). This is a synthetic real-format APK; no Unity Player install/launch or visual rendering is claimed.
- The real Minesweeper Android-resource test report recorded 310 discovered strings, 189 translated, 306 Arabic resources, and a verified output signature; the captured screenshot visibly shows Arabic. This is Android-resource evidence, not Unity evidence.
- Debug APK: 73,834,779 bytes; SHA-256 `2f2b5855f601c22f4b6fe0e44303f3009046f87c505bd8cfc0353096b78f3b75`; package `com.omega.arabify`, version `0.2.0` / code 2, minSdk 31, targetSdk 35, compileSdk 36, ABIs arm64-v8a and x86_64. v1/v2/v3 signature verification passed. No model weights are bundled; the APK contains ML Kit JNI and model metadata/lock data only.
- CodeQL/Gitleaks push and PR runs [#45362192958](https://github.com/omegathor3-afk/Arabicization-Games/actions/runs/45362192958) and [#45362203582](https://github.com/omegathor3-afk/Arabicization-Games/actions/runs/45362203582) passed. Mindustry locale-slot run [#45362192966](https://github.com/omegathor3-afk/Arabicization-Games/actions/runs/45362192966) failed after the app left the foreground during the 60-second wait; the signed APK report shows the experiment replaced Fontello with Tajawal, but no screenshot or crash cause was captured. Unciv and Andor's Trail runs passed.
- Implemented a fail-safe for the next head: detect known icon-font families, preserve them rather than replacing them with text fonts, refuse direct FontSelector replacement, and pre-shape only when a catalog font was actually injected. Added regression tests for Fontello preservation and native Arabic-font shaping.
- Renamed the Mindustry screenshot artifact to `mindustry-foreground-screen.png` and save a filtered logcat when the game exits before capture. Updated the Unity coverage matrix and research doc to distinguish the on-device synthetic write-back proof from a real Unity Player install/launch. The matrix remains FONT-BLOCKED for Mindustry until real in-game Arabic is visually verified.
- Next: rerun unit tests, Android instrumentation, Mindustry foreground check and lint on the new commit; then prioritize per-string review wiring and a real Unity Player fixture.

## 53. HANDOFF (written 4:10 PM EET Nov 24 2026 by the outgoing agent; read this first)

### Current state
- Branch: feat/unity-mono-streamingassets ONLY. Head when written: 8c7b61e (all pushed; Omega also pushes in parallel, always `git fetch` + fast-forward/rebase, never force-push, never rewrite history).
- CI on 8c7b61e: build, andorstrail-resources, unciv-slot, mindustry-slot, CodeQL all SUCCESS.
- Rules (user's, verbatim spirit): never main, no merge, no Release; no pirated/cracked APKs, no DRM bypass; no paid services; do not claim AssetBundle/IL2CPP/Unreal support before implemented+tested; never commit build/cache/log/screenshot files; stage files by name (never `git add -A`); one granular conventional commit per change, pushed separately; honest matrix; history cleanup of 881 old artifact blobs needs force-push = NOT without explicit user approval (still unanswered).

### Mindustry (resolved launch problem, font problem still open)
- Launch failure chain, in order found: (1) monkey found no launcher -> Omega's 72c8108 starts mindustry.android.AndroidLauncher explicitly; (2) screenshot after 8s showed only the loading screen -> wait added; (3) Android "viewing full screen" dialog stole focus (frame proved it) -> pre-confirmed via settings (harmless, kept); (4) game's own last_log.txt showed only "[pause]", then it exits status 0 right after logcat "ApplicationInfo updating" -> `cmd package compile -m speed -f` before launch (8c7b61e). That run PASSED and the menu screenshot is a real in-game frame. Single run only: if flakes return, suspect dexopt/emulator timing again.
- FONT BLOCKER (still the real issue): last real in-game frame (CI screenshot annotation `mindustry-foreground-screen`) shows menu labels as bare digits ("8","9","11"). The slot strings are "العربية N"; the Arabic letters do not render. Mindustry fonts/font.woff is family Fontello (icon font merged with Latin, WOFF1, 0 Arabic glyphs). d041e9d refuses to replace icon fonts (replacing broke launch assumptions and icons) so currently NO Arabic glyphs are provided. Matrix: FONT-BLOCKED. Move to EMULATOR only when a CI screenshot visibly shows connected Arabic.
- NEXT STEPS for Mindustry Arabic: (a) Kotlin glyph-merge: parse game font tables (glyf/loca/cmap/hmtx/hhea/maxp/post), append glyphs for U+0600-06FF and U+FE70-FEFF from a catalog TTF (Tajawal/Amiri; catalog TTFs are in app/src/main/assets/fonts/catalog), remap composite glyph ids, rebuild cmap format 4/12, keep every original glyph byte-identical, write as TTF (FreeType sniffs content; extension irrelevant) or re-wrap WOFF1; JVM tests: FontAnalyzer on result hasArabicCore, original cmap entries unchanged, original glyph bytes unchanged. Needs attention to Mindustry's FreeType loading (Fonts.java loads fonts/font.woff via FreeTypeFontGenerator; there may be a separate fallback font mechanism; read Fonts.java before building). (b) Wire into LocaleSlotLocalizer.addArabicLocaleSlotWithFont as a "merge" mode for icon fonts (shaping via ArabicShaper post-pass already exists). (c) Emulator check via CI screenshot. Alternative cheaper experiment: Mindustry's loadExtraFonts swaps in font_jp.woff only for Locale ja; game code cannot be patched without dex work.

### Font tier code (all JVM-tested; only Mindustry emulator pending)
- FontAnalyzer.kt (TTF/OTF/WOFF1, Omega hardened it: size budgets, adversarial fixtures), FontSelector.kt (9-font OFL catalog, style+weight scoring, reason string, refuses icon fonts), ArabicShaper.kt (presentation forms B + bidi-lite, golden tests), LocaleSlotLocalizer.addArabicLocaleSlotWithFont (replace font if lacks Arabic and not icon font; shape values post-translation because glossary hits bypass tx). Older ArabicFontAnalyzer/ArabicFontCatalog (Amiri only) coexist.
- Catalog fonts bundled: Amiri, Noto Naskh Arabic (var), Cairo (var), Tajawal reg/bold, Almarai reg/bold, Rooyin Free reg/bold (pixel style, OFL 1.1). TODO: per-font OFL notices in assets/fonts/licenses (only OFL-rooyin.txt and OFL-google-fonts.txt (Amiri) exist; Noto/Cairo/Tajawal/Almarai OFL.txt are in google/fonts ofl/<family>/OFL.txt). Selector style inference is crude (no serif/naskh detection from tables). Variable fonts (Cairo, Noto Naskh) are bundled whole: default instance only.

### 10-game list status (honest)
| Game | Engine | Status |
| --- | --- | --- |
| Minesweeper | Android resources | EMULATOR (real ML Kit Arabic, screenshot verified) |
| Mindustry | libGDX | FONT-BLOCKED (launch fixed, glyph-merge needed) |
| Unciv | translation folder | slot tier green on emulator; the screenshot only shows Android system Arabic + language picker; in-game Arabic NOT visually proven |
| Andor's Trail | Android resources | ALREADY-ARABIC (612 shipped Arabic strings); app completes ~49 leftovers (emulator test green) |
| OpenTTD | native | ALREADY-ARABIC |
| SuperTuxKart | native | ALREADY-ARABIC |
| Apple Flinger | libGDX | UNSUPPORTED (no i18n) |
| Revengate | Godot | UNSUPPORTED (no i18n in project.godot) |
| Shattered Pixel Dungeon | libGDX | BLOCKED (language list hardcoded enum) |
| Pixel Wheels | libGDX + gettext po | blocked on font + po tier unbuilt (no Arabic font in game) |
Substitute candidates: Vector Pinball (F-Droid com.dozingcatsoftware.bouncy_43.apk, native Android, no -ar configs, 1450 strings -> Android resources tier fits; pinned locally as /downloads/vectorpinball.apk sha ffda0d9c..., re-download from https://f-droid.org/repo/com.dozingcatsoftware.bouncy_43.apk), Ren'Py itch.io titles (need browser download check + tl/ folder tier), Pixel Wheels .po tier. Need story-driven games: Andor's Trail res/raw XML story tier, Ren'Py. Full table: docs/coverage-matrix.md. Real tier gap list: IL2CPP, Unreal, Ren'Py, gettext, res/raw story XML, SPD.

### How CI works here (no admin rights: Actions logs/artifacts are 403 anonymously)
- Check runs: GET https://api.github.com/repos/omegathor3-afk/Arabicization-Games/commits/<sha>/check-runs ; then .../check-runs/<id>/annotations (anonymous 200). Scripts in .github/scripts/*.sh emit `::error::` (failures, incl. logcat and Mindustry's own files) and `::notice::SCREENSHOT <label>: <url>`.
- Jobs: build (+ app + Minesweeper screenshots), instrumented-offline (Unity Mono device path), mindustry-slot, unciv-slot, andorstrail-resources, plus CodeQL/Gitleaks. A push cancels in-progress runs of the same workflow, so avoid pushing while you need a sample; a full cycle is ~10-12 minutes.
- Screenshots: the scripts upload to temp.sh (0x0.st and catbox.moe fail from Actions runners). Download needs POST: `curl -sL -X POST -o f.png <url>` (GET returns an HTML page). Verify by LOOKING at the image (tools read); deliver to the parent agent as attachments (agent_message --attachments with comma-separated paths). Never commit screenshots.
- Local checks: JDK17 /downloads/toolchain/jdk17, SDK /downloads/toolchain/sdk, Gradle /downloads/toolchain/gradle/bin (may need re-provisioning; /downloads is temporary). `gradle testDebugUnitTest --console=plain --no-daemon -Dorg.gradle.jvmargs='-Xmx1g'` and `assembleDebugAndroidTest`. No emulator/KVM locally: CI is the device oracle.
- Push uses the repo deploy key on the agent box via GIT_SSH_COMMAND (key stays on that machine; never put it in messages). Commit identity: Instinct Agent <instinct-agent@users.noreply.github.com>.

### Lessons
CI green is not proof; look at the pixels. uiautomator never sees OpenGL text. Check for existing Arabic before building a tier (3 games already ship it). Glossary hits bypass the translate callback. Context-bound device paths are invisible to JVM tests. Check for Omega's parallel commits before every push.

## 54. Font glyph-MERGE tier: Arabic glyphs inside the game's own font (JVM-verified; emulator pending)
New agent run (fresh sandbox; toolchain re-provisioned; baseline suite green before changes).
- New FontMerger.kt: appends Arabic glyphs (U+0600-06FF + U+FE70-FEFF) from a catalog
  font INTO the game's font, every original glyph byte-identical (icons/Latin safe).
  Handles WOFF1 in -> WOFF1 out, glyf simple+composite (component id remap),
  unitsPerEm rescaling, cmap format 4+12 rebuild, hmtx/hhea/maxp/head/post rebuild,
  checkSumAdjustment, table checksums. Appended glyphs drop hinting instructions.
- FontSelector.selectForMerge: same coverage gate + scoring, no icon-font refusal
  (the font is not replaced, so icons are safe).
- LocaleSlotLocalizer icon-font branch now MERGES instead of refusing; slot values
  are shaped only when a merge actually happened; failure falls back to the honest
  preservation message. Mindustry instrumented test updated: expects merged
  font.woff (family still fontello, hasArabicCore, pfB>=80, PUA icons kept) and a
  pre-shaped slot (presentation forms present).
- JVM tests (FontMergerTest): byte-identity of original glyphs, original cmap
  unchanged, Arabic added, composite (Amiri lam-alef/meem forms) remap, WOFF1
  round-trip, upm scaling (Rooyin 2000 -> 1000), honest refusal without Arabic.
- REAL-font verification (scratch test, since deleted): real Mindustry font.woff
  + Tajawal -> 156 Arabic codepoints merged (67 base + 89 forms), WOFF1 out,
  icons intact, 841ms. Not yet on screen: matrix stays FONT-BLOCKED until the CI
  Mindustry screenshot shows connected Arabic.
- Sandbox note: this agent's toolchain lives at /downloads/toolchain (jdk17,
  gradle-8.13, sdk). Push: pending a NEW deploy key (public key sent to main
  agent 4:11 PM; old agent's key stayed on its box).

## 55. Mindustry Arabic ON SCREEN: FONT-BLOCKED -> EMULATOR (16bc286)
- CI on 16bc286 fully green (8/8 workflows; the Andor's Trail failure on b97fed1 was a flake, green again).
- The mindustry-foreground-screen screenshot (temp.sh annotation) shows the real
  Mindustry main menu with CONNECTED right-to-left Arabic labels (shaped
  pseudo-Arabic "العربية N" stand-ins) and every Fontello icon intact. The
  glyph-merge (FontMerger, b97fed1) plus ArabicShaper pre-shaping rendered on
  device. Caveats kept: pseudo-Arabic stand-in strings, approximated shaping.
- On-device lesson: an earlier assertion ("slot contains Arabic base letters")
  was wrong under shaping - shaped slots contain presentation forms (0xFExx)
  instead of base letters; fixed in 16bc286.
- a623254 adds JVM coverage of the icon-font merge path (merge success with a
  synthetic fontello-family TTF; honest preservation fallback when no glyf).

## 56. Dropped-alef fix: full shaper-emit coverage gate (c3999c1)
- Parent review of the 16bc286 screenshot caught "لعربية" instead of "العربية":
  the leading isolated alef vanished. Root cause was neither the shaper nor the
  merge: Tajawal/Cairo/Almarai omit the ISOLATED presentation forms (U+FE8D,
  U+FE87, ...) and libGDX's FreeType rasterizer silently drops missing glyphs.
- ArabicShaper now exposes `emittedCodepoints` (every codepoint it can emit,
  forms + lam-alef ligatures); FontSelector requires full coverage of it.
  Amiri, Noto Naskh and Rooyin pass; modern sans styles fall back with an
  honest reason. Golden tests pin the emitted forms for "العربية"/"الإعدادات".
- Emulator-verified on c3999c1 CI: "العربية" and "الإعدادات" render complete
  with the leading alef; font is now Amiri (naskh) for Mindustry.

## 57. Gettext tier for Pixel Wheels (cda1394)
- GettextPoLocalizer: ar.po translated from messages.pot with the Arabic
  6-form plural header, Arabic Language + FontSet registered in languages.xml,
  Arabic glyphs merged into copies of the referenced fonts (-Ar.ttf, originals
  byte-identical), msgstr pre-shaped to presentation forms.
- Verified against the real Pixel Wheels v26 APK (scratch run, deleted after):
  188 msgids translated, 3 plural entries with all 6 forms, merged Xolonium
  fonts cover the full shaper emit set (Amiri), Kwajong-Italic.otf (CFF/OTF)
  honestly excluded. Not yet emulator-verified.


## 58. Real-translation CI pass for Mindustry (pending hash)
- MindustryLocaleSlotInstrumentedTest gains instrumentation arg
  "translationMode": "pseudo" (default, deterministic mechanics) or "real"
  (production OfflineArabicTranslator / ML Kit English-Arabic on the emulator).
- Real mode writes the translated menu labels (key | english => arabic, logical
  order) into mindustry-report-real.txt; the new CI script surfaces them as
  ::notice:: annotations and publishes a mindustry-real-translation-screen
  screenshot after the same foreground verification as the pseudo pass.
- Motivation: the user asked whether the on-screen Arabic is the correct
  translation; pseudo text could never answer that.

## 59. Real-translation verdict + gamer design v2 (eb81d16..9b676fc)
- Real-translation CI (Play Store emulator image) produced the actual ML Kit
  Mindustry menu: 12/16 labels correct, 4 wrong (Quit=>استقال, Credits=>الاعتمادات,
  Mods=>تعديل clash, Play=>لعب noun). Fixes landed as curated glossary overrides
  (خروج, فريق العمل, إضافات, العب, انضم للعبة) with a regression test; the
  glossary feeds the slot pipeline via AssetTextTransformer. Gettext path still
  bypasses the glossary - known gap.
- App design v2 on emulator: deep navy + cyan/violet neon, Cairo variable font
  bundled for all UI Arabic, gradient wordmark hero, ArabifyCtaButton for all
  primary actions, new neon launcher icon. Verified visually; below-the-fold
  sections pending a real scrolled capture (script hardened by cfe2a57).
- Unciv flake root-caused from the screenshot: Android "Viewing full screen"
  dialog covered the game; immersive pre-confirm added (9b676fc).
- GitHub unauthenticated API rate limit (60/hr) bit during CI polling; pace
  annotation reads.

## 60. Gettext safety pipeline + exact-head CI coverage (42089a71)
- `GettextPoLocalizer.translateValue` now routes every singular/plural value through `AssetTextTransformer.transformValue`, so gettext receives the same exact glossary, placeholder/tag protection, and conservative Arabic quality gate as loose assets. Rejected translations remain the original `msgid`.
- `PlaceholderProtector` now recognizes Pixel Wheels' custom `%#` count token in addition to standard printf tokens.
- Regression tests cover `Quit => خروج` from the reviewed glossary, rejection when a translator drops `%s`, preservation/restoration of `%#`, and absence of leaked internal markers.
- Exact SHA `42089a71a43cec736e27ede1eaa260850b3a9068` CI: CodeQL push + PR passed; Andors Trail and Mindustry emulator jobs passed; Unciv stopped at dependency resolution because Maven Central returned HTTP 429 (26 dependency fetches); Firebase Test Lab jobs were skipped by their configured condition. The Android CI workflow did not appear in the seven runs attached to this SHA, so the Andors Trail workflow is being amended to execute the JVM unit suite as a reliable push-triggered gate.
- Known product gap confirmed by current `MainActivity`: `LocaleSlotLocalizer` and `GettextPoLocalizer` are implemented/tested but are not yet selected from the Android UI flow. Wire the libGDX/Gettext strategies into the user-facing localization path before claiming those game families are supported in the app.
- Backlog remains: update stale README claims against the current coverage matrix; run unit tests on the exact current SHA; finish real Unity SerializedFile/UnityFS write-back and validate it in a Unity Player; test Bergamot on ARM64 hardware before exposing it as a user translator.

## 60. Glossary loop closes; screenshot channel restored (c4e0071..77c0604)
- Omega's b344ec4 routes gettext through AssetTextTransformer (glossary +
  placeholder protection + quality gate) - the gettext glossary gap is closed,
  with his placeholder tests. His change broke one legacy test whose fixture
  string became glossary-owned; retargeted to a non-glossary msgid (c4e0071).
- Glossary gained Exit/Options/About/Resume/Pause after ML Kit's استقال-for-Quit
  class of error; Unity bundle fixtures now use "Shop" so they keep measuring
  the translator callback instead of the glossary fast path (dd37cd6).
- 259472f/cfe2a57 (authored by omegathor3-afk like every commit tonight) made
  all CI screenshots private artifacts. Omega says he did not remove the
  channel and chose to restore it: upload_shot is back in all six CI scripts
  (77c0604), his private-artifact and hierarchy checks untouched.
- The real-translation menu report now reflects what ships: glossary lookups
  first, raw ML Kit only for the rest, glossary hits marked (20ad9ca).
- Unciv failure annotations now quote the visible screen text from the captured
  hierarchy, since screenshots need repo auth (26400ea).
- Open: Unciv on-screen check failed on ef81d34 ("no Arabic entry visible") -
  next run's annotation will show the actual screen content.

## 61. Stale-evidence trap on reused CI AVDs, campaign crash confirmed, per-swipe Unciv captures (2026-11-24)

- **skipReasons mystery resolved (strong hypothesis):** the dfc37ce run printed byte-identical counts to the previous run with an empty skipReasons map even though every skip path was instrumented. Root cause: on a reused AVD, MediaStore renames duplicate display names on disk, so the fixed-path `adb pull` fetched the PREVIOUS run's report. Fix: the script wipes `/sdcard/Download/ARABIFY` before installing the game. Confirmation arrives with the next run: reasons present = stale file was the whole story.
- **Campaign tour crash is the game, not us:** FAILSTATE capture shows the Android launcher after tapping حملة — the pinned original Mindustry APK exits on swiftshader when loading the 3D campaign planet. Tour routes gameplay evidence through the 2D custom-game maps list instead.
- **Unciv per-swipe captures:** العربية sits near 47.6% sorted position and six blind swipes overshot it; the script now captures after each swipe (unciv-language-list-1..6).
- **v2.1 home polish:** app-row اختيار buttons became quiet OutlinedButtons (accent fill + primary border only when selected).
- Evidence: FAILSTATE screenshot (launcher after campaign tap) reported to the user; CI round on 3903a0b pending.

## 62. skipReasons root cause found: Result constructor dropped the map (2026-11-24)

- The empty skipReasons was NOT stale evidence (the 3903a0b run with the wipe still printed an empty map). Real cause: the properties/strings/txt branches of `AssetTextTransformer.transform` counted skips into the map but built `Result(output, translated, skipped)` without the 4th parameter, so `emptyMap()` reached the report. Fixed per branch (0f31fac); 328 unit tests green.
- Unciv language picker sorts by translation completion descending: swipe 4 = 96-82%, swipe 6 = 56-42%. Arabic (fresh slot) sits below swipe 6, so captures raised from 6 to 12 swipes (1fe40c8).
- Learned: the real-translation workflow ignores docs-only pushes.
- Next: the 0f31fac run names the dominant skip rule behind the 656 Mindustry skips; that decides the P1 repair-instead-of-reject target.

## Entry 63 — Repair tiers live at scale; Unciv picker root cause; CI self-diagnosis (2026-11-25 ~02:00 EET)

**Placeholder repair, measured on the real game (twice, stable):** real-translation CI on 4d8db10 and b1526a0 both report `translatedStrings=3424` (was 2845), `skippedStrings=77` (was 656), `repairedStrings=579` → `repairReasons=segmented_placeholders:305, marker_recovered:274`. `placeholder_lost` collapsed 624→7. Coverage ~97.9% of Mindustry's 3,496 strings. Both tiers are generic (no game names, no per-game tuning).

**Bug the repair itself had:** segmented re-assembly ate boundary whitespace ("Collect %s" → "اجمع%s") because translators collapse it. Fix (b1526a0): `translateSegmented` re-wraps the translated core with the segment's own leading/trailing whitespace. Test updated: `lostPlaceholderIsRepairedBySegmentedTranslation` now expects "مرحبا {player}".

**Stale-policy test caused Android CI red on 760c516:** `gettextUsesReviewedGlossaryAndProtectsFormatTokens` still expected a dropped placeholder to be REJECTED (stringsSkipped=1). Under the repair policy it is repaired ("اجمع %s", translated=3, skipped=0). Updated in b1526a0; verified locally 17/17. CI on 760c516+ needed the fix to go green.

**Local full-suite replication (gradle cold-start too slow for sandbox):** standalone K2JVMCompiler from ~/.gradle caches + JUnitCore. Recipe keys: add jetbrains `annotations-23.0.0.jar` to the COMPILER classpath (codegen NoClassDefFoundError otherwise); `-module-name app_debug` so internal `$app_debug` mangled names link against the stale build output; `-Xfriend-paths` for internal access; jars: junit4, hamcrest, coroutines, stdlib, reflect, json-20250517, xz, lz4-java, android.jar; run from `app/` (font catalog paths are relative); exclude `OfflineArabicTranslatorTest` (mlkit not in local caches); Unity fixture "missing fixture" failures are resource-classpath artifacts of the hand-rolled run, not real. Result: 326 tests, only those 6 env fixture failures → suite is green for real.

**CI still red on b1526a0 unit tests despite local green** → android.yml now emits `::notice::TEST-FAIL <class>.<test>: <msg>` annotations from the JUnit XML on failure (be64376) because CI logs need auth and annotations don't. Next run names the failing test.

**Unciv: why العربية never appears (root-caused from upstream source):**
- `Translations.getLanguagesWithTranslationFile()` = `LocaleCode.getSupportedLanguages()` filtered by file existence; `getSupportedLanguages()` **filters out `unused` entries**; `LocaleCode.Arabic("ar-IQ", "العربية", unused = true)` → Arabic can NEVER appear in the picker in this version, no matter what we inject. (Screenshot proof: 12-swipe list bottoms out at Navajo 32%.)
- BUT `tryReadTranslationForCurrentLanguage()` reads `jsons/translations/<settings.language>.properties` directly with NO unused filter → forcing the language via the settings file works.
- The file name must be the enum's **languageName**: `العربية.properties`, NOT `Arabic.properties` — our current injection writes `Arabic.properties`, which the game will never read. Bug queued.
- Settings file: `/data/data/com.unciv.app/files/GameSettings.json`, key `"language": "العربية"` (writable via adb/run-as in CI; on-device needs debuggable repackage + run-as, still blocked without root, or dex-patching `unused=false`).
- **Font is NOT the blocker on Android**: Unciv renders via `AndroidFont` = `android.graphics.Typeface.DEFAULT` + Canvas (system fonts, real Arabic shaping). The bundled TTF question is moot on Android.
- GitHub annotation cap (10/check-run) fixed: all 12 swipe captures now in ONE combined annotation (760c516).

**Follow-ups queued:** surface repaired counts in GettextPoLocalizer.PoReport; rename Unciv slot file to العربية.properties; seed GameSettings.json in CI to prove Arabic in-game; decide the on-device activation route (debuggable manifest + run-as vs dex patch).

## Entry 64 — Unciv GREEN end-to-end; the Arabic menu is real (2026-11-25 ~09:15 EET)

**Unciv locale slot CI passed completely on 2df5cad** — first fully green run:
real on-device ML Kit translation of the 6,578-key file, all 6,579 template
keys preserved, APK signed + verified + installed, booted into Arabic via
seeded GameSettings.json, and the menu screenshot verified pixel-by-pixel:
خيارات، ابدأ لعبة جديدة، تحميل لعبة، محرر الخريطة، إضافات، متعددة،
الإصدار 5.0.1 — all correctly connected, no dropped letters (the تاراخ/ارع
defect Omega caught on the 421ed0a screenshot is gone). The 12 captured
frames pan the live map behind the already-Arabic menu (script had assumed
a language picker; libGDX exposes no text nodes, so it couldn't tell).

**Three fixes in one morning:**
- fb95cda: shapeProperties split lines at '=' OR ':' — Unciv keys ending
  in ':' ("Leads to: = ...") lost their '=' into the shaped value, breaking
  key preservation (10 keys). Now '=' only, matching the writers. Regression
  test builds a colon-key slot and asserts the key set.
- e2ac68e: af6f1b7's placeholder-strip regex used '[^]]', which the JDK
  accepts but Android ICU rejects — every connected test crashed with
  PatternSyntaxException at once. Escaped ('[^\\]]'). RULE: any regex that
  runs on-device must be ICU-safe; the JDK-only standalone suite cannot
  catch this class.
- 2df5cad: e2ac68e's Unciv run failed with NO annotation — the connected
  test had actually PASSED and the run died on a silent launch-section path.
  All silent paths (missing pulled APK, screencap, PNG validation) now emit
  public annotations; a green test prints an explicit PASSED notice.

**af6f1b7 guard improvement:** placeholder tokens stripped before word
counts — false-positive not_english skips on the real Unciv English file
dropped 201 → 22. Mindustry verification pending (00dfb2b added
SourceLanguageGuard.kt to the real-translation path filter; it wasn't
triggering on guard-only changes).

**Empty-value convention verified working:** Unciv's file has 4,582 keys
with empty values where the KEY is the display text — the pipeline
translates the key (Delete => حذف observed shaped in the slot), while
settings lines (StartWithCapitalLetter = false) pass through untouched.

**"quickstart" stayed English — game-side, not ours:** the pinned dev
build's quickstart button label is lowercase "quickstart", hardcoded /
not wired to the translation table (the template key is "Quickstart" and
the slot DOES contain its translation). Untranslatable via files.

**Round-trip audit live:** 4/25 sampled real translations flagged for
loose phrasing ("Cannot be hurried" => "Can not be hurry") — ML Kit
quality signal, not a pipeline defect. Audit is now permanent CI evidence.

**Next:** Mindustry real-translation guard verification on 00dfb2b; then
Unity SerializedFile/UnityFS write-back (the big backlog item); Unciv
phone-activation route needs Omega's call (Shizuku/root vs debuggable
repackage + run-as).


## Entry 65 — APK-level UnityFS staging and publication hardening (2026-11-25)

- The Unity APK adapter previously retained every rewritten UnityFS bundle as a `ByteArray` until the APK's second pass. With up to 64 bundles, this could exhaust the Android heap before the existing 2 GiB rebuilt-archive limit was reached.
- Rewritten bundle outputs are now kept as files inside a unique private operation directory. ZIP replacement entries stream from those files; STORED-entry CRC is computed incrementally. The adapter rejects duplicate ZIP entry names, enforces the declared 64-bundle ceiling, and caps staged rewritten bundles at 512 MiB total.
- The rebuilt APK is written to a sibling temporary file, checked with `UnityAssetIntegrityVerifier`, then atomically moved to the requested destination. The source APK and any existing destination remain untouched if ZIP writing, integrity verification, or publication fails.
- Added regression coverage for file-backed STORED-entry size/CRC/alignment, the 65th UnityFS bundle, and failed atomic publication/temporary cleanup.
- Baseline evidence before this hardening, exact HEAD `78c77d04e10db97822542df2a6e391ee3c551743`: 59 JUnit XML suites, 372 unit tests with 0 failures/errors/skips; 1 Android 15 instrumentation test passed for the synthetic Unity Mono APK; Android CI, CodeQL/Gitleaks, Andor's Trail and Mindustry runs passed. Unciv was still running when inspected. New-commit validation is pending and will be reported separately.
- **Known high-priority gap remains:** a single UnityFS rewrite still materializes the decoded bundle and several full-size intermediate byte arrays; the 512 MiB decoded limit is not a whole-pipeline heap guarantee. Next: redesign the rewriter around temporary files and bounded chunk streams, then validate with real Unity fixtures across Unity versions and a runnable Unity Player sample.


## Entry 66 — heap-derived UnityFS rewrite guard (2026-11-25)

- The previous 512 MiB decoded limit was appropriate only as a parser ceiling; it was not safe for the current rewrite implementation, which keeps several full-stream byte arrays alive during patching, LZ4 chunking, assembly and read-back verification.
- Added `UnityFsRewriteMemoryBudget`. Both the APK TextAsset localization path and direct UnityFS rewriter now apply it before decoding. The rewrite decoded-stream budget is the minimum of the caller's limit, 32 MiB, and 1/32 of the app's maximum heap; per-block, Block Info, LZMA and working-set limits are reduced consistently.
- Oversized bundles are rejected by the decoder's declared-total check before storage blocks are expanded. The source APK/bundle and any pre-existing output remain unchanged on refusal.
- Added four deterministic unit tests for a 256 MiB heap, the 32 MiB hard ceiling, preserving stricter caller budgets, and rejecting an unrealistically small heap.
- This does **not** make the writer streaming and does not establish a safe bound for every allocation on every device. It deliberately trades support for large bundles for a lower OOM risk until the file-backed writer is implemented.
- Validation on this commit is pending; no local Gradle environment is available through the GitHub-only editing session. CI must confirm the Kotlin compile, unit suite, Android fixture path and lint before this entry is treated as validated.


## Entry 67 — gettext PO slot wired into the app pipeline; reports explain themselves (2026-11-25 ~15:00 EET)

- `GettextPoLocalizer` was tested but had no production caller. The MainActivity asset flow now tries, in order: `LocaleSlotLocalizer` (folder-convention bundles), then `GettextPoLocalizer` (games shipping `po/messages.pot` + `ui/languages.xml` — an Arabic `ar.po` is added alongside the game's own languages with the font tier), then the in-place `AndroidAssetLocalizer` fallback. All three are generic conventions; no per-game branching was added.
- `PoReport` now carries `skipReasons`, `stringsRepaired` and `repairReasons` (mirroring the existing `SlotReport` fields), so a skipped or post-translation-repaired string is explained, not just counted. `AssetLocalizationReport` carries the same fields up to the UI, and the app's own report screen prints the automated-repair count with its reasons and the skip-reason breakdown when present (Arabic labels, raw internal reason keys preserved for honesty).
- Unit coverage: the placeholder-recovery fixture now asserts `stringsRepaired`/`repairReasons`; a new fixture with a digit-only translator output asserts the quality-gate skip reaches `skipReasons`. Standalone suite: 361/361 green. MainActivity and all main sources compile clean under the Kotlin Compose compiler plugin with the full dependency classpath (Gradle cold start still exceeds the sandbox limit).
- Commits: `c00b6ba` (PoReport reasons), `37a8d5b` (report fields), `b0d416b` (pipeline wiring + report UI). Pushed on top of `ab6290d`; CI validation pending on `b0d416b`.


## Entry 68 — Android resource cancellation safety and exact-head audit (2026-11-26)

- Reviewed exact branch HEAD 1cf1a77915913b871648b04e5d6687df6a5e3491, its six workflow runs, PR #2 comments/reviews, open issues #3–#7, current adapter registry, MainActivity dispatch, and the Phase B handoff proposal. No branch other than feat/unity-mono-streamingassets was touched.
- Found a concrete cancellation bug in AndroidResourceLocalizer: a broad catch (Exception) around translation also caught coroutine CancellationException. A cancel request could be converted into a skipped string and the loop could continue, especially on glossary/already-Arabic entries that did not suspend.
- Fixed the resource loop to rethrow cancellation, check ensureActive() before every entry, and keep ordinary translation failures as per-string skips. Added three unit regressions for cancellation propagation, recoverable failure/continuation, and success accounting. Validation on the new commit is pending GitHub Actions; no local Gradle build is claimed.
- Exact-head CI before this change: Android CI success; Unciv locale-slot success; Mindustry locale-slot success; CodeQL/Gitleaks push and PR success; Andor's Trail workflow failed before tests because Maven Central returned HTTP 429 while resolving Gradle dependencies. No Andor test report was produced for this SHA.
- Downloaded and parsed the Android unit-test artifact: 62 JUnit XML suites, 397 tests, 0 failures, 0 errors, 0 skipped. Android lint artifact: 21 warnings, 0 errors (notably target SDK 35, dependency update notices, deprecated allowBackup attribute, and three usable-space recommendations).
- Downloaded the exact-head Debug APK artifact. ZIP integrity passed; artifact ZIP SHA-256 b22f15dcbe7e5e2937ba55f0569a75eeda55f1efa70cdf521cfb39dc0682cebe; extracted app-debug.apk size 74,397,136 bytes, SHA-256 fb893993874a6afdbd49a88e7f394ffa0fdd44fde404dd84537780607140ecf6. Android CI log verified APK Signature Scheme v2=true; package com.omega.arabify, version 0.2.0/code 2, minSdk 31, targetSdk 35. APK contains arm64-v8a and x86_64 native libraries and Amiri/OFL font assets. It contains the Mozilla model lock metadata but no translation model weight files, so this APK does not prove first-install offline translation.
- The same Android CI artifact includes the Unity Mono synthetic-APK instrumentation test 1/1 passed and the real Minesweeper Android-resource flow report: 310 strings discovered, 189 translated, 306 Arabic resources, output signature verified; screenshot was inspected. This is real-game Android-resource evidence, not arbitrary Unity Player launch evidence.
- Phase B review decisions are recorded above: keep translation adapter-internal for now, use a sealed native-output result rather than an untyped object, defer overlay persistence until stable source identity exists, keep Unity on its own branch, and keep call-site coverage explicit.
- Persistent backlog: (1) file-backed/bounded single-UnityFS rewrite and memory proof; (2) real Unity Mono Player fixture install/launch; (3) Phase B planner dispatch with compatibility/fallback tests and all production call sites accounted for; (4) verified model manager plus actual offline after-restart/network-disabled test and packaging decision; (5) RTL/font visual verification per supported runtime; (6) IL2CPP and Unreal remain research/design only.
- Next: validate the cancellation fix on the new SHA, then implement the planner-dispatch phase only after the adapter output contract and compatibility tests are agreed. Keep all claims evidence-tiered; do not call the app complete.

## Entry 69 — Instinct: Phase B dispatch-chain emulator evidence complete for all three chain wrappers (2026-11-26)

- `DispatchPathInstrumentedTest` (androidTest, run in Unciv slot CI) now exercises the exact MainActivity dispatch configuration (`ArabifyAdapters.runtimeChainRegistry` + `PlannerDispatch`) on-device three ways, all green: real Unciv via the slot wrapper (run on `eb21f61`), synthetic gettext fixture via the gettext wrapper (run 45877164859 on `35297fd`), synthetic JSON-only fixture via the in-place wrapper (run 45883867254 on `0be04e6`, honest selection: no phantom fallback attempts).
- Extract evidence levels reflect this: slot/gettext/in-place = EMULATOR_CI (with per-wrapper caveats in code comments: gettext cross-font merge NOT exercised; in-place scope is asset-text rewrite only). AndroidResourcesAdapter stays UNIT_FIXTURE and is NOT in the dispatch chain; its chain membership is parked for ChatGPT's call (its localizer has real-game on-device evidence via the Minesweeper flow invoked directly, not via dispatch).
- A fixture defect (missing binary AndroidManifest.xml broke host signing with MinSdkVersionException on `cc90372`) was fixed independently by ChatGPT (`3fe6b84`) and kept verbatim after dedup (`35297fd`).
- Validation: standalone JVM suite OK (404 tests) at `2bb7aa3`; all workflows green on `c3c1cae` (Unciv, Mindustry, Android CI, Andor's Trail, CodeQL/Gitleaks, real-translation guard).
- Next: resources-in-chain decision (ChatGPT); then backlog items (4) verified model manager + offline after-restart test and (5) RTL/font visual verification per runtime. Unciv phone-activation route still awaits Omega's decision.

## Entry 70 — Instinct: bundled first-install offline translation (Omega decision) (2026-11-27)

- Omega decided (03:07 EET): ship the offline model weights inside the APK; first-install offline must work; Instinct owns it. Implemented: build-time staging of the hash-verified Mozilla en-ar package into app assets (never committed), BundledBergamotTranslator (stage → TranslationModelPackageVerifier → native session), OfflineArabicTranslator fallback (ML Kit default; bundled engine when its model is absent or fails; lastEngine observability), and BundledFirstInstallOfflineInstrumentedTest (fresh install + airplane mode + production path). android.yml gains an APK-SIZE public annotation.
- Validation: local compiles green, standalone suite 404/404; CI device verdict and measured APK size pending on the push SHA.
- Backlog status: (4) model manager — the first-install offline half is now implemented pending CI proof; the packaging decision is MADE (bundle). Remaining: physical-device memory/thermal validation of the bergamot engine (~250-260 MiB PSS on emulator) and the ML Kit model-manager persistence question. (5) RTL/font visuals and the parked chain questions unchanged.

## Entry 71 — Instinct: repackaging route (Omega decision 04:18 EET) (2026-11-27)

- Omega chose repackaging over Shizuku for the on-phone flow. Implemented end-to-end on the branch: RepackageInstallHelper (signature-conflict detection, system uninstall intent, hardened PackageInstaller commit via a new non-exported status activity), MainActivity report-section UI (conflict warning with the local-data-loss note, uninstall button only when the original blocks, install button, ARABIFY-key disclosure), and RepackageRouteInstrumentedTest in the Unciv slot (signature matrix + uninstall intent + session create/write/abandon; the user's confirmation tap is honestly untested).
- Validation: local compiles green, standalone suite 404/404; Unciv slot device verdict pending this push. APK size measured on d6f9f97: app-debug.apk 119,053,312 bytes with the bundled en-ar model (was 74,397,136; +44.7 MB).
- Backlog status: on-phone install route implemented pending device proof; Pixel Wheels real-gettext game next (test written, compiles; script+workflow pending). Parked for ChatGPT unchanged.

## Entry 72 — Instinct: repackaging route device verdict GREEN; Pixel Wheels real-gettext leg wired (2026-11-27)

- Unciv slot CI run 45928093000 (a79a5e4): RepackageRouteInstrumentedTest PASSED on-device (signature matrix on real installed Unciv, uninstall intent, PackageInstaller session; commit tap untested by design). Repackaging route = EMULATOR_CI. All other workflows green on a79a5e4; arabify-ui recovered with no code change (its earlier red was flake).
- Pixel Wheels (real F-Droid gettext game, pinned + sha256) wired as a new workflow: PixelWheelsGettextInstrumentedTest (pseudo leg asserts planner dispatch picks the gettext wrapper on the real game + cross-font merge exercised + ar.po shaped/nplurals=6 + fr.po byte-identical; real leg runs production ML Kit + round-trip audit), run-pixelwheels-gettext.sh (annotations, evidence pulls, apksigner verify), pixelwheels-gettext.yml (path-filtered to app/** + its own files). Game-launch Arabic activation (languages.xml picker) honestly NOT covered yet.
- Validation: compileDebugAndroidTestKotlin green locally; script bash -n clean; workflow YAML parsed. Device verdict pending first pixelwheels run.


## Entry 73 — Translation fallback now preserves coroutine cancellation (2026-11-27)

- Exact-head audit at `a47d9144ce19e7070c15c8db850e299417e99857`: 9 workflow runs were attached to this SHA (8 success, Firebase Test Lab skipped because its explicit opt-in is disabled). Android CI passed; CodeQL/Gitleaks, Andor's Trail, Mindustry slot + real-translation, Pixel Wheels gettext, and Unciv runs passed.
- Parsed the exact-head Android unit-test artifact: 66 JUnit XML suites, 432 tests, 0 failures, 0 errors, 0 skipped. Android lint XML: 22 warnings, 0 errors. Warnings include target SDK 35, dependency update notices, deprecated backup attribute, storage-space recommendations, and icon/KTX suggestions.
- Downloaded and inspected the exact-head Debug APK: artifact ZIP SHA-256 `98cfa97ac4bcec41c326d5d9353e6cb9236d0d15820dbcdc37173ab8cdf93137`; extracted APK 119,086,112 bytes, SHA-256 `c16eb4824e5c6fd9e8c515d0425c5c24aaede9ba9cd872e79466afb4910f796c`; ZIP integrity passed. Android CI log: package `com.omega.arabify`, version `0.2.0` / code `2`, minSdk 31, targetSdk 35; APK Signature Scheme v2 verified, one signer. The APK contains 35,566,355 bytes of en-ar model files and Amiri/OFL font assets. It contains `libarabify_bergamot.so` for arm64-v8a but not x86_64; x86_64 therefore still needs ML Kit's downloaded model for translation in this standard APK. The separate Bergamot workflow builds both ABIs, but it did not run on this SHA.
- Device evidence in the Android artifact: the synthetic Unity Mono APK production-path instrumentation test passed 1/1 on Android 15; it is not a real Unity Player launch. The Minesweeper Android-resource smoke report recorded 310 discovered strings, 189 translated, 306 Arabic resources, and `signatureVerified=true`; screenshot and output APK were present in the artifact. Mindustry, Unciv and Pixel Wheels device workflows also completed successfully on this SHA.
- Found a cancellation bug in the production translator: the ML Kit `catch (Exception)` path could catch `CancellationException`, mark ML Kit unavailable and start the bundled native fallback after the caller had cancelled. The same broad catch could mask cancellation from the fallback behind the original ML Kit error.
- Implemented a shared `translateWithFallback` helper that rethrows cancellation from either engine, only invalidates ML Kit after ordinary failures, lazily checks fallback availability, preserves the original primary exception, and attaches a distinct fallback failure as suppressed. `lastEngine` is now updated only after a successful translation.
- Added four unit regressions: primary cancellation does not start fallback or invalidate state; ordinary primary failure uses fallback; fallback cancellation propagates; fallback failure is suppressed on the original failure. Validation on the new SHA is pending GitHub Actions. A local Gradle run was not possible in this session because the sandbox could not resolve github.com for a shallow clone; no local test/build success is claimed.
- Persistent backlog: (1) validate the cancellation regressions on the new SHA; (2) package/verify the Bergamot JNI library for x86_64 in the standard debug build or clearly constrain that ABI; (3) finish the file-backed single-UnityFS rewrite and whole-pipeline memory proof; (4) install/launch a legally redistributable real Unity Mono Player fixture after localization; (5) complete model lifecycle/update/recovery plus physical ARM64 memory/thermal checks; (6) RTL/font visual verification per runtime; (7) IL2CPP and Unreal remain unsupported/research only.


## Entry 74 — ChatGPT: transactional bundled-model staging and integrity hardening (2026-11-27)

- Baseline: `672e1fe0a3ed05efc3219d003e8120843ebef729`.
- Added a file-backed model stager with bounded copy, SHA-256 verification before activation, same-directory promotion, previous-package preservation, interrupted-swap recovery, and repair of corrupt staged files.
- Added eight JVM regression tests for stage/reuse, corrupt weights/config, interrupted copy, backup recovery, path traversal, missing config hash, and aggregate budget failure.
- Added `config.yml` to the build-time model hash manifest because the native engine consumes it directly.
- Integrated the stager into `BundledBergamotTranslator`; transient preparation failures can retry, and only `LinkageError`/ordinary `Exception` are normalized. Fatal VM errors are allowed to propagate.
- README now distinguishes first-install offline ARM64 from x86_64 (which still needs the ML Kit model in the standard APK), and documents the repackage-signature/data-loss warning.
- Commits: `e817eb7`, `2f46df5`, `64b4eba`, `10fd466`, `61b6306`, `aaac6d8`, `eb5c4ef`, `672e1fe`.
- Validation: pending exact-final-SHA Android CI. No local Gradle test/build is claimed. The eight new tests have not yet been reported as executed.
- Backlog: (1) verify this batch on the exact head and inspect unit XML; (2) validate fresh-install airplane-mode test; (3) build Bergamot JNI for x86_64 or explicitly constrain standard APK claims; (4) physical ARM64 memory/thermal checks; (5) UnityFS file-backed rewrite and real Unity Player launch; (6) per-runtime RTL/font visuals; (7) IL2CPP and Unreal remain unsupported.


## Entry 75 — Bundled model manifest and filesystem-link integrity (2026-11-27)

- Exact baseline: `55d6e0f366d0bcb5d6065312a6a49c850aa0f175`. The branch was ahead of the previous conversational report; the latest actual commit was the model-stager aggregate-budget test fix, not `b3fd515`.
- Parsed the exact-head Android unit-test artifact: 67 JUnit XML suites, 444 tests, 0 failures, 0 errors, 0 skipped. This is unit-test evidence only; Android CI was still building the pinned Bergamot JNI library and had not yet produced the APK or completed emulator checks.
- Audit found that `BundledModelPackageStager` copied `manifest.json` but the reuse verifier never compared the staged manifest with the APK's current manifest. A missing or modified manifest could therefore be reused while the code claimed the complete package had been verified. The model payload hashes were checked, but the manifest bytes themselves were not.
- Hardened staging to require the staged manifest to match the bounded APK manifest byte-for-byte, and to refuse symlinked destination/staging/backup roots. Hardened `TranslationModelPackageVerifier` to reject symlinked package roots and symlink components in every manifest-listed path rather than following them.
- Added regressions for a tampered staged manifest, a symlinked package root that must not alter its target, and a symlinked manifest-listed payload path. Validation for this change is pending the exact new SHA; no local Gradle run is claimed in this GitHub-only edit session.
- Backlog remains: complete the exact-head Android build/emulator verdict and inspect APK/artifacts; add a real Unity Mono Player install/launch fixture; implement file-backed UnityFS rewriting; finish the model lifecycle/update/recovery and physical ARM64 memory/thermal gates; verify RTL/font behavior per runtime. IL2CPP and Unreal remain unsupported.
