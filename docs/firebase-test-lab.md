# Firebase Test Lab: real-game localization test

This workflow tests the ARABIFY APK and runs an instrumentation test against a real, separately installed Android game package. The test game is the open-source native Android Minesweeper project (`com.joeld.minesweeper`), built from pinned GitHub source commit `115ee86a0d5d998c6c882de63953aef54100a1fd` and licensed under Apache-2.0. The test copies its installed APK, downloads the English-to-Arabic ML Kit model, adds Arabic-qualified Android string resources, rebuilds the APK, checks a known Arabic translation, and verifies the generated APK signature.

## Workflow

The workflow watches pushes to `feat/unity-mono-streamingassets` that touch `app/**` or the Firebase workflow file. To enable its Firebase job, first add the three secrets below and set the repository Actions variable `FIREBASE_TEST_LAB_ENABLED` to `true` under GitHub → Settings → Secrets and variables → Actions → Variables. Then push a commit touching one of those paths. Until that explicit opt-in is set, the Firebase job is skipped and no Test Lab device minutes are consumed.

The workflow:
1. Builds the app and its Android instrumentation APK.
2. Builds [JoelDankert/Minesweeper-for-Android](https://github.com/JoelDankert/Minesweeper-for-Android) from the pinned GitHub commit in a separate job with no Firebase credentials, then records the APK SHA-256.
3. Authenticates to Google Cloud with GitHub OIDC (no service-account JSON key).
4. Runs only `RealGameLocalizationInstrumentedTest` on the `MediumPhone.arm` virtual device in Arabic and English locales.
5. Verifies that the known `new_game` string is Arabic, counts Arabic-qualified resources, and verifies the generated APK signature.
6. Pulls the generated APK and report from the Firebase test device. The GitHub Android emulator smoke test independently installs the generated APK, launches the game, and records a screenshot and launch log.

## One-time Google Cloud setup

A Firebase project and Google Cloud project must already exist and be linked. Enable `testing.googleapis.com` and `iamcredentials.googleapis.com`; the workflow verifies that the Test Lab API is enabled and lists the available virtual-device catalog before running. The current matrix uses `MediumPhone.arm`, Android API 35, in Arabic and English locales.

Create a dedicated Google Cloud service account for this repository. Grant it:
- `roles/cloudtestservice.testRunner`
- `roles/serviceusage.serviceUsageConsumer`

Configure Workload Identity Federation for GitHub Actions:
1. Create a Workload Identity Pool and an OIDC provider for `https://token.actions.githubusercontent.com`.
2. Restrict the provider attribute condition to this repository: `attribute.repository == "omegathor3-afk/Arabicization-Games"`.
3. Grant the provider's repository principal `roles/iam.workloadIdentityUser` on the dedicated service account.
4. Enable the required Google APIs, including `testing.googleapis.com` and `iamcredentials.googleapis.com`.

Add this **repository Actions variable** and these three **repository Actions secrets** under GitHub → Settings → Secrets and variables → Actions:

| Variable | Value |
|---|---|
| `FIREBASE_TEST_LAB_ENABLED` | `true` (explicitly enables the Firebase job) |



| Secret | Value |
|---|---|
| `FIREBASE_PROJECT_ID` | Firebase / Google Cloud project ID |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full provider resource name: `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL_ID/providers/PROVIDER_ID` |
| `GCP_SERVICE_ACCOUNT` | Dedicated service-account email |

Do not put private keys, JSON credentials, or tokens in the repository or in chat. This workflow intentionally uses GitHub OIDC rather than a long-lived JSON key.

## Scope and honest interpretation

A passing instrumentation test proves that the tested game APK's Android string resources can be translated into Arabic resources and that the rebuilt APK passes signature verification. It does **not** prove every screen renders correctly, that all game text is stored in Android resources, or that Unity/Unreal/IL2CPP assets are supported. A separate launch/visual review is still needed for the rebuilt APK.

Firebase Test Lab's current gcloud workflow is scheduled for shutdown on **2027-09-30**. This integration is for the requested near-term test; plan migration to Google's successor device-testing service before that date.


## Device cost and limitations

The workflow uses two virtual-device executions (`MediumPhone.arm`, API 35), one per locale. On Spark, Test Lab includes up to 10 virtual-device test runs per day. On Blaze, the first 60 virtual-device minutes per day are no-cost, then usage is billed at USD $1 per device-hour, rounded up per minute. The two locale runs count separately. The workflow uses a 30-minute per-test timeout and does not run physical devices. It remains gated by `FIREBASE_TEST_LAB_ENABLED=true` and the three GitHub Actions secrets; no Test Lab run has been launched because those settings are not yet enabled. See [Google's current quotas and pricing](https://firebase.google.com/docs/test-lab/usage-quotas-pricing).


## Migration to Developer Device Platform

Firebase Test Lab is deprecated and scheduled to shut down on 2027-09-30. Google recommends migrating to Developer Device Platform (DDP). DDP is currently in Preview, requires Google Cloud billing, and offers Device Run on physical and virtual Android devices. Published Preview rates are USD $0.083 per physical-device minute and USD $0.017 per emulator minute; standard rates are scheduled to change in April 2027. ARABIFY will not launch DDP sessions until billing and project access are explicitly approved. See [Firebase migration FAQ](https://firebase.google.com/docs/test-lab/migrate-faq), [DDP overview](https://docs.cloud.google.com/developer-device-platform/overview), and [DDP pricing](https://cloud.google.com/products/developer-device-platform).
