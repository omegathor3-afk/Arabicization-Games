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

This boundary does not download or install models. The model-v3.0 registry records are zstd-compressed and directional; a model config must refer to the verified decompressed model, shortlist and SentencePiece files. No translation should be enabled until the installer, config generation, real model load, airplane-mode translation test and device benchmarks are complete.

## Known gates

- Confirm Android cross-build succeeds for the exact pinned source and its full dependency graph.
- Verify the exact model-v3.0 config schema against Mozilla's released runtime before model loading.
- Add device instrumentation tests for both directions and measure APK delta, installed model bytes, peak PSS/native heap and cold/warm latency.
- Audit and ship every transitive dependency notice, not only the Bergamot MPL-2.0 notice.

## Mozilla Arabic model registry audit (2026-11-10)

The live Mozilla registry is:
`https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json`
Generated timestamp observed: `2026-11-10T00:44:44Z`.

Both required directions exist as separate one-way entries:

- `ar-en`: `base-memory`, `releaseStatus: Release`; model uncompressed size 31,561,787 bytes; uncompressed model SHA-256 `7b7af0282dc5f4d8805b9a298c2fa828967e3f09ca10f1942ebeea0b2cfc12fa`.
- `en-ar`: `base-memory`, `releaseStatus: Release`; model uncompressed size 31,561,787 bytes; uncompressed model SHA-256 `ae659d7045fc2e5d6ba50583586a5d94ee4358d92c019847e37b57a0e627faa8`.

These are distinct directional models; do not reverse one model to synthesize the other. Registry model hashes are hashes of the uncompressed model payload, not the downloaded gzip bytes. The registry JSON entries inspected expose no SHA-256 for the lexical-shortlist and SentencePiece vocabulary files. Therefore this repository must not claim complete package integrity until those two exact compressed artifacts are downloaded, hashed locally, pinned in a generated lock file, and checked against a trusted registry revision. The model package installer must verify both the downloaded bytes and decompressed model hash.

The current native bridge pin is a separate compatibility question: the registry's current model format and config schema must be proven compatible with the pinned native runtime by loading both actual model configs in CI. Do not infer compatibility from matching language tags or MPL-2.0 licensing. Until that test passes, the Arabic model is catalogued but not enabled in the app.
