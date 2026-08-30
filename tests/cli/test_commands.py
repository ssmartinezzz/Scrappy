"""Tests for cli.core.commands — the single command vocabulary shared by
both presenters (Textual console + plain runner).

The registry is the reason `help`, the autocomplete list and the plain
menu can never drift apart: they are three renderings of one table.
"""
from __future__ import annotations

from cli.core.commands import COMMANDS, complete, find, help_lines, menu_text


def test_every_command_has_a_name_and_a_one_line_help():
    for cmd in COMMANDS:
        assert cmd.name and " " not in cmd.name
        assert cmd.help and "\n" not in cmd.help


def test_command_names_are_unique():
    names = [c.name for c in COMMANDS]
    assert len(names) == len(set(names))


def test_registry_covers_the_documented_verbs():
    names = {c.name for c in COMMANDS}
    assert {
        "build", "start", "stop", "scrape", "retrain", "status",
        "sites", "add-site", "del-site", "logs", "open", "clear",
        "help", "quit",
    } <= names


def test_find_returns_the_command_by_name():
    assert find("scrape").name == "scrape"


def test_find_returns_none_for_an_unknown_verb():
    assert find("rm -rf") is None


def test_complete_returns_names_matching_the_prefix():
    assert complete("st") == ["start", "status", "stop"]


def test_complete_is_case_insensitive():
    assert complete("SC") == ["scrape"]


def test_complete_on_empty_prefix_returns_every_command():
    assert complete("") == sorted(c.name for c in COMMANDS)


def test_complete_returns_empty_for_a_prefix_that_matches_nothing():
    assert complete("zzz") == []


def test_complete_never_matches_mid_word():
    """Prefix completion only — typing 'rape' must not offer 'scrape',
    otherwise the ghost text would rewrite what the user already typed."""
    assert "scrape" not in complete("rape")


def test_aliases_resolve_to_their_canonical_command():
    assert find("q").name == "quit"
    assert find("exit").name == "quit"


def test_aliases_are_not_offered_as_completions():
    """Aliases exist for muscle memory, but the suggester should teach the
    canonical name rather than the shorthand."""
    assert "q" not in complete("")
    assert "exit" not in complete("")


def test_commands_taking_arguments_document_their_usage():
    assert find("add-site").usage == "add-site <nombre> <url> [plataforma]"
    assert find("del-site").usage == "del-site <nombre>"


def test_usage_defaults_to_the_bare_name_for_argument_less_commands():
    assert find("status").usage == "status"


def test_help_lines_render_one_row_per_command_with_its_usage():
    lines = help_lines()
    assert len(lines) == len(COMMANDS)
    assert any(line.startswith("add-site <nombre> <url> [plataforma]") for line in lines)


def test_menu_text_lists_every_command():
    text = menu_text()
    for cmd in COMMANDS:
        assert cmd.name in text


def test_help_names_the_aliases_a_command_actually_accepts():
    """`find()` accepts them, so a help that hides them is withholding what the
    CLI already does. Completions still teach the canonical name only."""
    fila = next(l for l in help_lines() if l.startswith("start "))
    assert "(alias: up)" in fila

    salir = next(l for l in help_lines() if l.startswith("quit "))
    assert "(alias: q, exit)" in salir


def test_help_leaves_alias_less_commands_untouched():
    fila = next(l for l in help_lines() if l.startswith("build "))
    assert "alias" not in fila


def test_the_canonical_name_still_leads_the_usage_column():
    """The alias goes in the help text, never into the usage column: the name
    the suggester teaches has to be the first thing read."""
    for cmd in COMMANDS:
        assert "alias" not in cmd.usage
