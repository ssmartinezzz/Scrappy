"""Non-interactive first-run setup: create `.env` and choose the admin account.

Both run from `python -m cli --init-env` / `--set-admin USER`, so a caller
without a terminal (the MCP `configurar_admin` tool, a script) can finish an
install. The admin password is never printed: it lives in `.env` only.
"""
from __future__ import annotations

import secrets
from typing import Optional

from cli.core.config import Config
from cli.core.env_file import compute_defaults, generate_env, update_env
from cli.core.errors import EnvGenError

MIN_PASSWORD_LENGTH = 12

# Same rule as ar.scraper.security.Placeholders: the backend refuses to seed
# an account whose password still carries an example value. Kept in step by
# hand, like every copy of a fact (CONTRIBUTING DOC-1).
_EXACT_PLACEHOLDERS = frozenset({
    "cambiame-por-una-password-real",
    "replace-me-with-random-too",
    "replace-me-with-at-least-32-bytes-of-random",
})
_PLACEHOLDER_MARKERS = ("cambiame", "replace-me")


def is_placeholder(value: str) -> bool:
    lowered = value.strip().lower()
    if not lowered:
        return False
    return lowered in _EXACT_PLACEHOLDERS or any(m in lowered for m in _PLACEHOLDER_MARKERS)


def init_env(cfg: Config) -> None:
    """Create `.env` if absent, or append the keys it is missing."""
    generate_env(cfg.repo_root / ".env.example", cfg.repo_root / ".env", compute_defaults(cfg))


def set_admin(cfg: Config, username: str, password: Optional[str]) -> bool:
    """Write the bootstrap admin into `.env`. Returns True when the password
    was generated here (the caller asked for none).

    Only the first boot uses it: the seeder never overwrites an existing
    account, so after that this changes the file and not the login."""
    username = username.strip()
    if not username or any(c.isspace() for c in username):
        raise EnvGenError("usuario admin inválido", "Usá un nombre sin espacios, ej: admin")

    generated = not password
    if generated:
        password = secrets.token_urlsafe(18)
    elif is_placeholder(password):
        raise EnvGenError(
            "esa password es un valor de ejemplo público",
            "El backend se niega a sembrarla; elegí otra.",
        )
    elif len(password) < MIN_PASSWORD_LENGTH:
        raise EnvGenError(f"la password debe tener al menos {MIN_PASSWORD_LENGTH} caracteres")

    init_env(cfg)
    update_env(cfg.repo_root / ".env", {
        "ADMIN_BOOTSTRAP_USERNAME": username,
        "ADMIN_BOOTSTRAP_PASSWORD": password,
    })
    return generated
