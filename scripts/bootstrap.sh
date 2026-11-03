#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

if ! command -v mise >/dev/null 2>&1; then
    printf '%s\n' "mise is not installed. Install it first: https://mise.jdx.dev/getting-started.html" >&2
    exit 1
fi

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

mise trust
mise install
mise run setup
