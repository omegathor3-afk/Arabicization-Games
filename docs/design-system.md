# ARABIFY — Design System v1

## Status

This is the implementation specification for the current Android Compose UI. The first code pass is implemented in `ArabifyDesignSystem.kt` and consumed by `MainActivity.kt`. It centralizes semantic colors, typography, spacing, corner radii, and card outlines. It is not a Figma file or a finished multi-screen redesign.

Figma and MagicPath are not connected to this workspace yet. Until those design integrations are connected, this document and the Compose tokens are the source of truth; no editable Figma prototype is claimed.

## Visual direction: Dark Workshop

A focused developer tool, not a generic game launcher:
- Obsidian canvas with deep forest surfaces.
- Jade/sea-glass as the primary action and success color.
- Muted copper for emphasis and secondary actions.
- High-contrast warm white for important content.
- Technical details and package identifiers remain visually secondary.

The design references are broad pattern research, not assets to copy:
- [Dribbble — Arabic RTL mobile UI](https://dribbble.com/search/arabic-rtl-mobile-ui)
- [Dribbble — dark file manager](https://dribbble.com/search/file-manager-dark)
- [Dribbble — mobile developer tools](https://dribbble.com/search/mobile-developer-tools)
- [Figma Community — Android design systems](https://www.figma.com/community/search?query=android%20design%20system)

## Semantic color tokens

| Token | Hex | Use |
|---|---|---|
| canvas | #080D0C | App background |
| surface | #111A17 | Main content cards |
| surfaceRaised | #17231E | Hero / primary panel |
| surfaceAccent | #1B2823 | Secondary result panels |
| surfaceElevated | #202B26 | Material surface variant |
| outline | #2A3B33 | Card and control borders |
| primary | #9AD8BC | Main actions and positive emphasis |
| accent | #D6A66D | Secondary emphasis |
| tertiary | #78BFA7 | Supporting accent |
| textPrimary | #F0F1E8 | Main headings |
| textSecondary | #B8C6BE | Body copy |
| textMuted | #9BAEA5 | Supporting metadata |
| success | #A8D9C0 | Verified state |
| warning | #E2BB80 / #FFC46B | Caution and pending state |
| danger | #FF9B91 | Errors |

Never use color alone to communicate a state; pair it with text or an icon.

## Typography

The Compose theme defines a compact hierarchy:
- Display: 30sp, black weight.
- Main screen headline: 21sp, bold, 29sp line height.
- Section title: 18sp, bold.
- Body: 14sp / 22sp line height.
- Supporting copy: 12sp / 19sp line height.
- Technical labels: 10–12sp.

Arabic is the primary UI language. Keep code, package names, paths, hashes, and engine identifiers in LTR spans when the layout is refined further. Do not use a decorative Arabic font for code or dense technical output.

## Layout and components

Spacing tokens: 4, 8, 12, 16, 24, 32dp.
Corner tokens: 12dp controls, 20dp cards, 28dp hero.
Cards use a 1dp semantic outline to separate adjacent dark surfaces.

Core component patterns:
1. **Hero / identity** — ARABIFY brand, OMEGA DEVELOPMENT LAB eyebrow, one-sentence value proposition, privacy and safety status.
2. **Installed game picker** — searchable list, application icon, display name, package identifier, split count, one unambiguous selection action.
3. **Manual APK picker** — explicit fallback, separate from installed-app selection.
4. **Scan report** — engine, package/version, size, archive counts, font coverage, and unsupported features.
5. **Translation readiness** — model availability and download requirement must be explicit; never label a downloaded-on-demand model as embedded.
6. **Progress and recovery** — current stage, completed stages, cancel action, recoverable error, and preserved source file.
7. **Result/export** — output path, backup identity, signature/integrity status, and warnings.

## User journey

`Home → choose installed game or APK → inspect archive → choose language and font profile → preview changes → create backup → translate/patch → verify output → export report`

The current app implements a single scrollable screen with the home picker, ML Kit model status, manual APK picker, scan report, and font assessment. The full multi-step workflow above is the target, not a claim that every stage is already implemented.

## RTL and accessibility

- Arabic is the default reading direction; technical identifiers stay LTR.
- All actions need meaningful text labels or content descriptions.
- Minimum touch target: 48dp.
- Preserve system font scaling; do not clip Arabic diacritics.
- Progress uses text plus a bar; warning/error/success states use text plus color.
- Avoid mirrored icons for actions whose meaning is directional only when appropriate.
- Test Arabic and English locales, narrow phones, large font scale, and landscape.

## Next design iteration

1. Split the long scroll into Home, Scan, Localization Setup, Progress, and Report destinations.
2. Add a persistent progress header and explicit safe/cancel states.
3. Add a preview surface for translated strings and font pairings.
4. Run Maestro flows on API 35 and capture baseline screenshots before adding visual-regression thresholds.
5. When Figma is connected, import these semantic tokens and create an editable prototype; do not treat a static mockup as implementation evidence.
