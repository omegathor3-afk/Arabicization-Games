# Unity Mono Serialized Asset Research (read-only inspection and extraction phase)

## Scope and safety gate

This phase is a bounded, read-only inspection and extraction pipeline. It parses UnityFS headers, block metadata and storage blocks, extracts validated CAB node ranges into a new staging directory, and inspects supported SerializedFile headers, metadata preambles, SerializedType/TypeTree records and object tables. It does **not** decode serialized object payloads or rewrite `resources.assets`, `sharedassets*.assets`, `AssetBundles`, external resource streams or managed DLLs. Unsupported bytes remain untouched.

A valid SerializedFile header is not proof that its TypeTree or object table can be parsed. Player builds often omit TypeTrees; without a supported TypeTree, generic object decoding is not safely possible. Text-looking bytes inside arbitrary objects are not treated as translatable strings.

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

- Read-only APK inventory for Unity data entries, with explicit support states and parser-stage reporting.
- SerializedFile header validation for legacy, v9–21 and v22+ layouts, followed by a bounded full-metadata read capped at 64 MiB.
- Metadata preamble, SerializedType records, supported flat TypeTrees / v23+ TypeTree handlers, and the object-info table are connected through `UnitySerializedFileInspector`.
- The APK inventory reports Unity version, platform, TypeTree state, type count, object count and the achieved inspection stage when parsing succeeds.
- UnityFS header parsing, decompressed block-info parsing, and a read-only storage-block decoder for NONE, LZ4 and LZMA. Defaults cap compressed/decoded blocks at 64 MiB, aggregate decoded bytes at 512 MiB and the working set at 192 MiB.
- The UnityFS decoder streams decoded storage blocks to a caller-owned output stream.
- A read-only CAB-node extractor validates path traversal, case-insensitive duplicates, overlapping/out-of-range byte ranges, per-file and aggregate limits, file/directory collisions and destination symlinks. It writes to a fresh staging tree and publishes the complete tree with a same-filesystem directory move that refuses an existing target; partial output is cleaned on failure.
- `UnityFsBundleExtractor` composes bounded UnityFS decoding with CAB-node extraction through a private scratch file that is deleted on success or failure. The original bundle is never modified.
- Classification for StreamingAssets text candidates, SerializedFiles, bundles, external streams, managed DLLs and unknown binary data.
- Synthetic tests cover parser bounds, endian handling, object counts, decompression limits and byte-identical preservation. They are not substitutes for real Unity game fixtures.

## SerializedFile / TypeTree progress (read-only)

- The full SerializedFile inspector reads the declared metadata region only after enforcing a 64 MiB cap. A separate partial-prefix mode remains available for inventory fallback when a complete metadata read is not possible.
- A bounded metadata-preamble reader extracts those fields for supported endian-aware format versions and stops before interpreting type records.
- A bounded standalone parser now reads the raw flat TypeTree node/string-buffer representation for TypeTree format versions 12–100 (the TypeTree format version is not always identical to the enclosing SerializedFile version).
- It uses Unity's 24-byte node layout, expanding to 32 bytes when the TypeTree format version is 19+, and reads the node's 16-bit version field correctly.
- It validates node counts, record-buffer arithmetic, hierarchy levels, local string offsets, NUL termination, UTF-8 validity, and trailing bytes before accepting a tree.
- Common-string-table offsets are retained as unresolved references rather than guessed or silently mapped to the wrong field name.
- The `mhtt` TypeTreeNodeInfoHandler envelope used by SerializedFile versions 23+ now has its own bounded parser, including per-tree format version and reference-hash arrays.
- A bounded SerializedType table parser now reads class IDs, stripped flags, script type indices, applicable script IDs, type hashes, inline TypeTrees or v23+ handlers, and v21+ type dependencies.
- A separate bounded SerializedFile object-table parser now reads PathIDs, data-relative offsets, byte sizes, type IDs, legacy class/script fields, and version-specific PathID alignment (including the v7–13 big-ID flag).
- Deep inspection enforces a 100,000-record per-file cap and a 250,000-record aggregate object-index cap across selected UnityFS bundles. CAB output also has a cumulative byte budget across the complete inspection, separate from per-bundle decoded-storage and CAB-output ceilings. Scratch preflight reserves the peak concurrent bundle copy, decoded-storage intermediate, CAB staging tree and safety margin; those temporary files are removed before the next bundle starts.
- Object-table indexing is capped at 100,000 records per SerializedFile to bound the temporary object list and PathID set on memory-constrained Android devices. If a declared count exceeds the configured cap, the inspector reports the declared count and limit explicitly; it does not display the file as a complete zero-object table, and the file remains read-only.
- Deep-inspection reports distinguish a successfully parsed empty object table (`objects=0`) from a malformed or unsupported table (`objects=unavailable`); only successfully indexed records consume the aggregate object-record budget. When both per-file and aggregate limits would reject a table, the per-file limit is reported first and the aggregate budget is not consumed; aggregate exhaustion is reported only when the table fits the per-file cap.
- The TypeTree/type-record parser's CI compile failure in run #155 was traced to two block-bodied local helpers missing explicit `return` statements; both were fixed in commit `6472731`. Run #155 did not reach tests or APK packaging, so it produced no valid APK for that revision.
- Object records are rejected for duplicate PathIDs, invalid type indices, negative offsets, truncated metadata, excessive counts, or ranges outside the declared file/data section. Alignment is calculated against the absolute file position and padding bytes are skipped without assuming a particular fill value.
- The object-table parser is wired into the bounded inspector and APK inventory. It returns offsets and IDs only; it does not dereference or load object payload bytes and cannot authorize edits.
- The trailing-metadata parser is now wired into the same streaming inspector. It reports script identifiers, external file references (including GUID/type/path), v20+ reference type records, v26+ shared TypeTree handlers, user information, and any unconsumed tail-byte count. The inventory and deep-inspection details expose counts without resolving or modifying references.
- External paths are not yet resolved to APK entries or sidecar .resS/.resource byte ranges, and object payloads remain unread. This is still metadata-only, read-only inspection; no asset writer or automatic Unity resource mutation is enabled.
- Synthetic tests cover versions 18/19, local strings, unresolved common-string references, malformed counts, hierarchy, offsets, and trailing data. Real Unity fixtures and differential comparison against UnityPy/UnityDataTools remain required.

## Player builds with stripped TypeTrees — verified constraint

Unity's current documentation states that the `binary2text` inspection tool is only useful for SerializedFiles containing TypeTrees and that **Player data does not include TypeTrees by default** ([Unity: Analyze built assets](https://docs.unity.com/en-us/engine/6000.6/manual/assets-and-media/assets-optimizing/assets-analyze-built-assets)). Unity's TextAsset documentation also makes clear that imported `.bytes` assets can contain arbitrary binary data, so the class name alone is not permission to treat every TextAsset payload as UTF-8 ([Unity: Text assets](https://docs.unity.com/en-us/engine/6000.6/manual/assets-and-media/asset-types/class-text-asset)).

UnityDataTools exposes this distinction explicitly: `enableTypeTree` may be false while the type-entry count is still present; its documentation notes that TypeTrees are required for object-list and external-reference subcommands to show type names ([UnityDataTools SerializedFile command](https://github.com/Unity-Technologies/UnityDataTools/blob/main/Documentation/command-serialized-file.md)). Therefore, an absent TypeTree is a normal Player-build condition, not proof of a corrupt file. ARABIFY may continue to report numeric type IDs, object ranges and references where their metadata is valid, but it must not run a generic object decoder without a trusted schema.

### Safe fallback strategy (not yet implemented)

- **Built-in Unity classes:** evaluate a versioned, hash-pinned TypeTree schema database. UnityPy's upstream implementation uses its TPK data blob and resolves a class's versioned schema by class ID and Unity version ([UnityPy TPK helper](https://github.com/K0lb3/UnityPy/blob/master/UnityPy/helpers/Tpk.py)). This is a reference for a possible offline schema catalog, not approval to bundle the TPK resource: its provenance, redistribution terms, size, and version coverage must be audited first.
- **MonoBehaviour scripts:** require a resolved MonoScript reference and matching managed assembly/class identity. A script name, class ID 114, or a nearby string is not sufficient to infer a serialized layout. Assembly stripping, missing dependencies, generic types, and version drift must produce an unsupported result.
- **TextAsset candidates:** decode only after a schema has been established and the payload's length, encoding, and expected fields validate. Preserve binary `.bytes` data and invalid/unknown encodings unchanged.
- **Write gate:** no object writer is enabled until read -> write -> read tests prove unchanged round trips, preserved PathIDs/references and unchanged hashes for all unrelated payloads, followed by a Unity Player smoke test on a legally redistributable fixture.

The SerializedType reader also enforces a **250,000-node aggregate TypeTree budget per type table**, in addition to the per-tree limit. This is a defensive memory policy, not a Unity format maximum. A table whose individual trees fit but whose combined node count exceeds the budget is rejected at the type-table stage before object indexing; it is never partially decoded. Regression tests cover both inline format-22 trees and format-23 `mhtt` handlers.

The current regression fixture deliberately has `typeTreeEnabled=false`; it verifies that metadata and the object index can still be read without claiming that the payload is decodable. This distinction is now an explicit test requirement.

## Required gate before any asset edit

1. Validate CAB-node extraction against legally redistributable real UnityFS bundles across engine versions; compare extracted paths, byte ranges and hashes with UnityPy/UnityDataTools. Keep extraction read-only.
2. Validate the newly connected trailing-metadata sections against legally redistributable real fixtures, resolve external references to sidecar resource streams without modifying them, and document any version-specific layouts that remain unsupported.
3. Preserve original object bytes and all unrecognized fields; do not normalize or reorder unknown records.
4. Build read -> write -> read round-trip tests against legally redistributable real Unity samples, comparing object IDs, type IDs, external references, TypeTrees, offsets, sizes and hashes of unchanged payloads.
5. Test a modified copy against the Unity runtime or an actual game supplied by its owner. A parser round-trip alone does not prove runtime compatibility.
6. Only then add a separately gated text-object adapter for known safe object types. Keep automatic edits disabled for unsupported versions/types.

## Test provenance

The current unit tests use generated minimal binary headers, synthetic UnityFS bundles and synthetic ZIP fixtures. They are useful for parser invariants and regression checks, but **they are not real game samples**. No third-party game APK or real game resource file has been claimed as tested. A legally redistributable sample set is still required before declaring real-file compatibility.
