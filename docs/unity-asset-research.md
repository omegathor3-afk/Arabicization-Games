# Unity Mono Serialized Asset Research (read-only phase)

## Scope and safety gate

This phase inventories Unity data and validates container headers only. It does **not** rewrite `resources.assets`, `sharedassets*.assets`, `AssetBundles`, TypeTrees, object payloads, external resource streams, or managed DLLs. The Android app labels candidates and preserves unsupported bytes.

A valid SerializedFile header is not proof that its TypeTree/object table can be parsed. Player builds often omit TypeTrees; without them, generic object decoding is not safely possible. Text-looking bytes inside arbitrary objects are not treated as translatable strings.

## Format map

- `resources.assets`, `sharedassets*.assets`, `levelN`, `globalgamemanagers`: Unity SerializedFiles. The format has a header, metadata, type records/optional TypeTrees, object table, script metadata, external references, and object data.
- `.bundle`, `.unity3d`, and related files: Unity Archive / AssetBundle containers. Common signatures include `UnityFS`, `UnityRaw`, and `UnityWeb`. A bundle may contain one or more SerializedFiles (often `CAB-...`) and sidecar data.
- `.resS` and `.resource`: external binary streams referenced by serialized objects; textures/meshes and audio/video may live here.
- Android Unity players can wrap `assets/bin/Data` in an inner `data.unity3d` archive, so APK ZIP listing alone is not a full content inventory.
- `StreamingAssets` plain text is a separate, lower-risk candidate path; it is not evidence that other Unity assets are editable.

## Library assessment

| Project | License / state | Android integration decision |
|---|---|---|
| [UnityPy](https://github.com/K0lb3/UnityPy) | MIT; active Python parser/editor | Best reference implementation for format behavior and differential fixtures. Not a drop-in Kotlin/Android dependency: Python runtime and package dependencies/native decoders need a separate size, ABI, and compatibility evaluation. No package added in this phase. |
| [UnityDataTools](https://github.com/Unity-Technologies/UnityDataTools) | Unity Companion License for Unity-dependent projects; .NET 9 toolchain; wraps native UnityFileSystemApi | Excellent authoritative reference for SerializedFiles, Archives, inventory and test data. Current packaged native binaries target desktop OSes, not Android. Do not bundle into this Android app until license scope and Android native port/build are independently cleared. |
| [AssetRipper](https://github.com/AssetRipper/AssetRipper) | GPL-3.0; desktop-oriented .NET application | Useful for understanding extraction breadth, but not selected as an embedded dependency. GPL obligations and desktop/runtime footprint require a separate distribution decision. |
| [AssetStudio](https://github.com/Perfare/AssetStudio) | MIT; archived upstream | Useful historical C# reference, but not selected as a production parser without a maintained fork and compatibility review. |

License statements are based on upstream repository license files/readmes at the time of this study. Re-check pinned commit licenses and all transitive dependencies before vendoring or distributing code.

## Implemented in this phase

- Read-only APK inventory for Unity data entries.
- Conservative SerializedFile header validation for legacy, v9–21, and v22+ header layouts.
- Unity Archive signature recognition only; no decompression or block table parsing yet.
- Classification for StreamingAssets text candidates, SerializedFiles, bundles, external streams, managed DLLs, and unknown binary data.
- UI report explicitly says what is and is not parsed.
- Header parser round-trip tests assert byte-for-byte preservation of unknown serialized payload bytes.

## SerializedFile / TypeTree progress (read-only)

- The APK inventory now uses a bounded metadata-prefix read to report the Unity version, target platform, TypeTree-enabled flag, and declared type count when available; it does not load the entire metadata region just to show this summary.
- A bounded metadata-preamble reader extracts those fields for supported endian-aware format versions and stops before interpreting type records.
- A bounded standalone parser now reads the raw flat TypeTree node/string-buffer representation for TypeTree format versions 12–100 (the TypeTree format version is not always identical to the enclosing SerializedFile version).
- It uses Unity's 24-byte node layout, expanding to 32 bytes when the TypeTree format version is 19+, and reads the node's 16-bit version field correctly.
- It validates node counts, record-buffer arithmetic, hierarchy levels, local string offsets, NUL termination, UTF-8 validity, and trailing bytes before accepting a tree.
- Common-string-table offsets are retained as unresolved references rather than guessed or silently mapped to the wrong field name.
- The `mhtt` TypeTreeNodeInfoHandler envelope used by SerializedFile versions 23+ now has its own bounded parser, including per-tree format version and reference-hash arrays.
- A bounded SerializedType table parser now reads class IDs, stripped flags, script type indices, applicable script IDs, type hashes, inline TypeTrees or v23+ handlers, and v21+ type dependencies.
- A separate bounded SerializedFile object-table parser now reads PathIDs, data-relative offsets, byte sizes, type IDs, legacy class/script fields, and version-specific PathID alignment (including the v7–13 big-ID flag).
- Object records are rejected for duplicate/zero PathIDs, invalid type indices, negative offsets, truncated metadata, excessive counts, or ranges outside the declared file/data section. Alignment is calculated against the absolute file position and padding bytes are skipped without assuming a particular fill value.
- The object-table reader returns an index only; it does not dereference payloads or rewrite data. The APK inventory UI is not yet wired to run the full metadata/object-table parser on large files.
- This is still a metadata-only parser: it does not yet walk the object table, script reference list, external references, v20+ serialized-reference type table as a complete section, v26 shared-subtree table, or object payloads.
- This parser is **not yet wired into the SerializedFile metadata/type-record reader**. It does not parse object tables, object payloads, script IDs, external references, or write assets. No automatic translation or asset mutation is enabled.
- Synthetic tests cover versions 18/19, local strings, unresolved common-string references, malformed counts, hierarchy, offsets, and trailing data. Real Unity fixtures and differential comparison against UnityPy/UnityDataTools remain required.

## Required gate before any asset edit

1. Parse archive block tables and decompression safely with strict limits (entry count, total expanded bytes, per-block size, recursion depth, CPU/time).
2. Parse SerializedFile metadata, type records, TypeTrees, object table, alignment, and external references for explicitly supported format versions.
3. Preserve original object bytes and all unrecognized fields; do not normalize or reorder unknown records.
4. Build read -> write -> read round-trip tests against legally redistributable real Unity samples, comparing object IDs, type IDs, external references, TypeTrees, offsets, sizes, and hashes of unchanged payloads.
5. Test a modified copy against the Unity runtime or an actual game supplied by its owner. A parser round-trip alone does not prove runtime compatibility.
6. Only then add a separately gated text-object adapter for known safe object types. Keep automatic edits disabled for unsupported versions/types.

## Test provenance

The current unit tests use generated minimal binary headers and synthetic ZIP fixtures. They are useful for parser invariants and regression checks, but **they are not real game samples**. No third-party game APK or real game resource file has been claimed as tested. A legally redistributable sample set is still required before declaring real-file compatibility.
