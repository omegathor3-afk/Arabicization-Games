package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnitySerializedTrailingMetadataParserTest {
    private data class Fixture(
        val header: UnitySerializedHeader,
        val metadata: ByteArray,
        val prefix: UnitySerializedMetadataPrefix,
        val objects: UnitySerializedObjectTable
    )

    private fun fixture(version: Int = 22, withSharedSubtree: Boolean = false): Fixture {
        val out = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        out.put("2022.3.0f1".toByteArray(Charsets.UTF_8))
        out.put(0)
        out.putInt(13)
        out.put(0) // TypeTree disabled
        out.putInt(1)
        out.putInt(1) // SerializedType class ID
        out.put(0) // stripped
        out.putShort(0) // script type index
        out.put(ByteArray(16) { 0x44 }) // old type hash
        out.putInt(1) // object count
        fun alignAbsolute(alignment: Int) {
            while ((48L + out.position()) % alignment != 0L) out.put(0x7f)
        }
        alignAbsolute(4)
        out.putLong(11)
        out.putLong(0)
        out.putInt(16)
        out.putInt(0)

        // LocalSerializedObjectIdentifier
        out.putInt(1)
        out.putInt(0)
        alignAbsolute(4)
        out.putLong(999)

        // FileIdentifier
        out.putInt(1)
        out.put(0) // temporary empty string
        out.put(ByteArray(16) { (it + 1).toByte() })
        out.putInt(2)
        out.put("archive:/CAB-123/resource.assets".toByteArray(Charsets.UTF_8))
        out.put(0)

        // Reference SerializedType
        out.putInt(1)
        out.putInt(114)
        out.put(0)
        out.putShort(0)
        out.put(ByteArray(16) { 0x55 }) // script ID
        out.put(ByteArray(16) { 0x66 }) // old type hash

        if (version >= 26 && withSharedSubtree) {
            out.putInt(1)
            out.put(ByteArray(16) { 0x77 })
            out.putInt(0) // empty TypeTreeNodeInfoHandler payload
        } else if (version >= 26) {
            out.putInt(0)
        }
        out.put("metadata-fixture".toByteArray(Charsets.UTF_8))
        out.put(0)

        val metadata = out.array().copyOf(out.position())
        val header = UnitySerializedHeader(
            metadata.size.toLong(), 4096, version, 2048, "little", 48
        )
        val prefix = UnitySerializedMetadataPrefixParser.parse(header, metadata)!!
        val types = UnitySerializedTypeTableParser.parse(header, prefix, metadata)!!
        val objects = UnitySerializedObjectTableParser.parse(header, types, metadata)!!
        return Fixture(header, metadata, prefix, objects)
    }

    @Test fun parsesScriptsExternalsReferenceTypesAndUserInformation() {
        val fixture = fixture()
        val parsed = UnitySerializedTrailingMetadataParser.parse(
            fixture.header, fixture.prefix, fixture.metadata, fixture.objects
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.scriptReferences.size)
        assertEquals(0, parsed.scriptReferences.single().localSerializedFileIndex)
        assertEquals(999L, parsed.scriptReferences.single().localIdentifierInFile)
        assertEquals(1, parsed.externals.size)
        assertEquals("archive:/CAB-123/resource.assets", parsed.externals.single().path)
        assertEquals(16, parsed.externals.single().guid!!.size)
        assertEquals(2, parsed.externals.single().type)
        assertEquals(1, parsed.referenceTypes.size)
        assertEquals(114, parsed.referenceTypes.single().classId)
        assertEquals(16, parsed.referenceTypes.single().scriptId!!.size)
        assertEquals("metadata-fixture", parsed.userInformation)
        assertEquals(0, parsed.unknownTailBytes)
    }

    @Test fun parsesVersion26SharedTypeTreeHandler() {
        val fixture = fixture(version = 26, withSharedSubtree = true)
        val parsed = UnitySerializedTrailingMetadataParser.parse(
            fixture.header, fixture.prefix, fixture.metadata, fixture.objects
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.sharedSubtreeHandlers.size)
        assertEquals(0L, parsed.sharedSubtreeHandlers.single().serializedSize)
        assertEquals(null, parsed.sharedSubtreeHandlers.single().info)
        assertEquals("metadata-fixture", parsed.userInformation)
    }

    @Test fun rejectsTruncatedExternalReferencesAndMalformedCounts() {
        val fixture = fixture()
        assertNull(UnitySerializedTrailingMetadataParser.parse(
            fixture.header, fixture.prefix, fixture.metadata.copyOf(fixture.metadata.size - 1),
            fixture.objects
        ))

        val malformed = fixture.metadata.copyOf()
        val externalCountOffset = fixture.objects.bytesConsumed + 4 + 4 + 4 + 8
        // The external count immediately follows the script reference.
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(externalCountOffset, Int.MAX_VALUE)
        assertNull(UnitySerializedTrailingMetadataParser.parse(
            fixture.header, fixture.prefix, malformed, fixture.objects
        ))
    }
}
