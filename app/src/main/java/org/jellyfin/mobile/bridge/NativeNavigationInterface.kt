package org.jellyfin.mobile.bridge

import android.webkit.JavascriptInterface
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Navigation entry points of the native shell (downloads, settings, server selection, exit),
 * exposed to the web view as `window.NativeNavigation`.
 */
class NativeNavigationInterface : KoinComponent {
    private val activityEventHandler: ActivityEventHandler = get()

    @JavascriptInterface
    fun openDownloadManager() {
        activityEventHandler.emit(ActivityEvent.OpenDownloads)
    }

    @JavascriptInterface
    fun openClientSettings() {
        activityEventHandler.emit(ActivityEvent.OpenSettings)
    }

    @JavascriptInterface
    fun openServerSelection() {
        activityEventHandler.emit(ActivityEvent.SelectServer)
    }

    @JavascriptInterface
    fun exitApp() {
        activityEventHandler.emit(ActivityEvent.ExitApp)
    }
}
