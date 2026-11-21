package com.a11yland.hissi.data

import com.a11yland.hissi.BuildConfig

// Demo mode for the Play screenshots — the Android counterpart of the iOS
// DemoMode: the catalog and every status request answer from DemoFixtures
// (the bundled seed with fixed statuses) instead of the API, so the shots
// are reproducible and need no token. Switched on only by the screenshot
// tests (app/src/androidTest), before the first catalog access; release
// builds ignore it.
object DemoMode {
    @Volatile
    var requested: Boolean = false

    val isActive: Boolean get() = BuildConfig.DEBUG && requested
}
