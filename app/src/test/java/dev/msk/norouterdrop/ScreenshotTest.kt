package dev.msk.norouterdrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Draws the three screens with sample data into app/build/screenshots (a JVM render, not a phone). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private fun shot(name: String, tab: Int) {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val act = ctl.get()
        act.previewState(
            listOf(TrustedPeer("p1", "Honor X8d", "5e:d2:6b:6c:46:ce", "k")),
            listOf(PeerInfo("5e:d2:6b:6c:46:ce", "HONOR X8d", true), PeerInfo("aa:bb:cc:dd:ee:ff", "Pixel 8", true)),
            listOf(Selected(Uri.parse("content://x/1"), null, "IMG_20261122_133233.jpg"), Selected(null, "hello", "Text-1.txt")),
            "NoRouterDrop is on", tab)
        ShadowLooper.idleMainLooper()
        val v = act.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2340)
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots"); dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun darkReceive() = shot("dark-receive", 0)
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun darkSend() = shot("dark-send", 1)
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun darkSettings() = shot("dark-settings", 2)
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xxhdpi") @Test fun lightReceive() = shot("light-receive", 0)
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xxhdpi") @Test fun lightSend() = shot("light-send", 1)
}
