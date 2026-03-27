package com.ipcam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticModelsTest {

    @Before
    fun resetDeviceBrand() {
        ShadowBuild.setManufacturer("Google")
    }

    // Non-Device-Owner apps cannot trigger reboot; the blocking reason must mention "Device Owner".
    @Test
    fun createWhenNotDeviceOwnerBlocksReboot() {
        val d = RebootDiagnostics.create(
            isDeviceOwner = false,
            isDeviceAdmin = true,
            isDeviceLocked = false,
            selinuxStatus = "enforcing",
            knoxVersion = null
        )
        assertFalse(d.rebootPossible)
        assertTrue(d.blockingReason!!.contains("Device Owner", ignoreCase = true))
    }

    // A locked keyguard must block reboot even when the app is Device Owner.
    @Test
    fun createWhenDeviceLockedBlocksReboot() {
        val d = RebootDiagnostics.create(
            isDeviceOwner = true,
            isDeviceAdmin = true,
            isDeviceLocked = true,
            selinuxStatus = "enforcing",
            knoxVersion = null
        )
        assertFalse(d.rebootPossible)
        assertTrue(d.blockingReason!!.contains("locked", ignoreCase = true))
    }

    // Samsung devices with active Knox MDM must block reboot and mention Knox in the reason.
    @Test
    fun createWhenSamsungWithKnoxBlocksReboot() {
        ShadowBuild.setManufacturer("samsung")
        val d = RebootDiagnostics.create(
            isDeviceOwner = true,
            isDeviceAdmin = true,
            isDeviceLocked = false,
            selinuxStatus = "enforcing",
            knoxVersion = "1.2.3"
        )
        assertFalse(d.rebootPossible)
        assertTrue(d.blockingReason!!.contains("Knox", ignoreCase = true))
    }

    // Happy path: Device Owner, unlocked, no Knox — reboot must be allowed with no blocking reason.
    @Test
    fun createHappyPathAllowsReboot() {
        val d = RebootDiagnostics.create(
            isDeviceOwner = true,
            isDeviceAdmin = true,
            isDeviceLocked = false,
            selinuxStatus = "enforcing",
            knoxVersion = null
        )
        assertTrue(d.rebootPossible)
        assertNull(d.blockingReason)
    }

    // Only the Success variant must report isSuccess()=true; all others must be false.
    @Test
    fun rebootResultSuccessFlag() {
        assertTrue(RebootResult.Success.isSuccess())
        assertFalse(RebootResult.NotDeviceOwner.isSuccess())
    }

    // AllMethodsFailed.toJson() must include all three method errors and a nested diagnostics object.
    @Test
    fun allMethodsFailedJsonStructure() {
        ShadowBuild.setManufacturer("Google")
        val diag = RebootDiagnostics.create(true, true, false, "enforcing", null)
        val r = RebootResult.AllMethodsFailed("a", "b", "c", diag)
        val j = JSONObject(r.toJson())
        assertEquals("error", j.getString("status"))
        assertEquals("all_methods_failed", j.getString("error"))
        assertEquals("a", j.getString("method1"))
        assertEquals("b", j.getString("method2"))
        assertEquals("c", j.getString("method3"))
        assertTrue(j.getJSONObject("diagnostics").getBoolean("rebootPossible"))
    }

    // Samsung without Knox: the Knox check must not block when knoxVersion is null.
    @Test
    fun createSamsungWithoutKnoxAllowsReboot() {
        ShadowBuild.setManufacturer("samsung")
        val d = RebootDiagnostics.create(true, true, false, "enforcing", null)
        assertTrue(d.rebootPossible)
        assertNull(d.blockingReason)
    }

    // Exhaustive check: every non-Success sealed variant must return isSuccess()=false.
    @Test
    fun allNonSuccessVariantsReturnIsSuccessFalse() {
        assertFalse(RebootResult.DeviceLocked.isSuccess())
        assertFalse(RebootResult.SecurityException("err", "method1").isSuccess())
        val diag = RebootDiagnostics.create(true, true, false, "enforcing", null)
        assertFalse(RebootResult.AllMethodsFailed("a", "b", "c", diag).isSuccess())
    }

    // Every RebootResult sealed variant must produce valid JSON with a correct status and error code.
    @Test
    fun eachVariantProducesValidJson() {
        val successJson = JSONObject(RebootResult.Success.toJson())
        assertEquals("ok", successJson.getString("status"))
        assertTrue(successJson.has("message"))

        val notOwnerJson = JSONObject(RebootResult.NotDeviceOwner.toJson())
        assertEquals("error", notOwnerJson.getString("status"))
        assertEquals("not_device_owner", notOwnerJson.getString("error"))

        val lockedJson = JSONObject(RebootResult.DeviceLocked.toJson())
        assertEquals("error", lockedJson.getString("status"))
        assertEquals("device_locked", lockedJson.getString("error"))

        val secJson = JSONObject(RebootResult.SecurityException("msg", "meth").toJson())
        assertEquals("error", secJson.getString("status"))
        assertEquals("security_exception", secJson.getString("error"))
        assertEquals("msg", secJson.getString("message"))
        assertEquals("meth", secJson.getString("method"))
    }
}
