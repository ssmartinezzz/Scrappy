"""Los cinco escenarios, como tests.

No son cinco intensidades de lo mismo: son cinco preguntas distintas, y cada una
se juzga con un criterio propio. `test_stress` es el caso que mejor lo muestra —
se espera que rompa, así que no afirma un presupuesto.
"""
from __future__ import annotations

import pytest

from carga import PRESUPUESTO, BaselineUser, CatalogoUser, LoginUser


def dentro_de_presupuesto(medicion) -> None:
    """Afirma el presupuesto de CADA endpoint, y nombra todos los que se pasaron.

    De a uno se arregla uno y aparece el siguiente en la corrida que viene; así
    se ven todos en la misma pasada.
    """
    fallas = []

    # Un error bajo carga no es "lento", es roto — y se chequea aparte del
    # tiempo, porque un endpoint que falla rápido tiene un p95 excelente.
    assert medicion.requests > 0, "cero requests: el test no llegó a correr"
    assert medicion.tasa_de_error <= 0.01, (
        f"{medicion.fallos} fallos en {medicion.requests} requests "
        f"({medicion.tasa_de_error:.1%})\n{medicion.tabla()}"
    )

    for nombre, techo in PRESUPUESTO.items():
        p95 = medicion.p95(nombre)
        if p95 is not None and p95 > techo:
            fallas.append(f"  {nombre}: p95 {p95:.0f} ms > presupuesto {techo} ms")

    assert not fallas, "presupuesto de latencia excedido:\n" + "\n".join(fallas)


def test_baseline(correr_carga):
    """Smoke: un usuario, sin concurrencia.

    NO mide capacidad. Mide cuánto tarda cada endpoint cuando nadie más molesta,
    y ese número es la baseline: es contra él que se escriben los presupuestos.
    Es lo primero que se corre, siempre — sin baseline, un rojo no distingue
    "la app está lenta" de "el techo estaba mal puesto".

    Usa `BaselineUser`, que recorre todos los endpoints en orden: el mix
    aleatorio deja endpoints sin muestras en una corrida corta.
    """
    medicion = correr_carga(BaselineUser, usuarios=1, spawn_rate=1, segundos=30)

    sin_muestras = [n for n in PRESUPUESTO if n != "login" and medicion.p95(n) is None]
    assert not sin_muestras, f"sin una sola muestra, no hay baseline: {sin_muestras}"
    dentro_de_presupuesto(medicion)


def test_login_bajo_concurrencia(correr_carga):
    """El costo de autenticarse, medido aparte de todo lo demás.

    Argon2id es memory-hard a propósito: ~22 ms de verify es el precio de que un
    hash robado no se pueda romper a fuerza de GPU. Es el único POST de la suite.
    """
    medicion = correr_carga(LoginUser, usuarios=10, spawn_rate=2, segundos=60)

    p95 = medicion.p95("login")
    assert p95 is not None, "el login no registró ni una muestra"
    assert p95 <= PRESUPUESTO["login"], (
        f"login: p95 {p95:.0f} ms > presupuesto {PRESUPUESTO['login']} ms"
    )


@pytest.mark.lento
def test_carga_esperada(correr_carga):
    """El tráfico que se espera de verdad.

    Acá el veredicto significa algo: si un endpoint se pasa de su presupuesto
    con el tráfico normal, es un problema hoy, no una proyección.
    """
    medicion = correr_carga(CatalogoUser, usuarios=20, spawn_rate=2, segundos=180)
    dentro_de_presupuesto(medicion)


@pytest.mark.lento
def test_stress(correr_carga):
    """Subir hasta que duela, para encontrar DÓNDE duele.

    **Se espera que rompa**, y por eso este test no afirma ningún presupuesto:
    un rojo acá no significa lo mismo que un rojo en `test_carga_esperada`, y
    mezclarlos haría que el rojo deje de significar algo. Lo único que se afirma
    es que la corrida ocurrió; el dato que se busca —en qué carga se dio vuelta—
    se lee en la tabla que imprime.
    """
    medicion = correr_carga(CatalogoUser, usuarios=200, spawn_rate=1, segundos=300)

    assert medicion.requests > 0, "cero requests: el test no llegó a correr"
    print(f"tasa de error bajo 200 usuarios: {medicion.tasa_de_error:.1%}")


@pytest.mark.lento
def test_spike(correr_carga):
    """Un pico de golpe, no una rampa.

    Contesta otra pregunta que el stress: no "cuánto aguanta" sino "¿se
    recupera?". Los 150 usuarios entran de una (`spawn_rate` = `usuarios`), que
    es justo lo que una rampa suaviza y esconde.

    Se afirma la tasa de error, no la latencia: bajo un pico la latencia SUBE y
    eso es correcto. Lo que no puede pasar es que el backend empiece a rechazar.
    """
    medicion = correr_carga(CatalogoUser, usuarios=150, spawn_rate=150, segundos=120)

    assert medicion.requests > 0, "cero requests: el test no llegó a correr"
    assert medicion.tasa_de_error <= 0.05, (
        f"el pico produjo {medicion.tasa_de_error:.1%} de errores "
        f"({medicion.fallos}/{medicion.requests})\n{medicion.tabla()}"
    )
