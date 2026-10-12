#!/usr/bin/env bash
# check-hooks.sh - verify that all SelfFramed hooks in upstream files are still in place.
#
# Reads tools/sf/hooks.txt (lines "<repo-relative-path><TAB><min marker lines>") and checks that
# every listed file exists and contains at least that many lines with the literal tag
# "[SelfFramed]". Afterwards it scans all tracked files (git grep) and warns about marker lines in
# files that are not listed (fork-owned files are skipped, see is_fork_owned).
#
# Usage:   tools/sf/check-hooks.sh [--strict]
#          --strict  treat warnings (unlisted markers, duplicate entries) as errors
# Exit:    0 = all hooks present (warnings possible), 1 = hook missing / strict warning,
#          2 = usage or environment error
#
# Works in Git Bash on Windows and on Linux; tolerates CRLF in hooks.txt and in checked files.

set -euo pipefail

readonly MARKER='[SelfFramed]'
readonly HOOKS_REL='tools/sf/hooks.txt'

strict=0
case "${1:-}" in
    '') ;;
    --strict) strict=1 ;;
    -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "usage: $0 [--strict]" >&2; exit 2 ;;
esac

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if ! repo_root=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null); then
    echo "check-hooks: ERROR: $script_dir is not inside a git work tree" >&2
    exit 2
fi
cd "$repo_root"

if [[ ! -f $HOOKS_REL ]]; then
    echo "check-hooks: ERROR: $HOOKS_REL not found" >&2
    exit 2
fi

# Files that belong to the fork entirely; markers there are expected and never reported.
is_fork_owned() {
    local p=$1
    case $p in
        docs/*|tools/sf/*|.github/*|CLAUDE.md) return 0 ;;
        app/src/*/java/com/limelight/selfframed/*) return 0 ;;
        app/selfframed.*|sf-signing.properties) return 0 ;;
        */res/*/sf_*) return 0 ;;
    esac
    return 1
}

# Number of lines containing the marker (0 if none).
count_markers() {
    local n
    n=$(grep -c -F -- "$MARKER" "$1" 2>/dev/null || true)
    echo "${n:-0}"
}

failures=0
warnings=0
entries=0
declare -A listed=()

echo "check-hooks: $HOOKS_REL"

lineno=0
while IFS= read -r line || [[ -n $line ]]; do
    lineno=$((lineno + 1))
    line=${line%$'\r'}
    # trim leading/trailing whitespace
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z $line || $line == \#* ]] && continue

    if [[ $line == *$'\t'* ]]; then
        path=${line%%$'\t'*}
        min=${line##*$'\t'}
    else
        # tolerate spaces instead of a TAB
        path=${line%%[[:space:]]*}
        min=${line##*[[:space:]]}
    fi
    path="${path%"${path##*[![:space:]]}"}"
    path=${path#./}
    min="${min#"${min%%[![:space:]]*}"}"

    if [[ -z $path || $path == "$line" || ! $min =~ ^[0-9]+$ ]]; then
        echo "  FAIL  line $lineno: malformed entry '$line' (expected <path><TAB><count>)"
        failures=$((failures + 1))
        continue
    fi
    if [[ -n ${listed[$path]+x} ]]; then
        echo "  WARN  line $lineno: duplicate entry for $path"
        warnings=$((warnings + 1))
        continue
    fi
    listed[$path]=$min
    entries=$((entries + 1))

    if [[ ! -f $path ]]; then
        echo "  FAIL  $path: file missing (renamed or deleted upstream?)"
        failures=$((failures + 1))
        continue
    fi
    have=$(count_markers "$path")
    if (( have < min )); then
        echo "  FAIL  $path: $have marker line(s), expected >= $min"
        failures=$((failures + 1))
    else
        echo "  OK    $path: $have marker line(s) (>= $min)"
    fi
done < "$HOOKS_REL"

# Markers in tracked files that are not listed in hooks.txt.
while IFS= read -r hit; do
    hit=${hit%$'\r'}
    [[ -z $hit ]] && continue
    path=${hit%:*}
    n=${hit##*:}
    [[ -n ${listed[$path]+x} ]] && continue
    is_fork_owned "$path" && continue
    echo "  WARN  $path: $n marker line(s), but not listed in $HOOKS_REL"
    warnings=$((warnings + 1))
done < <(git grep -c -I -F -e "$MARKER" -- . 2>/dev/null || true)

if (( strict && warnings > 0 )); then
    failures=$((failures + warnings))
fi

if (( failures > 0 )); then
    echo "check-hooks: FAILED ($entries listed file(s), $failures problem(s), $warnings warning(s))"
    echo "check-hooks: re-apply the missing hooks by intent (docs/selfframed/PATCHES.md) or fix $HOOKS_REL"
    exit 1
fi
echo "check-hooks: OK ($entries listed file(s), $warnings warning(s))"
exit 0
