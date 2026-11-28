# Big-game Arabization feasibility study (2026-11-24)

Question (Omega, 11:35pm): can the app Arabize BIG mobile games — ARK:
Ultimate Mobile Edition, Yes, Your Grace, and games like them — and what
would it take? Limits: no pirated APKs, no DRM bypass, no universal claim.

One-paragraph answer: yes for a real slice of big games — offline premium
ports whose text lives in files inside the APK (Yes, Your Grace is exactly
this profile) — and no for online live-service titles (ARK, Genshin, CoD,
PUBG), whose text and integrity are protected server-side and client-side.
The deciding factor is not engine or size; it is (1) where the text lives,
(2) whether the package is encrypted, (3) whether the game verifies its
signature / Play Integrity, (4) whether the text is on-device at all.

## How big Android games actually store text

| Game | Engine (source) | Text storage | Packaging | Protection |
|---|---|---|---|---|
| ARK: Ultimate Mobile Edition | Unreal Engine 4; port of ASE by Grove Street Games, Dec 2024 (ark.wiki.gg; Epic spotlight for the original port) | UE4 localization = .locres compiled string tables inside .pak / IoStore containers; format fully documented (reverseengineering.wiki, CUE4Parse Apache-2.0, UnrealLocres) | huge post-install downloads: OBB historically, Play Asset Delivery now | online game, account system; pak encryption possible; integrity walls |
| Yes, Your Grace (Android) | Unity — dev blog "Moving the game to Unity" (braveatnight.co.uk 2019); mobile port by Noodlecake | Unity IL2CPP: literals in global-metadata.dat + TextAssets in UnityFS bundles | single APK + in-APK assets (premium, ~1 GB class) | none visible: offline, single-player, premium |
| Genshin Impact | Unity (flytrap.dev SDK scan, analyzed 2026-06) | IL2CPP + custom packed assets | APK + multi-GB in-game download | Google Play Integrity SDK + GeeTest + HoYo anti-cheat (official notice) |
| Call of Duty Mobile | Unity (TiMi/Activision, widely reported) | IL2CPP + asset bundles | APK + downloads | online anti-cheat |
| PUBG Mobile | Unreal Engine 4 (PocketGamer.biz dev interview) | .locres in paks | APK + downloads | online anti-cheat |
| Slay the Spire (Android) | NOT libGDX on mobile: original is libGDX but Sickhead ported mobile via C# cross-compile (sickhead.com) | custom data files | APK | offline premium |

## The four walls (decide feasibility before any engine work)

1. **Text must be on-device.** Server-side text (online games) cannot be
   translated locally, ever.
2. **No encryption bypass.** UE4 paks may be AES-encrypted with the key in
   the binary; extracting that key is circumventing a technical protection
   measure and stays out of scope. If we open a package and it is encrypted,
   we stop.
3. **Signature / Play Integrity.** We re-sign modified APKs; that breaks
   Play Integrity, Google sign-in, and every online feature by design, and
   violates online games' ToS. Offline single-player games do not care.
4. **Where assets sit after install.** Our rewrite model edits the APK.
   OBB files live in shared storage (restricted since Android 11) and Play
   Asset Delivery packs live in app-private storage that no other app can
   touch without root. Text that only exists there is unreachable on a
   stock phone.

## Per-game verdicts

- **Yes, Your Grace — FEASIBLE TARGET.** Offline, premium, single-player,
  no anti-tamper. Text is IL2CPP literals + TextAssets. Our existing
  UnityFS text-asset route covers part today; full coverage needs the
  IL2CPP metadata path (P1/P2 in the engine-coverage plan). Best real-world
  proof target in his list. Requires the user to own and install it (paid
  game); we never fetch it.
- **ARK: Ultimate Mobile Edition — NOT FEASIBLE within limits.** The locres
  format is documented and open-source readers exist, so a UE4 text path is
  buildable in principle — but ARK is an online game (wall 1+3), likely
  pak-encrypted (wall 2), and its gigabytes of assets arrive via OBB/PAD
  (wall 4). Each wall alone is a stopper.
- **Genshin / CoD / PUBG — permanently out** (online + anti-cheat +
  Integrity), as already agreed.
- **The feasible "big game" category:** offline premium ports — RPGs,
  deckbuilders, story games, strategy ports (the Stardew/Dead Cells/Slay
  the Spire class). Their engines vary (Unity IL2CPP, custom C#, libGDX,
  MonoGame) but they pass all four walls.

## What the app must add to serve that category

- **Unity IL2CPP text path** (the 87% engine): (a) deepen the existing
  UnityFS text-asset route — works today, reaches menus/items/subtitles of
  many IL2CPP games; (b) global-metadata.dat string-table patcher
  (length-constrained replacement, P2 spike).
- **Unreal text path (bounded):** pak/IoStore reader-writer + .locres codec
  v0-3 (documented format, Apache-2.0 reference code exists). Worth it only
  for offline UE4 games; ARK itself stays out.
- **Arabic rendering inside foreign engines:** translating strings is half
  the job. libGDX we already handle (font injection + shaping). Unity needs
  a pre-shaped-text approach (our ArabicShaper) plus a font asset swap;
  Unreal shapes complex scripts via its text layout layer but needs Arabic
  glyph coverage injected into its font assets. Each engine path needs its
  own pixel-verified proof before any claim.
- **Packaging honesty:** APK-internal text only; OBB/PAD-resident text is
  unreachable on unrooted devices. Detection must classify where a game's
  text lives BEFORE promising anything.

## Sources

See the research ledger; key ones: Epic Games ARK mobile spotlight
(unrealengine.com, Oct 2018); ark.wiki.gg Ultimate Mobile Edition;
reverseengineering.wiki .locres format (from CUE4Parse, Apache-2.0);
flytrap.dev Genshin tech stack; braveatnight.co.uk 2019 wrap-up (Unity);
developer.android.com Play Asset Delivery; sickhead.com Slay the Spire;
AppGoblin engine share scan (jamesoclaire.com, Feb 2025).
