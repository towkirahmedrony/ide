#!/usr/bin/env bash
#
# Point a termux-packages checkout at the AgentX package name and prove, by
# sourcing upstream's own properties.sh, that the derived prefix/rootfs/home are
# the ones :termux-runtime expects.
#
# Usage:
#   ./apply-agentx-prefix.sh --termux-packages-dir <path> [--check-only]
#
# What it changes: exactly one line, the assignment of TERMUX_APP__PACKAGE_NAME.
# What it deliberately does NOT change: TERMUX_REPO_APP__PACKAGE_NAME. See the
# comment at that check below; upstream relies on the mismatch to refuse to
# download official debs.
#
# Exits non-zero (and changes nothing) if upstream no longer looks the way this
# script was written against. A silent wrong prefix would produce archives that
# install but cannot run, so every assumption is an assertion.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$(readlink -f -- "$0")")" && pwd)"
# shellcheck source=agentx-prefix.env
. "$SCRIPT_DIR/agentx-prefix.env"

TERMUX_PACKAGES_DIR=""
CHECK_ONLY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --termux-packages-dir)
            [ $# -gt 1 ] || { echo "error: --termux-packages-dir needs a value" >&2; exit 64; }
            TERMUX_PACKAGES_DIR="$2"; shift 2 ;;
        --check-only) CHECK_ONLY=1; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "error: unknown option '$1'" >&2; exit 64 ;;
    esac
done

[ -n "$TERMUX_PACKAGES_DIR" ] || { echo "error: --termux-packages-dir is required" >&2; exit 64; }
TERMUX_PACKAGES_DIR="$(cd -- "$TERMUX_PACKAGES_DIR" && pwd)"

PROPERTIES="$TERMUX_PACKAGES_DIR/scripts/properties.sh"
[ -f "$PROPERTIES" ] || { echo "error: $PROPERTIES not found" >&2; exit 66; }

say()  { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------------
# 1. Upstream must still declare the package name exactly the way we patch it.
# ---------------------------------------------------------------------------------

# The authoritative variable is the double-underscore TERMUX_APP__PACKAGE_NAME.
# TERMUX_APP_PACKAGE is a deprecated alias that properties.sh assigns *from* it,
# so patching only the alias (which is what build-bootstraps.sh --help mentions)
# changes nothing at all.
ASSIGNMENT_RE='^TERMUX_APP__PACKAGE_NAME="com\.termux"[[:space:]]*$'
ASSIGNMENT_LINES="$(grep -cE "$ASSIGNMENT_RE" "$PROPERTIES" || true)"
ASSIGNMENT_LINE_NO="$(grep -nE "$ASSIGNMENT_RE" "$PROPERTIES" | cut -d: -f1 || true)"

if [ "$ASSIGNMENT_LINES" != "1" ]; then
    fail "expected exactly one 'TERMUX_APP__PACKAGE_NAME=\"com.termux\"' line in $PROPERTIES, found $ASSIGNMENT_LINES.
       Upstream changed; re-read scripts/properties.sh before building, because the
       prefix this repository pins ($AGENTX_PREFIX) depends on that one assignment."
fi

# Any *other* assignment of the same variable would make the patch ambiguous.
OTHER_ASSIGNMENTS="$(grep -cE '^[[:space:]]*(export[[:space:]]+)?TERMUX_APP__PACKAGE_NAME=' "$PROPERTIES" || true)"
[ "$OTHER_ASSIGNMENTS" = "1" ] || fail "found $OTHER_ASSIGNMENTS assignments of TERMUX_APP__PACKAGE_NAME; expected 1 (line $ASSIGNMENT_LINE_NO)"

# The alias must stay derived from the authoritative variable, otherwise
# build-package.sh's compatibility check reads a stale value.
grep -qE '^TERMUX_APP_PACKAGE="\$TERMUX_APP__PACKAGE_NAME"' "$PROPERTIES" \
    || fail "TERMUX_APP_PACKAGE is no longer derived from TERMUX_APP__PACKAGE_NAME in $PROPERTIES"

say "upstream assignment : line $ASSIGNMENT_LINE_NO of scripts/properties.sh"
say "  before            : TERMUX_APP__PACKAGE_NAME=\"com.termux\""
say "  after             : TERMUX_APP__PACKAGE_NAME=\"$AGENTX_PACKAGE_NAME\""

# ---------------------------------------------------------------------------------
# 2. The repo-compatibility variable must stay at com.termux.
# ---------------------------------------------------------------------------------

# properties.sh documents TERMUX_REPO_APP__PACKAGE_NAME as "the variable values
# for which the packages hosted on the repos defined in repo.json are compiled
# for". build-package.sh (line ~633 at the pinned commit) reads:
#
#   if [[ "$TERMUX_REPO_APP__PACKAGE_NAME" != "$TERMUX_APP_PACKAGE" ]]; then
#       echo "Ignoring -i option to download dependencies since repo package name
#             (...) does not equal app package name (...)"
#
# Because we change the app package name and NOT this one, upstream's build
# system refuses to download official debs as dependencies and compiles them
# locally instead. That is the safe behaviour: a downloaded com.termux deb would
# drag the official prefix into the build. Leaving this line alone is therefore
# intentional, not an oversight.
grep -qE '^TERMUX_REPO_APP__PACKAGE_NAME="com\.termux"[[:space:]]*$' "$PROPERTIES" \
    || fail "TERMUX_REPO_APP__PACKAGE_NAME is no longer \"com.termux\"; the official-repo download guard in build-package.sh cannot be relied on"

say "repo compat variable : TERMUX_REPO_APP__PACKAGE_NAME stays \"com.termux\" (intentional)"
say "  effect             : build-package.sh ignores -i and compiles dependencies locally"

if [ "$CHECK_ONLY" = "1" ]; then
    say "check-only: no changes written"
    exit 0
fi

# ---------------------------------------------------------------------------------
# 3. Patch the single assignment.
# ---------------------------------------------------------------------------------

python3 - "$PROPERTIES" "$AGENTX_PACKAGE_NAME" "$ASSIGNMENT_LINE_NO" <<'PY'
import sys
path, package, line_no = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(path, encoding="utf-8") as handle:
    lines = handle.readlines()
index = line_no - 1
if 'TERMUX_APP__PACKAGE_NAME="com.termux"' not in lines[index]:
    sys.exit("error: line %d is not the expected assignment" % line_no)
lines[index] = 'TERMUX_APP__PACKAGE_NAME="%s"\n' % package
with open(path, "w", encoding="utf-8") as handle:
    handle.writelines(lines)
PY

# ---------------------------------------------------------------------------------
# 4. Prove the derivation by sourcing upstream's own properties.sh.
# ---------------------------------------------------------------------------------

# properties.sh needs jq (it reads repo.json) and validates itself, calling
# `exit` on a bad value, so it is sourced in a subshell whose output is captured.
DERIVED="$(
    cd "$TERMUX_PACKAGES_DIR"
    bash -c 'set -euo pipefail
        . ./scripts/properties.sh >/dev/null
        printf "%s\n" \
            "$TERMUX_APP__PACKAGE_NAME" \
            "$TERMUX_APP__DATA_DIR" \
            "$TERMUX__ROOTFS" \
            "$TERMUX__PREFIX" \
            "$TERMUX__PREFIX_CLASSICAL" \
            "$TERMUX__HOME" \
            "$TERMUX_PREFIX_CLASSICAL"' 2>/dev/null
)" || fail "sourcing scripts/properties.sh failed; the patch or the pinned revision is wrong"

mapfile -t DERIVED_LINES <<<"$DERIVED"
[ "${#DERIVED_LINES[@]}" -eq 7 ] || fail "properties.sh did not yield the expected variables (got ${#DERIVED_LINES[@]})"

D_PACKAGE="${DERIVED_LINES[0]}"
D_DATA_DIR="${DERIVED_LINES[1]}"
D_ROOTFS="${DERIVED_LINES[2]}"
D_PREFIX="${DERIVED_LINES[3]}"
D_PREFIX_CLASSICAL="${DERIVED_LINES[4]}"
D_HOME="${DERIVED_LINES[5]}"
D_PREFIX_CLASSICAL_DEPRECATED="${DERIVED_LINES[6]}"

assert_eq() {
    [ "$2" = "$3" ] || fail "$1 is '$2', expected '$3'"
    say "  $1 = $2"
}

say ""
say "derived by sourcing scripts/properties.sh:"
assert_eq "TERMUX_APP__PACKAGE_NAME"    "$D_PACKAGE"                 "$AGENTX_PACKAGE_NAME"
assert_eq "TERMUX_APP__DATA_DIR"        "$D_DATA_DIR"                "$AGENTX_APP_DATA_DIR"
assert_eq "TERMUX__ROOTFS"              "$D_ROOTFS"                  "$AGENTX_ROOTFS"
assert_eq "TERMUX__PREFIX"              "$D_PREFIX"                  "$AGENTX_PREFIX"
assert_eq "TERMUX__PREFIX_CLASSICAL"    "$D_PREFIX_CLASSICAL"        "$AGENTX_PREFIX"
assert_eq "TERMUX_PREFIX_CLASSICAL"     "$D_PREFIX_CLASSICAL_DEPRECATED" "$AGENTX_PREFIX"
assert_eq "TERMUX__HOME"                "$D_HOME"                    "$AGENTX_HOME"

# properties.sh itself refuses a TERMUX__PREFIX that is not
# TERMUX_PREFIX_CLASSICAL; assert the official prefix is gone from both.
case "$D_PREFIX" in
    *"/data/data/com.termux/"*) fail "derived prefix still contains the official Termux data dir" ;;
esac

say ""
say "ok: $TERMUX_PACKAGES_DIR is configured for $AGENTX_PREFIX"

# ---------------------------------------------------------------------------
# Per-package build tweaks.
#
# Each patches/<package>.extra-args file holds extra configure arguments for that one package.
# They are appended to the package's TERMUX_PKG_EXTRA_CONFIGURE_ARGS rather than applied as a
# source patch, because the two failures worth fixing this way are detection failures: a probe
# that answers "yes" against the NDK sysroot while the Bionic symbol it implies does not exist.
# Appending is visible (the header below is greppable) and it is the mechanism the packages
# themselves already use for Bionic gaps.
# ---------------------------------------------------------------------------
tweaks_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches"
if [ -d "$tweaks_dir" ]; then
    shopt -s nullglob
    for tweak in "$tweaks_dir"/*.extra-args; do
        package="$(basename "$tweak" .extra-args)"
        build_sh="$TERMUX_PACKAGES_DIR/packages/$package/build.sh"
        [ -f "$build_sh" ] || { echo "ERROR: $tweak exists but $build_sh does not" >&2; exit 1; }
        if grep -q "AGENTX EXTRA CONFIGURE ARGS" "$build_sh"; then
            echo "tweak: $build_sh already carries the AgentX arguments"
            continue
        fi
        # Comment lines are documentation for whoever reads the tweak, never arguments: passing
        # them through would hand configure a pile of prose.
        arguments="$(grep -v '^[[:space:]]*#' "$tweak" | tr '\n' ' ' | sed 's/  */ /g; s/^ //; s/ $//')"
        if [ -z "$arguments" ]; then
            echo "tweak: $tweak is comment-only, nothing to append"
            continue
        fi
        {
            printf '\n# AGENTX EXTRA CONFIGURE ARGS -- added by tools/termux-bootstrap (do not edit by hand)\n'
            printf 'TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" %s"\n' "$arguments"
        } >> "$build_sh"
        echo "tweak: appended to packages/$package/build.sh: $arguments"
    done
    shopt -u nullglob
fi

# ---------------------------------------------------------------------------
# Upstream's bootstrap package list is stale by one entry.
#
# scripts/build-bootstraps.sh asks for "bzip2", but packages/bzip2 does not exist at this
# revision (nor at upstream master) -- the package was renamed to libbz2. The build stops on
# it with
#   ERROR: No package bzip2 found in any of the enabled repositories.
#   Are you trying to set up a custom repository?
# Upstream's own CI never notices, because it assembles archives from published debs with
# generate-bootstraps.sh instead of building them from source. The stale name is replaced
# with the one that exists, and only after confirming that bzip2 is really gone and libbz2
# really is there, so this cannot silently point at the wrong package.
# ---------------------------------------------------------------------------
bootstraps_sh="$TERMUX_PACKAGES_DIR/scripts/build-bootstraps.sh"
if [ -f "$bootstraps_sh" ] && grep -q 'PACKAGES+=("bzip2")' "$bootstraps_sh"; then
    if [ -f "$TERMUX_PACKAGES_DIR/packages/libbz2/build.sh" ] \
        && [ ! -f "$TERMUX_PACKAGES_DIR/packages/bzip2/build.sh" ]; then
        sed -i 's|PACKAGES+=("bzip2")|PACKAGES+=("libbz2") # AGENTX: bzip2 was renamed to libbz2|' "$bootstraps_sh"
        echo "tweak: build-bootstraps.sh: stale package 'bzip2' -> 'libbz2'"
    else
        echo "WARNING: build-bootstraps.sh lists 'bzip2' but packages/bzip2 or packages/libbz2" >&2
        echo "         is not in the expected state; leaving the list untouched." >&2
    fi
fi

# ---------------------------------------------------------------------------
# Give packages' configure scripts the AgentX values.
#
# properties.sh derives the "classical" aliases from the AgentX variables and never exports
# any of them:
#
#   TERMUX_APP_PACKAGE  = "$TERMUX_APP__PACKAGE_NAME"
#   TERMUX_BASE_DIR     = "$TERMUX__ROOTFS"
#   TERMUX_PREFIX       = "$TERMUX__PREFIX"
#   TERMUX_ANDROID_HOME = "$TERMUX__HOME"
#   TERMUX_CACHE_DIR    = "$TERMUX__CACHE_DIR"
#   TERMUX_PREFIX_CLASSICAL = "$TERMUX__PREFIX"
#
# A package's configure therefore sees them unset and takes its own fallback to the official
# Termux values. termux-tools' configure.ac does exactly that (v1.45.0 lines 34-57):
#
#   if test "${TERMUX_APP_PACKAGE+set}" = set; then termux_app_package="$TERMUX_APP_PACKAGE"; else termux_app_package="com.termux"; fi
#   if test "${TERMUX_BASE_DIR+set}" = set;    then termux_base_dir="$TERMUX_BASE_DIR";       else termux_base_dir="/data/data/$termux_app_package/files"; fi
#   if test "${TERMUX_PREFIX+set}" = set;      then termux_prefix="$TERMUX_PREFIX";          else termux_prefix="$termux_base_dir/usr"; fi
#
# and AC_SUBSTs those into its .in templates. That is why a fork's bootstrap shipped
# etc/termux-login.sh, etc/motd.sh, etc/profile.d/init-termux-properties.sh,
# share/examples/termux/termux.properties and the bin/termux-* tools still naming com.termux
# and /data/data/com.termux/files/usr. Upstream never notices, because for upstream the
# fallbacks are the correct values.
#
# The value of TERMUX_PREFIX_CLASSICAL does NOT need overriding: properties.sh already sets
# it to the AgentX prefix, which is what the patch step substitutes for @TERMUX_PREFIX_CLASSICAL@.
# What was missing is the export, and that is all this adds -- beside the export of PREFIX in
# the same file, which exists for the same reason.
# ---------------------------------------------------------------------------
setup_variables_sh="$TERMUX_PACKAGES_DIR/scripts/build/termux_step_setup_variables.sh"
if [ -f "$setup_variables_sh" ] && ! grep -q "AGENTX: export the classical aliases" "$setup_variables_sh"; then
    python3 - "$setup_variables_sh" <<'PYEOF'
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
anchor = "\texport PREFIX=${TERMUX_PREFIX}\n"
if anchor not in text:
    sys.exit("anchor not found: export PREFIX=${TERMUX_PREFIX}")
addition = anchor + """
\t# AGENTX: export the classical aliases too, so a package's configure sees the AgentX
\t# values instead of falling back to the official Termux ones. Only non-empty variables are
\t# exported on purpose: configure tests `${VAR+set}`, so an exported-but-empty variable would
\t# be worse than an unset one.
\tfor agentx_variable in TERMUX_APP_PACKAGE TERMUX_APP__PACKAGE_NAME TERMUX_BASE_DIR \
\t\tTERMUX_CACHE_DIR TERMUX_PREFIX TERMUX_PREFIX_CLASSICAL TERMUX_ANDROID_HOME; do
\t\tif [ -n "${!agentx_variable}" ]; then
\t\t\texport "$agentx_variable"
\t\tfi
\tdone
"""
open(path, "w", encoding="utf-8").write(text.replace(anchor, addition, 1))
print("AGENTX: termux_step_setup_variables.sh: exported the classical aliases")
PYEOF
elif [ -f "$setup_variables_sh" ]; then
    echo "tweak: termux_step_setup_variables.sh already exports the aliases"
fi

# ---------------------------------------------------------------------------
# Source patches.
#
# patches/<package>.<name>.patch is installed into packages/<package>/<name>.patch, where
# upstream's patch step picks up any *.patch in a package directory and -- this is the part
# that matters -- substitutes the @TERMUX_*@ tokens inside it with the build's values before
# applying it (termux_step_patch_package). That is upstream's own mechanism for local
# changes, and it is why these patches carry @TERMUX_PREFIX@/@TERMUX_HOME@ tokens instead of
# literal paths: the same patch text stays correct for any prefix.
#
# The four files they fix carry the official prefix in prose or in a diagnostic string that
# no build variable reaches -- three documentation comments and one proot error message --
# which is exactly why exporting the build variables did not fix them and why they need a
# patch rather than a configure flag.
# ---------------------------------------------------------------------------
if [ -d "$tweaks_dir" ]; then
    shopt -s nullglob
    for patch_file in "$tweaks_dir"/*.patch; do
        patch_name="$(basename "$patch_file")"
        package="${patch_name%%.*}"
        installed="${patch_name#*.}"
        target_dir="$TERMUX_PACKAGES_DIR/packages/$package"
        if [ ! -d "$target_dir" ]; then
            echo "ERROR: $patch_file names package '$package' but $target_dir does not exist" >&2
            exit 1
        fi
        cp -f "$patch_file" "$target_dir/$installed"
        echo "patch: installed packages/$package/$installed"
    done
    shopt -u nullglob
fi
