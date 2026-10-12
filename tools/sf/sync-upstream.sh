#!/usr/bin/env bash
# sync-upstream.sh - local procedure for merging an upstream Moonlight release tag into the
# SelfFramed fork branch "main". Runbook: docs/selfframed/UPSTREAM_SYNC.md
#
# Usage:
#   tools/sf/sync-upstream.sh [<tag>]     merge <tag> (default: newest upstream release tag) into a
#                                         new branch sync/upstream-<tag> created from main
#   tools/sf/sync-upstream.sh --continue  after resolving conflicts (git add): verify, check hooks,
#                                         commit the merge, reset sf.revision to 1
#   tools/sf/sync-upstream.sh --abort     abort the sync merge and return to main
#
# The script never pushes. Exit codes: 0 = done or nothing to do, 1 = error,
# 3 = merge stopped with conflicts (resolve them, then run --continue).
#
# Works in Git Bash on Windows and on Linux.

set -euo pipefail

readonly UPSTREAM_REMOTE=upstream
readonly UPSTREAM_URL=https://github.com/moonlight-stream/moonlight-android.git
readonly MAIN_BRANCH=main
readonly TAG_RE='^v[0-9]+(\.[0-9]+)*$'
readonly BRANCH_PREFIX=sync/upstream-
readonly MARKER='[SelfFramed]'
readonly PROPS=app/selfframed.properties
readonly PATCHES=docs/selfframed/PATCHES.md
readonly HOOKS=tools/sf/hooks.txt
readonly CHECK_HOOKS=tools/sf/check-hooks.sh
readonly RUNBOOK=docs/selfframed/UPSTREAM_SYNC.md

die()  { echo "ERROR: $*" >&2; exit 1; }
warn() { echo "WARNING: $*" >&2; }
info() { echo "==> $*"; }

usage() { sed -n '2,16p' "${BASH_SOURCE[0]}"; }

merge_message() { echo "Merge upstream $1 into SelfFramed main"; }

current_branch() { git symbolic-ref --short -q HEAD || true; }

merge_in_progress() { git rev-parse -q --verify MERGE_HEAD >/dev/null; }

# File inside .git that remembers the conflicted paths between start and --continue.
conflict_note() { git rev-parse --git-path sf-sync-conflicts; }

tag_commit() { git rev-parse -q --verify "refs/tags/$1^{commit}"; }

newest_upstream_tag() {
    git ls-remote --tags --refs "$UPSTREAM_REMOTE" \
        | tr -d '\r' \
        | sed 's#.*refs/tags/##' \
        | grep -E "$TAG_RE" \
        | sort -V \
        | tail -n 1 || true
}

submodule_paths() {
    git config -f .gitmodules --get-regexp '\.path$' 2>/dev/null | awk '{print $2}' || true
}

# Prints "yes", "name" (only the file name is mentioned) or "no"; "n/a" if the file is missing.
mentioned_in() {
    local doc=$1 path=$2
    [[ -f $doc ]] || { echo "n/a"; return; }
    if grep -q -F -- "$path" "$doc"; then
        echo "yes"
    elif grep -q -F -- "${path##*/}" "$doc"; then
        echo "name"
    else
        echo "no"
    fi
}

report_conflicts() {
    local tag=$1 f patches hooks ours
    local -a files
    mapfile -t files < <(git diff --name-only --diff-filter=U)
    printf '%s\n' "${files[@]}" > "$(conflict_note)"

    echo
    echo "Merge of $tag stopped with ${#files[@]} conflicted file(s):"
    printf '  %-72s %-10s %-9s %s\n' "file" "PATCHES.md" "hooks.txt" "[SelfFramed] lines on main"
    for f in "${files[@]}"; do
        patches=$(mentioned_in "$PATCHES" "$f")
        hooks=$(mentioned_in "$HOOKS" "$f")
        ours=$(git show ":2:$f" 2>/dev/null | grep -c -F -- "$MARKER" || true)
        printf '  %-72s %-10s %-9s %s\n' "$f" "$patches" "$hooks" "${ours:-0}"
    done
    cat <<EOF

How to continue (rules: $RUNBOOK, section "Konfliktregeln"):
  1. Resolve every file. Upstream wins everywhere except inside [SelfFramed] blocks;
     re-apply each hook by its intent as described in $PATCHES.
     Inspect: git diff <file>  |  main side: git show :2:<file>  |  upstream side: git show :3:<file>
  2. Do not leave conflict markers. Stage each resolved file: git add <file>   (do NOT commit)
  3. Run: bash tools/sf/sync-upstream.sh --continue
  To give up: bash tools/sf/sync-upstream.sh --abort
EOF
}

# Fails if files changed relative to main still contain conflict markers.
check_conflict_markers() {
    local f
    local -a bad=()
    while IFS= read -r f; do
        [[ -f $f ]] || continue
        if grep -I -q -E '^(<<<<<<<|>>>>>>>|\|\|\|\|\|\|\|)( |$)' -- "$f" 2>/dev/null; then
            bad+=("$f")
        fi
    done < <(git diff --name-only "$MAIN_BRANCH" --)
    if (( ${#bad[@]} > 0 )); then
        echo "Conflict markers remain in:" >&2
        printf '  %s\n' "${bad[@]}" >&2
        die "remove the markers, git add the files, then run --continue again"
    fi
}

run_hook_check() {
    if [[ ! -f $CHECK_HOOKS ]]; then
        warn "$CHECK_HOOKS not found; hook check skipped"
        return 0
    fi
    info "Checking hooks ($CHECK_HOOKS)"
    bash "$CHECK_HOOKS"
}

# Sets sf.revision=1 in app/selfframed.properties and commits that change separately.
reset_revision() {
    local tag=$1 cur tmp
    if [[ ! -f $PROPS ]]; then
        warn "$PROPS not found; sf.revision not reset"
        return 0
    fi
    cur=$(sed -n 's/^[[:space:]]*sf\.revision[[:space:]]*[=:][[:space:]]*\([0-9][0-9]*\).*/\1/p' "$PROPS" | head -n 1)
    if [[ -z $cur ]]; then
        warn "no sf.revision entry in $PROPS; not reset"
        return 0
    fi
    if [[ $cur == 1 ]]; then
        info "sf.revision is already 1"
        return 0
    fi
    tmp="$PROPS.sf-tmp.$$"
    # sed keeps a trailing CR, so CRLF files stay CRLF; \1 followed by a literal 1.
    sed 's/^\([[:space:]]*sf\.revision[[:space:]]*[=:][[:space:]]*\)[0-9][0-9]*/\11/' "$PROPS" > "$tmp"
    cat "$tmp" > "$PROPS"
    rm -f "$tmp"
    git add -- "$PROPS"
    git commit -q -m "Reset sf.revision to 1 for upstream $tag" -- "$PROPS"
    info "sf.revision reset from $cur to 1 (separate commit)"
}

print_next_steps() {
    local tag=$1 branch=$2 p changed=()
    for p in $(submodule_paths); do
        git diff --quiet "$MAIN_BRANCH" HEAD -- "$p" || changed+=("$p")
    done
    echo
    info "Branch $branch now contains upstream $tag (nothing was pushed)."
    git --no-pager log --oneline -3
    if (( ${#changed[@]} > 0 )); then
        echo
        echo "NOTE: upstream moved submodule pointer(s): ${changed[*]}"
    fi
    cat <<EOF

Next steps ($RUNBOOK):
  1. Update submodules:     git submodule update --init --recursive
  2. Build and unit tests (low priority, Windows):
        powershell -ExecutionPolicy Bypass -File tools/sf/build-lowprio.ps1 -Wait
     elsewhere:  ./gradlew --no-daemon testNonRootDebugUnitTest assembleNonRootDebug
  3. If upstream touched decoder, surface or rendering code: re-read docs/selfframed/ARCHITEKTUR.md
     and run the device test in docs/selfframed/TESTPROTOKOLL.md.
  4. Update docs/selfframed/PATCHES.md (re-applied or changed hooks, $HOOKS counts)
     and docs/selfframed/CHANGELOG.md; commit on $branch.
  5. Integrate (pushing needs Martin's consent):
        PR:    git push -u origin $branch   then open a PR into main
               (gh pr create --repo SelfFramed/moonlight-android --base main);
               merge it with "Create a merge commit", never squash or rebase
        local: git switch $MAIN_BRANCH && git merge --ff-only $branch
EOF
}

# finish <tag> <branch> [hooks-checked]
finish() {
    local tag=$1 branch=$2 checked=${3:-} note
    if [[ -z $checked ]]; then
        run_hook_check || die "hook check failed. Re-apply the hooks on $branch (see $PATCHES), commit, then run --continue again."
    fi
    reset_revision "$tag"
    note=$(conflict_note)
    rm -f "$note"
    print_next_steps "$tag" "$branch"
}

start_sync() {
    local tag_arg=$1 tag branch rc url hooked f base

    [[ $(current_branch) == "$MAIN_BRANCH" ]] \
        || die "not on '$MAIN_BRANCH' (current: '$(current_branch)'). Run: git switch $MAIN_BRANCH"
    merge_in_progress && die "a merge is already in progress. Use --continue or --abort."
    if ! git diff --quiet --ignore-submodules=all || ! git diff --cached --quiet --ignore-submodules=all; then
        git status --short --untracked-files=no >&2
        die "working tree has uncommitted changes. Commit or stash them first."
    fi
    if [[ -n $(git ls-files --others --exclude-standard | head -n 1) ]]; then
        warn "untracked files present (not blocking): git status"
    fi

    if ! url=$(git remote get-url "$UPSTREAM_REMOTE" 2>/dev/null); then
        echo "ERROR: remote '$UPSTREAM_REMOTE' is missing. Add it with:" >&2
        echo "  git remote add $UPSTREAM_REMOTE $UPSTREAM_URL" >&2
        exit 1
    fi
    [[ $url == "$UPSTREAM_URL" || $url == "${UPSTREAM_URL%.git}" ]] \
        || warn "remote '$UPSTREAM_REMOTE' points to $url (expected $UPSTREAM_URL)"

    # Local main must not be behind the fork on GitHub (e.g. after a merged watcher PR).
    if git remote get-url origin >/dev/null 2>&1; then
        if git fetch -q --no-tags origin "+refs/heads/$MAIN_BRANCH:refs/remotes/origin/$MAIN_BRANCH" 2>/dev/null; then
            git merge-base --is-ancestor "refs/remotes/origin/$MAIN_BRANCH" HEAD \
                || die "local $MAIN_BRANCH is behind or diverged from origin/$MAIN_BRANCH. Run: git pull --ff-only origin $MAIN_BRANCH"
        else
            warn "could not fetch origin/$MAIN_BRANCH; skipping the up-to-date check"
        fi
    fi

    info "Fetching $UPSTREAM_REMOTE"
    git fetch -q --no-tags "$UPSTREAM_REMOTE" "+refs/heads/master:refs/remotes/$UPSTREAM_REMOTE/master"

    if [[ -n $tag_arg ]]; then
        [[ $tag_arg =~ $TAG_RE ]] \
            || die "'$tag_arg' is not an upstream release tag (pattern $TAG_RE). Fork tags like v12.2-sf.1 are never merged."
        git ls-remote --exit-code --tags --refs "$UPSTREAM_REMOTE" "refs/tags/$tag_arg" >/dev/null \
            || die "tag $tag_arg does not exist on $UPSTREAM_REMOTE"
        tag=$tag_arg
    else
        tag=$(newest_upstream_tag)
        [[ -n $tag ]] || die "no upstream release tags found on $UPSTREAM_REMOTE"
        info "Newest upstream release tag: $tag"
    fi

    # Fetch just this tag; refuses to overwrite a local tag of the same name that differs.
    git fetch -q --no-tags "$UPSTREAM_REMOTE" "refs/tags/$tag:refs/tags/$tag"
    tag_commit "$tag" >/dev/null || die "could not resolve tag $tag after fetching"

    if git merge-base --is-ancestor "refs/tags/$tag^{commit}" HEAD; then
        info "$tag is already merged into $MAIN_BRANCH. Nothing to do."
        exit 0
    fi

    branch="$BRANCH_PREFIX$tag"
    if git show-ref --verify --quiet "refs/heads/$branch"; then
        die "branch $branch already exists. Continue there (git switch $branch) or delete it (git branch -D $branch)."
    fi
    if git show-ref --verify --quiet "refs/remotes/origin/$branch" \
        || git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
        die "branch $branch exists on origin (probably opened by the upstream watcher). Use it: git fetch origin && git switch --track origin/$branch"
    fi

    info "Upstream commits to merge: $(git rev-list --count --no-merges "HEAD..refs/tags/$tag")"
    base=$(git merge-base HEAD "refs/tags/$tag")
    hooked=()
    while IFS= read -r f; do
        f=${f%$'\r'}
        [[ -z $f || $f == \#* ]] && continue
        f=${f%%[[:space:]]*}
        git diff --quiet "$base" "refs/tags/$tag" -- "$f" || hooked+=("$f")
    done < <([[ -f $HOOKS ]] && cat "$HOOKS")
    if (( ${#hooked[@]} > 0 )); then
        echo "    upstream changed hooked file(s):"
        printf '      %s\n' "${hooked[@]}"
    fi

    info "Creating $branch and merging $tag"
    git switch -q -c "$branch"
    set +e
    git merge --no-ff --no-edit -m "$(merge_message "$tag")" "refs/tags/$tag"
    rc=$?
    set -e

    if (( rc != 0 )); then
        if merge_in_progress; then
            report_conflicts "$tag"
            exit 3
        fi
        git switch -q "$MAIN_BRANCH"
        git branch -q -D "$branch"
        die "git merge failed without leaving a merge in progress (see output above). Back on $MAIN_BRANCH."
    fi

    finish "$tag" "$branch"
}

continue_sync() {
    local branch tag f unstaged note msg
    local -a unmerged files

    branch=$(current_branch)
    [[ $branch == "$BRANCH_PREFIX"* ]] || die "not on a $BRANCH_PREFIX<tag> branch (current: '$branch')"
    tag=${branch#"$BRANCH_PREFIX"}
    [[ $tag =~ $TAG_RE ]] || die "cannot derive an upstream tag from branch name $branch"
    tag_commit "$tag" >/dev/null || die "tag $tag is missing locally. Run: git fetch --no-tags $UPSTREAM_REMOTE refs/tags/$tag:refs/tags/$tag"

    if merge_in_progress; then
        [[ $(git rev-parse MERGE_HEAD) == "$(tag_commit "$tag")" ]] \
            || die "the merge in progress is not a merge of $tag"
        mapfile -t unmerged < <(git diff --name-only --diff-filter=U)
        if (( ${#unmerged[@]} > 0 )); then
            echo "Still unresolved (resolve, then git add):" >&2
            printf '  %s\n' "${unmerged[@]}" >&2
            die "unresolved files remain"
        fi
        unstaged=$(git diff --name-only --ignore-submodules=all)
        [[ -z $unstaged ]] || die "unstaged changes in: $(echo $unstaged). git add them (or revert them) first."
        check_conflict_markers
        run_hook_check || die "hook check failed. Fix the hooks (see $PATCHES), git add, then run --continue again."

        msg=$(merge_message "$tag")
        note=$(conflict_note)
        if [[ -s $note ]]; then
            mapfile -t files < "$note"
            msg+=$'\n\nResolved conflicts in:'
            for f in "${files[@]}"; do msg+=$'\n'"  $f"; done
        fi
        git commit -q -m "$msg"
        info "Merge commit created: $(git rev-parse --short HEAD)"
        finish "$tag" "$branch" checked
        return
    else
        git merge-base --is-ancestor "refs/tags/$tag^{commit}" HEAD \
            || die "$tag is not merged into $branch and no merge is in progress. Abort (--abort) and start again."
        if ! git diff --quiet --ignore-submodules=all || ! git diff --cached --quiet --ignore-submodules=all; then
            die "uncommitted changes on $branch. Commit them first."
        fi
        check_conflict_markers
    fi

    finish "$tag" "$branch"
}

abort_sync() {
    local branch
    branch=$(current_branch)
    [[ $branch == "$BRANCH_PREFIX"* ]] || die "not on a $BRANCH_PREFIX<tag> branch (current: '$branch')"
    if merge_in_progress; then
        git merge --abort
        info "Merge aborted"
    fi
    rm -f "$(conflict_note)"
    git switch -q "$MAIN_BRANCH"
    if git branch -q -d "$branch" 2>/dev/null; then
        info "Back on $MAIN_BRANCH; deleted $branch (it had no own commits)"
    else
        info "Back on $MAIN_BRANCH; kept $branch (it has commits). Delete it with: git branch -D $branch"
    fi
}

main() {
    local mode=start tag_arg=''
    while (( $# > 0 )); do
        case $1 in
            --continue) mode=continue ;;
            --abort) mode=abort ;;
            -h|--help) usage; exit 0 ;;
            -*) usage >&2; die "unknown option $1" ;;
            *) [[ -z $tag_arg ]] || die "only one tag allowed"; tag_arg=$1 ;;
        esac
        shift
    done
    [[ $mode == start || -z $tag_arg ]] || die "--continue/--abort take no tag"

    local root
    root=$(git rev-parse --show-toplevel 2>/dev/null) || die "not inside a git work tree"
    cd "$root"

    case $mode in
        start) start_sync "$tag_arg" ;;
        continue) continue_sync ;;
        abort) abort_sync ;;
    esac
}

main "$@"
