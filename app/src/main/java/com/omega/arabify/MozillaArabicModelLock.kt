package com.omega.arabify

/**
 * Locked Mozilla Remote Settings records for the Arabic direction pair.
 * Hashes below are the SHA-256 of the downloaded .zst attachment AND the
 * decompressed payload hash published by Mozilla. They are not yet proof
 * that the native runtime can load model v3.0 on Android.
 */
internal object MozillaArabicModelLock {
    const val collectionUrl = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models-v2/records"
    const val engineRepository = "https://github.com/browsermt/bergamot-translator"
    const val engineCommit = "9271618ebbdc5d21ac4dc4df9e72beb7ce644774"
    const val engineLicense = "MPL-2.0"
    const val modelLicense = "MPL-2.0"
    const val modelVersion = "3.0"
    const val architecture = "base-memory"

    data class FileRecord(
        val name: String,
        val direction: String,
        val type: String,
        val compressedBytes: Long,
        val compressedSha256: String,
        val decompressedBytes: Long,
        val decompressedSha256: String,
        val registryLocation: String
    )

    val files = listOf(
        FileRecord("model.enar.intgemm.alphas.bin.zst", "en-ar", "model", 22272394L,
            "41dc919c1110508ad7f03ee53bbb28a54abd61d1b15bbc3fe51c5df5c9331016",
            31561787L, "ae659d7045fc2e5d6ba50583586a5d94ee4358d92c019847e37b57a0e627faa8",
            "main-workspace/translations-models-v2/0d56ac93-c0ac-4854-b48e-09e38d51dace.zst"),
        FileRecord("lex.50.50.enar.s2t.bin.zst", "en-ar", "lex", 1204697L,
            "f5fe1d8d145e38b4c6fd9f7a35d0d5779388fa135743561e1d25e982d7191222",
            3139692L, "2b7194817c5dd9225ca90c0d908cc92e1d6d1f45625781a5feb037ac568d6a61",
            "main-workspace/translations-models-v2/6b7c1061-729a-44e3-858d-409a7061a704.zst"),
        FileRecord("vocab.enar.spm.zst", "en-ar", "vocab", 351089L,
            "9f68a65a64d4c64ea41d94024160e1443ff51b6c7ba0a2f93e7702beaa0b1d25",
            863591L, "8d93c54aa5e2044c416ec680b5ff9af0227bd698521666e8b1a1ea1b041fbae8",
            "main-workspace/translations-models-v2/371728a2-8c02-4528-bd76-4cd1c4e8f05e.zst"),
        FileRecord("model.aren.intgemm.alphas.bin.zst", "ar-en", "model", 22770191L,
            "beed6b0da7378dfe256a9c0604a0d07dcb2b9ad4ce5295ac1ea6f1c5815eed65",
            31561787L, "7b7af0282dc5f4d8805b9a298c2fa828967e3f09ca10f1942ebeea0b2cfc12fa",
            "main-workspace/translations-models-v2/c1fc44d3-7b80-415e-9b74-57c4ee027559.zst"),
        FileRecord("lex.50.50.aren.s2t.bin.zst", "ar-en", "lex", 1623998L,
            "0dc3d51a8e41da0ee6f53784e04b2cd044e20e15f15b09aa9c1d96047de803b4",
            4627200L, "4b45f14dbea40d368093a13563fc1ca48457ee70c1291c82b206f3baff210081",
            "main-workspace/translations-models-v2/5fef8e32-9215-4eda-b63d-b4105b9820f6.zst"),
        FileRecord("vocab.aren.spm.zst", "ar-en", "vocab", 349058L,
            "4e7256a9b84ae2773a5b32a234418d1c111a018b9e1bf2580ad83972d5166687",
            860035L, "51b4ee3f828d10015464523d0b4f5a6c086b0b2a9bde716a581001cf6c260366",
            "main-workspace/translations-models-v2/324af319-7174-4574-a0e5-dc047a78aa33.zst")
    )

    fun isComplete(): Boolean = listOf("en-ar", "ar-en").all { direction ->
        val pair = files.filter { it.direction == direction }
        pair.size == 3 && pair.map { it.type }.toSet() == setOf("model", "lex", "vocab")
    }
}
