"""Qué se pide y quién lo pide: el catálogo de endpoints y los usuarios virtuales.

El veredicto —cuándo está mal— no vive acá: vive en las aserciones de
`test_rendimiento.py`, que es donde pytest lo espera.
"""
from __future__ import annotations

import os
import random

from locust import HttpUser, between, task

# ─────────────────────────────────────────────────────────────────────────────
# QUÉ se pide
# ─────────────────────────────────────────────────────────────────────────────
#
# peso   — cuán seguido le pega el mix. Sin pesos, el test le pega igual de
#          seguido a /api/status que a /api/data, y eso no se parece a ningún
#          usuario real. El perfil de tráfico es parte del test.
#
# Presupuestos MEDIDOS contra el catálogo real (15.987 productos, 2026-09-22),
# con 20 usuarios durante 3 minutos: 2653 requests, cero errores.
#
# La regla: presupuesto = max(2 × p95, p95 + 25 ms), redondeado. El factor 2 da
# aire para que una regresión chica no ponga todo rojo; el "+25 ms" es un piso
# absoluto, porque el doble de 3 ms es ruido del reloj, no un presupuesto.
#
# ⚠ LA INTUICIÓN ESTABA AL REVÉS, y por eso se mide. La primera versión de este
# archivo le daba 2000-2500 ms a los armadores y a /api/grupos por "caros por
# diseño", y 300 ms a /api/facets por "precalculado". Medido: pcs_builder sale
# 23 ms y grupos 97; facets sale 150 ms y es el tercero más lento. Los caros son
# los que pegan a Postgres, no los algoritmos en memoria — un solver sobre
# 15.987 productos le gana 7x a una consulta SQL con faceteo.
#
# Sólo lectura: ningún POST/PUT/DELETE fuera del login. Un test de carga que
# escribe contamina el catálogo y hace que la segunda corrida ya no mida lo
# mismo que la primera.

ENDPOINTS = [
    # un campo en memoria — p95 medido 3 ms
    ("status", "/api/status", 1, 30),
    # IPC + dólar ya refrescados por un job aparte — p95 medido 3 ms
    ("indices", "/api/indices", 1, 30),
    # agregación por marca sobre el snapshot — p95 medido 6 ms
    ("marcas", "/api/marcas-browser", 1, 40),
    # la primera pantalla de /catalogo, y el endpoint más pedido — p95 medido
    # 160 ms, el segundo más lento
    ("data", "/api/data?page=0&size=24", 5, 350),
    # el mismo SQL con WHERE + ORDER BY — p95 medido 170 ms, EL más lento
    ("data_filtrado", "/api/data?page=0&size=24&rubro=tecnologia&orden=precio_asc", 3, 350),
    # p95 medido 150 ms: el tercero más lento, no el barato que parece
    ("facets", "/api/facets", 2, 300),
    # re-agrupa el catálogo entero por request — p95 medido 97 ms
    ("grupos", "/api/grupos?minSitios=2&page=0&size=20", 2, 200),
    # mejor pick por categoría sobre el snapshot — p95 medido 13 ms
    ("mejores", "/api/mejores", 1, 40),
    # arma el FeedbackModel y pega 2 queries — p95 medido 57 ms.
    # `page` es BASE 1: hasta 2026-09-22 un page=0 tiraba 500
    ("recomendados", "/api/recomendados?page=1&size=24", 2, 120),
    # MCKP con branch-and-bound — p95 medido 7 ms. `categorias` es obligatorio
    ("outfits_builder",
     "/api/outfits/builder?categorias=Remera,Jean,Zapatilla&presupuesto=150000&estilo=gym",
     1, 40),
    # una pasada por precedencia sobre 33 subtipos — p95 medido 11 ms.
    # `tipos` es obligatorio
    ("suplementos_builder",
     "/api/suplementos/builder?tipos=Prote%C3%ADna%20en%20Polvo,Creatina&presupuesto=100000",
     1, 40),
    # parsea TechSpecs al armar y corre siete slots con sus vetos — p95 medido 23 ms
    ("pcs_builder", "/api/pcs/builder?presupuesto=2000000&conGpu=true&gama=alta", 1, 50),
]

PRESUPUESTO = {nombre: p95 for nombre, _, _, p95 in ENDPOINTS}
# Argon2id: ~22 ms de verify medidos. El login entero clava 43 ms p95 con diez
# usuarios concurrentes.
PRESUPUESTO["login"] = 150

# El token compartido. Lo escribe la fixture `token` de conftest.py, UNA vez,
# antes de que exista el primer usuario virtual.
#
# El error clásico de una suite de performance es loguearse adentro del loop:
# ahí el número que sale no es la latencia del endpoint, es la de Argon2id.
# `on_start` sería mejor —corre una vez por usuario— pero con 100 usuarios
# siguen siendo 100 hashes durante el ramp-up, justo cuando arranca la medición.
TOKEN: str | None = None


# ─────────────────────────────────────────────────────────────────────────────
# QUIÉN lo pide
# ─────────────────────────────────────────────────────────────────────────────

class CatalogoUser(HttpUser):
    """Un usuario navegando la app, con el mix de tráfico de ENDPOINTS."""

    # Tiempo de lectura, no relleno: un usuario real mira la pantalla entre
    # click y click. Sin esa pausa, 20 usuarios virtuales generan el tráfico de
    # varios cientos reales y "20 usuarios" deja de querer decir nada.
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

    `CatalogoUser` tira el dado respetando el peso, que es lo correcto para
    imitar tráfico — pero en una corrida corta deja endpoints con cero muestras,
    y un endpoint sin muestras no tiene baseline. Medido: el primer smoke de
    30 s hizo 23 requests y tres endpoints no aparecieron en el reporte.

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
    """Mide el costo de autenticarse, aparte de todo lo demás.

    Argon2id es memory-hard a propósito: ese costo es el precio de que un hash
    robado no se pueda romper a fuerza de GPU. Mezclarlo con los GET arruinaría
    las dos mediciones a la vez.
    """

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
