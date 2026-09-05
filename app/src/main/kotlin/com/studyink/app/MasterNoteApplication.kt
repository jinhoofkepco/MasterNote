package com.studyink.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import com.studyink.library.ui.MasterNoteBackupCoordinator

/** Tracks the whole app rather than one Activity, so screen transitions never trigger backups. */
class MasterNoteApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        // AndroidX PDF renders documents in an isolated service process. That process receives
        // this Application class too, but it has neither app-private SharedPreferences nor the
        // normal files directory. Initializing backup storage there crashes the PDF renderer and
        // leaves ReaderActivity showing only its paper background.
        if (Process.isIsolated() || getProcessName() != packageName) return
        MasterNoteBackupCoordinator.initialize(this)
        MasterNoteRemoteMonitorCoordinator.initialize(this)
        MasterNoteRemoteReviewCoordinator.initialize(this)
        MasterNoteConstructionSyncCoordinator.initialize(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount++ == 0) {
            MasterNoteBackupCoordinator.onAppForeground()
            RemoteMonitorService.startIfEnabled(this)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
            MasterNoteBackupCoordinator.onAppBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
