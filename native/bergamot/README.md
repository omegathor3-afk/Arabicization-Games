# Bergamot Android JNI bridge

This is the first native integration boundary, not yet an enabled end-user translation feature.

## Pins

- Bergamot: `browsermt/bergamot-translator@9271618ebbdc5d21ac4dc4df9e72beb7ce644774`
- Engine license: MPL-2.0. Keep upstream notices and make corresponding source available when distributing the executable.
- Submodules are checked out from the exact gitlinks in that pinned superproject; CMake is explicitly prevented from changing them.

## Build

Requires Android NDK, CMake and Ninja:

```bash
ANDROID_NDK_HOME=/path/to/android-ndk native/bergamot/build-android.sh
```

The script targets arm64-v8a / Android API 28 and writes the JNI library plus libc++_shared to `app/src/main/jniLibs/arm64-v8a`. The CI workflow builds this separately from the regular app workflow.

## JNI contract

`BergamotNative.nativeCreate(configPath)` loads a Bergamot model configuration from app-private storage and returns an opaque session handle. `nativeTranslate` translates one input synchronously; `nativeDestroy` releases the session.

This boundary does not yet provide the production model-installation flow. The model-v3.0 registry records are zstd-compressed and directional; a model config must refer to the verified decompressed model, shortlist and SentencePiece files.

CI now generates both model configs, builds the pinned JNI runtime for arm64-v8a and x86_64, loads the actual Mozilla base-memory v3.0 packages in an Android 15 / API 35 x86_64 emulator, and translates in both directions with airplane mode enabled, Wi-Fi/mobile data disabled, and zero internet-capable networks. This proves the pinned model/runtime combination in that emulator environment; it does **not** prove physical-device performance or a production installer. The JNI boundary remains test-only and is not wired into the end-user translation UI.

## CI emulator validation (2026-11-12)

The successful API 35 / Android 15 x86_64 instrumentation run recorded:

| Direction | Model package (decompressed) | Native load | First translation | Warm translation | PSS after first translation |
|---|---:|---:|---:|---:|---:|
| Arabic → English | 37,049,402 bytes | 77.6 ms | 211.9 ms | 6.3 ms | 265,901 KiB |
| English → Arabic | 35,565,450 bytes | 67.0 ms | 209.9 ms | 7.8 ms | 255,704 KiB |

The test asserted both directions returned meaningful text while networking was disabled. These are emulator measurements, not phone benchmarks. The observed roughly 250–260 MiB PSS is substantial and must be treated as a device-compatibility risk until tested on representative ARM64 phones.

The instrumentation suite now also guards repeated warm translations with a 384 MiB total-PSS ceiling and a 32 MiB maximum PSS increase over the first translated result. Those are CI regression ceilings, not minimum-device specifications.

## Latest offline emulator verification (2026-11-14)

Workflow: [Bergamot Android native CI run 43584256442](https://github.com/omegathor3-afk/Arabicization-Games/actions/runs/43584256442). The model-package audit, ARM64 native build, and Android 15 / API 35 x86_64 instrumentation job all completed successfully.

The uploaded metrics record `offlineVerified: true`, exit code 0, airplane mode enabled before and after the test, Wi-Fi and mobile data disabled, and zero internet-capable networks.

| Direction | Decompressed package | Native load | First translation | Warm translation | PSS after repeated calls |
|---|---:|---:|---:|---:|---:|
| Arabic → English | 37,049,402 bytes | 56.8 ms | 158.6 ms | 4.8 ms | 266,724 KiB |
| English → Arabic | 35,565,450 bytes | 53.6 ms | 150.9 ms | 5.6 ms | 256,528 KiB |

Eight warm translations were measured per direction. Example outputs included `مرحبا بالعالم → Hello, the world.` and `The ocean is deep. → المحيط عميق.`.

These are repeatable emulator measurements only. The roughly 250–260 MiB post-translation PSS remains a substantial physical-device compatibility risk; neither battery/thermal behavior nor ARM64 phone performance has been validated. This result does not enable Bergamot in the end-user UI or replace the existing ML Kit path.

## Remaining gates

- Build and integrate a production model installer that atomically verifies every downloaded/decompressed file before activation and supports safe removal.
- Run both directions on physical ARM64 devices; measure cold/warm latency, peak PSS/native heap, APK and installed model bytes, battery/thermal behavior, and output quality on representative game dialogue.
- Audit and ship every transitive dependency notice, not only the Bergamot MPL-2.0 notice.
- Keep the native engine out of the user-facing translation flow until the installer, lifecycle cleanup, device memory budget, and quality gates pass.

## Mozilla Arabic model registry audit (2026-11-10)

The live Mozilla registry is:
`https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json`
Generated timestamp observed: `2026-11-10T00:44:44Z`.

Both required directions exist as separate one-way entries:

- `ar-en`: `base-memory`, `releaseStatus: Release`; model uncompressed size 31,561,787 bytes; uncompressed model SHA-256 `7b7af0282dc5f4d8805b9a298c2fa828967e3f09ca10f1942ebeea0b2cfc12fa`.
- `en-ar`: `base-memory`, `releaseStatus: Release`; model uncompressed size 31,561,787 bytes; uncompressed model SHA-256 `ae659d7045fc2e5d6ba50583586a5d94ee4358d92c019847e37b57a0e627faa8`.

These are distinct directional models; do not reverse one model to synthesize the other. Registry model hashes are hashes of the uncompressed model payload, not the downloaded gzip bytes. The registry JSON entries inspected expose no SHA-256 for the lexical-shortlist and SentencePiece vocabulary files. Therefore this repository must not claim complete package integrity until those two exact compressed artifacts are downloaded, hashed locally, pinned in a generated lock file, and checked against a trusted registry revision. The model package installer must verify both the downloaded bytes and decompressed model hash.

The current native bridge pin is a separate compatibility question: the registry's current model format and config schema must be proven compatible with the pinned native runtime by loading both actual model configs in CI. Do not infer compatibility from matching language tags or MPL-2.0 licensing. Until that test passes, the Arabic model is catalogued but not enabled in the app.
