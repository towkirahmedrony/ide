// JVM harness that executes the assertions added in Part 2 against the real
// TermuxBootstrapArchive / TermuxBootstrapCatalog sources, without the Android SDK.
// It mirrors termux-runtime's TermuxBootstrapArchiveTest and
// TermuxBootstrapCatalogTest: those tests need an Android unit-test runtime, the pure
// logic under test does not.

import com.agentx.app.termux.TermuxBootstrapArchive
import com.agentx.app.termux.TermuxBootstrapCatalog
import com.agentx.app.termux.TermuxPaths

var failures = 0
var checks = 0

fun check(name: String, condition: Boolean) {
    checks++
    if (condition) {
        println("  PASS  $name")
    } else {
        failures++
        println("  FAIL  $name")
    }
}

fun main() {
    val staging = "/data/data/com.agentx.app/files/usr-staging"
    val final = "/data/data/com.agentx.app/files/usr"

    println("TermuxBootstrapArchive: prefix resolution")
    check(
        "real shape 1: bare-name target (bin/ls -> coreutils) is accepted",
        TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/ls", "coreutils"),
    )
    check(
        "real shape 2: relative target with .. inside the prefix is accepted",
        TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "include/ncursesw/term.h", "../term.h"),
    )
    check(
        "real shape 3: absolute target into the final prefix is accepted",
        TermuxBootstrapArchive.isSafeSymlinkTarget(
            staging, final, "share/pacman/keyrings/mradityaalok.gpg",
            "$final/share/termux-keyring/mradityaalok.gpg",
        ),
    )
    check(
        "absolute target into the staging prefix is accepted",
        TermuxBootstrapArchive.isSafeSymlinkTarget(
            staging, final, "share/pacman/keyrings/mradityaalok.gpg",
            "$staging/share/termux-keyring/mradityaalok.gpg",
        ),
    )
    check(
        "official Termux prefix absolute target is still refused",
        !TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/sh", "/data/data/com.termux/files/usr/bin/sh"),
    )
    check(
        "absolute target outside both prefixes is refused",
        !TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/sh", "/etc/passwd"),
    )
    check(
        "a traversal that climbs above the prefix root is refused",
        !TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/sh", "../../escape"),
    )
    check(
        "a traversal that normalises away is accepted",
        TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "lib/libssl.so", "../lib/libssl.so.3"),
    )
    check(
        "escaping link path is still refused",
        TermuxBootstrapArchive.resolveSymlink(staging, "../bin/sh") == null,
    )

    println("TermuxBootstrapArchive: a real manifest parses without dropping a link")
    val manifest = listOf(
        "coreutils\u2190./bin/ls",
        "../ncurses.h\u2190./include/ncursesw/term.h",
        "$final/share/termux-keyring/mradityaalok.gpg\u2190./share/pacman/keyrings/mradityaalok.gpg",
    ).joinToString("\n") + "\n"
    val parsed = TermuxBootstrapArchive.parseSymlinks(manifest, staging, final)
    check("no line is dropped: invalid is empty", parsed.invalid.isEmpty())
    check("all three links are parsed", parsed.links.size == 3)
    check("manifest is usable", parsed.isUsable)

    val evil = TermuxBootstrapArchive.parseSymlinks("/etc/passwd\u2190bin/evil\n", staging, final)
    check("absolute target outside the prefix is reported, not skipped", evil.invalid.isNotEmpty())
    check("no link is created for it", evil.links.isEmpty())

    val malformed = TermuxBootstrapArchive.parseSymlinks("bin/sh\nbin/bash\u2190bin/bash\n", staging, final)
    check("malformed line reported", malformed.invalid.size == 1)
    check("well-formed line kept", malformed.links.size == 1)

    println("TermuxBootstrapCatalog: staging and published-entry contracts")
    check(
        "all four ABIs are catalogued",
        TermuxBootstrapCatalog.entries.map { it.androidAbi }.toSet() ==
            setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
    )
    for (entry in TermuxBootstrapCatalog.entries) {
        check("${entry.androidAbi}: targets the agentx prefix", entry.prefix == TermuxPaths.AGENTX_PREFIX)
        check("${entry.androidAbi}: asset name is per-ABI", entry.assetName == "bootstrap-${entry.termuxArch}.zip")
        check("${entry.androidAbi}: unbuilt entry is unavailable", !entry.available)
        check(
            "${entry.androidAbi}: unbuilt entry publishes nothing",
            entry.url == null && entry.sha256 == null &&
                entry.archiveSizeBytes == null && entry.fileCount == null,
        )
    }

    val pending = TermuxBootstrapCatalog.forAbi("arm64-v8a")!!
    val ready = pending.copy(
        url = "https://github.com/example/releases/download/agentx-bootstrap-r1/bootstrap-aarch64.zip",
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        archiveSizeBytes = 12L,
        fileCount = 4,
        sourceRevision = "termux-packages@2fdb0c07f3fec34adf24c8af515c852fc51f4c9b+agentx",
    )
    check("a complete entry is available", ready.available)
    check("missing url makes it unavailable", !ready.copy(url = null).available)
    check("blank url makes it unavailable", !ready.copy(url = "  ").available)
    check("missing digest makes it unavailable", !ready.copy(sha256 = null).available)
    check("short digest makes it unavailable", !ready.copy(sha256 = "abc").available)
    check("non-hex digest makes it unavailable", !ready.copy(sha256 = "Z".repeat(64)).available)
    check("missing size makes it unavailable", !ready.copy(archiveSizeBytes = null).available)
    check("zero size makes it unavailable", !ready.copy(archiveSizeBytes = 0L).available)
    check("missing file count makes it unavailable", !ready.copy(fileCount = null).available)
    check("zero file count makes it unavailable", !ready.copy(fileCount = 0).available)
    check(
        "unbuilt source revision makes it unavailable",
        !ready.copy(sourceRevision = TermuxBootstrapCatalog.SOURCE_REVISION_UNBUILT).available,
    )
    // `available` deliberately stops at "is there a complete artifact reference". The
    // extra rules (no mutable tag, https only) are enforced by TermuxBootstrapCatalogTest,
    // reproduced here because the harness cannot run kotlin.test/JUnit.
    fun urlIsAcceptable(url: String) = url.startsWith("https://") && !url.contains("latest")
    check(
        "the available getter does not itself forbid a mutable tag (the catalog test does)",
        ready.copy(url = "https://example.invalid/latest/bootstrap.zip").available,
    )
    check(
        "the catalog test's URL rule rejects a 'latest' URL",
        !urlIsAcceptable("https://example.invalid/latest/bootstrap.zip"),
    )
    check("the catalog test's URL rule accepts an immutable release URL", urlIsAcceptable(ready.url!!))

    println("TermuxBootstrapArchive: link paths are created inside the staging prefix")
    // This is what TermuxBootstrapInstaller installs: the tree is unpacked into
    // usr-staging and renamed to usr afterwards, so the created link paths are rooted at
    // the STAGING prefix even though the manifest's absolute target names the final one.
    val stagingBase = java.io.File(staging).canonicalPath
    check(
        "bare-name link lands under staging",
        TermuxBootstrapArchive.resolveSymlink(staging, "./bin/ls") == "$stagingBase/bin/ls",
    )
    check(
        "relative link lands under staging",
        TermuxBootstrapArchive.resolveSymlink(staging, "./include/ncursesw/term.h") ==
            "$stagingBase/include/ncursesw/term.h",
    )
    check(
        "absolute-target link lands under staging",
        TermuxBootstrapArchive.resolveSymlink(staging, "./share/pacman/keyrings/mradityaalok.gpg") ==
            "$stagingBase/share/pacman/keyrings/mradityaalok.gpg",
    )

    println("")
    println("checks: $checks, failures: $failures")
    if (failures > 0) throw AssertionError("$failures check(s) failed")
}
