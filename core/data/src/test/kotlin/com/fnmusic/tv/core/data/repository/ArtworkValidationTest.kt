package com.fnmusic.tv.core.data.repository

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArtworkValidationTest {
    @Test fun `header-only image with valid bounds cannot enter cache`() {
        val valid = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).run {
                try {
                    assertTrue(compress(Bitmap.CompressFormat.PNG, 100, output))
                } finally {
                    recycle()
                }
            }
            output.toByteArray()
        }
        val idatType = valid.findAscii("IDAT")
        val headerOnly = valid.copyOf(idatType - Integer.BYTES)
        assertTrue(isValidArtworkBytes(valid))
        assertFalse(isValidArtworkBytes(headerOnly))
        assertFalse(
            isValidArtworkBytes(
                bytes = headerOnly,
                readBounds = { ArtworkBounds(width = 8, height = 8) },
                decodeSampled = { _, _ -> false },
            ),
        )
    }

    private fun ByteArray.findAscii(value: String): Int {
        val target = value.encodeToByteArray()
        return indices.firstOrNull { start ->
            start + target.size <= size && target.indices.all { offset -> this[start + offset] == target[offset] }
        } ?: error("$value chunk not found")
    }
}
