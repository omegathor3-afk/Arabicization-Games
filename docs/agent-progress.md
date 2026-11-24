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
