"""Personal settings of the dev tools: local/dev.env (git-ignored; template tools/dev.env.example).

A variable already set in the environment wins over the file.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load():
    path = os.path.join(ROOT, "local", "dev.env")
    if not os.path.exists(path):
        return
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())


def need(name):
    """The variable's value, or exit with a hint where to set it."""
    load()
    value = os.environ.get(name)
    if not value:
        sys.exit(f"{name} is not set: put it in local/dev.env (see tools/dev.env.example)")
    return value
