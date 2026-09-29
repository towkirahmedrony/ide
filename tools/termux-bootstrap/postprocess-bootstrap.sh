#!/usr/bin/env bash
#
# Turn the archive produced by termux-packages' scripts/build-bootstraps.sh into
# the artifact :termux-runtime expects, and nothing more.
#
# What it does, in order:
#   1. unpacks the archive;
#   2. asserts the shape (SYMLINKS.txt present, no symlinks left in the tree);
#   3. writes the install marker the installer tests for
#      (etc/termux/agentx-bootstrap.ok);
#   4. rewrites etc/apt/sources.list so apt cannot reach the official Termux
#      repository, which is compiled for a prefix this app cannot use;
#   5. repacks the archive with the same entry naming upstream uses.
#
# Usage:
#   ./postprocess-bootstrap.sh --zip <bootstrap-<arch>.zip> --arch <aarch64|arm|i686|x86_64>
#                             [--apt-repo-url <url>] [--apt-repo-distribution stable]
#                             [--variant auto|normal|android10]
#                             [--apt-repo-component main] [--out-dir <dir>]
#
# Exits non-zero on any violation. It never writes a modified archive in place:
# the original is left untouched so a failed post-process cannot be mistaken for
# a good build.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$(readlink -f -- "$0")")" && pwd)"
# shellcheck source=agentx-prefix.env
. "$SCRIPT_DIR/agentx-prefix.env"

ZIP=""
ARCH=""
APT_REPO_URL=""
APT_REPO_DISTRIBUTION="stable"
APT_REPO_COMPONENT="main"
VARIANT="auto"
OUT_DIR=""

while [ $# -gt 0 ]; do
    case "$1" in
        --zip)                   ZIP="$2"; shift 2 ;;
        --arch)                  ARCH="$2"; shift 2 ;;
        --apt-repo-url)          APT_REPO_URL="$2"; shift 2 ;;
        --apt-repo-distribution) APT_REPO_DISTRIBUTION="$2"; shift 2 ;;
        --apt-repo-component)    APT_REPO_COMPONENT="$2"; shift 2 ;;
        --variant)               VARIANT="$2"; shift 2 ;;
        --out-dir)               OUT_DIR="$2"; shift 2 ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "error: unknown option '$1'" >&2; exit 64 ;;
    esac
done

[ -n "$ZIP" ]  || { echo "error: --zip is required" >&2; exit 64; }
[ -n "$ARCH" ] || { echo "error: --arch is required" >&2; exit 64; }
[ -f "$ZIP" ]  || { echo "error: no such archive: $ZIP" >&2; exit 66; }

ANDROID_ABI="$(agentx_android_abi_for_arch "$ARCH")" || { echo "error: unknown Termux arch '$ARCH'" >&2; exit 64; }
[ -n "$OUT_DIR" ] || OUT_DIR="$(dirname -- "$(readlink -f -- "$ZIP")")"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd -- "$OUT_DIR" && pwd)"
ASSET_NAME="$(agentx_asset_name_for_arch "$ARCH")"

say()  { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

for tool in unzip zip sha256sum python3 find; do
    command -v "$tool" >/dev/null 2>&1 || fail "required tool '$tool' is not available"
done

# A repository URL is opt-in, and it may never be an official Termux repository:
# "do not silently point apt at the official repo" is enforced, not documented.
if [ -n "$APT_REPO_URL" ]; then
    case "$APT_REPO_URL" in
        *packages.termux.dev*|*packages-cf.termux.dev*)
            fail "--apt-repo-url points at an official Termux repository ($APT_REPO_URL).
       Those debs are built for /data/data/com.termux/files/usr and cannot run in
       $AGENTX_PREFIX. Host a repository built for the AgentX prefix instead (see README.md)." ;;
    esac
    case "$APT_REPO_URL" in
        https://*|http://*|file:*) : ;;
        *) fail "--apt-repo-url must be an http(s) or file: URL (got '$APT_REPO_URL')" ;;
    esac
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agentx-bootstrap-post.XXXXXXXX")"
cleanup() { rm -rf "$WORK" 2>/dev/null || true; }
trap cleanup EXIT

TREE="$WORK/tree"
mkdir -p "$TREE"

say "unpacking $(basename "$ZIP")"
unzip -qq -o "$ZIP" -d "$TREE"

# --- 2. Shape assertions ----------------------------------------------------------

[ -f "$TREE/$AGENTX_SYMLINK_MANIFEST" ] || fail \
    "$AGENTX_SYMLINK_MANIFEST is missing from $(basename "$ZIP"); the installer refuses such an archive"

LEFTOVER_LINKS="$(find "$TREE" -type l | wc -l)"
[ "$LEFTOVER_LINKS" = "0" ] || fail \
    "found $LEFTOVER_LINKS symlinks still inside the archive. Upstream moves symlinks into
       $AGENTX_SYMLINK_MANIFEST and deletes them; if that changed, the installer would
       extract dangling links. First offender: $(find "$TREE" -type l | head -1)"

# The manifest must use the U+2190 separator the installer splits on, with exactly
# one separator per line.
python3 - "$TREE/$AGENTX_SYMLINK_MANIFEST" <<'PY'
import sys
path = sys.argv[1]
separator = "\u2190"
bad = []
count = 0
with open(path, encoding="utf-8") as handle:
    for number, raw in enumerate(handle, 1):
        line = raw.rstrip("\n")
        if not line.strip():
            continue
        count += 1
        if line.count(separator) != 1:
            bad.append((number, line))
if bad:
    print("error: %s has %d lines that do not contain exactly one U+2190 separator" % (path, len(bad)), file=sys.stderr)
    for number, line in bad[:5]:
        print("  line %d: %s" % (number, line), file=sys.stderr)
    sys.exit(1)
if count == 0:
    print("error: %s is empty" % path, file=sys.stderr)
    sys.exit(1)
print("  symlink manifest: %d entries, all with exactly one U+2190" % count)
PY

# --- 2b. Which upstream variant is this? -------------------------------------------
# Two independent markers, both switched by the same build-bootstraps.sh flag: the dpkg
# database exists only in the normal variant, and proot only in the --android10 one.
VARIANT_DETECTED="normal"
if [ -f "$TREE/bin/proot" ] && [ ! -f "$TREE/var/lib/dpkg/status" ]; then
    VARIANT_DETECTED="android10"
elif [ ! -f "$TREE/bin/proot" ] && [ ! -f "$TREE/var/lib/dpkg/status" ]; then
    fail "extracted archive is neither variant: no bin/proot and no var/lib/dpkg/status"
fi
if [ "$VARIANT" != "auto" ] && [ "$VARIANT" != "$VARIANT_DETECTED" ]; then
    fail "--variant $VARIANT was requested but the archive is $VARIANT_DETECTED"
fi
VARIANT="$VARIANT_DETECTED"
say "variant: $VARIANT"

# --- 3. Install marker ------------------------------------------------------------

MARKER="$TREE/$AGENTX_INSTALL_MARKER"
mkdir -p "$(dirname -- "$MARKER")"
if [ "$VARIANT" = "android10" ]; then
    # This variant ships neither apt nor etc/apt, so there is nothing to harden. Recorded in
    # the marker so the reason travels with the archive instead of looking like a mistake.
    APT_REPOSITORY_VALUE="not-applicable-android10"
else
    APT_REPOSITORY_VALUE="${APT_REPO_URL:-disabled}"
fi
cat >"$MARKER" <<EOF
# AgentX Termux bootstrap marker.
#
# Presence of this file is what TermuxBootstrapInstaller.isInstalled() checks
# before it decides a prefix is usable.
package=$AGENTX_PACKAGE_NAME
rootfs=$AGENTX_ROOTFS
prefix=$AGENTX_PREFIX
home=$AGENTX_HOME
android_abi=$ANDROID_ABI
termux_arch=$ARCH
source_revision=$AGENTX_SOURCE_REVISION
apt_repository=$APT_REPOSITORY_VALUE
EOF
say "wrote $AGENTX_INSTALL_MARKER"

# --- 4. apt sources ---------------------------------------------------------------
# Skipped entirely for --android10: writing etc/apt/sources.list into an archive that has no
# apt would invent a file upstream does not ship.
if [ "$VARIANT" = "android10" ]; then
    say "apt sources: not applicable to the android10 variant (no apt in this bootstrap)"
else

SOURCES="$TREE/etc/apt/sources.list"
mkdir -p "$(dirname -- "$SOURCES")"

if [ -n "$APT_REPO_URL" ]; then
    cat >"$SOURCES" <<EOF
# AgentX Termux bootstrap: package sources.
#
# This line points at a repository whose packages were built for
# $AGENTX_PREFIX.
deb $APT_REPO_URL $APT_REPO_DISTRIBUTION $APT_REPO_COMPONENT

# The official Termux repositories are deliberately NOT listed. Their packages
# are compiled for /data/data/com.termux/files/usr and would install binaries
# that cannot run in this prefix.
# deb https://packages-cf.termux.dev/apt/termux-main/ stable main
# deb https://packages.termux.dev/apt/termux-main/ stable main
EOF
    say "apt sources: $APT_REPO_URL ($APT_REPO_DISTRIBUTION $APT_REPO_COMPONENT)"
else
    cat >"$SOURCES" <<EOF
# AgentX Termux bootstrap: package sources are intentionally empty.
#
# The official Termux repositories publish .deb files compiled for
# /data/data/com.termux/files/usr. Installing those into this prefix
# ($AGENTX_PREFIX) yields binaries whose ELF RUNPATH
# points at a directory this app does not own, so they cannot start. apt is
# therefore pointed at nothing at all rather than at a repository that is known
# to be incompatible.
#
# Consequence, stated plainly: 'pkg install <package>' fails with
# "Unable to locate package" until an AgentX-prefix repository is hosted and
# added below. Run 'pkg upgrade' / 'apt update' only after that.
#
# To enable package installation:
#   1. build packages with the same pinned termux-packages revision and the same
#      TERMUX_APP__PACKAGE_NAME patch (tools/termux-bootstrap/README.md);
#   2. publish the resulting deb repository;
#   3. add a line such as the following to this file, or drop it into
#      /etc/apt/sources.list.d/:
#          deb https://example.invalid/agentx-apt stable main

# Upstream's defaults, kept commented out so the decision stays visible:
# The main termux repository, with cloudflare cache
# deb https://packages-cf.termux.dev/apt/termux-main/ stable main
# The main termux repository, without cloudflare cache
# deb https://packages.termux.dev/apt/termux-main/ stable main
EOF
    say "apt sources: disabled (no AgentX repository configured)"
fi

# Guard: no active 'deb' line may exist unless a repository was explicitly given.
ACTIVE_DEBS="$(grep -cE '^[[:space:]]*deb[[:space:]]' "$SOURCES" || true)"
if [ -z "$APT_REPO_URL" ] && [ "$ACTIVE_DEBS" != "0" ]; then
    fail "generated $SOURCES with $ACTIVE_DEBS active 'deb' line(s) although no repository was configured"
fi

# Any other sources.list.d entry shipped by a package must not reach the official repo.
if [ -d "$TREE/etc/apt/sources.list.d" ]; then
    while IFS= read -r extra; do
        if grep -qE '^[[:space:]]*deb[[:space:]].*(packages\.termux\.dev|packages-cf\.termux\.dev)' "$extra"; then
            fail "$extra points apt at an official Termux repository; remove or rewrite it in postprocess-bootstrap.sh"
        fi
    done < <(find "$TREE/etc/apt/sources.list.d" -type f)
fi

fi

# --- 5. Repack --------------------------------------------------------------------

# Entry naming must match upstream's `cd $PREFIX && zip -r9 <out> ./*`: paths are
# relative to the prefix with no leading './'. The installer tolerates either, but
# keeping the two identical means a diff of two archives is meaningful.
OUT="$WORK/$ASSET_NAME"
( cd "$TREE" && zip -q -r9 "$OUT" ./* )

# An empty top level would mean the glob matched nothing. The listing is captured
# first: `unzip | grep -q` would let grep close the pipe early, and with pipefail
# the resulting SIGPIPE on unzip would be mistaken for a failed verification.
REPACKED_LISTING="$(unzip -l "$OUT")"
grep -qE '(^|[[:space:]])(bin|etc|lib|var)/' <<<"$REPACKED_LISTING" \
    || fail "repacked archive has no recognised top-level directories"

mkdir -p "$OUT_DIR"
FINAL="$OUT_DIR/$ASSET_NAME"
cp -f "$OUT" "$FINAL"

FILES=$(find "$TREE" -type f | wc -l)
DIRS=$(find "$TREE" -mindepth 1 -type d | wc -l)
LINKS=$(grep -c . "$TREE/$AGENTX_SYMLINK_MANIFEST")
SIZE=$(stat -c%s "$FINAL")
SHA=$(sha256sum "$FINAL" | cut -d' ' -f1)

say ""
say "postprocessed $ASSET_NAME"
say "  path          : $FINAL"
say "  archive bytes : $SIZE"
say "  sha256        : $SHA"
say "  files         : $FILES"
say "  directories   : $DIRS"
say "  symlinks      : $LINKS"
