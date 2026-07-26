"""Plain-text fallback presenter — non-interactive text driver over the
SAME headless `core/` the Textual TUI drives. Zero `textual`/`rich`
imports anywhere under this package (see `runner.py`'s module docstring):
this is what lets the plain path run on a machine where Textual can't be
imported at all (design.md §2, spec: native-cli-orchestration, "Graceful
TUI Degradation").
"""
