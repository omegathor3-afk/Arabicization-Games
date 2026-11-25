# ARABIFY coverage matrix - what "Arabicizes any game" honestly means

Every row names its verification level. Levels, strongest first:

- **EMULATOR**: a real or real-format game APK was installed on the CI
  emulator, localized by the app, reinstalled, launched, and Arabic was
  verified on screen or in the built artifact.
- **FIXTURE**: verified against real engine files (Unity's own
  UnityDataTools fixtures, UnityPy oracle) in JVM tests, not on a device.
- **DEVICE-FIXTURE**: Android emulator executes the production localization
  path against a synthetic but real-format engine APK, signs the output and
  reads translated data back after network isolation. This is not a real
  game-player install/launch or visual rendering proof.
- **DESIGNED**: researched approach, not implemented.
- **UNSUPPORTED**: not implemented and not claimed.
- **BLOCKED**: the game resists modification (anti-tamper, signature
  locks, DRM). We report the block; we never bypass it.

| Engine / format | Strategy | Status | Evidence |
| --- | --- | --- | --- |
| Android resources (values/strings.xml) | Inject values-ar + sign | EMULATOR | RealGameLocalizationInstrumentedTest on real Minesweeper APK: localize, sign, install, reboot to ar-EG, launch, Arabic verified. CI run 45294493537 green. |
| Loose text assets (JSON/CSV/TXT/Properties/Strings in assets/, res/raw/, localization/, strings/) | Translate in place, rebuild APK, sign | FIXTURE + EMULATOR (resource path); asset path JVM-verified | AndroidAssetLocalizer tests; used by UnityMonoLocalizer stage 2 |
| Unity Mono - loose StreamingAssets text | Same asset path | FIXTURE + DEVICE-FIXTURE | Android 15 instrumentation runs the production UnityMonoLocalizer path after model download and with validated internet disabled; signed APK read-back contains Arabic. Synthetic APK; no Unity Player launch. Run 45362192860. |
| Unity Mono - TextAssets inside UnityFS bundles | Decode bundle, patch SerializedFile, rewrite container, rebuild APK, sign | FIXTURE + DEVICE-FIXTURE | Android 15 instrumentation translated a TextAsset inside a synthetic UnityFS/SerializedFile APK after network isolation, signed it and read Arabic back through the strict parsers (2 bundled strings). Not a real Unity Player install/launch. Run 45362192860. |
| Unity Mono - split APK sets (text assets) | Rebuild every member, one-key sign, ZIP export, session install | FIXTURE (JVM); device signing CI-emulator-covered only via existing paths | SplitApkSetLocalizerTest; 8c204af |
| Unity IL2CPP - global-metadata string literals | Patch string literals in global-metadata.dat | DESIGNED (research next) | not implemented |
| Unity IL2CPP - managed strings in blueprints | - | UNSUPPORTED | - |
| Unreal Engine - .locres / pak IoStore | Parse + patch locres string tables | DESIGNED (issue #7) | not implemented |
| Godot - .pck / CSV translations | CSV translation import is text; pck repack | DESIGNED | loose CSVs already covered by asset path |
| Cocos2d - JSON/plist strings | Mostly JSON text assets | PARTIAL via asset path | covered when stored as supported text |
| libGDX - i18n properties bundles | Java .properties text | IMPLEMENTED (LocaleSlotLocalizer) | 09809d7; local real-APK run on Mindustry: 2,849 values translated, all other entries byte-identical |
| Unciv (libGDX/Kotlin, F-Droid, MPL-2.0) | Translation-folder slot (العربية.properties) + PFB visual-order shaping (libGDX renders LTR with no bidi) | EMULATOR | CI green end-to-end on 2df5cad: real on-device ML Kit translation of the 6,578-key file, all 6,579 template keys preserved, APK signed/verified/installed, booted into Arabic via seeded GameSettings.json; menu screenshot pixel-verified: خيارات etc. fully connected. Empty-value keys (4,582, key = display text) are translated from the key. Caveats: Unciv hides Arabic from its own picker upstream (activation = settings file; unrooted-phone route undecided), one dev-build button label is hardcoded English game-side. Round-trip audit (AR=>EN) flags loose ML Kit phrasing as permanent CI evidence. |
| Shattered Pixel Dungeon (libGDX, F-Droid, GPLv3) | messages/*.properties translatable, BUT language list is a hardcoded code enum | BLOCKED (text-only) | needs the per-game language-table tier (code patching) to activate a new language |
| OpenTTD (native C++, F-Droid, GPLv2) | none needed | ALREADY-ARABIC | ships lang/arabic_egypt.lng inside nested data zip; game supports Arabic natively |
| Revengate (Godot, F-Droid, GPL) | Godot i18n absent from project.godot; text hardcoded in scenes | UNSUPPORTED | only the dialogue addon has .po; main text needs .tscn/.gd literal rewriting - fragile tier not built |
| Andor's Trail (native Android, F-Droid, GPL) | Android resources (671 own strings) | ALREADY-ARABIC | ships values-ar with 612 real Arabic strings; device run showed the app correctly respects them (existingArabic) and translated only the ~49 leftovers; story text in res/raw XML - future tier |
| Mindustry (libGDX/Java, F-Droid, GPLv3) | libGDX bundle slot (bundle_ar.properties) + font glyph-merge | EMULATOR (real translations) | Real ML Kit path runs in CI on the full 3,496-key bundle: 3,399 translated / 102 skipped / 573 repaired (run on 00dfb2b), glossary-reviewed menu labels (Play => العب), SourceLanguageGuard false-positives at 26. Menu/settings screenshots show real connected Arabic; Arabic glyphs (156 Tajawal OFL codepoints) merged into a copy of the game's font, original glyphs byte-identical. The in-game tour remains blocked by a swiftshader environment flake (the pinned game exits to the launcher on the 3D campaign screen, no crash log), so in-game rendering is not yet visually verified. Shaping is approximated presentation forms, not HarfBuzz. |
| Game-native language tables (add "Arabic" slot) | Per-game format research | DESIGNED | - |
| Universal fallback: screen OCR + translation overlay | On-device OCR + overlay, engine-agnostic | DESIGNED | large separate feature |
| Games with anti-tamper/DRM/integrity locks | None - reported as blocked | BLOCKED (by policy) | - |
| Arabic fonts when game fonts lack Arabic | Bundled OFL catalog; bounded sfnt/WOFF1 analysis; slot-tier glyph merge | FIXTURE + EMULATOR (libGDX tier only) | Mindustry: 156 Arabic glyphs (Tajawal, OFL) merged into a copy of the game's font, every original glyph byte-identical, icons intact - verified in CI screenshots. Games that build fonts from Android system fonts at runtime (Unciv) need no injection and are shaped instead. Icon-font replacement is refused. No font-atlas injection for Unity or other engines yet. |

## What is NOT claimed

- "Any game" is the goal, not a claim. IL2CPP and Unreal together cover
  most large commercial titles; both are pre-implementation.
- Font injection into Unity/engine font atlases is not implemented; the
  libGDX glyph merge is the only EMULATOR-verified font path.
- RTL layout inside engine text renderers is engine-side. For libGDX's
  LTR renderer the slot ships visual-order presentation forms (verified
  on Mindustry and Unciv); engines that shape text without OpenType
  Arabic support remain unfixable from outside.
- Mindustry's in-game (post-menu) rendering is not yet visually verified:
  the pinned game exits on swiftshader during the 3D campaign tour.
  Unciv's picker never lists Arabic (upstream `unused` flag); CI proves
  activation via the settings file, the unrooted-phone route is undecided.
