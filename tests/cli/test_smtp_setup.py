"""cli.core.smtp_setup — the SMTP dialogue, headless."""
from __future__ import annotations

import time

import pytest

from cli.core.smtp_setup import SmtpWizard, known_public_origin


def _run(wizard: SmtpWizard, answers: list[str]) -> list[str]:
    errores = []
    for respuesta in answers:
        error = wizard.answer(respuesta)
        if error:
            errores.append(error)
    return errores


def test_gmail_asks_provider_user_password_and_from_in_that_order():
    wizard = SmtpWizard({})
    claves = []
    for respuesta in ["1", "cuenta@gmail.com", "abcd efgh ijkl mnop", ""]:
        claves.append(wizard.current().key)
        assert wizard.answer(respuesta) is None

    assert claves == ["PROVEEDOR", "SMTP_USERNAME", "SMTP_PASSWORD", "SMTP_FROM_ADDRESS"]
    assert wizard.done
    assert wizard.current() is None


def test_gmail_preset_fills_host_and_port_and_defaults_from_to_the_username():
    wizard = SmtpWizard({})
    _run(wizard, ["gmail", "cuenta@gmail.com", "abcdefghijklmnop", ""])

    assert wizard.values() == {
        "SMTP_HOST": "smtp.gmail.com",
        "SMTP_PORT": "587",
        "SMTP_USERNAME": "cuenta@gmail.com",
        "SMTP_PASSWORD": "abcdefghijklmnop",
        "SMTP_FROM_ADDRESS": "cuenta@gmail.com",
    }


def test_the_spaces_google_shows_in_an_app_password_are_dropped():
    wizard = SmtpWizard({})
    _run(wizard, ["1", "cuenta@gmail.com", "abcd efgh ijkl mnop", ""])

    assert wizard.values()["SMTP_PASSWORD"] == "abcdefghijklmnop"


def test_outlook_and_yahoo_presets():
    for opcion, host in (("2", "smtp.office365.com"), ("3", "smtp.mail.yahoo.com")):
        wizard = SmtpWizard({})
        _run(wizard, [opcion, "cuenta@example.com", "clave", ""])
        assert wizard.values()["SMTP_HOST"] == host
        assert wizard.values()["SMTP_PORT"] == "587"


def test_custom_provider_asks_host_and_port():
    wizard = SmtpWizard({})
    assert wizard.answer("4") is None
    assert wizard.current().key == "SMTP_HOST"
    _run(wizard, ["mail.empresa.com", "2525", "avisos@empresa.com", "clave", "no-reply@empresa.com"])

    assert wizard.values()["SMTP_HOST"] == "mail.empresa.com"
    assert wizard.values()["SMTP_PORT"] == "2525"
    assert wizard.values()["SMTP_FROM_ADDRESS"] == "no-reply@empresa.com"


def test_invalid_answers_are_refused_and_the_question_stays():
    wizard = SmtpWizard({})
    assert "proveedor" in wizard.answer("9").lower()
    assert wizard.current().key == "PROVEEDOR"
    wizard.answer("4")
    assert wizard.answer("smtp://mail.empresa.com") is not None
    assert wizard.answer("mail empresa.com") is not None
    assert wizard.current().key == "SMTP_HOST"
    wizard.answer("mail.empresa.com")
    assert wizard.answer("70000") is not None
    assert wizard.answer("abc") is not None
    assert wizard.current().key == "SMTP_PORT"


def test_a_from_address_that_is_not_an_address_is_refused():
    wizard = SmtpWizard({})
    _run(wizard, ["4", "mail.empresa.com", "25", "usuario", "clave"])

    assert wizard.current().key == "SMTP_FROM_ADDRESS"
    assert wizard.answer("") is not None
    assert wizard.answer("sin-arroba") is not None
    assert wizard.answer("avisos@empresa.com") is None


def test_a_blank_password_keeps_the_one_already_configured():
    wizard = SmtpWizard({"SMTP_PASSWORD": "ya-guardada", "SMTP_USERNAME": "cuenta@gmail.com"})
    _run(wizard, ["1", "", "", ""])

    assert wizard.values()["SMTP_PASSWORD"] == "ya-guardada"
    assert wizard.values()["SMTP_USERNAME"] == "cuenta@gmail.com"


def test_a_blank_password_with_none_configured_is_refused():
    wizard = SmtpWizard({})
    _run(wizard, ["1", "cuenta@gmail.com"])

    assert wizard.answer("") is not None
    assert wizard.current().key == "SMTP_PASSWORD"


def test_the_password_question_is_secret_and_the_others_are_not():
    wizard = SmtpWizard({})
    secretos = []
    for respuesta in ["1", "cuenta@gmail.com", "clave", ""]:
        secretos.append(wizard.current().secret)
        wizard.answer(respuesta)

    assert secretos == [False, False, True, False]


def test_the_gmail_question_explains_the_app_password_in_one_line():
    wizard = SmtpWizard({})
    _run(wizard, ["1", "cuenta@gmail.com"])

    texto = wizard.current().text.lower()
    assert "contraseña de aplicación" in texto
    assert "\n" not in texto


def test_a_known_public_origin_becomes_the_api_base_url():
    wizard = SmtpWizard({}, public_origin="https://192.168.1.50:3443")
    _run(wizard, ["1", "cuenta@gmail.com", "clave", ""])

    assert wizard.values()["NOTIFICACIONES_API_BASE_URL"] == "https://192.168.1.50:3443"


def test_without_a_public_origin_the_default_is_left_alone():
    wizard = SmtpWizard({})
    _run(wizard, ["1", "cuenta@gmail.com", "clave", ""])

    assert "NOTIFICACIONES_API_BASE_URL" not in wizard.values()


def test_the_summary_never_contains_the_password():
    wizard = SmtpWizard({})
    _run(wizard, ["1", "cuenta@gmail.com", "clave-super-secreta", ""])

    resumen = "\n".join(wizard.summary())
    assert "clave-super-secreta" not in resumen
    assert "smtp.gmail.com:587" in resumen


def test_known_public_origin_prefers_the_explicit_override_and_ignores_loopback():
    assert known_public_origin({}, {"SCRAPPY_BACKEND_ORIGIN": "https://mi.tunel.dev"}) == "https://mi.tunel.dev"
    assert known_public_origin({"VITE_API_BASE_URL": "https://10.0.0.5:3443"}, {}) == "https://10.0.0.5:3443"
    assert known_public_origin({"VITE_API_BASE_URL": "http://localhost:3000"}, {}) is None
    assert known_public_origin({"VITE_API_BASE_URL": "http://127.0.0.1:3000"}, {}) is None
    assert known_public_origin({}, {}) is None


@pytest.mark.parametrize("malo", [
    "a@b.com\r\nBcc: z@evil.com", "a@b.com, c@d.com", "a,b@x.com", "<a@x.com>", "a@x.com>", '"a"@x.com',
    "noat", "a@x", "a@x..com", "a@-x.com", ".a@x.com", "a..b@x.com", "ñandú@x.com", "a b@x.com",
    "a" * 65 + "@x.com", "a" * 300 + "@example.com",
])
def test_a_from_address_must_be_one_plain_address(malo):
    wizard = SmtpWizard({})
    _run(wizard, ["4", "mail.empresa.com", "25", "usuario", "clave"])

    assert wizard.answer(malo) is not None
    assert wizard.current().key == "SMTP_FROM_ADDRESS"


def test_address_validation_is_linear_on_hostile_input():
    wizard = SmtpWizard({})
    _run(wizard, ["4", "mail.empresa.com", "25", "usuario", "clave"])

    inicio = time.perf_counter()
    for hostil in ["a@" + "b." * 50_000, "@" * 100_000, "a@" + "a." * 50_000 + "!", "a" * 100_000]:
        assert wizard.answer(hostil) is not None
    assert time.perf_counter() - inicio < 0.5
