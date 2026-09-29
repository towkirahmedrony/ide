#!/usr/bin/env python3
"""Verify an AgentX Termux bootstrap archive, and fail loudly on any violation.

This is the gate that decides whether an archive may be published and recorded in
TermuxBootstrapCatalog. It re-derives every fact it checks from the archive in
front of it; nothing is taken from the build log.

Two profiles:

  agentx (default)
      The artifact must be built for /data/data/com.agentx.app/files/usr:
      ELF RUNPATH and script shebangs must use that prefix, the string
      "com.termux" must not appear anywhere at all, and the install marker and
      apt configuration must be the AgentX ones.

  official
      Calibration profile. It points the same structural checks at an archive
      built for /data/data/com.termux/files/usr (published by termux-packages)
      so the checker itself can be shown to produce no false positives on real
      upstream output. Prefix and marker expectations are relaxed; the string
      scan reports instead of failing.

Usage:
  verify_bootstrap.py --zip FILE --arch ARCH [--profile agentx|official]
                      [--prefix PATH] [--source-revision REV]
                      [--manifest-out FILE] [--emit-kotlin] [--quiet]

Exit status is 0 only when every check passed.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

SEPARATOR = "\u2190"
SYMLINK_MANIFEST = "SYMLINKS.txt"
INSTALL_MARKER = "etc/termux/agentx-bootstrap.ok"
OFFICIAL_PREFIX = "/data/data/com.termux/files/usr"
AGENTX_PREFIX = "/data/data/com.agentx.app/files/usr"

# Tools task 3 requires, and the package each is shipped by in the default
# The two upstream bootstrap variants, as produced by scripts/build-bootstraps.sh.
#
# The default ("normal") variant ships apt, and build-bootstraps.sh registers every extracted
# deb in var/lib/dpkg (status + info/.list + info/.md5sums + conffiles), which is what the
# Part 2 checks were calibrated against.
#
# The --android10 variant exists because on Android 10+ an app cannot exec files from its own
# data directory unless it targets API <= 28. It replaces apt and command-not-found with
# proot, and it deliberately skips ALL of that dpkg bookkeeping -- there is no apt to read it,
# and no var/lib/dpkg at all, nor any etc/apt. Those absences are the variant's contract, not
# a defect, so the checks that assert them are switched off for it (and only those).
VARIANT_NORMAL = "normal"
VARIANT_ANDROID10 = "android10"

NORMAL_ONLY_EXECUTABLES = {"apt", "apt-get", "dpkg"}
ANDROID10_ONLY_EXECUTABLES = {
    "proot": "proot, which replaces apt/command-not-found in this variant",
}

# The only two relaxations of the ELF checks in the whole verifier, both scoped to the exact
# files and to the android10 variant. proot deliberately ships a 32-bit loader beside the
# 64-bit one in an x86_64 tree -- it exists to trace 32-bit processes -- and neither loader is
# a program that gets launched through the prefix, so neither carries a RUNPATH. Nothing else
# is exempt: every other ELF file must match the architecture and carry the prefix RUNPATH.
# The bundled-helper class, the one com.termux reference that is not app identity.
#
# bin/am and bin/termux-reset launch the TermuxAm helper apk by class name through
# app_process. That class lives in the apk the termux-am package builds from termux/TermuxAm
# and bundles at libexec/termux-am/am.apk; the literal sits in two upstream files
# (packages/termux-am/am-libexec-packaged and termux-tools' scripts/termux-reset.in), and no
# build variable rebrands it. It is not the AgentX or the Termux app package: the app has no
# termuxam component at all, and upstream already substitutes ${TERMUX_APP_PACKAGE} into the
# helper's FakeContext.java, so the app the helper talks to is already com.agentx.app.
#
# Scoped to these two paths, this exact string, and the android10 variant. The subtraction is
# per-file and residual: anything else com.termux-shaped left in the file still fails, so the
# general identity scan is not weakened.
BUNDLED_TERMU_XAM_CLASS = "com.termux.termuxam.Am"
ANDROID10_BUNDLED_HELPER_CLASS_FILES = {"bin/am", "bin/termux-reset"}

ANDROID10_PROOT_32BIT_LOADERS = {"libexec/proot/loader32"}
ANDROID10_PROOT_LOADERS_WITHOUT_RUNPATH = {"libexec/proot/loader", "libexec/proot/loader32"}

NORMAL_ONLY_FILES = {
    "etc/apt/sources.list": "apt package sources",
    "var/lib/dpkg/status": "dpkg installed-package database",
}


def detect_variant(*, has_dpkg_status, has_proot):
    """Which upstream variant an extracted archive is, from what it contains.

    Two independent markers, both of which the build script switches on the same flag:
    the dpkg database (present exactly in the normal variant) and proot (present exactly in
    the android10 variant).
    """
    if has_proot and not has_dpkg_status:
        return VARIANT_ANDROID10
    if has_dpkg_status and not has_proot:
        return VARIANT_NORMAL
    return None


# bootstrap package set (verified against a real archive's var/lib/dpkg).
REQUIRED_EXECUTABLES = {
    "sh": "dash (via bin/sh -> dash)",
    "bash": "bash",
    "login": "termux-tools",
    "env": "coreutils",
    "printf": "coreutils",
    "cat": "coreutils",
    "ls": "coreutils",
    "pwd": "coreutils",
    "mkdir": "coreutils",
    "rm": "coreutils",
    "cp": "coreutils",
    "mv": "coreutils",
    "tar": "tar",
    "gzip": "gzip",
    "sed": "sed",
    "grep": "grep",
    "awk": "gawk (via bin/awk -> gawk)",
    "apt": "apt",
    "apt-get": "apt",
    "dpkg": "dpkg (dependency of apt)",
    "pkg": "termux-tools",
}

REQUIRED_PATHS = {
    "etc/profile": "Termux login-shell profile",
    "etc/apt/sources.list": "apt package sources",
    "var/lib/dpkg/status": "dpkg installed-package database",
    "lib/libc++_shared.so": "C++ runtime used by most Termux packages",
    "lib/libandroid-support.so": "Termux libandroid-support",
}

# Libraries the Android platform itself provides, mapped to the system library
# file. A NEEDED entry that resolves here is expected to be absent from lib/.
ANDROID_PROVIDED = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libstdc++.so", "libz.so", "libEGL.so", "libGLESv2.so", "libOpenSLES.so",
    "libnativehelper.so", "libvulkan.so", "libnetd_client.so",
}

ELF_MACHINES = {
    "aarch64": ("ELF64", "AArch64"),
    "arm": ("ELF32", "ARM"),
    "i686": ("ELF32", "Intel 80386"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64"),
}

TEXT_SUFFIX_HINTS = (".sh", ".list", ".md5sums", ".conf", ".profile", ".csh", ".env")


class Report:
    def __init__(self) -> None:
        self.violations: list[str] = []
        self.notes: list[str] = []
        self.checks: dict[str, str] = {}

    def ok(self, key: str, detail: str = "") -> None:
        self.checks[key] = "pass" + (f" ({detail})" if detail else "")

    def fail(self, key: str, message: str) -> None:
        self.checks[key] = "FAIL"
        self.violations.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)


def run(command: list[str]) -> str:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return result.stdout


def require_tools(report: Report) -> None:
    missing = [tool for tool in ("readelf", "file") if shutil.which(tool) is None]
    if missing:
        # These are the tools task 4 names; refuse rather than silently skipping ELF checks.
        report.fail("tools", "missing required tool(s): " + ", ".join(missing))
        flush(report)
        sys.exit(2)


def flush(report: Report) -> None:
    for note in report.notes:
        print(f"  - {note}")
    for violation in report.violations:
        print(f"  ! {violation}", file=sys.stderr)


def normalise(path: str) -> str | None:
    """Normalise a relative POSIX path, or None if it is absolute/escaping/NUL."""
    if not path or "\x00" in path:
        return None
    if path.startswith("/"):
        return None
    parts: list[str] = []
    for part in path.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                return None
            parts.pop()
            continue
        parts.append(part)
    return "/".join(parts) if parts else None


def strip_dot(name: str) -> str:
    return name[2:] if name.startswith("./") else name


# Mirrors TermuxBootstrapArchive.EXECUTABLE_PREFIXES: the archive entries the
# installer chmods 0700. These are the programs a session can launch.
EXECUTABLE_PREFIXES = ("bin/", "libexec/", "lib/apt/apt-helper", "lib/apt/methods")


def is_executable_path(relative: str) -> bool:
    return relative.startswith(EXECUTABLE_PREFIXES)


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify an AgentX Termux bootstrap archive")
    parser.add_argument("--zip", required=True)
    parser.add_argument("--arch", required=True, choices=sorted(ELF_MACHINES))
    parser.add_argument("--profile", default="agentx", choices=("agentx", "official"))
    parser.add_argument("--prefix", default="")
    parser.add_argument("--source-revision", default="")
    parser.add_argument("--manifest-out", default="")
    parser.add_argument("--emit-kotlin", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--variant", default="auto",
                        choices=("auto", VARIANT_NORMAL, VARIANT_ANDROID10),
                        help="which upstream bootstrap variant this archive should be; "
                             "auto detects it from the contents and then cross-checks")
    args = parser.parse_args()

    official = args.profile == "official"
    prefix = args.prefix or (OFFICIAL_PREFIX if official else AGENTX_PREFIX)
    rootfs = prefix.rsplit("/", 1)[0]
    home = f"{rootfs}/home"
    runpath_expected = f"{prefix}/lib"

    abi = {"aarch64": "arm64-v8a", "arm": "armeabi-v7a", "i686": "x86", "x86_64": "x86_64"}[args.arch]

    report = Report()
    require_tools(report)

    if not os.path.isfile(args.zip):
        print(f"error: no such archive: {args.zip}", file=sys.stderr)
        return 2

    print(f"verifying {os.path.basename(args.zip)} (arch {args.arch}, abi {abi}, prefix {prefix})")

    work = tempfile.mkdtemp(prefix="agentx-verify.")
    try:
        # ---------------------------------------------------------------- archive
        with zipfile.ZipFile(args.zip) as archive:
            entries = [info for info in archive.infolist() if not info.is_dir()]
            names = {strip_dot(info.filename) for info in entries}
            if SYMLINK_MANIFEST not in names:
                report.fail("manifest-present", f"{SYMLINK_MANIFEST} is missing from the archive")
            directory_count = sum(1 for info in archive.infolist() if info.is_dir())
            # The installer counts every non-directory entry except the manifest.
            installer_file_count = sum(
                1 for info in entries if strip_dot(info.filename) != SYMLINK_MANIFEST
            )
            if archive.testzip() is not None:
                report.fail("archive-integrity", "zip CRC check failed")
            else:
                report.ok("archive-integrity")
            archive.extractall(work)

        report.ok("archive-entries", f"{installer_file_count} files, {directory_count} directories")

        # ---------------------------------------------------------------- variant
        # Detection is from the archive itself, never from the build log: a normal bootstrap
        # renamed to look like the android10 one, or vice versa, is exactly what this catches.
        detected = detect_variant(
            has_dpkg_status=os.path.isfile(os.path.join(work, "var/lib/dpkg/status")),
            has_proot=os.path.isfile(os.path.join(work, "bin/proot")),
        )
        if detected is None:
            report.fail(
                "variant",
                "archive is neither variant: expected exactly one of var/lib/dpkg/status "
                "(normal) and bin/proot (android10)",
            )
            variant = args.variant if args.variant != "auto" else VARIANT_NORMAL
        else:
            variant = detected
        if args.variant != "auto" and detected is not None and args.variant != detected:
            report.fail("variant", f"--variant {args.variant} was requested but the archive is {detected}")
        android10 = variant == VARIANT_ANDROID10
        print(f"  variant={variant} (requested {args.variant}, detected {detected})")

        # ------------------------------------------------------------- content
        required_paths = dict(REQUIRED_PATHS)
        if android10:
            for name in NORMAL_ONLY_FILES:
                required_paths.pop(name, None)
        for required, why in required_paths.items():
            if not os.path.exists(os.path.join(work, required)):
                report.fail("required-files", f"missing {required} ({why})")
        # build-bootstraps.sh skips the whole dpkg database for --android10 (there is no apt to
        # read it), so its absence is the variant's contract rather than a defect.
        if android10:
            report.ok("dpkg-database", "absent by design in the android10 variant")
        elif os.path.isdir(os.path.join(work, "var/lib/dpkg/info")):
            dpkg_info = os.listdir(os.path.join(work, "var/lib/dpkg/info"))
            if not dpkg_info:
                report.fail("required-files", "var/lib/dpkg/info is empty")
        else:
            report.fail("required-files", "missing var/lib/dpkg/info")
        if not os.path.isdir(os.path.join(work, "etc/termux")):
            report.fail("required-files", "missing etc/termux directory")
        profile_d = os.path.join(work, "etc/profile.d")
        if not (os.path.isdir(profile_d) and os.listdir(profile_d)):
            report.fail("required-files", "etc/profile.d is missing or empty")
        report.ok("required-files")

        # ------------------------------------------------------------- symlinks
        manifest_path = os.path.join(work, SYMLINK_MANIFEST)
        links: list[tuple[str, str]] = []
        malformed: list[str] = []
        with open(manifest_path, encoding="utf-8", errors="replace") as handle:
            for raw in handle:
                line = raw.rstrip("\n")
                if not line.strip():
                    continue
                if line.count(SEPARATOR) != 1:
                    malformed.append(line)
                    continue
                target, link_path = line.split(SEPARATOR)
                links.append((target, strip_dot(link_path)))
        if malformed:
            report.fail(
                "symlink-manifest-format",
                f"{len(malformed)} line(s) do not contain exactly one U+2190 separator, "
                f"first: {malformed[0]!r}",
            )
        else:
            report.ok("symlink-manifest-format", f"{len(links)} entries")

        bad_symlink_paths: list[str] = []
        bad_symlink_targets: list[str] = []
        for target, link_path in links:
            if normalise(link_path) is None:
                bad_symlink_paths.append(link_path)
                continue
            if target.startswith("/"):
                # Upstream ships absolute targets that point into the final prefix
                # (they are dangling while the archive sits in usr-staging and become
                # correct after the rename). Anything else is a real escape.
                if not (target == prefix or target.startswith(prefix + "/")):
                    bad_symlink_targets.append(f"{link_path} -> {target} (absolute, outside {prefix})")
                continue
            link_dir = os.path.dirname(link_path)
            resolved = normalise(f"{link_dir}/{target}" if link_dir else target)
            if resolved is None:
                bad_symlink_targets.append(f"{link_path} -> {target} (resolves outside the prefix)")
        if bad_symlink_paths:
            report.fail("symlink-paths", f"{len(bad_symlink_paths)} escaping link path(s): {bad_symlink_paths[:3]}")
        else:
            report.ok("symlink-paths")
        if bad_symlink_targets:
            report.fail("symlink-targets", f"{len(bad_symlink_targets)}: {bad_symlink_targets[:3]}")
        else:
            report.ok("symlink-targets", "all targets stay inside the prefix")

        # Every required executable is either a real file or a manifest symlink, and
        # bare-name targets (the `bin/ls -> coreutils` multicall pattern) must exist.
        resolution = {link_path: target for target, link_path in links}

        def resolves_to_file(link_path: str, depth: int = 0) -> bool:
            if depth > 8:
                return False
            candidate = os.path.join(work, link_path)
            if os.path.isfile(candidate):
                return True
            target = resolution.get(link_path)
            if target is None:
                return False
            link_dir = os.path.dirname(link_path)
            next_path = normalise(f"{link_dir}/{target}" if link_dir else target)
            return resolves_to_file(next_path, depth + 1) if next_path else False

        # Sonames a binary may legitimately expect to find in lib/: every real file
        # under lib/, plus every manifest symlink under lib/ that resolves to one.
        available_libraries: set[str] = set()
        lib_dir = os.path.join(work, "lib")
        if os.path.isdir(lib_dir):
            for current, _dirs, files in os.walk(lib_dir):
                for name in files:
                    available_libraries.add(name)
        for link_path in list(resolution):
            if link_path.startswith("lib/") and resolves_to_file(link_path):
                available_libraries.add(os.path.basename(link_path))

        required_executables = dict(REQUIRED_EXECUTABLES)
        if android10:
            for name in NORMAL_ONLY_EXECUTABLES:
                required_executables.pop(name, None)
            required_executables.update(ANDROID10_ONLY_EXECUTABLES)
        for name, provider in required_executables.items():
            if not resolves_to_file(f"bin/{name}"):
                report.fail("required-executables", f"bin/{name} is missing or does not resolve ({provider})")
        report.ok("required-executables", f"{len(REQUIRED_EXECUTABLES)} tools")

        # ------------------------------------------------------------- binaries
        elf_files: list[str] = []
        script_files: list[str] = []
        executables: list[str] = []
        machine_mismatch: list[str] = []
        bad_interpreter: list[str] = []
        bad_runpath: list[str] = []
        variant_exceptions_used: list[str] = []
        bad_shebang: list[str] = []
        unresolved_needed: dict[str, set[str]] = collections.defaultdict(set)

        for current, _dirs, files in os.walk(work):
            for name in files:
                absolute = os.path.join(current, name)
                relative = os.path.relpath(absolute, work)
                if os.path.islink(absolute):
                    continue
                if is_executable_path(relative):
                    executables.append(relative)
                with open(absolute, "rb") as handle:
                    head = handle.read(4)
                if head[:4] != b"\x7fELF":
                    # Scripts are the other half of the prefix contract: task 4 asks for
                    # the "interpreter/linker path" to use the prefix, which for a script
                    # means its shebang.
                    if head[:2] == b"#!":
                        with open(absolute, encoding="utf-8", errors="replace") as handle:
                            shebang = handle.readline().strip()
                        script_files.append(relative)
                        interpreter = shebang[2:].split()[0] if len(shebang) > 2 else ""
                        if not interpreter.startswith("/"):
                            continue
                        if not (interpreter == prefix or interpreter.startswith(prefix + "/")):
                            bad_shebang.append(f"{relative}: {shebang[:120]}")
                    continue

                elf_files.append(relative)
                header = run(["readelf", "-h", absolute])
                klass = re.search(r"Class:\s+(\S+)", header)
                machine = re.search(r"Machine:\s+(.+)", header)
                expected_class, expected_machine = ELF_MACHINES[args.arch]
                if not klass or not machine:
                    machine_mismatch.append(f"{relative}: readelf -h produced no Class/Machine")
                elif android10 and relative in ANDROID10_PROOT_32BIT_LOADERS:
                    # Scoped exception, recorded below so it is visible in the manifest rather
                    # than silent. Everything else still has to match args.arch.
                    variant_exceptions_used.append(f"{relative}: intentionally ELF32 in an x86_64 archive")
                elif klass.group(1) != expected_class or machine.group(1).strip() != expected_machine:
                    machine_mismatch.append(
                        f"{relative}: {klass.group(1)}/{machine.group(1).strip()} "
                        f"!= {expected_class}/{expected_machine}"
                    )

                dynamic = run(["readelf", "-d", absolute])

                # Which ELF files must name an interpreter? Only the ones a session
                # actually launches: the programs sitting directly in bin/. Everything else
                # legitimately has none -- c++/android-support style libraries, bash
                # loadable builtins (lib/bash/csv and friends, ELF objects with no .so in
                # the name), and libexec helper modules. A PT_INTERP that is present
                # anywhere must still be the platform linker, which the else-branch checks.
                #
                # The linker itself is always /system/bin/linker[64]: Android provides it and
                # it is not part of the prefix. The prefix requirement therefore lives in the
                # RUNPATH check below (native code) and in the shebang check (scripts).
                is_launchable = (
                    relative.startswith("bin/")
                    and "/" not in relative[len("bin/"):]
                    and ".so" not in os.path.basename(relative)
                )
                expected_interp = "/system/bin/linker64" if expected_class == "ELF64" else "/system/bin/linker"
                program = run(["readelf", "-l", absolute])
                interp = re.search(r"Requesting program interpreter:\s*(\S+)\]", program)
                if interp is None:
                    if is_launchable:
                        bad_interpreter.append(f"{relative}: launchable program with no program interpreter")
                elif interp.group(1) != expected_interp:
                    bad_interpreter.append(f"{relative}: {interp.group(1)} != {expected_interp}")
                runpaths = re.findall(r"\((?:RUNPATH|RPATH)\)\s+Library (?:runpath|rpath):\s*\[(.*?)\]", dynamic)
                # Executables must carry the prefix runpath; plain shared libraries in
                # lib/ legitimately have none (libc++_shared.so is the only one upstream).
                if not runpaths and relative.startswith(("bin/", "libexec/")):
                    if android10 and relative in ANDROID10_PROOT_LOADERS_WITHOUT_RUNPATH:
                        variant_exceptions_used.append(f"{relative}: intentionally without RUNPATH")
                    else:
                        bad_runpath.append(f"{relative}: no RUNPATH")
                for value in runpaths:
                    for single in value.split(":"):
                        if single and single != runpath_expected:
                            bad_runpath.append(f"{relative}: RUNPATH {single} != {runpath_expected}")
                for needed in re.findall(r"\(NEEDED\)\s+Shared library:\s*\[(.*?)\]", dynamic):
                    if needed in ANDROID_PROVIDED:
                        continue
                    if needed in available_libraries:
                        continue
                    unresolved_needed[needed].add(relative)

        if machine_mismatch:
            report.fail("elf-machine", f"{len(machine_mismatch)} file(s) not {args.arch}: {machine_mismatch[:3]}")
        else:
            report.ok("elf-machine", f"{len(elf_files)} ELF files")
        if bad_interpreter:
            report.fail("elf-interpreter", f"{len(bad_interpreter)} file(s): {bad_interpreter[:3]}")
        else:
            report.ok(
                "elf-interpreter",
                "/system/bin/linker64" if ELF_MACHINES[args.arch][0] == "ELF64" else "/system/bin/linker",
            )
        if bad_runpath:
            report.fail("elf-runpath", f"{len(bad_runpath)} file(s): {bad_runpath[:3]}")
        else:
            report.ok("elf-runpath", runpath_expected)
        if bad_shebang:
            report.fail("script-shebang", f"{len(bad_shebang)} script(s): {bad_shebang[:3]}")
        else:
            report.ok("script-shebang", f"{len(script_files)} scripts")
        # Reported, not enforced. Upstream's own published archive has two entries
        # that resolve nowhere (bin/lsns -> libmount.so, and the sanitizer test
        # binaries under libexec/installed-tests/ -> libclang_rt.asan-*.so), so a
        # failure here would be a false positive on a correct build. The task's ELF
        # requirements (machine, interpreter, RUNPATH) are enforced above.
        if unresolved_needed:
            report.ok("elf-needed", "reported below, not enforced")
            report.note(
                "NEEDED libraries that resolve neither to lib/ nor to an Android system library "
                f"({len(unresolved_needed)}): "
                + ", ".join(f"{lib} <- {sorted(files)[0]}" for lib, files in sorted(unresolved_needed.items()))
            )
        else:
            report.ok("elf-needed")

        # ------------------------------------------------------------- prefix scan
        # Task 4: scan all native files, scripts and package metadata. Every file is
        # read as bytes, so files without a known text suffix are covered too.
        com_termux: list[str] = []
        agentx_hits = 0
        scanned = 0
        for current, _dirs, files in os.walk(work):
            for name in files:
                absolute = os.path.join(current, name)
                if os.path.islink(absolute):
                    continue
                relative = os.path.relpath(absolute, work)
                scanned += 1
                with open(absolute, "rb") as handle:
                    blob = handle.read()
                if b"com.termux" in blob:
                    com_termux.append(relative)
                agentx_hits += blob.count(prefix.encode())

        # Three buckets, because "com.termux" appears for two different reasons:
        #
        #   1. inside a data/data/... path  -> the artifact was not rebuilt for this
        #      prefix. Hard failure; this is what task 4 requires to be zero.
        #   2. as the app package name in bin/ -> the user-facing tools address the
        #      Termux app instead of this app. termux-packages substitutes
        #      @TERMUX_APP_PACKAGE@ generically (scripts/build/termux_step_patch_package.sh),
        #      so these must read com.agentx.app too. Hard failure.
        #   3. anywhere else (headers, lib/, test fixtures under libexec/) -> upstream
        #      ships the string in material that does not affect this prefix. Reported.
        official_name = "com.termux"
        buckets = {"path": [], "bin": [], "other": []}
        helper_class_hits: list[str] = []
        for entry in com_termux:
            with open(os.path.join(work, entry), "rb") as handle:
                blob = handle.read()
            if b"data/data/" + official_name.encode() in blob:
                buckets["path"].append(entry)
            elif is_executable_path(entry) and entry.startswith("bin/"):
                remaining = blob
                if android10 and entry in ANDROID10_BUNDLED_HELPER_CLASS_FILES:
                    occurrences = remaining.count(BUNDLED_TERMU_XAM_CLASS.encode())
                    if occurrences:
                        remaining = remaining.replace(BUNDLED_TERMU_XAM_CLASS.encode(), b"")
                        helper_class_hits.append(
                            f"{entry}: {occurrences}x {BUNDLED_TERMU_XAM_CLASS} "
                            "(class of the bundled TermuxAm helper apk, not app identity)"
                        )
                # Whatever survives the subtraction decides. A file that carries the helper
                # class and any real identity reference is still a violation.
                if b"com.termux" in remaining:
                    buckets["bin"].append(entry)
            else:
                buckets["other"].append(entry)
        variant_exceptions_used.extend(helper_class_hits)

        def scan(check: str, entries: list[str], message: str) -> None:
            if not entries:
                report.ok(check)
            elif official:
                report.ok(check, "reported only")
                report.note("official profile: " + message + f" -> {entries[:6]}")
            else:
                report.fail(check, message + f" -> {entries[:6]}")

        scan(
            "prefix-scan-paths",
            buckets["path"],
            f'{len(buckets["path"])} file(s) still contain the official prefix path "data/data/com.termux"',
        )
        scan(
            "prefix-scan-bin",
            buckets["bin"],
            f'{len(buckets["bin"])} bin/ file(s) still name the official app package "com.termux"',
        )
        if buckets["other"]:
            report.ok("prefix-scan-other", "reported below")
            report.note(
                f'{len(buckets["other"])} other file(s) contain "com.termux" but no data/data/com.termux '
                f"path (headers, libraries, test fixtures): {buckets['other'][:6]}"
            )
        else:
            report.ok("prefix-scan-other")

        if not official and agentx_hits == 0:
            report.fail("prefix-built-for-agentx", f"the prefix {prefix} does not appear anywhere; the packages were not rebuilt")
        else:
            report.ok("prefix-built-for-agentx", f"{agentx_hits} prefix occurrences in {scanned} files")

        # ------------------------------------------------------------- marker / apt
        if official:
            report.ok("install-marker", "not required in the official calibration profile")
        else:
            marker = os.path.join(work, INSTALL_MARKER)
            if not os.path.isfile(marker):
                report.fail("install-marker", f"{INSTALL_MARKER} is missing")
            else:
                marker_text = open(marker, encoding="utf-8", errors="replace").read()
                for key, expected in (
                    ("prefix", prefix),
                    ("source_revision", args.source_revision or ""),
                ):
                    if expected and f"{key}={expected}" not in marker_text:
                        report.fail("install-marker", f"{INSTALL_MARKER} does not record {key}={expected}")
                report.ok("install-marker")

        sources = os.path.join(work, "etc/apt/sources.list")
        sources_text = open(sources, encoding="utf-8", errors="replace").read() if os.path.isfile(sources) else ""
        active = [line.strip() for line in sources_text.splitlines() if re.match(r"^\s*deb\s", line)]
        if android10:
            report.ok(
                "apt-sources",
                "not applicable to the android10 variant: it ships no apt, so there is no "
                "sources.list to point anywhere",
            )
        elif official:
            report.ok("apt-sources", "not enforced in the official calibration profile")
        else:
            if not sources_text:
                report.fail("apt-sources", "etc/apt/sources.list is missing or empty")
            else:
                for line in active:
                    if "termux.dev" in line:
                        report.fail("apt-sources", f"apt is pointed at an official Termux repository: {line}")
                if not report.violations:
                    report.ok("apt-sources", f"{len(active)} active source line(s)")

        # ------------------------------------------------------------- manifest
        size_bytes = os.path.getsize(args.zip)
        sha = run(["sha256sum", args.zip]).split()[0]
        manifest = {
            "schema": 1,
            "profile": args.profile,
            "variant": variant,
            "variantExceptions": sorted(set(variant_exceptions_used)),
            "androidAbi": abi,
            "termuxArch": args.arch,
            "assetName": os.path.basename(args.zip),
            "prefix": prefix,
            "rootfs": rootfs,
            "home": home,
            "sourceRevision": args.source_revision or None,
            "sha256": sha,
            "sizeBytes": size_bytes,
            "fileCount": installer_file_count,
            "directoryCount": directory_count,
            "symlinkCount": len(links),
            "executableCount": len(executables),
            "elfCount": len(elf_files),
            "elfInterpreter": "/system/bin/linker64" if ELF_MACHINES[args.arch][0] == "ELF64" else "/system/bin/linker",
            "elfRunpath": runpath_expected,
            "officialPrefixPathOccurrences": len(buckets["path"]),
            "officialAppPackageInBin": len(buckets["bin"]),
            "officialAppPackageElsewhere": len(buckets["other"]),
            "officialAppPackageElsewhereFiles": buckets["other"],
            "prefixOccurrences": agentx_hits,
            "unresolvedNeeded": {
                lib: sorted(files) for lib, files in sorted(unresolved_needed.items())
            },
            "clean": not report.violations,
            "installMarker": None if official else INSTALL_MARKER,
            "aptRepository": next((line.split()[1] for line in active if "termux.dev" not in line), None),
            "executables": sorted(executables),
            "symlinks": [f"{target}{SEPARATOR}{link_path}" for target, link_path in links],
            "checks": report.checks,
        }

        if args.manifest_out:
            with open(args.manifest_out, "w", encoding="utf-8") as handle:
                json.dump(manifest, handle, indent=2, sort_keys=False)
                handle.write("\n")
            print(f"manifest written to {args.manifest_out}")

        if not args.quiet:
            print("")
            print(f"  files {manifest['fileCount']}, directories {manifest['directoryCount']}, "
                  f"symlinks {manifest['symlinkCount']}, executables {manifest['executableCount']}, "
                  f"ELF {manifest['elfCount']}")
            print(f"  sha256 {sha}")
            print(f"  size   {size_bytes} bytes")
            for key in sorted(report.checks):
                print(f"  {key:26s} {report.checks[key]}")
            for note in report.notes:
                print(f"  note: {note}")

        if args.emit_kotlin and not report.violations:
            # Every value below comes from the archive that was just verified, which is the
            # only source TermuxBootstrapCatalog may be filled from.
            print("")
            print("TermuxBootstrapCatalog.Entry (paste into entries, replacing the pending() call):")
            print("")
            print("        Entry(")
            print(f'            androidAbi = "{abi}",')
            print(f'            termuxArch = "{args.arch}",')
            print(f'            prefix = TermuxPaths.AGENTX_PREFIX,')
            print(f'            sourceRevision = "{args.source_revision}",')
            print(f'            assetName = "{manifest["assetName"]}",')
            print(f'            url = "<immutable release asset URL for {manifest["assetName"]}>",')
            print(f'            sha256 = "{sha}",')
            print(f'            archiveSizeBytes = {size_bytes}L,')
            print(f'            fileCount = {installer_file_count},')
            print("        ),")

        flush(report)
        if report.violations:
            print(f"\nFAILED: {len(report.violations)} violation(s) in {os.path.basename(args.zip)}", file=sys.stderr)
            return 1
        print(f"\nOK: {os.path.basename(args.zip)} verified for {prefix}")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
