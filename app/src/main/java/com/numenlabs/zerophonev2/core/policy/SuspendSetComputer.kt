package com.numenlabs.zerophonev2.core.policy

/**
 * Pure Kotlin suspend-set computation (adapted from ZeroPhone V1 SuspendPolicy).
 *
 * Rules:
 *  - never suspend the app itself;
 *  - only suspend packages explicitly marked as distracting;
 *  - never suspend explicitly protected packages (critical system apps,
 *    packages the platform refuses to suspend anyway);
 *  - never suspend packages matching a protected prefix (defensive: any com.android.*).
 */
object SuspendSetComputer {
    val DEFAULT_PROTECTED_PACKAGES: Set<String> =
        setOf(
            "com.android.phone",
            "com.android.dialer",
            "com.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.android.installer",
            "com.android.permissioncontroller",
        )

    val DEFAULT_PROTECTED_PREFIXES: Set<String> = setOf("com.android.")

    fun computeSuspendSet(
        distractingPackages: Set<String>,
        selfPackage: String,
        protectedPackages: Set<String> = DEFAULT_PROTECTED_PACKAGES,
        protectedPrefixes: Set<String> = DEFAULT_PROTECTED_PREFIXES,
    ): Set<String> =
        distractingPackages
            .asSequence()
            .filter { it != selfPackage }
            .filter { it !in protectedPackages }
            .filter { pkg -> protectedPrefixes.none { prefix -> pkg.startsWith(prefix) } }
            .toSet()

    /**
     * Packages we suspended earlier that must be released now: only members of
     * the previous suspend set that left the new one — never packages the
     * policy did not suspend itself.
     */
    fun computeReleaseSet(
        lastSuspended: Set<String>,
        suspendSet: Set<String>,
    ): Set<String> = lastSuspended - suspendSet

    /** Deactivation catch-all: everything we might have ever suspended. */
    fun computeFullReleaseSet(
        lastSuspended: Set<String>,
        distractingPackages: Set<String>,
        activeGrantPackage: String? = null,
    ): Set<String> = buildSet {
        addAll(lastSuspended)
        addAll(distractingPackages)
        activeGrantPackage?.let { add(it) }
    }
}
