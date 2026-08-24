package com.fnmusic.tv.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateInstallerTest {
    @Test fun `installer delegates verified apk with dynamic provider authority`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apk = File(context.cacheDir, "updates/candidate.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        var receivedContext: Context? = null
        var receivedAuthority: String? = null
        var receivedApk: File? = null
        val installer = UpdateInstaller(context) { actualContext, authority, actualApk ->
            receivedContext = actualContext
            receivedAuthority = authority
            receivedApk = actualApk
        }

        installer.install(apk)

        assertSame(context, receivedContext)
        assertEquals("${context.packageName}.fileProvider", receivedAuthority)
        assertSame(apk, receivedApk)
        apk.delete()
    }
}
