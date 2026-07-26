"""Headless core of the native CLI.

Every module in this package MUST NOT import `textual`/`rich` and MUST NOT
write interactive output to stdout — it returns data and raises typed
errors (see `cli.core.errors`). Both the Textual presenter (`cli.tui`,
slice 2) and the plain-text fallback (`cli.plain`, slice 2) drive this same
core, which is what makes it unit-testable without a terminal.
"""
