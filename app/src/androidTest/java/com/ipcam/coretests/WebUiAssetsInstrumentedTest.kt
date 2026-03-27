package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ipcam.BuildConfig
import com.ipcam.testsupport.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebUiAssetsInstrumentedTest : BaseDeviceCoreTest() {

    private fun contentType(resp: HttpResponse): String =
        resp.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull().orEmpty()

    // Verifies that / and /index.html serve resolved HTML (no {{ placeholders left), reference
    // CSS/JS assets, and include the build version name from BuildConfig.
    @Test
    fun rootAndIndexHtmlServeResolvedHtmlWithAssetReferences() {
        for (path in listOf("/", "/index.html")) {
            val r = env.httpGet(path)
            assertEquals(200, r.statusCode)
            val ct = contentType(r)
            assertTrue("Expected HTML for $path, Content-Type=$ct", ct.contains("text/html", ignoreCase = true))
            val body = r.bodyText()
            assertFalse("Template placeholders must be resolved for $path", body.contains("{{"))
            assertTrue(body.contains("styles.css", ignoreCase = true))
            assertTrue(body.contains("script.js", ignoreCase = true))
            assertTrue("HTML should contain version name for $path",
                body.contains(BuildConfig.VERSION_NAME))
        }
    }

    // Verifies that bundled CSS and JS assets are served with correct Content-Type headers
    // and are non-trivially sized (not empty stub files).
    @Test
    fun staticCssAndJsUseExpectedContentTypes() {
        val css = env.httpGet("/styles.css")
        assertEquals(200, css.statusCode)
        assertTrue(contentType(css).contains("css", ignoreCase = true))
        assertTrue(css.bodyText().length > 50)

        val js = env.httpGet("/script.js")
        assertEquals(200, js.statusCode)
        val jsCt = contentType(js)
        assertTrue(
            "Expected JavaScript content type, got $jsCt",
            jsCt.contains("javascript", ignoreCase = true) || jsCt.contains("ecmascript", ignoreCase = true)
        )
        assertTrue(js.bodyText().length > 50)
    }

    // Requesting a non-existent asset path must return 404, not a fallback or 200.
    @Test
    fun missingBundledAssetPathReturns404() {
        val r = env.httpGet("/this-asset-does-not-exist-xyz.css", expectedCode = null)
        assertEquals(404, r.statusCode)
    }
}
