# ARABIFY coverage matrix - what "Arabicizes any game" honestly means

Every row names its verification level. Levels, strongest first:

- **EMULATOR**: a real or real-format game APK was installed on the CI
  emulator, localized by the app, reinstalled, launched, and Arabic was
  verified on screen or in the built artifact.
- **FIXTURE**: verified against real engine files (Unity's own
  UnityDataTools fixtures, UnityPy oracle) in JVM tests, not on a device.
- **DESIGNED**: researched approach, not implemented.
- **UNSUPPORTED**: not implemented and not claimed.
- **BLOCKED**: the game resists modification (anti-tamper, signature
  locks, DRM). We report the block; we never bypass it.

| Engine / format | Strategy | Status | Evidence |
| --- | --- | --- | --- |
| Android resources (values/strings.xml) | Inject values-ar + sign | EMULATOR | RealGameLocalizationInstrumentedTest on real Minesweeper APK: localize, sign, install, reboot to ar-EG, launch, Arabic verified. CI run 45294493537 green. |
| Loose text assets (JSON/CSV/TXT/Properties/Strings in assets/, res/raw/, localization/, strings/) | Translate in place, rebuild APK, sign | FIXTURE + EMULATOR (resource path); asset path JVM-verified | AndroidAssetLocalizer tests; used by UnityMonoLocalizer stage 2 |
| Unity Mono - loose StreamingAssets text | Same asset path | FIXTURE | JVM + CI unit tests |
| Unity Mono - TextAssets inside UnityFS bundles | Decode bundle, patch SerializedFile, rewrite container, rebuild APK, sign | FIXTURE (UnityDataTools real fixtures + UnityPy 1.25.4 oracle, incl. real 2021.3 LZMA bundle round-trip) | UnityApkBundleLocalizer tests; 38f80c3 wiring |
| Unity Mono - split APK sets (text assets) | Rebuild every member, one-key sign, ZIP export, session install | FIXTURE (JVM); device signing CI-emulator-covered only via existing paths | SplitApkSetLocalizerTest; 8c204af |
| Unity IL2CPP - global-metadata string literals | Patch string literals in global-metadata.dat | DESIGNED (research next) | not implemented |
| Unity IL2CPP - managed strings in blueprints | - | UNSUPPORTED | - |
| Unreal Engine - .locres / pak IoStore | Parse + patch locres string tables | DESIGNED (issue #7) | not implemented |
| Godot - .pck / CSV translations | CSV translation import is text; pck repack | DESIGNED | loose CSVs already covered by asset path |
| Cocos2d - JSON/plist strings | Mostly JSON text assets | PARTIAL via asset path | covered when stored as supported text |
| libGDX - i18n properties bundles | Java .properties text | IMPLEMENTED (LocaleSlotLocalizer) | 09809d7; local real-APK run on Mindustry: 2,849 values translated, all other entries byte-identical |
| Unciv (libGDX/Kotlin, F-Droid, MPL-2.0) | Translation-folder slot (Arabic.properties); language list is folder-driven | EMULATOR pending | b18399b verified on real APK locally (5,448 strings, key-set equality, others byte-identical); d1df88a adds CI emulator tier |
| Shattered Pixel Dungeon (libGDX, F-Droid, GPLv3) | messages/*.properties translatable, BUT language list is a hardcoded code enum | BLOCKED (text-only) | needs the per-game language-table tier (code patching) to activate a new language |
| OpenTTD (native C++, F-Droid, GPLv2) | none needed | ALREADY-ARABIC | ships lang/arabic_egypt.lng inside nested data zip; game supports Arabic natively |
| Revengate (Godot, F-Droid, GPL) | Godot i18n absent from project.godot; text hardcoded in scenes | UNSUPPORTED | only the dialogue addon has .po; main text needs .tscn/.gd literal rewriting - fragile tier not built |
| Andor's Trail (native Android, F-Droid, GPL) | Android resources (671 own strings) | ALREADY-ARABIC | ships values-ar with 612 real Arabic strings; device run showed the app correctly respects them (existingArabic) and translated only the ~49 leftovers; story text in res/raw XML - future tier |
| Mindustry (libGDX/Java, F-Droid, GPLv3) | libGDX bundle slot (bundle_ar.properties) | FONT-BLOCKED | slot injected and APK-verified on emulator (install, launch, key preservation); on-screen Arabic does not render - game font has no Arabic glyphs (labels showed only the digits of the pseudo strings); fix = font-injection tier (issue #5) |
| Game-native language tables (add "Arabic" slot) | Per-game format research | DESIGNED | - |
| Universal fallback: screen OCR + translation overlay | On-device OCR + overlay, engine-agnostic | DESIGNED | large separate feature |
| Games with anti-tamper/DRM/integrity locks | None - reported as blocked | BLOCKED (by policy) | - |
| Arabic fonts when game fonts lack Arabic | Bundled OFL catalog (Amiri), per-role recommendation | FIXTURE (real TTF parses suitable) | da4fe7e; injection NOT implemented |

## What is NOT claimed

- "Any game" is the goal, not a claim. IL2CPP and Unreal together cover
  most large commercial titles; both are pre-implementation.
- Font injection into engine font atlases is not implemented anywhere.
- RTL layout/shaping inside engine text renderers is engine-side; the
  app cannot fix engines that shape text without OpenType Arabic support.
