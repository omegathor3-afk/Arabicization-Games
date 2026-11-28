# OMEGA DEVELOPMENT LAB — ARABIFY tool status

| Tool | Current use | Status / gate |
|---|---|---|
| GitHub | Source, commits, branch CI, artifacts | Active on `feat/unity-mono-streamingassets`; no merge to main |
| GitHub Actions | Kotlin tests, APK build/signature, real-game test, Bergamot native CI | Active; emulator and offline-model issues are being corrected |
| Dribbble | Visual pattern research | Search references documented; no design copied |
| Figma | Editable design system and prototype | Not connected; Compose tokens/spec are the current source of truth |
| MagicPath | Interactive prototypes | Not connected; no prototype claimed |
| Maestro | Black-box Android UI flows | Local CLI workflow being added; no Maestro run claimed yet |
| Firebase Test Lab | Cloud virtual-device testing | Workflow ready but gated; skipped until owner configures project and explicitly opts in |
| Google Developer Device Platform | Physical/virtual device automation | Preview; billing required; not launched |
| CodeQL / Gitleaks | Static analysis and secret scan | CI workflow being added |
| Dependabot | Dependency updates | Weekly Gradle and Actions checks being added |
| Sentry | Optional crash telemetry | Not integrated; privacy gate required |
| Supabase / Vercel / Replit | Optional cloud sync/dashboard/tools | Not integrated; not required for offline core |

## Cloud cost policy

- No paid Google Cloud device sessions are launched without explicit approval.
- Firebase Test Lab is gated by `FIREBASE_TEST_LAB_ENABLED=true` and configured OIDC secrets.
- Developer Device Platform requires a billing-enabled Google Cloud project. Its current Preview pricing is listed at [Google Cloud DDP pricing](https://cloud.google.com/products/developer-device-platform).
- Maestro is run with the local CLI in GitHub Actions; Maestro Cloud is not used.

## Product truth policy

A build passing does not prove a game was localized. A translated APK passing signature verification does not prove every screen renders correctly. Engine support, offline readiness, font replacement, and real-device compatibility are reported only when their corresponding tests and artifacts exist.
