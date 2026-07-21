#!/usr/bin/env bash
# Bash test for menu.sh — interactive-cli-launcher (RED case from the design
# threat matrix: "Shell command composition" boundary).
#
# Requires `jq` on PATH (vendored under _tools/jq on POSIX by the installer;
# for local test runs, point PATH at a jq binary).
#
# Run with:  bash tests/menu_test.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MENU_SH="$SCRIPT_DIR/../menu.sh"

if ! command -v jq >/dev/null 2>&1; then
  echo "SKIP: jq not found on PATH — cannot verify safe JSON body building"
  echo "      (vendored under _tools/jq by the POSIX installer; see design D6)"
  exit 0
fi

# Sourcing menu.sh must NOT run its main loop / spawn any process — guarded
# by the standard `[[ "${BASH_SOURCE[0]}" == "${0}" ]]` idiom inside menu.sh.
# shellcheck source=/dev/null
source "$MENU_SH"

fail=0

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" != "$actual" ]; then
    echo "FAIL: $desc"
    echo "  expected: $expected"
    echo "  actual:   $actual"
    fail=1
  else
    echo "PASS: $desc"
  fi
}

# Threat matrix case: nombre with quotes, semicolon, and a command-
# substitution-looking sequence must reach jq as a literal --arg value,
# never interpolated into a shell string or eval'd.
hostile='a"b;$(x)'
json="$(build_site_json "$hostile" "https://example.com" "tiendanube")"

parsed_nombre="$(printf '%s' "$json" | jq -r '.nombre')"
parsed_url="$(printf '%s' "$json" | jq -r '.url')"
parsed_plataforma="$(printf '%s' "$json" | jq -r '.plataforma')"

assert_eq "nombre survives as literal string" "$hostile" "$parsed_nombre"
assert_eq "url passthrough" "https://example.com" "$parsed_url"
assert_eq "plataforma passthrough" "tiendanube" "$parsed_plataforma"

# Default plataforma
json_default="$(build_site_json "MiMarca" "https://mimarca.com")"
assert_eq "plataforma defaults to tiendanube" "tiendanube" "$(printf '%s' "$json_default" | jq -r '.plataforma')"

# remove_sitio must URL-encode the site name before building the DELETE
# path (parity with menu.ps1's Remove-Sitio / [uri]::EscapeDataString) so
# names with spaces/special chars resolve to the right site.
name_with_space="Mi Marca & Co"
encoded="$(printf '%s' "$name_with_space" | "$JQ_BIN" -sRr @uri)"
# @uri encoding never leaves a literal space or & in the output.
case "$encoded" in
  *" "*|*"&"*)
    echo "FAIL: URL-encoding of site name still contains raw space/& -> $encoded"
    fail=1
    ;;
  *)
    echo "PASS: URL-encoding of site name strips raw space/&"
    ;;
esac

if [ "$fail" -ne 0 ]; then
  echo "menu_test.sh: FAILED"
  exit 1
fi

echo "menu_test.sh: ALL PASSED"
