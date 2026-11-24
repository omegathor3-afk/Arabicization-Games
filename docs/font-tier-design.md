# Font injection tier - automatic per-game Arabic font selection

User requirement (Nov 24, 2026): the app must automatically find the most
suitable font for each game by itself - per game and per text role (UI,
dialogue, titles, pixel/stylized) - analyze the game's own fonts (coverage,
shaping, style, weight, size), then pick and apply the best-fitting
redistributable-licence Arabic font from a catalog, with a recorded reason
per choice. Report honestly what is auto-selected and emulator-verified vs
designed only.

## Why this tier exists

Device finding (Mindustry, eaa026e): injected bundle_ar text does not render
when the game's bundled font lacks Arabic glyphs. Same class: Pixel Wheels.
libGDX (and most game engines) also ship no Arabic shaper/bidi, so correct
rendering additionally needs pre-shaped text (Arabic Presentation Forms-B in
visual order) baked into the localized string tables.

## Design

### 1. Font catalog (license-aware, app-bundled)

Each entry: id, displayName, url, license (OFL-1.1 only for v1), style tags
(naskh, kufi, geometric-sans, handwriting, pixel), roles (ui, dialogue,
title), weights, monospace flag, coverage facts (Arabic block, Presentation
Forms-A/B ranges), size. OFL permits redistribution with the font's license
text; every applied font writes its OFL notice into the localized APK and
the selection report names the license.

Seed candidates (all OFL, verify coverage in a JVM test before listing):
- Amiri (naskh, classical; full presentation forms) - dialogue/title roles
- Noto Naskh Arabic (naskh, UI-safe) - ui/dialogue
- Noto Kufi Arabic (kufi, geometric) - titles/ui
- Cairo (geometric sans, multiple weights) - ui for modern sans games
- Tajawal (modern sans, light) - ui
- Almarai (modern sans) - ui
- Reem Kufi / Aref Ruqaa (display) - titles
- Rooyin (pixel-style Arabic, OFL 1.1, github.com/MohamadDarvishi/Rooyin) -
  pixel/retro games; coverage (Arabic + presentation forms) must pass the
  catalog JVM test before use. Alternatives to verify later: chahar, Unixel,
  Sorena, Noqte (licenses unconfirmed).

### 2. FontAnalyzer (game font introspection)

Parse the game's font assets on-device (pure Kotlin TTF/OTF/WOFF reader):
cmap coverage (Arabic? presentation forms? Latin?), OS/2 weight class,
head.macStyle (bold/italic), post.isFixedPitch, name table family/style,
bitmap strikes (EBDT/CBLC/CBDT = bitmap/pixel font), unitsPerEm. Output a
per-asset FontProfile. WOFF handled by decompression (brotli) or content
sniff; sfnt parsing operates on the decoded tables.

### 3. Selection (automatic, with reason)

For each text role the game exposes (v1: single default role per font asset;
role inference = font usage position, e.g. Mindustry font.woff = ui):
score catalog fonts by (a) full Arabic + presentation-forms coverage
(hard requirement), (b) style tag match to the game font profile
(pixel->pixel, geometric sans->Cairo/Tajawal, serif/naskh->Amiri/Naskh),
(c) weight distance, (d) monospace match. Pick max score; the report stores
the chosen font id, the score breakdown, and a human-readable reason.
No silent fallback: when nothing matches well, the reason says so.

### 4. Application (v1: replace, not merge)

Replace the game font asset only when it is a text font and a compatible
catalog candidate passes the hard coverage gate. Known icon-only families
(Fontello, Font Awesome, Material Icons, IcoMoon, etc.) are preserved: their
codepoints may be game UI symbols, so replacing them with a text font can
silently corrupt the interface. The report must say that Arabic rendering
remains unsupported for a preserved icon font until a safe merge/reference
strategy exists.

Pre-shape translated values only when a catalog font was actually injected.
If the game's own font already covers Arabic, keep the original strings so
the game's native shaping/bidi path is not double-applied. On-device sfnt
merge (game Latin + catalog Arabic) remains future work.
Mindustry note: FreeType sniffs font content, so a TTF replacing
assets/fonts/font.woff still loads (verify on emulator).

### 5. Text shaping (required companion)

ArabicShaper (Kotlin): contextual shaping to Presentation Forms-B plus a
bidi-lite pass (pure-Arabic segments reversed; Latin/digit runs kept in
order). Applied when writing localized string tables (bundle_ar, values-ar,
.po) for engines without a shaper (v1: libGDX slot). Docs and reports state
plainly: shaping is approximated, not HarfBuzz-quality; verified visually
via emulator screenshots.

### 6. Verification plan

- JVM: catalog coverage test (parse each bundled font, assert Arabic +
  presentation-form ranges), FontAnalyzer tests on real game fonts
  (Mindustry woff, Pixel Wheels ttf), shaper golden tests.
- Emulator (CI): Mindustry slot + injected font + shaped bundle, launch,
  screenshot published via temp.sh; matrix row moves FONT-BLOCKED ->
  EMULATOR only when the screenshot visually shows connected Arabic.
