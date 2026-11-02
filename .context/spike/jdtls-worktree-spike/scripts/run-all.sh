#!/usr/bin/env bash
# Reproduces every run in FINDINGS.md from scratch: rebuilds the agent, recreates the fixture before each
# scenario that edits it, and replaces $SPIKE_HOME/data and $EVIDENCE.
set -euo pipefail
cd "$(dirname "$0")"
SPIKE_HOME="${SPIKE_HOME:-$HOME/.local/state/fractal-harness/jdtls-worktree-spike}"
EVIDENCE="${EVIDENCE:-$HOME/.local/state/fractal-harness/evidence/jdtls-worktree-spike}"
rm -rf "$SPIKE_HOME/data" "$EVIDENCE"
./build-agent.sh
run() { echo "== $1"; python3 lsp_driver.py --run "$@" > /dev/null; }
./make-fixture.sh > /dev/null
run 01-baseline --mode none
run 02-trace --mode trace
run 03-names --mode names --steps import edits reimport
./make-fixture.sh > /dev/null
run 04-names-no-maven-requester --mode names --no-maven-requester
./make-fixture.sh > /dev/null
run 05-fix --mode fix --steps import edits reimport
cp -a "$SPIKE_HOME/data/05-fix" "$SPIKE_HOME/data/07-copy"
run 06-restart-fix --mode fix --data "$SPIKE_HOME/data/05-fix"
run 07-restart-no-agent --mode none --data "$SPIKE_HOME/data/07-copy"
echo "evidence in $EVIDENCE"
