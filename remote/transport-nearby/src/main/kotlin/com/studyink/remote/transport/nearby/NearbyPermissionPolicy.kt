package com.studyink.remote.transport.nearby

import android.Manifest

object NearbyPermissionPolicy {
    fun runtimePermissions(apiLevel: Int): List<String> = buildList {
        if (apiLevel == 31) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (apiLevel >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (apiLevel >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}
