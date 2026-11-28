# ARABIFY — Security and privacy posture

## Implemented / being added to CI

- CodeQL scans Kotlin/Java and GitHub Actions workflows with the extended query suite.
- Gitleaks scans the repository history for accidentally committed credentials.
- Dependabot checks Gradle and GitHub Actions dependencies weekly.
- APK and model integrity use SHA-256 manifests and fail-closed verification.
- UnityFS decoding is read-only and bounded by explicit compressed, decoded, total-output, and LZMA working-set limits.

## Privacy rules

- Game APKs, asset bundles, extracted game strings, filenames, file paths, and user-created translations must remain on-device by default.
- No game file or extracted string may be uploaded to Figma, Sentry, Supabase, Vercel, Replit, or an AI service without explicit user consent.
- Firebase Test Lab receives only the project test APKs and the pinned public Minesweeper fixture; its workflow is gated behind an explicit repository variable and OIDC secrets.
- Sentry is not integrated. If later added, it must be opt-in, disabled by default, omit game text/file paths/identifiers, and queue no network dependency for offline operation.
- Supabase, Vercel, and Replit are not required by the core localization path and are not currently integrated.

## Important offline limitation

The current UI translator uses Google ML Kit and downloads its language model on demand. Bergamot JNI and Mozilla model packages are built and audited in CI, but the JNI boundary is explicitly not wired into the production UI until offline runtime and model compatibility tests pass. Therefore the product is **not yet fully offline-first**. The lock file in app assets is provenance metadata, not proof that model binaries are embedded in the APK.

## Repository settings still requiring owner confirmation

GitHub native Secret Scanning / Push Protection settings are not exposed through the current repository integration. The Gitleaks workflow provides an additional CI scan, but it does not prove that GitHub's native settings are enabled. Verify these under repository Settings → Code security and analysis.
