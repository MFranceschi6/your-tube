package com.yourtube.app.haptics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestVibratePermissionTest {

    @Test
    fun manifest_declares_vibrate_permission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android.permission.VIBRATE",
            manifest.contains("android.permission.VIBRATE"),
        )
    }
}
