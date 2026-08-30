// Rewritten at launch time by the CLI (cli/core/runtime_config.py). Vite copies
// it verbatim into dist/, so an unmanaged build still serves a valid, inert
// file instead of a 404. Blank = fall back to the build-time value.
window.__API_BASE__ = '';
