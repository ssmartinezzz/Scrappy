"""Fixtures de la suite de performance: el backend, el token, y correr la carga.

⚠ El `monkey.patch_all()` de abajo tiene que ser lo PRIMERO del archivo, antes
de cualquier otro import. Locust corre sobre gevent, que reemplaza el socket, el
threading y el sleep de la stdlib por versiones cooperativas; si algo ya importó
`socket` cuando el parche llega, quedan dos mundos conviviendo y la carga se
cuelga o serializa sin avisar. pytest importa medio mundo apenas arranca, así
que el parche va acá arriba y no adentro de un test.
"""
from gevent import monkey  # noqa: E402  (tiene que ser el primer import)

monkey.patch_all()

import os  # noqa: E402
import sys  # noqa: E402
from dataclasses import dataclass  # noqa: E402
from pathlib import Path  # noqa: E402

import gevent  # noqa: E402
import pytest  # noqa: E402
import requests  # noqa: E402
from locust.env import Environment  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import carga  # noqa: E402


@pytest.fixture(scope="session")
def host() -> str:
    """El backend que se mide. Esta suite NO lo levanta.

    Duplicar el manejo de procesos que ya hace `tests/e2e/run-e2e.sh` es una
    copia que después se separa. Si no hay nadie escuchando, esto falla
    diciendo qué correr, en vez de medir el vacío.
    """
    url = os.environ.get("PERF_API_BASE_URL", "http://localhost:3000")
    try:
        r = requests.get(f"{url}/", timeout=5)
    except requests.RequestException as e:
        pytest.fail(
            f"no hay backend en {url} ({e}).\n"
            "  scripts/dev-db.sh up\n"
            "  tests/e2e/run-e2e.sh --api --keep-up",
            pytrace=False,
        )
    assert r.status_code == 200, f"GET / contestó {r.status_code}, esperaba el 200 de liveness"
    return url


@pytest.fixture(scope="session")
def credenciales() -> tuple[str, str]:
    usuario, password = os.environ.get("PERF_USERNAME"), os.environ.get("PERF_PASSWORD")
    if not usuario or not password:
        pytest.fail(
            "faltan PERF_USERNAME / PERF_PASSWORD.\n"
            "  tests/perf/perf-user.sh          # crea la cuenta por la API\n"
            "  set -a; . tests/perf/.perf-credentials.env; set +a\n"
            "No tienen default porque un default sería una password commiteada.",
            pytrace=False,
        )
    return usuario, password


@pytest.fixture(scope="session", autouse=True)
def token(host: str, credenciales: tuple[str, str]) -> str:
    """UN login por sesión, y el token lo comparten todos los usuarios virtuales.

    Este es EL error clásico de una suite de performance, y vale la pena decirlo
    entero: si cada usuario virtual se loguea, el número que sale no es la
    latencia del endpoint, es la de Argon2id — ~22 ms de verify medidos, muchas
    veces lo que tarda un GET de catálogo. Con el login adentro del loop, bajar
    un endpoint a la mitad de su costo no movería la aguja y el test diría que
    no pasó nada.

    El costo de loguearse se mide aparte, en `test_login_bajo_concurrencia`, que
    es donde esa pregunta sí es la pregunta.
    """
    usuario, password = credenciales
    r = requests.post(
        f"{host}/api/auth/login",
        json={"username": usuario, "password": password},
        timeout=30,
    )
    assert r.status_code == 200, (
        f"login como {usuario!r} contestó {r.status_code}: {r.text[:200]} — "
        "sin token no hay nada que medir"
    )
    carga.TOKEN = r.json()["accessToken"]
    return carga.TOKEN


@dataclass
class Medicion:
    """El resultado de una corrida, con lo que hace falta para juzgarla."""

    stats: object

    def p95(self, nombre: str) -> float | None:
        """El p95 de un endpoint, o None si no tuvo ni una muestra."""
        entrada = self.stats.get(nombre, "GET") if nombre != "login" \
            else self.stats.get(nombre, "POST")
        if entrada.num_requests == 0:
            return None
        return entrada.get_response_time_percentile(0.95)

    @property
    def requests(self) -> int:
        return self.stats.total.num_requests

    @property
    def fallos(self) -> int:
        return self.stats.total.num_failures

    @property
    def tasa_de_error(self) -> float:
        return self.stats.total.fail_ratio

    def tabla(self) -> str:
        filas = [f"{'endpoint':22} {'n':>5} {'p95 ms':>8} {'techo':>7}"]
        for nombre in sorted(carga.PRESUPUESTO):
            p = self.p95(nombre)
            if p is None:
                continue
            n = (self.stats.get(nombre, "POST") if nombre == "login"
                 else self.stats.get(nombre, "GET")).num_requests
            filas.append(f"{nombre:22} {n:>5} {p:>8.0f} {carga.PRESUPUESTO[nombre]:>7}")
        return "\n".join(filas)


@pytest.fixture
def correr_carga(host: str):
    """Corre una forma de carga y devuelve su `Medicion`.

    Locust se usa como librería, no por su CLI: el runner vive adentro del
    proceso de pytest, así que el veredicto es una aserción común y no un exit
    code que haya que interpretar desde afuera.
    """

    def _correr(clase_de_usuario, *, usuarios: int, spawn_rate: float, segundos: float) -> Medicion:
        entorno = Environment(user_classes=[clase_de_usuario], host=host)
        entorno.create_local_runner()
        entorno.runner.start(usuarios, spawn_rate=spawn_rate)
        gevent.spawn_later(segundos, entorno.runner.quit)
        entorno.runner.greenlet.join()

        medicion = Medicion(entorno.stats)
        print(f"\n{medicion.tabla()}\n")
        return medicion

    return _correr
