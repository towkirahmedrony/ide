package com.agentx.app.ubuntu

/**
 * Proof that the APK actually shipped a PRoot runtime into [NativeRuntimeLayout.nativeLibraryDir].
 *
 * Source-tree files and a downloaded Ubuntu rootfs are not this check. Android only extracts
 * the packaged `lib/<abi>/` libraries into `applicationInfo.nativeLibraryDir`; if they are
 * missing the APK was built without the native runtime, and Ubuntu must not be downloaded to
 * "fix" it.
 *
 * The ABI directory is named without its library glob on purpose: Kotlin nests block comments, so
 * spelling the glob out would open a second comment here and swallow the rest of this file.
 */
data class ProotSelfTestResult(
    val nativeLibraryDir: String,
    val present: List<String>,
    val missing: List<String>,
    val optionalPresent: List<String>,
    val notExecutable: List<String>,
    val loader: String,
    val proot: String,
    val versionOutput: String? = null,
    val failure: String? = null,
) {
    val ok: Boolean get() = failure == null

    val summary: String
        get() = if (ok) {
            "PRoot self-test passed in $nativeLibraryDir: ${present.joinToString()}"
        } else {
            failure.orEmpty()
        }
}

/**
 * Inspects [NativeRuntimeLayout.nativeLibraryDir] and, when asked, starts `libproot.so -V`.
 *
 * File predicates are injected so the presence/permission rules are unit testable without a
 * device. The live runner is only used on Android.
 */
object ProotSelfTest {

    const val VERSION_FLAG: String = "-V"

    fun inspect(
        layout: NativeRuntimeLayout,
        exists: (String) -> Boolean,
        canExecute: (String) -> Boolean = exists,
    ): ProotSelfTestResult {
        val probe = NativeRuntimeProbe.probe(layout, exists)
        val notExecutable = (probe.present + probe.optionalPresent).filter { name ->
            !canExecute("${layout.nativeLibraryDir}/$name")
        }
        val failure = when {
            probe.missing.isNotEmpty() ->
                NativeRuntimeProbe.MISSING_APK_MESSAGE +
                    " nativeLibraryDir=${layout.nativeLibraryDir}." +
                    " Missing: ${probe.missing.joinToString()}." +
                    " Present: ${probe.present.ifEmpty { listOf("(none)") }.joinToString()}."
            notExecutable.isNotEmpty() ->
                NativeRuntimeProbe.MISSING_APK_MESSAGE +
                    " nativeLibraryDir=${layout.nativeLibraryDir}." +
                    " Not executable: ${notExecutable.joinToString()}."
            else -> null
        }
        return ProotSelfTestResult(
            nativeLibraryDir = layout.nativeLibraryDir,
            present = probe.present,
            missing = probe.missing,
            optionalPresent = probe.optionalPresent,
            notExecutable = notExecutable,
            loader = layout.loader,
            proot = layout.proot,
            failure = failure,
        )
    }

    /**
     * Starts PRoot with `-V` so the binary can actually exec from [layout.nativeLibraryDir].
     *
     * [inspect] is applied first; a missing library never reaches the process start. A spawn
     * failure is reported as a packaging/runtime error, not as a rootfs problem.
     */
    fun run(
        layout: NativeRuntimeLayout,
        exists: (String) -> Boolean,
        canExecute: (String) -> Boolean,
        starter: (ProotInvocation) -> Pair<Int, String>,
    ): ProotSelfTestResult {
        val inspected = inspect(layout, exists, canExecute)
        if (!inspected.ok) return inspected
        return try {
            val invocation = ProotCommand.version(layout)
            val (exit, output) = starter(invocation)
            if (exit != 0 && output.isBlank()) {
                inspected.copy(
                    versionOutput = output,
                    failure = NativeRuntimeProbe.MISSING_APK_MESSAGE +
                        " PRoot at ${layout.proot} could not start (exit $exit)." +
                        " PROOT_LOADER=${layout.loader}.",
                )
            } else {
                inspected.copy(versionOutput = output, failure = null)
            }
        } catch (error: Exception) {
            inspected.copy(
                failure = NativeRuntimeProbe.MISSING_APK_MESSAGE +
                    " PRoot at ${layout.proot} could not start: ${error.message}." +
                    " PROOT_LOADER=${layout.loader}.",
            )
        }
    }
}
