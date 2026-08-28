package com.ctl.metaglasses.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceQualityGateInstrumentedTest {

    @Test
    fun smallFaceFailsClosed() {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(128, 128, 128))

        val quality = FaceQuality.evaluate(bitmap, 96)

        assertFalse(quality.passes)
        assertTrue(quality.reasons.contains("face-too-small"))
    }

    @Test
    fun flatLowDetailFaceFailsClosed() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(128, 128, 128))

        val quality = FaceQuality.evaluate(bitmap, 96)

        assertFalse(quality.passes)
        assertTrue(quality.reasons.contains("low-contrast-or-blurry"))
    }
}
