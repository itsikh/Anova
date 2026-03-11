#!/usr/bin/env bash
set -uo pipefail

###############################################################################
# autofix-wrapper.sh — Meta-wrapper for autofix.sh
#
# Runs autofix.sh. If it exits non-zero, asks Claude to diagnose and fix the
# failure (stale lock, git divergence, script bug, etc.), then retries.
#
# The cron job calls THIS file, not autofix.sh directly.
#
# Cron (every 4 minutes):
#   */4 * * * * /Users/itsik-personal/dev/Anova/.claude/skills/autofix/autofix-wrapper.sh >> /Users/itsik-personal/dev/Anova/.autofix-logs/cron.log 2>&1
###############################################################################

export PATH="/opt/homebrew/bin:/Users/itsik-personal/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

PROJECT_DIR="/Users/itsik-personal/dev/Anova"
AUTOFIX_SCRIPT="$PROJECT_DIR/.claude/skills/autofix/autofix.sh"
LOG_DIR="$PROJECT_DIR/.autofix-logs"
MAX_META_RETRIES=2
MAX_RUN_SECONDS=3600   # 1 hour hard limit per run

timestamp() { date "+%Y-%m-%d %H:%M:%S"; }
log()       { echo "[$(timestamp)] [wrapper] $*"; }
log_err()   { echo "[$(timestamp)] [wrapper] ERROR: $*" >&2; }

mkdir -p "$LOG_DIR"

attempt=1
while [[ $attempt -le $((MAX_META_RETRIES + 1)) ]]; do
    log "=== Run attempt $attempt / $((MAX_META_RETRIES + 1)) ==="

    run_log="$LOG_DIR/wrapper_run_$(date +%Y%m%d_%H%M%S).log"
    bash "$AUTOFIX_SCRIPT" > >(tee "$run_log") 2>&1 &
    autofix_pid=$!
    ( sleep "$MAX_RUN_SECONDS" && kill -- -"$autofix_pid" 2>/dev/null; kill "$autofix_pid" 2>/dev/null ) &
    killer_pid=$!
    wait "$autofix_pid"
    exit_code=$?
    kill "$killer_pid" 2>/dev/null
    wait "$killer_pid" 2>/dev/null
    if [[ $exit_code -eq 143 ]]; then
        log_err "autofix.sh exceeded ${MAX_RUN_SECONDS}s hard limit — killed by timeout."
        exit_code=124
    fi

    if [[ $exit_code -eq 0 ]]; then
        log "autofix.sh succeeded on attempt $attempt."
        exit 0
    fi

    if [[ $attempt -gt $MAX_META_RETRIES ]]; then
        log_err "All $MAX_META_RETRIES meta-retries exhausted. Giving up."
        exit 1
    fi

    log "autofix.sh exited $exit_code on attempt $attempt — invoking Claude to fix..."

    error_context=$(tail -80 "$run_log" 2>/dev/null || echo "(no output captured)")

    fix_prompt="The autofix.sh script for the Anova Android project failed. Diagnose and fix the root cause so the next run succeeds.

Script: $AUTOFIX_SCRIPT
Project: $PROJECT_DIR

--- Last 80 lines of output ---
$error_context
--- end of output ---

Common issues and how to fix them:

1. Stale lock at /tmp/anova-autofix.lockdir
   - Check the PID: cat /tmp/anova-autofix.lockdir/pid
   - Only remove the lock if that PID is dead or stopped (T state):
     ps -p <pid> -o stat=
   - If stale: rm -rf /tmp/anova-autofix.lockdir

2. Git not initialized or missing remote
   - origin should be: git@bitbucket.org:itsik_harel/anova.git
   - github should be: https://github.com/itsikh/Anova.git
   - Check: git remote -v

3. Uncommitted changes left by a crashed run
   - Commit: git add -u && git commit -m 'fix: clean up after crash'
   - Or discard: git checkout -- .

4. Bug in $AUTOFIX_SCRIPT
   - Read the script and fix the bash logic
   - Known past bugs: HEAD tracking, PIPESTATUS swallowing, return codes in subshells

Constraints: minimal changes only. Do not remove a live lock. All strings in English."

    log "Calling Claude to diagnose and fix..."
    claude --dangerously-skip-permissions -p "$fix_prompt" </dev/null 2>&1
    claude_exit=$?

    if [[ $claude_exit -ne 0 ]]; then
        log_err "Claude exited $claude_exit — retrying autofix.sh anyway..."
    else
        log "Claude fix complete. Retrying autofix.sh..."
    fi

    sleep 3
    attempt=$((attempt + 1))
done
