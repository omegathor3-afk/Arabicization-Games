# Bergamot / Marian Android integration audit

Status: engineering gate / not yet a shipping translation engine.

## Candidate stack

- **Bergamot Translator runtime:** MPL-2.0. It is a Marian-derived native C++ inference runtime. If we distribute modified Bergamot source, comply with MPL file-level source disclosure and preserve notices. Keep its source and notices separate from this Apache-2.0 Android application.
- **Marian core:** MIT upstream license, but a Bergamot build is not automatically equivalent to a plain Marian integration; audit the exact pinned source tree and every bundled dependency.
- **Model package:** independently licensed. Do not infer model rights from the runtime license. Each model, shortlist/lexicon, SentencePiece vocabulary, tokenizer, config and conversion artifact must have provenance, license, source URL/revision and checksum recorded.
- **Reference implementation:** RTranslator 3.0 beta is Apache-2.0 at application level and contains a Bergamot JNI integration, but its bundled native components and model files retain their own licenses. Reuse only after a per-file audit; do not copy its app wholesale.
- **Arabic coverage:** not assumed. Firefox/Bergamot model registries are pair-specific and coverage must be verified from the actual registry/model card. A multilingual MADLAD model may cover Arabic but can be far too large for a phone; its Apache-2.0 model card is not proof that an Android runtime/conversion is ready.

## Arabic model availability

Mozilla documents Arabic among Firefox Translations supported languages, and the Mozilla translations pipeline publishes direction-specific Bergamot-compatible model packages. This establishes Arabic as a viable candidate, not yet a pinned model artifact: before shipping, select the exact `en→ar` and `ar→en` registry entries, record each file revision/size/SHA-256/license, and run Arabic game-dialogue quality tests. Bergamot models are directional; do not assume one model handles both directions. See https://www.mozilla.org/en-US/firefox/features/translate/ and https://github.com/mozilla/translations.

## Initial implementation decision

1. Introduce a strict model-package integrity gate before any native loader is called.
2. A package is rejected unless all required files exist, paths remain inside the package directory, and every SHA-256 matches.
3. Require explicit engine/model/tokenizer license metadata and provenance; never silently bundle weights with unclear terms.
4. Download/install packages into app-private storage, atomically verify before activation, and support removal.
5. Only then add a pinned Bergamot C++/JNI build and real Arabic-capable model pair. Do not label ML Kit as open source or fully offline before its model is downloaded.
6. Benchmark on physical Android devices: cold/warm latency, peak RSS/PSS, native heap, model/package bytes, APK delta, battery/thermal behavior and output quality on a fixed Arabic game-text set.

## Open gates

- Pin exact Bergamot and Marian commits and generate a complete third-party license inventory (including SentencePiece, protobuf, fastText and build dependencies).
- Select an Arabic-capable compatible model package; validate its redistribution/commercial terms and tokenizer/lexicon provenance.
- Build native arm64-v8a artifacts with reproducible NDK/CMake settings and verify ABI/symbol loading.
- Add instrumentation tests that load the real model and translate English to Arabic in airplane mode.
- Measure memory and package size on target phones before setting minimum hardware requirements.

## Sources checked

- Mozilla Bergamot project: https://github.com/browsermt/bergamot-translator (MPL-2.0; verify exact revision and bundled dependencies).
- RTranslator reference: https://github.com/niedev/RTranslator (Apache-2.0 application license; native/model assets require separate notices).
- RTranslator 3.0 project description: https://nlnet.nl/project/RTranslator/
- MADLAD-400 model card: https://huggingface.co/google/madlad400-7b-mt-bt (Apache-2.0 model card; 7B-class weights are not a practical default for modest Android devices).
- Firefox Translations model mirror: https://huggingface.co/midudev/firefox-translations (MPL-2.0; direction-specific packages; verify Arabic availability from the registry before selecting).


## Verified Mozilla Arabic registry snapshot (2026-11-10)

The official Mozilla Remote Settings collection `translations-models-v2` was fetched directly. It contained 404 records in this snapshot and exactly six records for Arabic: three for `en→ar` and three for `ar→en` (model, lexicon shortlist, SentencePiece vocabulary). All six are version `3.0`, architecture `base-memory`. The exact compressed and decompressed SHA-256 values, sizes and attachment locations are pinned in:

- `app/src/main/assets/translation-models/mozilla-arabic-models-v2.lock.json`
- `MozillaArabicModelLock.kt` with unit tests.

Each direction is a separate package; the compressed download totals are 23,828,180 bytes for en→ar and 24,743,247 bytes for ar→en (about 48.6 MB before decompression and storage overhead). The decompressed payloads total more; do not advertise this as the installed footprint. Mozilla's `mozilla/translations` repository states that its published model files are MPL-2.0. The registry hashes are integrity metadata, not proof of output quality or native compatibility.

## Engine pin candidate and Android integration boundary

Pinned source candidate: `browsermt/bergamot-translator@9271618ebbdc5d21ac4dc4df9e72beb7ce644774` (MPL-2.0). Its recorded submodule revisions are:

- `3rd_party/marian-dev`: `2781d735d4a10dca876d61be587afdab2726293c`
- `3rd_party/ssplit-cpp`: `a311f9865ade34db1e8e080e6cc146f55dafb067`
- `3rd_party/pybind11`: `9ec1128c7aac3d069a4ec2bd1dfc7f57c6526d1c`
- `bergamot-translator-tests`: `a04432d7921bfa1dd62bc2e5cdca46b226f256de`

This is a source pin, not yet an Android native binary. The upstream CMake project is C++17 and initializes git submodules during CMake configuration by default. A mobile integration must explicitly disable implicit submodule mutation and check out the exact gitlinks above. The Android JNI pattern in RTranslator v3.00 is a useful implementation reference, but its app-level Apache-2.0 license does not relicense Bergamot, Marian, SentencePiece or other native dependencies.

**Compatibility gate still open:** model v3.0 uses zstd-compressed registry attachments and must be decompressed and checked against each published decompressed hash before loading. We have not yet proved that this exact engine pin can load these model-v3.0 payloads on Android arm64-v8a. We have not built JNI, translated a real sentence, or measured Android memory/latency. Do not mark Bergamot as available until those checks pass.

Sources:
- Official Mozilla registry: https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models-v2/records
- Mozilla model source and MPL-2.0 statement: https://github.com/mozilla/translations
- Pinned Bergamot source: https://github.com/browsermt/bergamot-translator/tree/9271618ebbdc5d21ac4dc4df9e72beb7ce644774
- Android JNI reference: https://github.com/niedev/RTranslator/tree/v3.00
