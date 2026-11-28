# Engine coverage map — what Arabify can and cannot Arabize (2026-11-24)

Scope and limits: Arabify modifies only games the user already owns and has
installed, locally on-device. It never downloads or redistributes APKs, never
bypasses DRM/encryption/anti-tamper, and re-signing a modified APK breaks
Play Integrity, server-side checks and usually online play. Any game that
verifies its own signature or serves its text from a server is permanently
out of scope, whatever the engine.

Market grounding: AppGoblin's decompiled scan of ~24k Android apps (Feb 2025)
puts Unity in ~87% of Android games, Unreal under 1% on Android, Godot a
handful in the top charts. (jamesoclaire.com/2025/02/20/mobile-sdk-showdown...)
Since August 2019 Google Play requires 64-bit, and Unity's Mono backend has
no ARM64 support — so nearly every Unity game on the Play Store today ships
IL2CPP, not Mono.

## The map

| Engine / family | Share of Android games | Where the text lives | Arabify today | Path |
|---|---|---|---|---|
| Android-native / Java (libGDX, custom) | small indie slice | `resources.arsc`, loose asset files (`.properties`, `.xml`, `.json`, `.csv`) | SUPPORTED: AndroidResourceLocalizer + AndroidAssetLocalizer | Production hardening (coverage repair, sizes) |
| Gettext games (Unciv, many OSS) | small | `.po` / `.mo` files in assets | SUPPORTED: GettextPoLocalizer | Works; Unciv verified |
| Unity Mono | tiny on modern Play Store | managed DLL string tables, `assets/bin/data/managed` | SUPPORTED: UnityMonoLocalizer | Keep; mostly legacy games |
| Unity asset/text bundles (any backend) | part of the 87% | serialized TextAssets inside UnityFS bundles | SUPPORTED: UnityApkBundleLocalizer + UnityFs* rewriter | Deepen: this path reaches menu/UI text of many IL2CPP games too |
| **Unity IL2CPP** | **the large majority of Android games** | string literals in `global-metadata.dat`, code compiled native (`libil2cpp.so`) | **NOT SUPPORTED — biggest gap** | P1, see below |
| Unreal Engine | <1% | `.pak`/`.ucas` archives, `.locres` localization | not supported | P3, low value per effort |
| Godot | tiny | `.pck` pack; official PO/gettext workflow | not supported | P3 easy win: unpack `.pck`, reuse gettext path |
| Cocos2d-x / Creator | notable in Asia | JS/Lua scripts, JSON/plist | not supported | P4 |
| Native C++ custom engines | long tail | strings inside `.so` binaries | not supported | overlay-only realistically |
| Ren'Py | niche | `.rpy` scripts, official translation framework | not supported | P4, tractable |
| Online/live-service (Genshin, CoD, PUBG…) | many top-grossing | text server-side; anti-tamper + Play Integrity | never | permanently out of scope |

## The IL2CPP gap (the honest center of "any game")

Most Android games are Unity, and most Unity games ship IL2CPP. Text there is
length-prefixed string literals in `global-metadata.dat`; replacing a string
in place requires the Arabic to fit the same byte count (it almost never
does), and expanding the blob means rewriting every offset in the metadata.
Realistic routes, in order of honesty:

1. **Text-asset route (works today, partial):** many IL2CPP games keep menus,
   item descriptions and subtitles in TextAssets/ScriptableObjects inside
   UnityFS bundles — exactly what UnityApkBundleLocalizer already rewrites.
   This can Arabize a real share of IL2CPP games' visible text without
   touching `libil2cpp.so`. Priority: P1 — inventory what fraction of real
   games' text lives there.
2. **Metadata patching (hard, lossy):** same-length-or-shorter replacement in
   `global-metadata.dat`. Technically possible, fragile, and constrained.
   P2 research spike only.
3. **Runtime overlay (different product):** an accessibility overlay that
   reads the screen and floats Arabic on top, modifying nothing. Covers any
   game including protected ones, but is not "Arabizing the game" and needs
   its own UX honesty. Parked unless the user asks.

## Prioritized widening plan

- P0: finish the four supported paths to production quality (repair-on-reject
  coverage, font matching pixel-verified per game, size fitting).
- P1: measure and deepen the Unity text-asset route on real IL2CPP games —
  the highest-value coverage expansion available within our limits.
- P2: IL2CPP metadata patching research spike (bounded).
- P3: Godot `.pck` + gettext (easy), Unreal `.locres` (low value).
- P4: Cocos scripts, Ren'Py.
- Never: DRM/encryption bypass, server-side text, anti-cheat games, or
  claiming "any game" — the honest claim is "any game whose text lives in
  files we can rewrite without breaking its protection."

Sources:
- AppGoblin SDK scan 2025: https://jamesoclaire.com/2025/02/20/mobile-sdk-showdown-unveiling-2025s-most-popular-frameworks-powering-30k-apps/
- Google Play 64-bit requirement (Aug 2019) + Unity Mono lacking ARM64: why IL2CPP dominates Play Store Unity builds.
