"""Headless model of the "Configurar mail (SMTP)" dialogue, shared by the
console and the plain runner so both ask the same questions in the same order
and write the same keys. It never prints, logs or echoes an answer; the
password is only ever handed back through `values()`."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Optional
from urllib.parse import urlparse

from cli.core.env_file import parse_env, update_env

PROVEEDOR = "PROVEEDOR"
_LOOPBACK = {"localhost", "127.0.0.1", "::1"}
_DIRECCION = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


@dataclass(frozen=True)
class Preset:
    clave: str
    nombre: str
    host: str
    port: int


PRESETS = (
    Preset("gmail", "Gmail", "smtp.gmail.com", 587),
    Preset("outlook", "Outlook / Office 365", "smtp.office365.com", 587),
    Preset("yahoo", "Yahoo", "smtp.mail.yahoo.com", 587),
    Preset("custom", "Otro (host y puerto propios)", "", 587),
)


@dataclass(frozen=True)
class Question:
    key: str
    text: str
    secret: bool = False
    default: str = ""


class SmtpWizard:
    def __init__(self, existing: Mapping[str, str], public_origin: Optional[str] = None) -> None:
        self._existing = dict(existing)
        self._public_origin = public_origin
        self._preset: Optional[Preset] = None
        self._answers: dict[str, str] = {}
        self._step = PROVEEDOR

    # -- questions ---------------------------------------------------

    def current(self) -> Optional[Question]:
        step = self._step
        if step == PROVEEDOR:
            opciones = "  ".join(f"[{n}] {p.nombre}" for n, p in enumerate(PRESETS, 1))
            return Question(PROVEEDOR, f"Proveedor de mail: {opciones}")
        if step == "SMTP_HOST":
            return Question(step, "Host SMTP (ej: mail.empresa.com):", default=self._existing.get(step, ""))
        if step == "SMTP_PORT":
            return Question(step, "Puerto (587 = STARTTLS, 465 = SSL/TLS):", default=self._existing.get(step, "587"))
        if step == "SMTP_USERNAME":
            return Question(step, "Usuario (el mail completo):", default=self._existing.get(step, ""))
        if step == "SMTP_PASSWORD":
            texto = "Contraseña de aplicación (no se muestra)"
            if self._preset and self._preset.clave == "gmail":
                texto = ("Contraseña de aplicación de Google (myaccount.google.com/apppasswords; "
                         "Gmail no acepta la clave normal) — no se muestra")
            elif self._existing.get("SMTP_PASSWORD"):
                texto += "; Enter conserva la actual"
            return Question(step, texto + ":", secret=True)
        if step == "SMTP_FROM_ADDRESS":
            default = self._existing.get(step, "") or self._answers.get("SMTP_USERNAME", "")
            return Question(step, "Remitente (Enter = el usuario):", default=default if "@" in default else "")
        return None

    @property
    def done(self) -> bool:
        return self._step == ""

    def answer(self, text: str) -> Optional[str]:
        """Feed the reply to `current()`. Returns an error message (and stays
        on the question) or `None` after moving on."""
        handler = {
            PROVEEDOR: self._answer_provider,
            "SMTP_HOST": self._answer_host,
            "SMTP_PORT": self._answer_port,
            "SMTP_USERNAME": self._answer_username,
            "SMTP_PASSWORD": self._answer_password,
            "SMTP_FROM_ADDRESS": self._answer_from,
        }.get(self._step)
        return handler(text.strip()) if handler else None

    def _accept(self, text: str, next_step: str) -> None:
        self._answers[self._step] = text
        self._step = next_step

    def _answer_host(self, text: str) -> Optional[str]:
        if not text or re.search(r"[\s/:]", text):
            return "El host es sólo el nombre del servidor, sin esquema, puerto ni espacios."
        self._accept(text, "SMTP_PORT")
        return None

    def _answer_port(self, text: str) -> Optional[str]:
        text = text or self.current().default
        if not text.isdigit() or not 1 <= int(text) <= 65535:
            return "El puerto es un número entre 1 y 65535."
        self._accept(str(int(text)), "SMTP_USERNAME")
        return None

    def _answer_username(self, text: str) -> Optional[str]:
        text = text or self.current().default
        if not text:
            return "El usuario es obligatorio."
        self._accept(text, "SMTP_PASSWORD")
        return None

    def _answer_from(self, text: str) -> Optional[str]:
        text = text or self.current().default
        if not _DIRECCION.match(text):
            return "El remitente tiene que ser una dirección de mail (algo@dominio.com)."
        self._accept(text, "")
        return None

    def _answer_provider(self, text: str) -> Optional[str]:
        lowered = text.lower()
        for n, preset in enumerate(PRESETS, 1):
            if lowered in (str(n), preset.clave):
                self._preset = preset
                self._answers.update(SMTP_HOST=preset.host, SMTP_PORT=str(preset.port))
                self._step = "SMTP_HOST" if preset.clave == "custom" else "SMTP_USERNAME"
                return None
        return f"Proveedor desconocido: elegí un número de 1 a {len(PRESETS)} o su nombre."

    def _answer_password(self, text: str) -> Optional[str]:
        if not text:
            text = self._existing.get("SMTP_PASSWORD", "")
            if not text:
                return "La contraseña es obligatoria."
        else:
            text = "".join(text.split())
        if any(ord(c) < 32 for c in text):
            return "La contraseña tiene caracteres de control."
        self._answers["SMTP_PASSWORD"] = text
        self._step = "SMTP_FROM_ADDRESS"
        return None

    # -- result ------------------------------------------------------

    def values(self) -> dict[str, str]:
        out = {
            "SMTP_HOST": self._answers["SMTP_HOST"],
            "SMTP_PORT": self._answers["SMTP_PORT"],
            "SMTP_USERNAME": self._answers["SMTP_USERNAME"],
            "SMTP_PASSWORD": self._answers["SMTP_PASSWORD"],
            "SMTP_FROM_ADDRESS": self._answers["SMTP_FROM_ADDRESS"],
        }
        if self._public_origin:
            out["NOTIFICACIONES_API_BASE_URL"] = self._public_origin
        return out

    def summary(self) -> list[str]:
        v = self.values()
        lines = [
            f"servidor   {v['SMTP_HOST']}:{v['SMTP_PORT']}",
            f"usuario    {v['SMTP_USERNAME']}",
            "contraseña (guardada, no se muestra)",
            f"remitente  {v['SMTP_FROM_ADDRESS']}",
        ]
        if self._public_origin:
            lines.append(f"enlaces    {self._public_origin} (NOTIFICACIONES_API_BASE_URL)")
        return lines


def known_public_origin(env: Mapping[str, str], environ: Mapping[str, str]) -> Optional[str]:
    """A backend origin other devices can reach, if one is configured:
    `SCRAPPY_BACKEND_ORIGIN` (LAN mode / tunnel override) first, then the
    `VITE_API_BASE_URL` the install froze. Loopback never counts — it is the
    default the backend already has."""
    for candidate in (environ.get("SCRAPPY_BACKEND_ORIGIN", ""), env.get("VITE_API_BASE_URL", "")):
        candidate = candidate.strip()
        parsed = urlparse(candidate)
        if parsed.scheme in ("http", "https") and parsed.hostname and parsed.hostname not in _LOOPBACK:
            return candidate.rstrip("/")
    return None


CANCEL_WORD = "cancelar"

#: A dialogue that is refused this many times in a row gives up instead of looping.
MAX_RECHAZOS_SEGUIDOS = 3

RESTART_NOTE = (
    "Listo. El backend lee .env al arrancar: reinicialo con `stop` + `start` para aplicarlo, "
    "y después `mail test <tu mail>` para verificar el envío."
)


def new_wizard(repo_root: Path) -> SmtpWizard:
    env = parse_env(repo_root / ".env")
    return SmtpWizard(env, known_public_origin(env, os.environ))


def save(repo_root: Path, wizard: SmtpWizard) -> None:
    update_env(repo_root / ".env", wizard.values())


def send_test_mail(rest, repo_root: Path, destino: Optional[str]) -> str:
    """Ask the running backend to send a test mail. The service account has no
    address of its own, so without `destino` the configured sender is used."""
    destino = destino or parse_env(repo_root / ".env").get("SMTP_FROM_ADDRESS") or None
    payload = rest.correo_prueba(destino)
    if isinstance(payload, dict) and payload.get("mensaje"):
        return str(payload["mensaje"])
    return str(payload)
