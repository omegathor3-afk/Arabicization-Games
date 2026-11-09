# Bergamot / Marian Android integration audit

Status: engineering gate / not yet a shipping translation engine.

## Candidate stack

- **Bergamot Translator runtime:** MPL-2.0. It is a Marian-derived native C++ inference runtime. If we distribute modified Bergamot source, comply with MPL file-level source disclosure and preserve notices. Keep its source and notices separate from this Apache-2.0 Android application.
- **Marian core:** MIT upstream license, but a Bergamot build is not automatically equivalent to a plain Marian integration; audit the exact pinned source tree and every bundled dependency.
- **Model package:** independently licensed. Do not infer model rights from the runtime license. Each model, shortlist/lexicon, SentencePiece vocabulary, tokenizer, config and conversion artifact must have provenance, license, source URL/revision and checksum recorded.
- **Reference implementation:** RTranslator 3.0 beta is Apache-2.0 at application level and contains a Bergamot JNI integration, but its bundled native components and model files retain their own licenses. Reuse only after a per-file audit; do not copy its app wholesale.
- **Arabic coverage:** not assumed. Firefox/Bergamot model registries are pair-specific and coverage must be verified from the actual registry/model card. A multilingual MADLAD model may cover Arabic but can be far too large for a phone; its Apache-2.0 model card is not proof that an Android runtime/conversion is ready.

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
