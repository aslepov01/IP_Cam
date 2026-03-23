package com.ipcam.coretests

import com.ipcam.testsupport.DeviceTestEnvironment
import org.junit.After
import org.junit.Before

abstract class BaseDeviceCoreTest {
    protected lateinit var env: DeviceTestEnvironment

    @Before
    fun baseSetUp() {
        env = DeviceTestEnvironment()
        env.startFreshApp()
    }

    @After
    fun baseTearDown() {
        env.shutdownAndReset()
    }
}
