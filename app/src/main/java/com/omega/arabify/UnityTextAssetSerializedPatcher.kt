package com.omega.arabify

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class UnityTextAssetPatchReport(
    val pathId: Long,
    val oldObjectBytes: Long,
    val newObjectBytes: Long,
    val deltaBytes: Long,
    val shiftedObjectCount: Int
)

/**
 * Bounded writer that replaces the m_Script text of one TextAsset object inside
 * a standalone SerializedFile. This is the first real Unity binary write-back:
 *
 * - Only the target object's bytes change; every other object payload is copied
 *   byte-for-byte and re-compared after writing.
 * - The object table is patched in place (target byteSize, later byteStarts
 *   shifted by the delta); no other metadata byte is touched.
 * - The delta is padded to a multiple of 4 so following objects keep their
 *   original 4-byte alignment.
 * - After writing, the output is fully re-inspected, the target is re-read and
 *   compared against the reviewed text, and every other object payload is
 *   byte-compared with the source. Any mismatch deletes the output and fails.
 *
 * It never touches managed DLLs, TypeTrees, or external references, and it does
 * not write into AssetBundles: rebundling is a separate, later step.
 */
internal object UnityTextAssetSerializedPatcher {
    private const val TEXT_ASSET_CLASS_ID = 49
    private const val MAX_REVIEWED_BYTES = 2 * 1024 * 1024
    private const val MAX_DELTA_BYTES = 8L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 256 * 1024

    private data class FieldSpan(
        val name: String,
        val lengthPrefixOffset: Int,
        val dataOffset: Int,
        val dataLength: Int,
        val fieldEnd: Int
    )

    private data class EntryFields(
        val pathId: Long,
        val byteStart: Long,
        val byteSize: Long,
        val byteStartFieldOffset: Int,
        val byteStartFieldLength: Int,
        val byteSizeFieldOffset: Int
    )

    fun patch(
        input: File,
        output: File,
        header: UnitySerializedHeader,
        typeTable: UnitySerializedTypeTable,
        objectTable: UnitySerializedObjectTable,
        pathId: Long,
        reviewedScript: String
    ): UnityTextAssetPatchReport {
        require(input.isFile) { "ملف SerializedFile الأصلي غير موجود." }
        require(input.canonicalFile != output.canonicalFile) { "لا يمكن الكتابة فوق الملف الأصلي نفسه، حتى عبر مسار بديل." }
        require(header.fileSize == input.length()) { "حجم SerializedFile لا يطابق ترويسة الملف." }
        require(reviewedScript.isNotEmpty()) { "النص المراجعة فارغ." }
        val reviewedBytes = reviewedScript.toByteArray(Charsets.UTF_8)
        require(reviewedBytes.size <= MAX_REVIEWED_BYTES) { "النص المراجعة يتجاوز الحد الآمن." }
        require(isPlainText(reviewedScript)) { "النص المراجعة يحتوي محارف تحكم غير مسموحة." }
        require(header.endian == "little" || header.endian == "big")
        val order = if (header.endian == "little") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        val target = objectTable.objects.singleOrNull { it.pathId == pathId }
            ?: error("لم يتم العثور على كائن بالمعرّف المطلوب داخل جدول الكائنات.")
        val typeRecord = UnityTextAssetReader.findTypeRecord(header, typeTable, target)
            ?: error("سجل نوع الكائن غير متاح.")
        require(typeRecord.classId == TEXT_ASSET_CLASS_ID) { "الكائن المطلوب ليس TextAsset." }
        val tree = typeRecord.typeTree ?: error("شجرة أنواع TextAsset غير متاحة.")
        require(tree.unresolvedCommonStringReferences == 0) { "شجرة الأنواع تحتوي مراجع غير محلولة." }
        val fields = UnityTextAssetReader.parseStringFields(tree)
            ?: error("مخطط TextAsset غير مدعوم للكتابة الآمنة.")

        require(target.byteSize in 1L..Int.MAX_VALUE.toLong())
        val absoluteObjectOffset = Math.addExact(header.dataOffset, target.byteStart)
        require(absoluteObjectOffset == target.absoluteDataOffset)
        val payload = ByteArray(target.byteSize.toInt())
        val metadata = ByteArray(header.metadataSize.toInt())
        RandomAccessFile(input, "r").use { source ->
            require(source.length() == header.fileSize)
            source.seek(absoluteObjectOffset)
            source.readFully(payload)
            source.seek(header.headerSize.toLong())
            source.readFully(metadata)
        }

        val spans = walkFields(payload, absoluteObjectOffset, order, fields)
        val scriptSpan = spans.singleOrNull { it.name == "m_Script" }
            ?: error("حقل m_Script غير موجود في TextAsset.")

        val newPayload = buildPatchedPayload(payload, spans, scriptSpan, reviewedBytes, absoluteObjectOffset, order)
        val delta = newPayload.size.toLong() - target.byteSize
        require(Math.abs(delta) <= MAX_DELTA_BYTES) { "فرق حجم الكائن يتجاوز الحد الآمن." }
        val newFileSize = Math.addExact(header.fileSize, delta)

        val entries = locateObjectEntryFields(header, typeTable, metadata, order)
        require(entries.size == objectTable.objects.size) { "تعذر مطابقة جدول الكائنات مع مواضع الحقول." }
        val patchedMetadata = patchObjectTable(metadata, entries, target, newPayload.size.toLong(), delta, order)

        val originalNameBytes = spans.singleOrNull { it.name == "m_Name" }
            ?.let { payload.copyOfRange(it.dataOffset, it.dataOffset + it.dataLength) }
            ?: error("حقل m_Name غير موجود في TextAsset.")
        val report = UnityTextAssetPatchReport(
            pathId = pathId,
            oldObjectBytes = target.byteSize,
            newObjectBytes = newPayload.size.toLong(),
            deltaBytes = delta,
            shiftedObjectCount = objectTable.objects.count { it.byteStart > target.byteStart }
        )

        // Never expose a partially written or not-yet-verified SerializedFile.
        // The temporary sibling guarantees the final rename stays on one filesystem.
        val outputParent = output.absoluteFile.parentFile
            ?: error("مجلد الملف الناتج غير صالح.")
        require(outputParent.isDirectory || outputParent.mkdirs()) {
            "تعذر تجهيز مجلد الملف الناتج."
        }
        val temporaryOutput = Files.createTempFile(
            outputParent.toPath(), ".arabify-serialized-", ".tmp"
        ).toFile()
        try {
            writePatchedFile(input, temporaryOutput, header, newFileSize, patchedMetadata,
                absoluteObjectOffset, target.byteSize, newPayload)
            verifyPatchedFile(input, temporaryOutput, header, objectTable, target,
                fields, originalNameBytes, reviewedBytes, newPayload.size.toLong(), delta)
            Files.move(
                temporaryOutput.toPath(),
                output.absoluteFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            return report
        } finally {
            temporaryOutput.delete()
        }
    }

    /** Walks the known string fields exactly like the strict reader, recording spans. */
    private fun walkFields(
        payload: ByteArray,
        absoluteObjectOffset: Long,
        order: ByteOrder,
        fields: List<UnityTextAssetReader.StringField>
    ): List<FieldSpan> {
        val input = ByteBuffer.wrap(payload).order(order)
        val spans = mutableListOf<FieldSpan>()
        fields.forEach { field ->
            require(input.remaining() >= 4) { "حقل TextAsset مبتور." }
            val lengthPrefixOffset = input.position()
            val length = input.int
            require(length in 0..(4 * 1024 * 1024)) { "طول نص TextAsset غير صالح." }
            require(length <= input.remaining()) { "نص TextAsset يتجاوز حجم الكائن." }
            val dataOffset = input.position()
            input.position(dataOffset + length)
            if (field.alignBytes) {
                val absolutePosition = absoluteObjectOffset + input.position().toLong()
                val remainder = (absolutePosition % 4L).toInt()
                val padding = if (remainder == 0) 0 else 4 - remainder
                require(padding <= input.remaining()) { "حشوة محاذاة TextAsset مبتورة." }
                input.position(input.position() + padding)
            }
            spans += FieldSpan(field.name, lengthPrefixOffset, dataOffset, length, input.position())
        }
        require(!input.hasRemaining()) { "كائن TextAsset يحتوي بايتات زائدة غير مفهومة." }
        return spans
    }

    private fun buildPatchedPayload(
        payload: ByteArray,
        spans: List<FieldSpan>,
        scriptSpan: FieldSpan,
        reviewedBytes: ByteArray,
        absoluteObjectOffset: Long,
        order: ByteOrder
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(payload.size + reviewedBytes.size + 16)
        // Everything before the m_Script length prefix (m_Name incl. alignment) is copied verbatim.
        out.write(payload, 0, scriptSpan.lengthPrefixOffset)
        val lengthPrefix = ByteBuffer.allocate(4).order(order).putInt(reviewedBytes.size).array()
        out.write(lengthPrefix, 0, 4)
        out.write(reviewedBytes, 0, reviewedBytes.size)
        val afterScriptAbsolute = absoluteObjectOffset + scriptSpan.lengthPrefixOffset + 4L + reviewedBytes.size
        val scriptPadding = ((4L - (afterScriptAbsolute % 4L)) % 4L).toInt()
        repeat(scriptPadding) { out.write(0) }
        // Anything after m_Script (for example m_PathName) is copied verbatim; the
        // padding above re-aligns it exactly like the original layout did.
        out.write(payload, scriptSpan.fieldEnd, payload.size - scriptSpan.fieldEnd)
        // Pad the total delta to a multiple of 4 so following objects keep their alignment.
        val unpaddedDelta = out.size() - payload.size
        val deltaPadding = ((4 - (unpaddedDelta % 4)) % 4)
        repeat(deltaPadding) { out.write(0) }
        return out.toByteArray()
    }

    /** Mirrors the object-table layout to find the byte positions of each entry's fields. */
    private fun locateObjectEntryFields(
        header: UnitySerializedHeader,
        typeTable: UnitySerializedTypeTable,
        metadata: ByteArray,
        order: ByteOrder
    ): List<EntryFields> {
        val input = ByteBuffer.wrap(metadata).order(order)
        input.position(typeTable.bytesConsumed)

        fun readU32(): Long = input.int.toLong() and 0xffffffffL

        fun alignAbsolute(alignment: Int) {
            val absolute = header.headerSize.toLong() + input.position().toLong()
            val remainder = (absolute % alignment).toInt()
            if (remainder != 0) input.position(input.position() + alignment - remainder)
        }

        val bigIdEnabled = if (header.formatVersion in 7..13) input.int else null
        val objectCount = input.int
        require(objectCount >= 0)
        val entries = ArrayList<EntryFields>(objectCount)
        repeat(objectCount) {
            val pathId = when {
                bigIdEnabled != null && bigIdEnabled != 0 -> input.long
                header.formatVersion < 14 -> input.int.toLong()
                else -> {
                    alignAbsolute(4)
                    input.long
                }
            }
            val byteStartFieldOffset = input.position()
            val byteStart: Long
            val byteStartFieldLength: Int
            if (header.formatVersion >= 22) {
                byteStart = input.long
                byteStartFieldLength = 8
            } else {
                byteStart = readU32()
                byteStartFieldLength = 4
            }
            val byteSizeFieldOffset = input.position()
            val byteSize = readU32()
            input.position(input.position() + 4) // typeId
            if (header.formatVersion < 16) input.position(input.position() + 2) // classId
            if (header.formatVersion < 11) input.position(input.position() + 2) // destroyed
            if (header.formatVersion in 11..16) input.position(input.position() + 2) // scriptTypeIndex
            if (header.formatVersion == 15 || header.formatVersion == 16) input.position(input.position() + 1)
            entries += EntryFields(pathId, byteStart, byteSize,
                byteStartFieldOffset, byteStartFieldLength, byteSizeFieldOffset)
        }
        return entries
    }

    private fun patchObjectTable(
        metadata: ByteArray,
        entries: List<EntryFields>,
        target: UnitySerializedObjectInfo,
        newObjectSize: Long,
        delta: Long,
        order: ByteOrder
    ): ByteArray {
        require(newObjectSize in 1L..0xffffffffL)
        val patched = metadata.copyOf()
        val buffer = ByteBuffer.wrap(patched).order(order)
        entries.forEach { entry ->
            if (entry.pathId == target.pathId) {
                require(entry.byteStart == target.byteStart && entry.byteSize == target.byteSize) {
                    "جدول الكائنات تغيّر بين القراءة والكتابة؛ أُوقفت العملية."
                }
                buffer.putInt(entry.byteSizeFieldOffset, newObjectSize.toInt())
            } else if (entry.byteStart > target.byteStart) {
                val shifted = Math.addExact(entry.byteStart, delta)
                if (entry.byteStartFieldLength == 8) {
                    buffer.putLong(entry.byteStartFieldOffset, shifted)
                } else {
                    require(shifted <= 0xffffffffL) { "إزاحة كائن تتجاوز تمثيل 32 بت." }
                    buffer.putInt(entry.byteStartFieldOffset, shifted.toInt())
                }
            }
        }
        return patched
    }

    private fun writePatchedFile(
        input: File,
        output: File,
        header: UnitySerializedHeader,
        newFileSize: Long,
        patchedMetadata: ByteArray,
        absoluteObjectOffset: Long,
        oldObjectSize: Long,
        newPayload: ByteArray
    ) {
        RandomAccessFile(input, "r").use { source ->
            RandomAccessFile(output, "rw").use { target ->
                target.setLength(0L)
                // Header with patched fileSize.
                val headerBytes = ByteArray(header.headerSize)
                source.seek(0L)
                source.readFully(headerBytes)
                if (header.formatVersion >= 22) {
                    ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN).putLong(24, newFileSize)
                } else {
                    require(newFileSize <= 0xffffffffL) { "حجم الملف الجديد يتجاوز تمثيل 32 بت لهذا الإصدار." }
                    ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                        .putInt(4, newFileSize.toInt())
                }
                target.write(headerBytes)
                target.write(patchedMetadata)
                // Data section: bytes before the target object are copied verbatim.
                copyRange(source, target, header.headerSize.toLong() + header.metadataSize,
                    absoluteObjectOffset)
                target.write(newPayload)
                copyRange(source, target, absoluteObjectOffset + oldObjectSize, header.fileSize)
                require(target.length() == newFileSize) { "حجم الملف الناتج لا يطابق الحساب." }
            }
        }
    }

    private fun copyRange(source: RandomAccessFile, target: RandomAccessFile, from: Long, to: Long) {
        var remaining = to - from
        require(remaining >= 0L)
        source.seek(from)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0L) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            source.readFully(buffer, 0, chunk)
            target.write(buffer, 0, chunk)
            remaining -= chunk
        }
    }

    private fun verifyPatchedFile(
        input: File,
        output: File,
        header: UnitySerializedHeader,
        objectTable: UnitySerializedObjectTable,
        target: UnitySerializedObjectInfo,
        fields: List<UnityTextAssetReader.StringField>,
        originalNameBytes: ByteArray,
        reviewedBytes: ByteArray,
        newObjectSize: Long,
        delta: Long
    ) {
        val inspection = output.inputStream().use {
            UnitySerializedFileInspector.inspect(it, output.length())
        } ?: error("فشل التحقق: تعذر فحص الملف الناتج.")
        require(inspection.objectTable != null) { "فشل التحقق: جدول كائنات الملف الناتج غير مقروء." }
        val newObjects = inspection.objectTable.objects
        require(newObjects.size == objectTable.objects.size) { "فشل التحقق: عدد الكائنات تغيّر." }
        val order = if (header.endian == "little") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        objectTable.objects.forEachIndexed { index, old ->
            val new = newObjects[index]
            require(new.pathId == old.pathId) { "فشل التحقق: ترتيب الكائنات تغيّر." }
            if (old.pathId == target.pathId) {
                require(new.byteStart == old.byteStart && new.byteSize == newObjectSize) {
                    "فشل التحقق: حجم الكائن الهدف لم يُحدّث."
                }
            } else {
                val expectedStart = if (old.byteStart > target.byteStart) old.byteStart + delta else old.byteStart
                require(new.byteStart == expectedStart && new.byteSize == old.byteSize) {
                    "فشل التحقق: إزاحة كائن آخر غير صحيحة."
                }
            }
        }

        // Re-read the target object and compare against the reviewed text (tolerant
        // of up to 3 trailing alignment zeros we may have added).
        val newTarget = newObjects.single { it.pathId == target.pathId }
        val newAbsoluteOffset = Math.addExact(inspection.header.dataOffset, newTarget.byteStart)
        val newPayload = ByteArray(newTarget.byteSize.toInt())
        RandomAccessFile(output, "r").use { source ->
            source.seek(newAbsoluteOffset)
            source.readFully(newPayload)
        }
        verifyPatchedPayload(newPayload, newAbsoluteOffset, order, fields, originalNameBytes, reviewedBytes)

        // Byte-compare every other object payload against the source file.
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        RandomAccessFile(input, "r").use { oldFile ->
            RandomAccessFile(output, "r").use { newFile ->
                objectTable.objects.forEach { old ->
                    if (old.pathId == target.pathId) return@forEach
                    val oldStart = Math.addExact(header.dataOffset, old.byteStart)
                    val newStart = oldStart + (if (old.byteStart > target.byteStart) delta else 0L)
                    var remaining = old.byteSize
                    var offset = 0L
                    while (remaining > 0L) {
                        val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                        val oldBytes = ByteArray(chunk)
                        oldFile.seek(oldStart + offset)
                        oldFile.readFully(oldBytes)
                        newFile.seek(newStart + offset)
                        newFile.readFully(buffer, 0, chunk)
                        require(oldBytes.contentEquals(buffer.copyOf(chunk))) {
                            "فشل التحقق: تغيّرت بايتات كائن غير مستهدف (pathId=${old.pathId})."
                        }
                        remaining -= chunk
                        offset += chunk
                    }
                }
            }
        }
    }

    private fun verifyPatchedPayload(
        payload: ByteArray,
        absoluteObjectOffset: Long,
        order: ByteOrder,
        fields: List<UnityTextAssetReader.StringField>,
        originalNameBytes: ByteArray,
        reviewedBytes: ByteArray
    ) {
        val input = ByteBuffer.wrap(payload).order(order)
        fields.forEach { field ->
            require(input.remaining() >= 4) { "فشل التحقق: حقل مبتور في الكائن الناتج." }
            val length = input.int
            require(length in 0..(4 * 1024 * 1024) && length <= input.remaining()) {
                "فشل التحقق: طول نص غير صالح في الكائن الناتج."
            }
            val bytes = ByteArray(length)
            input.get(bytes)
            when (field.name) {
                "m_Script" -> require(bytes.contentEquals(reviewedBytes)) {
                    "فشل التحقق: النص المكتوب لا يطابق الترجمة المراجعة."
                }
                "m_Name" -> require(bytes.contentEquals(originalNameBytes)) {
                    "فشل التحقق: اسم TextAsset تغيّر أثناء الكتابة."
                }
            }
            if (field.alignBytes) {
                val absolutePosition = absoluteObjectOffset + input.position().toLong()
                val remainder = (absolutePosition % 4L).toInt()
                val padding = if (remainder == 0) 0 else 4 - remainder
                require(padding <= input.remaining()) { "فشل التحقق: حشوة محاذاة مبتورة." }
                input.position(input.position() + padding)
            }
        }
        val trailing = input.remaining()
        require(trailing in 0..3) { "فشل التحقق: بايتات زائدة غير متوقعة في نهاية الكائن." }
        while (input.hasRemaining()) {
            require(input.get().toInt() == 0) { "فشل التحقق: حشوة نهائية غير صفرية." }
        }
    }

    private fun isPlainText(value: String): Boolean = value.none { character ->
        character == '\u0000' ||
            (character.isISOControl() && character !in setOf('\n', '\r', '\t'))
    }
}
