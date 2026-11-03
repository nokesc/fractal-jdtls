# SPDX-License-Identifier: Apache-2.0
"""Test-only launch path for the control run (design §8): the installed launcher's command, minus the agent.

Usage: python -I -B launch_without_agent.py <release dir> [launcher arguments...]

It loads the installed bin/jdtls, lets it verify the install and build its java command exactly
as it would for a client, removes the two arguments that add the agent, and replaces itself
with java. Nothing in the product can start JDT LS without the agent; only this script can,
and it's never shipped.
"""

import importlib.machinery
import importlib.util
import os
import sys
from pathlib import Path


def load_launcher(release_dir: Path):
    path = release_dir / "bin" / "jdtls"
    loader = importlib.machinery.SourceFileLoader("fractal_jdtls_launcher", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def without_agent(command, release_dir: Path):
    agent = f"-javaagent:{release_dir / 'lib' / 'fractal-jdtls-agent.jar'}"
    delegation = "-Dorg.osgi.framework.bootdelegation=org.fractal.jdtls.boot"
    missing = [arg for arg in (agent, delegation) if arg not in command]
    if missing:
        raise SystemExit(f"launch_without_agent: the launcher's command no longer has {missing}; update this script")
    return [arg for arg in command if arg not in (agent, delegation)]


def main():
    sys.dont_write_bytecode = True  # nothing is written into the install
    release_dir = Path(os.path.realpath(sys.argv[1]))
    launcher = load_launcher(release_dir)
    command = without_agent(launcher.prepare(sys.argv[2:], release_dir, os.environ, os.getcwd()), release_dir)
    print("launch_without_agent: " + " ".join(command), file=sys.stderr, flush=True)
    os.execv(command[0], command)


if __name__ == "__main__":
    main()
