#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# autofix.sh — Cron-driven bug-fixing agent for Anova
#
# Monitors itsikh/Anova for open issues labelled "autofix",
# dispatches Claude CLI to fix each bug autonomously, retries on failure,
# and triggers a release when done.
#
# Cron example (every 4 minutes):
#   */4 * * * * /Users/itsik-personal/dev/Anova/.claude/skills/autofix/autofix-wrapper.sh >> /Users/itsik-personal/dev/Anova/.autofix-logs/cron.log 2>&1
###############################################################################

export PATH="/opt/homebrew/bin:/Users/itsik-personal/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
export GIT_SSH_COMMAND="ssh -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o BatchMode=yes"

# Bash-native timeout (macOS cron has no `timeout` command)
run_timed() {
    local secs="$1"; shift
    "$@" &
    local child=$!
    ( sleep "$secs" && kill "$child" 2>/dev/null ) &
    local timer=$!
    wait "$child"; local ret=$?
    kill "$timer" 2>/dev/null; wait "$timer" 2>/dev/null || true
    return $ret
}

# ── Configuration ─────────────────────────────────────────────────────────────
PROJECT_DIR="/Users/itsik-personal/dev/Anova"
BUGS_REPO="itsikh/Anova"
LOCK_DIR="/tmp/anova-autofix.lockdir"
LOG_DIR="$PROJECT_DIR/.autofix-logs"
PROMPT_TEMPLATE="$PROJECT_DIR/.claude/skills/autofix/fix-prompt.txt"
MAX_RETRIES=3
JAVA_HOME_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ACTIVE_LABEL="claude-active"
AUTOFIX_LABEL="autofix"

WORK_TMP=""

# ── Helpers ───────────────────────────────────────────────────────────────────
timestamp() { date "+%Y-%m-%d %H:%M:%S"; }
log()       { echo "[$(timestamp)] $*"; }
log_error() { echo "[$(timestamp)] ERROR: $*" >&2; }

cleanup() {
    rm -rf "$LOCK_DIR"
    [[ -n "$WORK_TMP" ]] && rm -rf "$WORK_TMP"
    log "Lock released, exiting."
}

# ── Lock Management ───────────────────────────────────────────────────────────
write_lock_info() {
    echo $$ > "$LOCK_DIR/pid"
    ps -p $$ -o lstart= > "$LOCK_DIR/lstart" 2>/dev/null || true
}

is_lock_holder_alive() {
    local old_pid
    old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "")
    [[ -z "$old_pid" ]] && return 1
    kill -0 "$old_pid" 2>/dev/null || return 1
    local pstate
    pstate=$(ps -p "$old_pid" -o stat= 2>/dev/null || echo "")
    [[ "$pstate" == T* ]] && return 1
    local old_lstart current_lstart
    old_lstart=$(cat "$LOCK_DIR/lstart" 2>/dev/null || echo "")
    if [[ -n "$old_lstart" ]]; then
        current_lstart=$(ps -p "$old_pid" -o lstart= 2>/dev/null || echo "")
        [[ "$old_lstart" != "$current_lstart" ]] && return 1
    fi
    return 0
}

acquire_lock() {
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_lock_info
        trap cleanup EXIT
        log "Lock acquired (PID $$)."
        return
    fi
    if is_lock_holder_alive; then
        local old_pid
        old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "?")
        log_error "Another autofix instance is running (PID $old_pid). Exiting."
        exit 0
    fi
    local old_pid
    old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "?")
    log "Stale lock detected (PID $old_pid no longer running). Removing."
    rm -rf "$LOCK_DIR"
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_lock_info
        trap cleanup EXIT
        log "Lock acquired (PID $$)."
    else
        log_error "Failed to acquire lock after stale removal. Exiting."
        exit 1
    fi
}

# ── Git State ─────────────────────────────────────────────────────────────────
push_to_remotes() {
    for remote in origin github; do
        if git remote get-url "$remote" &>/dev/null; then
            if run_timed 60 git push "$remote" main 2>/dev/null; then
                log "Pushed to $remote."
            else
                log "Fast-forward push to $remote rejected — retrying with --force-with-lease..."
                run_timed 60 git push "$remote" main --force-with-lease 2>/dev/null || \
                    log_error "Could not push to $remote"
            fi
        fi
    done
}

verify_git_state() {
    cd "$PROJECT_DIR"
    local branch
    branch=$(git rev-parse --abbrev-ref HEAD)
    if [[ "$branch" != "main" ]]; then
        log_error "Not on main branch (on '$branch'). Exiting."
        exit 1
    fi
    if ! git diff --quiet || ! git diff --cached --quiet; then
        log "Working tree has uncommitted changes. Auto-committing..."
        git add -u
        git commit -m "autofix: auto-commit pending changes before run" || true
    fi
    run_timed 60 git fetch origin 2>/dev/null || log "git fetch timed out or failed — continuing."
    local origin_ahead
    origin_ahead=$(git rev-list HEAD..origin/main --count 2>/dev/null || echo 0)
    if [[ "$origin_ahead" -gt 0 ]]; then
        log "origin/main is $origin_ahead commit(s) ahead — rebasing..."
        git rebase origin/main 2>/dev/null || {
            git rebase --abort 2>/dev/null || true
            git merge --no-edit origin/main 2>/dev/null || true
        }
    fi
    push_to_remotes
    log "Git state verified."
}

# ── JSON helper ───────────────────────────────────────────────────────────────
run_py() {
    local json_file="$1"; shift
    python3 -c "$*" "$json_file"
}

# ── Label Management ──────────────────────────────────────────────────────────
ensure_label_exists() {
    for label in "$ACTIVE_LABEL" "$AUTOFIX_LABEL"; do
        if ! gh label list --repo "$BUGS_REPO" --search "$label" --json name \
            | python3 -c "import sys,json; sys.exit(0 if any(l['name']=='$label' for l in json.load(sys.stdin)) else 1)" 2>/dev/null; then
            gh label create "$label" --repo "$BUGS_REPO" \
                --description "Autofix agent label" --color "F9D0C4" 2>/dev/null || true
            log "Created label '$label'"
        fi
    done
}

label_task_active() {
    local task_file="$1"
    run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
for i in issues: print(i["number"])' | while IFS= read -r num; do
        gh issue edit "$num" --repo "$BUGS_REPO" --add-label "$ACTIVE_LABEL" 2>/dev/null || true
    done
}

unlabel_task_active() {
    local task_file="$1"
    run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
for i in issues: print(i["number"])' | while IFS= read -r num; do
        gh issue edit "$num" --repo "$BUGS_REPO" --remove-label "$ACTIVE_LABEL" 2>/dev/null || true
    done
}

# ── Issue Fetching ────────────────────────────────────────────────────────────
fetch_and_group_issues() {
    local tmp_issues="$WORK_TMP/issues.json"
    gh issue list --repo "$BUGS_REPO" --state open --label "$AUTOFIX_LABEL" \
        --json number,title,body,labels --limit 100 > "$tmp_issues"

    local count
    count=$(run_py "$tmp_issues" 'import json,sys
with open(sys.argv[1]) as f: print(len(json.load(f)))')

    if [[ "$count" == "0" ]]; then echo "[]"; return; fi

    run_py "$tmp_issues" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
ACTIVE = "claude-active"
issues = [i for i in issues if ACTIVE not in [l["name"] for l in i.get("labels", [])]]
groups = {}
standalone = []
for issue in issues:
    labels = [l["name"] for l in issue.get("labels", [])]
    story_label = next((l for l in labels if l.startswith("story:")), None)
    if story_label:
        groups.setdefault(story_label, []).append(issue)
    else:
        standalone.append(issue)
tasks = list(groups.values())
for issue in standalone:
    tasks.append([issue])
print(json.dumps(tasks))
'
}

# ── Prompt Building ───────────────────────────────────────────────────────────
build_prompt() {
    local issues_desc="$1"
    local retry_context="$2"
    local prompt
    prompt=$(cat "$PROMPT_TEMPLATE")
    prompt="${prompt/\{\{ISSUES\}\}/$issues_desc}"
    if [[ -n "$retry_context" ]]; then
        local retry_block="## Previous Attempt Failed

\`\`\`
$retry_context
\`\`\`"
        prompt="${prompt/\{\{RETRY_CONTEXT\}\}/$retry_block}"
    else
        prompt="${prompt/\{\{RETRY_CONTEXT\}\}/}"
    fi
    echo "$prompt"
}

format_issues_for_prompt() {
    local task_file="$1"
    run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
parts = []
for issue in issues:
    labels = ", ".join(l["name"] for l in issue.get("labels", []))
    num = issue["number"]
    title = issue["title"]
    body = issue.get("body", "") or ""
    part = f"### Issue #{num}: {title}\n"
    if labels: part += f"Labels: {labels}\n"
    part += f"\n{body}\n"
    parts.append(part)
print("\n---\n".join(parts))
'
}

# ── Fix Execution ─────────────────────────────────────────────────────────────
attempt_fix() {
    local prompt="$1"
    local task_log="$2"
    local head_before
    head_before=$(git rev-parse HEAD 2>/dev/null || echo "")

    log "Invoking Claude CLI..."
    local claude_exit=0
    local claude_tmp prompt_file
    claude_tmp=$(mktemp)
    prompt_file=$(mktemp)
    printf '%s' "$prompt" > "$prompt_file"
    # Pass prompt via stdin to avoid ARG_MAX limits and reduce shell memory pressure
    # Unset CLAUDECODE so nested invocations are allowed when called from within a Claude session
    unset CLAUDECODE
    claude --dangerously-skip-permissions --print < "$prompt_file" 2>&1 | tee -a "$task_log" "$claude_tmp"
    claude_exit=${PIPESTATUS[0]}
    local claude_output
    claude_output=$(cat "$claude_tmp")
    rm -f "$claude_tmp" "$prompt_file"

    if [[ $claude_exit -ne 0 ]]; then
        log_error "Claude CLI exited with code $claude_exit"
        return 1
    fi

    if echo "$claude_output" | grep -q "ALREADY_FIXED:"; then
        log "Claude determined issue is already fixed."
        return 2
    fi

    local head_after
    head_after=$(git rev-parse HEAD 2>/dev/null || echo "")
    if [[ -n "$head_before" && "$head_before" != "$head_after" ]]; then
        log "Claude committed changes (HEAD moved to ${head_after:0:8})."
        return 0
    fi

    if git diff --quiet && git diff --cached --quiet; then
        log_error "Claude ran but no files were modified or committed."
        return 1
    fi

    log "Verifying build (assembleDebug)..."
    export JAVA_HOME="$JAVA_HOME_PATH"
    export PATH="$JAVA_HOME/bin:$PATH"
    local build_exit=0
    (cd "$PROJECT_DIR" && ./gradlew assembleDebug 2>&1) >> "$task_log" || build_exit=$?
    if [[ $build_exit -ne 0 ]]; then
        log_error "Build verification failed."
        return 1
    fi
    log "Build verification passed."
    return 0
}

try_fix_task() {
    local task_file="$1"
    local task_log="$2"
    local issues_desc
    issues_desc=$(format_issues_for_prompt "$task_file")
    local retry_context=""
    local attempt=1

    while [[ $attempt -le $MAX_RETRIES ]]; do
        log "Attempt $attempt/$MAX_RETRIES..."
        if [[ $attempt -gt 1 ]]; then
            git checkout -- . 2>/dev/null || true
            git clean -fd 2>/dev/null || true
        fi
        local prompt
        prompt=$(build_prompt "$issues_desc" "$retry_context")
        local fix_result=0
        attempt_fix "$prompt" "$task_log" || fix_result=$?

        if [[ $fix_result -eq 0 ]]; then
            git add -A
            local issue_numbers
            issue_numbers=$(run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
print(", ".join("#"+str(i["number"]) for i in issues))')
            git commit -m "autofix: resolve $issue_numbers" 2>/dev/null || true
            log "Fix committed for $issue_numbers"
            local fix_summary
            fix_summary=$(sed -n '/FIX_SUMMARY_START/,/FIX_SUMMARY_END/{/FIX_SUMMARY_START/d;/FIX_SUMMARY_END/d;p;}' "$task_log" 2>/dev/null || echo "")
            [[ -n "$fix_summary" ]] && echo "$fix_summary" > "$task_log.fix_summary"
            return 0

        elif [[ $fix_result -eq 2 ]]; then
            run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
for i in issues: print(i["number"])' | while IFS= read -r num; do
                gh issue close "$num" --repo "$BUGS_REPO" \
                    --comment "Autofix agent verified this issue is already resolved." \
                    2>/dev/null || true
                log "Closed already-fixed issue #$num"
            done
            return 0
        fi

        retry_context=$(tail -50 "$task_log" 2>/dev/null || echo "Unknown error")
        attempt=$((attempt + 1))
    done

    git checkout -- . 2>/dev/null || true
    git clean -fd 2>/dev/null || true
    run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
for i in issues: print(i["number"])' | while IFS= read -r num; do
        gh issue comment "$num" --repo "$BUGS_REPO" \
            --body "Autofix agent failed to resolve this issue after $MAX_RETRIES attempts. Manual intervention required." \
            2>/dev/null || true
    done
    log_error "All $MAX_RETRIES attempts failed."
    return 1
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    mkdir -p "$LOG_DIR"
    WORK_TMP=$(mktemp -d)

    log "=== Autofix run started ==="
    acquire_lock
    verify_git_state
    ensure_label_exists

    local any_fixed=false
    local any_failed=false
    local all_fixed_issues=()
    local round=1

    while true; do
        log "--- Round $round ---"
        local tasks_file="$WORK_TMP/tasks_r${round}.json"
        fetch_and_group_issues > "$tasks_file"

        local task_count
        task_count=$(run_py "$tasks_file" 'import json,sys
with open(sys.argv[1]) as f: print(len(json.load(f)))')

        if [[ "$task_count" == "0" ]]; then
            log "No open autofix issues found."; break
        fi
        log "Found $task_count task(s) to process."

        local fixed_this_round=()
        local task_index=0
        while [[ $task_index -lt $task_count ]]; do
            local task_file="$WORK_TMP/task_r${round}_${task_index}.json"
            python3 -c "
import json,sys
with open(sys.argv[1]) as f: tasks=json.load(f)
print(json.dumps(tasks[$task_index]))
" "$tasks_file" > "$task_file"

            local task_label
            task_label=$(run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
print(", ".join("#"+str(i["number"]) for i in issues))')

            log "Processing task: $task_label"
            label_task_active "$task_file"

            local task_log="$LOG_DIR/task_$(date +%Y%m%d_%H%M%S)_${task_label//[^0-9_]/_}.log"

            if try_fix_task "$task_file" "$task_log"; then
                any_fixed=true
                fixed_this_round+=("1")
                run_py "$task_file" 'import json,sys
with open(sys.argv[1]) as f: issues=json.load(f)
for i in issues: print(i["number"])' | while IFS= read -r num; do
                    local close_body="Fixed by autofix agent."
                    if [[ -f "$task_log.fix_summary" ]]; then
                        close_body=$(cat "$task_log.fix_summary")
                    fi
                    gh issue close "$num" --repo "$BUGS_REPO" --comment "$close_body" 2>/dev/null || true
                    log "Closed issue #$num"
                    all_fixed_issues+=("$num")
                done
                rm -f "$task_log.fix_summary"
            else
                any_failed=true
            fi

            unlabel_task_active "$task_file"
            task_index=$((task_index + 1))
        done

        if [[ ${#fixed_this_round[@]} -gt 0 ]]; then
            push_to_remotes
            log "Pushed fixes for round $round."
        else
            log "No issues fixed this round. Stopping."; break
        fi
        round=$((round + 1))
        sleep 5
    done

    log "=== Autofix run completed ==="

    if [[ "$any_fixed" == "true" ]]; then
        log "Issues were fixed — triggering release..."
        trigger_release || log_error "Release failed, but fixes are committed."
    fi

    if [[ "$any_failed" == "true" ]]; then return 1; fi
}

# ── Release ───────────────────────────────────────────────────────────────────
trigger_release() {
    cd "$PROJECT_DIR"
    local gradle="$PROJECT_DIR/app/build.gradle.kts"
    local appconfig="$PROJECT_DIR/app/src/main/java/com/template/app/AppConfig.kt"

    # Read current version
    local old_name old_code
    old_name=$(python3 -c "import re; m=re.search(r'versionName\s*=\s*\"([^\"]+)\"', open('$gradle').read()); print(m.group(1))")
    old_code=$(python3 -c "import re; m=re.search(r'versionCode\s*=\s*(\d+)', open('$gradle').read()); print(m.group(1))")

    # Bump patch
    local major minor patch
    IFS='.' read -r major minor patch <<< "$old_name"
    local new_name="$major.$minor.$((patch + 1))"
    local new_code=$(( old_code + 1 ))

    log "Bumping $old_name (code $old_code) → $new_name (code $new_code)"

    # Update build.gradle.kts
    python3 -c "
import re
content = open('$gradle').read()
content = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = $new_code', content)
content = re.sub(r'versionName\s*=\s*\"[^\"]+\"', 'versionName = \"$new_name\"', content)
open('$gradle', 'w').write(content)
"
    git add "$gradle"
    git commit -m "chore: release v$new_name

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

    # Push source to both remotes
    push_to_remotes

    # Detect available CPU cores for maximum parallelism
    local cpu_cores
    cpu_cores=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc 2>/dev/null || echo 4)
    # Leave 1 core for the OS; use the rest for Gradle workers
    local gradle_workers=$(( cpu_cores > 1 ? cpu_cores - 1 : cpu_cores ))
    # Allow up to 8 GB JVM heap (Gradle + Kotlin compilation is memory-hungry)
    local jvm_heap="8g"

    log "Building release APK (cores: $cpu_cores, workers: $gradle_workers, heap: $jvm_heap)..."
    export JAVA_HOME="$JAVA_HOME_PATH"
    export PATH="$JAVA_HOME/bin:$PATH"
    if ! (cd "$PROJECT_DIR" && ./gradlew assembleRelease \
        --parallel \
        --max-workers="$gradle_workers" \
        --build-cache \
        --configuration-cache \
        -Dorg.gradle.jvmargs="-Xmx${jvm_heap} -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError" \
        -Dkotlin.incremental=true \
        -Dkotlin.daemon.jvm.options="-Xmx4g -XX:+UseParallelGC" \
        2>&1) >> "$LOG_DIR/release_$(date +%Y%m%d_%H%M%S).log"; then
        log_error "Release build failed."
        return 1
    fi
    log "Build succeeded."

    # Tag + copy APK + push to GitHub
    local apk_src="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    local app_name
    app_name=$(python3 -c "import re; m=re.search(r'APP_NAME\s*=\s*\"([^\"]+)\"', open('$appconfig').read()); print(m.group(1).replace(' ','-'))")
    local apk_dest="$PROJECT_DIR/${app_name}-v${new_name}.apk"
    cp "$apk_src" "$apk_dest"

    git tag "v$new_name"
    push_to_remotes
    git push origin "v$new_name" 2>/dev/null || true
    git push github "v$new_name" 2>/dev/null || true

    # GitHub release (uses gh CLI authenticated as itsikh)
    local repo_owner repo_name
    repo_owner=$(python3 -c "import re; m=re.search(r'GITHUB_RELEASES_REPO_OWNER\s*=\s*\"([^\"]+)\"', open('$appconfig').read()); print(m.group(1))")
    repo_name=$(python3 -c "import re; m=re.search(r'GITHUB_RELEASES_REPO_NAME\s*=\s*\"([^\"]+)\"', open('$appconfig').read()); print(m.group(1))")

    gh release create "v$new_name" \
        --repo "$repo_owner/$repo_name" \
        --title "$app_name v$new_name" \
        --notes "## What's new
Autofix agent resolved issues: ${all_fixed_issues[*]:-}" \
        "$apk_dest"
    rm -f "$apk_dest"

    log "Released $app_name v$new_name → https://github.com/$repo_owner/$repo_name/releases/tag/v$new_name"
}

set -o pipefail
main "$@" 2>&1 | tee -a "$LOG_DIR/autofix_$(date +%Y%m%d).log"
