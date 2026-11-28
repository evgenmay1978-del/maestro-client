package com.maestrovpn.client.bg

/**
 * Which packages the tunnel carries.
 *
 * The app's own uid is ALWAYS kept outside the tunnel. The control plane — claim,
 * /sub refresh, panel update.json/APK download, purchase API, account card — must keep
 * working while the tunnel is up but dead. Before this policy the app put itself into the
 * tunnel, so a stale or broken config locked the app out of its only recovery path and the
 * user had to force-stop it (incident 21.10.2026: SEI KP1 on 1.0.168, every claim/sub request
 * died while the panel and CDN were healthy).
 *
 * The same reasoning covers the owner's diagnostic agent (DSHA): while the tunnel is
 * up but dead it is the only channel that can still read the device, so it must not
 * be swallowed by the tunnel either.
 *
 * Pure on purpose: the full mode matrix is unit-tested without Android.
 */
internal object PackagePolicy {
    /**
     * Packages that never enter the tunnel, in any mode.
     *
     * Kept as literals on purpose: the agent's applicationId is not a dependency of
     * this app, and a missing package is simply absent from the device — Android
     * throws NameNotFoundException only for names passed to it, and the caller
     * already skips those.
     */
    val CONTROL_PACKAGES: List<String> = listOf(
        "dsh.android.client",
        "dsh.android.client.fork",
        "com.dsh.client.fork",
    )
    data class Decision(val include: List<String>, val exclude: List<String>) {
        init {
            require(include.isEmpty() || exclude.isEmpty()) {
                "Android allows either the include or the exclude list, never both"
            }
        }
    }

    fun decide(
        appPackage: String,
        perAppEnabled: Boolean,
        includeMode: Boolean,
        appList: Set<String>,
    ): Decision {
        require(appPackage.isNotBlank()) { "app package is required" }
        // A package list that still names the app cannot tunnel it: it is always removed.
        // Control packages are removed the same way, so the include mode cannot pull them in.
        val others = (appList - appPackage - CONTROL_PACKAGES).sorted()
        val outside = (CONTROL_PACKAGES + appPackage).distinct().sorted()
        return when {
            // Include mode: only listed apps are tunneled; our app is simply not listed.
            perAppEnabled && includeMode && others.isNotEmpty() ->
                Decision(include = others, exclude = emptyList())
            // Include mode with nothing selected would tunnel every app — invert to exclude-self.
            perAppEnabled && includeMode -> Decision(include = emptyList(), exclude = outside)
            // Exclude mode: everything is tunneled except the user's bypass list and ourselves.
            perAppEnabled -> Decision(include = emptyList(), exclude = (others + outside).distinct().sorted())
            // No per-app list: tunnel everything except the app itself.
            else -> Decision(include = emptyList(), exclude = outside)
        }
    }
}
