#!/usr/bin/env bash
#
# Shell entry point for the artifact verifier. The checks themselves live in
# verify_bootstrap.py, which needs readelf/file and does the ELF and byte-level
# work; this wrapper only fills in the AgentX defaults and keeps the command line
# consistent with the rest of tools/termux-bootstrap.
#
# Usage:
#   ./verify-bootstrap.sh --zip bootstrap-aarch64.zip --arch aarch64 \
#                         [--profile agentx|official] [--prefix PATH] \
#                         [--source-revision REV] [--manifest-out manifest.json] \
#                         [--emit-kotlin]
#
# Exit status is 0 only when every check passed. A non-zero status must fail the
# build: publishing an unverified archive is worse than publishing nothing.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$(readlink -f -- "$0")")" && pwd)"
# shellcheck source=agentx-prefix.env
. "$SCRIPT_DIR/agentx-prefix.env"

ZIP=""
ARCH=""
PROFILE="agentx"
MANIFEST_OUT=""
EMIT_KOTLIN=0
EXTRA=()

while [ $# -gt 0 ]; do
    case "$1" in
        --zip)             ZIP="$2"; shift 2 ;;
        --arch)            ARCH="$2"; shift 2 ;;
        --profile)         PROFILE="$2"; shift 2 ;;
        --manifest-out)    MANIFEST_OUT="$2"; shift 2 ;;
        --emit-kotlin)     EMIT_KOTLIN=1; shift ;;
        --prefix|--source-revision) EXTRA+=("$1" "$2"); shift 2 ;;
        --quiet)           EXTRA+=("$1"); shift ;;
        -h|--help)         sed -n '2,17p' "$0"; exit 0 ;;
        *) echo "error: unknown option '$1'" >&2; exit 64 ;;
    esac
done

[ -n "$ZIP" ]  || { echo "error: --zip is required" >&2; exit 64; }
[ -n "$ARCH" ] || { echo "error: --arch is required" >&2; exit 64; }
[ -f "$ZIP" ]  || { echo "error: no such archive: $ZIP" >&2; exit 66; }

# Defaults come from the same constants the build uses, so a verifier run cannot
# disagree with the build about which prefix was intended.
if [ "$PROFILE" = "agentx" ]; then
    if [[ ! " ${EXTRA[*]} " =~ "--prefix " ]]; then
        EXTRA+=(--prefix "$AGENTX_PREFIX")
    fi
    if [[ ! " ${EXTRA[*]} " =~ "--source-revision " ]]; then
        EXTRA+=(--source-revision "$AGENTX_SOURCE_REVISION")
    fi
fi

[ -n "$MANIFEST_OUT" ] || MANIFEST_OUT="$(dirname -- "$(readlink -f -- "$ZIP")")/manifest-${ARCH}.json"
EXTRA+=(--manifest-out "$MANIFEST_OUT")
[ "$EMIT_KOTLIN" = "1" ] && EXTRA+=(--emit-kotlin)

python3 "$SCRIPT_DIR/verify_bootstrap.py" \
    --zip "$ZIP" --arch "$ARCH" --profile "$PROFILE" "${EXTRA[@]}"
