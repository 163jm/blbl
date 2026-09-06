package blbl.cat3399.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdaterVersionTest {
    @Test
    fun isRemoteNewer_should_detect_newer_patch_version() {
        assertTrue(ApkUpdater.isRemoteNewer(remoteVersionName = "0.1.30", currentVersionName = "0.1.29"))
    }

    @Test
    fun isRemoteNewer_should_reject_equal_version() {
        assertFalse(ApkUpdater.isRemoteNewer(remoteVersionName = "0.1.29", currentVersionName = "0.1.29"))
    }

    @Test
    fun isRemoteNewer_should_reject_older_version() {
        assertFalse(ApkUpdater.isRemoteNewer(remoteVersionName = "0.1.9", currentVersionName = "0.1.29"))
    }

    @Test
    fun isRemoteNewer_should_accept_bracketed_v_prefix() {
        assertTrue(ApkUpdater.isRemoteNewer(remoteVersionName = "v1.2.0", currentVersionName = "1.1.9"))
    }

    @Test
    fun isRemoteNewer_should_compare_by_numeric_segments_not_length() {
        // 1.2.10 先出现在字符串排序会误判更小，这里确保按数值分段比较。
        assertTrue(ApkUpdater.isRemoteNewer(remoteVersionName = "1.2.10", currentVersionName = "1.2.9"))
    }

    @Test
    fun apkUrlFor_should_strip_v_prefix_and_use_jsdelivr_release_branch() {
        val url = ApkUpdater.apkUrlFor("v0.1.30")
        assertTrue(url.startsWith("https://cdn.jsdelivr.net/gh/163jm/blbl@release/"))
        assertTrue(url.endsWith("blbl-0.1.30-release.apk"))
    }
}
