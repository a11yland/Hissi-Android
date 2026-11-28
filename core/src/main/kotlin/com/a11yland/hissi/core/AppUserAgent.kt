package com.a11yland.hissi.core

// The User-Agent of every request the app makes: "Hissi/1.5 (Android)".
// Without it Ktor sends its default, "ktor-client" — no app, no version. A
// data provider should be able to tell whose requests these are, and which
// version sent them. Same shape as Hissi-iOS ("Hissi/1.5.0 (iOS)"). Pure,
// unit-tested; ported from `Shared/AppUserAgent.swift`.
object AppUserAgent {
    const val PLATFORM = "Android"

    // A missing version still names the app.
    fun make(version: String?, platform: String = PLATFORM): String =
        if (version.isNullOrEmpty()) "Hissi ($platform)" else "Hissi/$version ($platform)"
}
