# Unity TextAsset translation preview

## Scope

The deep UnityFS inspector can identify built-in TextAsset objects (class ID 49) only when the SerializedFile provides a complete, resolved TypeTree matching the supported `m_Name`, `m_Script`, and optional `m_PathName` schema. The current UI exposes bounded previews from that read-only inspection.

This increment adds a local translation preview for complete previews whose path/name clearly identifies one of these formats:

- JSON
- TXT
- Java-style Properties
- Apple `.strings`

It reuses the existing `AssetTextTransformer`, placeholder protection, and translation quality gate. JSON keys and non-string values are not translated; TXT comments and Properties/Strings syntax are preserved by the existing transformer. Values rejected by the quality gate remain unchanged.

## Safety boundaries

- Truncated previews are rejected instead of translating partial JSON or text.
- Unknown extensions are rejected instead of guessing that arbitrary TextAsset bytes are prose. A non-empty `m_PathName` is authoritative; a supported-looking `m_Name` cannot override an explicitly unsupported path.
- The existing on-device Arabic ML Kit model must already be available.
- The translated output is shown and can be copied for review.
- Coroutine cancellation is propagated through the shared text transformer rather than being misreported as a skipped translation.
- **No SerializedFile object, UnityFS member, AssetBundle, APK, or original game file is rewritten by this preview.**

This is an extraction/translation-review milestone, not end-to-end Unity localization.

## Remaining work

1. Retain or stream the complete selected TextAsset under the existing aggregate byte budget instead of translating only the bounded UI preview.
2. Add a user-editable source/translation review and an exportable patch manifest.
3. Design a version-aware SerializedFile writer that updates object sizes, offsets, alignment, metadata, and file headers without touching unrelated bytes.
4. Rebuild UnityFS blocks and verify the result with UnityPy and UnityDataTools across real Mono fixtures before enabling any write path.
5. Test multiple Unity versions, stripped/no-TypeTree files, LZ4/LZMA, external `.resS`/`.resource` data, cancellation, and low-storage rollback.

Until those gates pass, Unity TextAsset support remains read-only plus translation preview.
