package com.a11yland.hissi.core

// Decides which onboarding sheet a launch shows: the full welcome once per
// install, afterwards a slim "Was ist neu" only for releases with curated
// notes — bugfix releases stay silent. Pure; the app feeds it the persisted
// state and the running version. Ported from `Shared/WelcomeGate.swift`.
object WelcomeGate {
    sealed interface Sheet {
        data object Welcome : Sheet
        data class WhatsNew(val version: String) : Sheet
    }

    // Releases with curated notes; the note content lives with the UI — add
    // the version here and its content in `WhatsNewContent` together.
    val curatedVersions: Set<String> = emptySet()

    fun sheet(
        hasSeenWelcome: Boolean,
        shownWhatsNewVersion: String?,
        currentVersion: String,
        curatedVersions: Set<String> = this.curatedVersions,
    ): Sheet? {
        if (!hasSeenWelcome) return Sheet.Welcome
        if (currentVersion !in curatedVersions) return null
        if (shownWhatsNewVersion == currentVersion) return null
        return Sheet.WhatsNew(version = currentVersion)
    }
}
