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
