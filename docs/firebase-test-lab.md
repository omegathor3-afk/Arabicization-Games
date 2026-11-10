# Firebase Test Lab: real-game localization test

This workflow tests the ARABIFY APK and runs an instrumentation test against a real, separately installed Android game package. The test game is Minesweeper 1.6 from F-Droid (`com.joeld.minesweeper`), distributed under Apache-2.0. The test copies its installed APK, downloads the English-to-Arabic ML Kit model, adds Arabic-qualified Android string resources, rebuilds the APK, checks that Arabic resources exist, and verifies the generated APK signature.

## Workflow

Run **Actions → Firebase Test Lab - real game localization → Run workflow** on the branch `feat/unity-mono-streamingassets`.

The workflow:
1. Builds the app and its Android instrumentation APK.
2. Downloads the pinned F-Droid Minesweeper 1.6 APK and records its SHA-256.
3. Authenticates to Google Cloud with GitHub OIDC (no service-account JSON key).
4. Runs the real-game localization instrumentation on Firebase Test Lab in Arabic and English device locales.
5. Verifies that translated Arabic resources were written into the output package and that the output APK is signed.

## One-time Google Cloud setup

A Firebase project and Google Cloud project must already exist and be linked. Enable the Android Device Streaming / Firebase Test Lab API used by your project; the workflow checks for the Pixel 5 API 31 target before running.

Create a dedicated Google Cloud service account for this repository. Grant it:
- `roles/cloudtestservice.testRunner`
- `roles/serviceusage.serviceUsageConsumer`

Configure Workload Identity Federation for GitHub Actions:
1. Create a Workload Identity Pool and an OIDC provider for `https://token.actions.githubusercontent.com`.
2. Restrict the provider attribute condition to this repository: `attribute.repository == "omegathor3-afk/Arabicization-Games"`.
3. Grant the provider's repository principal `roles/iam.workloadIdentityUser` on the dedicated service account.
4. Enable the required Google APIs, including `testing.googleapis.com` and `iamcredentials.googleapis.com`.

Add these three **repository Actions secrets** under GitHub → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `FIREBASE_PROJECT_ID` | Firebase / Google Cloud project ID |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full provider resource name: `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL_ID/providers/PROVIDER_ID` |
| `GCP_SERVICE_ACCOUNT` | Dedicated service-account email |

Do not put private keys, JSON credentials, or tokens in the repository or in chat. This workflow intentionally uses GitHub OIDC rather than a long-lived JSON key.

## Scope and honest interpretation

A passing instrumentation test proves that the tested game APK's Android string resources can be translated into Arabic resources and that the rebuilt APK passes signature verification. It does **not** prove every screen renders correctly, that all game text is stored in Android resources, or that Unity/Unreal/IL2CPP assets are supported. A separate launch/visual review is still needed for the rebuilt APK.

Firebase Test Lab's current gcloud workflow is scheduled for shutdown on **2027-09-30**. This integration is for the requested near-term test; plan migration to Google's successor device-testing service before that date.
