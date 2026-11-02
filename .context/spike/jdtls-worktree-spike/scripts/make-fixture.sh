#!/usr/bin/env bash
# (Re)creates the fixture: a Git repo "main" from ../fixture plus linked worktrees "wt-a" and "wt-b",
# all at one commit, so every root carries identical Maven coordinates. Only touches $SPIKE_HOME/worktrees.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
SPIKE_HOME="${SPIKE_HOME:-$HOME/.local/state/fractal-harness/jdtls-worktree-spike}"
wt="$SPIKE_HOME/worktrees"
if [ -d "$wt/main/.git" ]; then
  for w in wt-a wt-b; do git -C "$wt/main" worktree remove --force "$wt/$w" 2>/dev/null || true; done
fi
rm -rf "$wt"
mkdir -p "$wt/main"
# POMs are stored as pom.xml.fixture so IDEs on this checkout do not import the fixture (its parent
# shares the repository's own GAV); only sources are copied, never IDE metadata or build output.
(cd "$here/fixture" && tar -cf - --exclude=target --exclude=.project --exclude=.classpath \
  --exclude=.settings --exclude=.factorypath .) | tar -xf - -C "$wt/main"
find "$wt/main" -name pom.xml.fixture -exec sh -c 'mv "$1" "${1%.fixture}"' _ {} \;
git -C "$wt/main" init -q -b main
git -C "$wt/main" add -A
git -C "$wt/main" -c user.name=spike -c user.email=spike@localhost commit -q -m "fixture"
git -C "$wt/main" worktree add -q --detach "$wt/wt-a"
git -C "$wt/main" worktree add -q --detach "$wt/wt-b"
git -C "$wt/main" worktree list
