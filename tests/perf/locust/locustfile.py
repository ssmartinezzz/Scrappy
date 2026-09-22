"""Suite de performance de la API — Locust.

Se corre con ./run.sh (ver README.md).

Las tres piezas de cualquier test de carga están acá, en este orden:
  1. QUÉ se pide        → ENDPOINTS
  2. QUIÉN lo pide      → CatalogoUser / LoginUser
  3. CUÁNDO está mal    → _veredicto
"""
from __future__ import annotations

import logging
import os
import random

import requests
from locust import HttpUser, between, events, task

log = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────────────
# 1. QUÉ se pide
# ─────────────────────────────────────────────────────────────────────────────
#
# peso   — cuán seguido le pega el mix. Sin pesos, el test le pega igual de
#          seguido a /api/status que a /api/data, y eso no se parece a ningún
#          usuario real. El perfil de tráfico es parte del test.
#
# Presupuestos MEDIDOS contra el catálogo real (15.987 productos, 2026-09-22),
# con `carga`: 20 usuarios, 3 min, 2653 requests, cero errores.
#
# La regla: presupuesto = max(2 × p95, p95 + 25 ms), redondeado. El factor 2 da
# aire para que una regresión chica no ponga todo rojo; el "+25 ms" es un piso
# absoluto, porque el doble de 3 ms es ruido del reloj, no un presupuesto.
#
# ⚠ LA INTUICIÓN ESTABA AL REVÉS, y por eso se mide. La primera versión de este
# archivo le daba 2000-2500 ms a los armadores y a /api/grupos por "caros por
# diseño", y 300 ms a /api/facets por "precalculado". Medido: pcs_builder sale
# 23 ms y grupos 97; facets sale 150 ms y es el tercero más lento. Los caros son
# los que pegan a Postgres, no los algoritmos en memoria — un solver sobre 15.987
# productos le gana 7x a una consulta SQL con faceteo.

ENDPOINTS = [
    # un campo en memoria — p95 medido 3 ms
    ('status', '/api/status', 1, 30),
    # IPC + dólar ya refrescados por un job aparte — p95 medido 3 ms
    ('indices', '/api/indices', 1, 30),
    # agregación por marca sobre el snapshot — p95 medido 6 ms
    ('marcas', '/api/marcas-browser', 1, 40),
    # la primera pantalla de /catalogo, y el endpoint más pedido — p95 medido 160 ms, el segundo más lento
    ('data', '/api/data?page=0&size=24', 5, 350),
    # el mismo SQL con WHERE + ORDER BY — p95 medido 170 ms, EL más lento del catálogo
    ('data_filtrado', '/api/data?page=0&size=24&rubro=tecnologia&orden=precio_asc', 3, 350),
    # p95 medido 150 ms: el tercero más lento, no el barato que parece
    ('facets', '/api/facets', 2, 300),
    # re-agrupa el catálogo entero por request — p95 medido 97 ms
    ('grupos', '/api/grupos?minSitios=2&page=0&size=20', 2, 200),
    # mejor pick por categoría sobre el snapshot — p95 medido 13 ms
    ('mejores', '/api/mejores', 1, 40),
    # arma el FeedbackModel y pega 2 queries — p95 medido 57 ms. page es BASE 1: con page=0 el endpoint tira 500
    ('recomendados', '/api/recomendados?page=1&size=24', 2, 120),
    # MCKP con branch-and-bound — p95 medido 7 ms. `categorias` es obligatorio
    ('outfits_builder', '/api/outfits/builder?categorias=Remera,Jean,Zapatilla&presupuesto=150000&estilo=gym', 1, 40),
    # una pasada por precedencia sobre 33 subtipos — p95 medido 11 ms. `tipos` es obligatorio
    ('suplementos_builder', '/api/suplementos/builder?tipos=Prote%C3%ADna%20en%20Polvo,Creatina&presupuesto=100000', 1, 40),
    # parsea TechSpecs al armar y corre siete slots con sus vetos — p95 medido 23 ms
    ('pcs_builder', '/api/pcs/builder?presupuesto=2000000&conGpu=true&gama=alta', 1, 50),
]

PRESUPUESTO = {nombre: p95 for nombre, _, _, p95 in ENDPOINTS}
PRESUPUESTO["login"] = 150  # p95 medido 43 ms con 10 usuarios concurrentes. Ver README.

# Sólo lectura. Ningún POST/PUT/DELETE fuera del login: un test de carga que
# escribe contamina el catálogo y hace que la segunda corrida ya no mida lo
# mismo que la primera.


# ─────────────────────────────────────────────────────────────────────────────
# El token: UNO solo, antes de que exista el primer usuario virtual
# ─────────────────────────────────────────────────────────────────────────────
#
# El error clásico de una suite de performance es loguearse adentro del loop.
# Ahí el número que sale no es la latencia del endpoint: es la de Argon2id
# (76 ms medidos), entre diez y cien veces lo que tarda un GET. Bajar un
# endpoint a la mitad de su costo no movería la aguja.
#
# `on_start` sería mejor que eso —corre una vez por usuario— pero con 100
# usuarios siguen siendo 100 hashes durante el ramp-up, justo cuando arranca la
# medición. Acá se pide una vez y lo comparten todos.

TOKEN = None


@events.test_start.add_listener
def _login_unico(environment, **_):
    global TOKEN
    usuario, password = os.environ.get("PERF_USERNAME"), os.environ.get("PERF_PASSWORD")
    if not usuario or not password:
        raise SystemExit("faltan PERF_USERNAME / PERF_PASSWORD (ver README.md)")

    r = requests.post(
        f"{environment.host}/api/auth/login",
        json={"username": usuario, "password": password},
        timeout=30,
    )
    if r.status_code != 200:
        raise SystemExit(f"login respondió {r.status_code} — sin token no hay nada que medir")

    TOKEN = r.json()["accessToken"]
    log.info("token obtenido; arranca la carga")


# ─────────────────────────────────────────────────────────────────────────────
# 2. QUIÉN lo pide
# ─────────────────────────────────────────────────────────────────────────────

class CatalogoUser(HttpUser):
    """Un usuario navegando la app."""

    # Tiempo de lectura, no relleno: un usuario real mira la pantalla entre
    # click y click. Sin esa pausa, 20 usuarios virtuales generan el tráfico de
    # varios cientos reales y el test contesta una pregunta que nadie hizo.
    wait_time = between(0.5, 2.0)

    def on_start(self):
        self.client.headers["Authorization"] = f"Bearer {TOKEN}"

    @task
    def navegar(self):
        nombre, path, _, _ = random.choices(ENDPOINTS, weights=[e[2] for e in ENDPOINTS])[0]
        # `name=` agrupa las estadísticas. Sin él, cada query string sería una
        # fila distinta en el reporte y no habría un percentil por endpoint.
        self.client.get(path, name=nombre)


class BaselineUser(HttpUser):
    """Recorre TODOS los endpoints en orden, una vez por iteración.

    `CatalogoUser` elige al azar respetando el peso, que es lo correcto para
    imitar tráfico — pero en una corrida corta deja endpoints con cero muestras,
    y un endpoint sin muestras no tiene baseline. Medido: el primer smoke de 30 s
    hizo 23 requests y tres endpoints no aparecieron en el reporte.

    Para medir se recorre todo; para simular carga se tira el dado.
    """

    wait_time = between(0, 0)

    def on_start(self):
        self.client.headers["Authorization"] = f"Bearer {TOKEN}"

    @task
    def recorrer_todo(self):
        for nombre, path, _, _ in ENDPOINTS:
            self.client.get(path, name=nombre)


class LoginUser(HttpUser):
    """Mide el costo de autenticarse, aparte de todo lo demás."""

    wait_time = between(1.0, 3.0)

    @task
    def login(self):
        self.client.post(
            "/api/auth/login",
            json={
                "username": os.environ.get("PERF_USERNAME"),
                "password": os.environ.get("PERF_PASSWORD"),
            },
            name="login",
        )


# ─────────────────────────────────────────────────────────────────────────────
# 3. CUÁNDO está mal
# ─────────────────────────────────────────────────────────────────────────────
#
# Sin esto Locust termina en 0 pase lo que pase, y la suite es un reporte bonito
# que nunca falla. Un test que no puede ponerse rojo no es un test.

@events.quitting.add_listener
def _veredicto(environment, **_):
    fallas = []
    total = environment.stats.total

    if total.num_requests == 0:
        fallas.append("cero requests — el test no llegó a correr")
    elif total.fail_ratio > 0.01:
        fallas.append(f"tasa de error {total.fail_ratio:.2%} > 1% (un error no es lento, es roto)")

    for clave, stats in environment.stats.entries.items():
        nombre = clave[0] if isinstance(clave, tuple) else clave
        techo = PRESUPUESTO.get(nombre)
        if techo is None or stats.num_requests == 0:
            continue
        p95 = stats.get_response_time_percentile(0.95)
        if p95 > techo:
            fallas.append(f"{nombre}: p95 {p95:.0f} ms > presupuesto {techo} ms")

    for f in fallas:
        log.error("✗ %s", f)
    if not fallas:
        log.info("✓ todos los endpoints dentro de su presupuesto")
    environment.process_exit_code = 1 if fallas else 0
