package com.studyink.remote.transport.nearby

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionPolicyTest {
    @Test fun api31UsesLocationWhileApi32PlusUsesNearbyWifi() {
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in NearbyPermissionPolicy.runtimePermissions(31))
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in NearbyPermissionPolicy.runtimePermissions(31))
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in NearbyPermissionPolicy.runtimePermissions(32))
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in NearbyPermissionPolicy.runtimePermissions(32))
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in NearbyPermissionPolicy.runtimePermissions(33))
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in NearbyPermissionPolicy.runtimePermissions(36))
    }
}
