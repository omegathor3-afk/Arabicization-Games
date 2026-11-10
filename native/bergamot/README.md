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
