#!/usr/bin/env bash
# Builds spike-agent.jar (Byte Buddy + advice) and spike-boot.jar (bootstrap helper) into $SPIKE_HOME/agent.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
SPIKE_HOME="${SPIKE_HOME:-$HOME/.local/state/fractal-harness/jdtls-worktree-spike}"
BYTE_BUDDY="${BYTE_BUDDY:-$HOME/.m2/repository/net/bytebuddy/byte-buddy/1.18.14/byte-buddy-1.18.14.jar}"
out="$SPIKE_HOME/agent"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/25.0.1}"
PATH="$JAVA_HOME/bin:$PATH"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out" "$work/boot" "$work/agent"

javac --release 21 -d "$work/boot" $(find "$here/agent/src/boot" -name '*.java')
jar --create --file "$out/spike-boot.jar" -C "$work/boot" .

javac --release 21 -cp "$BYTE_BUDDY:$work/boot" -d "$work/agent" $(find "$here/agent/src/agent" -name '*.java')
(cd "$work/agent" && jar --extract --file "$BYTE_BUDDY" && rm -rf META-INF/versions/*/module-info.class module-info.class META-INF/MANIFEST.MF)
cat > "$work/manifest.txt" <<MF
Premain-Class: org.fractal.spike.jdtls.agent.SpikeAgent
Boot-Class-Path: spike-boot.jar
Can-Retransform-Classes: true
MF
jar --create --file "$out/spike-agent.jar" --manifest "$work/manifest.txt" -C "$work/agent" .
echo "built $out/spike-agent.jar and $out/spike-boot.jar"
