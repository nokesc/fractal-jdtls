# SPDX-License-Identifier: Apache-2.0
"""Every unit test runs with a scratch home (design §8, AGENTS.md).

HOME and the XDG folders point into the test's temporary folder, so code that falls
back to a default location, such as install.py's default root, can never write to the
real ~/.local/share, ~/.cache or ~/.config.
"""

import pytest


@pytest.fixture(autouse=True)
def scratch_home(tmp_path_factory, monkeypatch):
    home = tmp_path_factory.mktemp("home")
    monkeypatch.setenv("HOME", str(home))
    for name, folder in (("XDG_DATA_HOME", ".local/share"), ("XDG_CACHE_HOME", ".cache"), ("XDG_CONFIG_HOME", ".config")):
        monkeypatch.setenv(name, str(home / folder))
    monkeypatch.delenv("FRACTAL_JDTLS_ROOT", raising=False)
    return home
