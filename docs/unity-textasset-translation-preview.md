# Unity TextAsset translation preview

## Scope

The deep UnityFS inspector can identify built-in TextAsset objects (class ID 49) only when the SerializedFile provides a complete, resolved TypeTree matching the supported `m_Name`, `m_Script`, and optional `m_PathName` schema. The current UI exposes bounded previews from that read-only inspection.

This increment adds a local translation preview for complete decoded TextAsset sources whose path/name clearly identifies one of these formats:

- JSON
- TXT
- Java-style Properties
- Apple `.strings`

The complete decoded script is retained in memory only for the already-bounded preview candidates. The inspector caps each object at 1 MiB, total TextAsset reads at 8 MiB, the number of preview objects at 32, and the visible source preview at 1,200 Unicode code points. Translation uses the complete retained script, not the visible snippet. A TextAsset is additionally limited to 500 translation candidates, 10,000 text lines, JSON nesting depth 64, and 20,000 JSON delimiters/string tokens; excess input is rejected or left unchanged before expensive parsing/translation. The translated result is also displayed as a bounded preview; the user can copy the complete translated text.

The translator reuses the existing `AssetTextTransformer`, placeholder protection, and translation quality gate. JSON keys and non-string values are not translated; TXT comments and Properties/Strings syntax are preserved by the existing transformer. Values rejected by the quality gate remain unchanged.

## Safety boundaries

- Only complete decoded TextAsset scripts are translated; the UI's shortened display preview is never passed as if it were the full source.
- Unknown extensions are rejected instead of guessing that arbitrary TextAsset bytes are prose. A non-empty `m_PathName` is authoritative; a supported-looking `m_Name` cannot override an explicitly unsupported path.
- The existing on-device Arabic ML Kit model must already be available.
- The translated output is shown as a bounded preview and can be copied in full for external review.
- Translation cancellation is propagated; the preview offers an explicit cancel action. Cancellation never writes to game assets.
- **No SerializedFile object, UnityFS member, AssetBundle, APK, or original game file is rewritten by this preview.**

This is a complete-source extraction/translation-review milestone, not end-to-end Unity localization.

## Remaining work

1. Add a user-editable source/translation review and an exportable patch manifest.
2. Design a version-aware SerializedFile writer that updates object sizes, offsets, alignment, metadata, and file headers without touching unrelated bytes.
3. Rebuild UnityFS blocks and verify the result with UnityPy and UnityDataTools across real Mono fixtures before enabling any write path.
4. Test multiple Unity versions, stripped/no-TypeTree files, LZ4/LZMA, external `.resS`/`.resource` data, cancellation, and low-storage rollback.

Until those gates pass, Unity TextAsset support remains read-only plus full-source translation preview.
