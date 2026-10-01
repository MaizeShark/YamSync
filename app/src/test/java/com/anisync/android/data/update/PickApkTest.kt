package com.anisync.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickApkTest {
    private val release = listOf(
        "YamSync-v1.0.0-armeabi-v7a-release.apk",
        "YamSync-v1.0.0-arm64-v8a-release.apk",
        "YamSync-v1.0.0-x86-release.apk",
        "YamSync-v1.0.0-x86_64-release.apk",
        "YamSync-v1.0.0-universal-release.apk"
    )

    @Test
    fun `a phone gets its preferred ABI`() {
        assertEquals(
            "YamSync-v1.0.0-arm64-v8a-release.apk",
            pickApk(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        )
    }

    @Test
    fun `x86 does not match the x86_64 build`() {
        assertEquals("YamSync-v1.0.0-x86-release.apk", pickApk(release, listOf("x86")))
        assertEquals("YamSync-v1.0.0-x86_64-release.apk", pickApk(release, listOf("x86_64", "x86")))
    }

    @Test
    fun `an unknown ABI falls back to the universal build`() {
        assertEquals("YamSync-v1.0.0-universal-release.apk", pickApk(release, listOf("riscv64")))
    }

    @Test
    fun `a single unnamed APK is still used, and none means none`() {
        assertEquals("app.apk", pickApk(listOf("app.apk"), listOf("arm64-v8a")))
        assertNull(pickApk(emptyList(), listOf("arm64-v8a")))
    }
}
