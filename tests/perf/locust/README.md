# Performance testing con Locust

Suite de carga sobre la API de Scrappy. **Locust es el motor y pytest el
runner**: la carga se define como clases de usuario virtual, pero cada escenario
es un test común, y el veredicto es una aserción en vez de un exit code que haya
que interpretar desde afuera.

```
locust/
├── pyproject.toml        uv: locust + pytest + requests, y la config de pytest
├── conftest.py           el backend, el token, y `correr_carga(...)`
├── carga.py              qué se pide (ENDPOINTS) y quién lo pide (los usuarios)
├── test_rendimiento.py   los cinco escenarios, como tests
└── .venv/                la crea uv sola en la primera corrida (gitignored)
```

```bash
cd tests/perf/locust
uv run pytest                    # baseline + login — ~90 s
uv run pytest -m lento           # carga, stress y spike — ~10 min
uv run pytest -k baseline        # uno solo
```

`uv` se encarga del venv y de las dependencias: no hay nada que instalar a mano.

Por defecto corre lo rápido. Las formas largas están marcadas `lento` y se piden
explícitamente, porque una suite que tarda diez minutos por defecto deja de
correrse — y entonces no mide nada.

---

---

## Los cuatro tipos de test de carga

No son cuatro intensidades de lo mismo: son cuatro preguntas distintas.

| Test | La pregunta | Qué se espera |
|---|---|---|
| `test_baseline` | ¿Cuánto tarda cada endpoint cuando nadie más molesta? | Verde. Es la **baseline** — el número contra el que se escriben todos los presupuestos |
| `test_carga_esperada` | Con el tráfico esperado, ¿seguimos dentro del presupuesto? | Verde. Un rojo acá es un problema **hoy** |
| `test_stress` | ¿Dónde se rompe? | **Rojo, y está bien.** El dato no es verde/rojo: es *en cuántos usuarios* se dispara la latencia |
| `test_spike` | Un pico de golpe, ¿se recupera? | Contesta otra cosa que stress: no cuánto aguanta, sino si vuelve a la normalidad |

El orden importa. Sin la baseline, los presupuestos de `carga.py` son números
inventados y un rojo no distingue "la app está lenta" de "el techo estaba mal
puesto".

Cada test se juzga con su propio criterio, y eso se ve en el código:
`test_stress` **no afirma ningún presupuesto** —se espera que rompa, y mezclarlo
con `test_carga_esperada` haría que un rojo deje de significar algo— y
`test_spike` afirma la tasa de error y no la latencia, porque bajo un pico la
latencia sube y eso es correcto; lo que no puede pasar es que el backend empiece
a rechazar.

---

## Cómo leer los números

El reporte de Locust da, por endpoint: `# reqs`, `# fails`, `Avg`, `Min`, `Max`,
`Median`, los percentiles y `req/s`.

**El promedio es el número menos útil de la tabla.** Una request de 4 s escondida
entre mil de 20 ms lo mueve apenas — y es esa request la que un usuario ve. Lo
que se mira es:

- **p95** — el 5% peor. Es el presupuesto que esta suite hace cumplir.
- **p99** — la cola larga. Si p95 y p99 están lejos, hay algo intermitente
  (un lock, el pool de conexiones, un GC).
- **fails** — un error bajo carga no es "lento", es roto. Tolerancia: 1%.
- **req/s** — el throughput. Sube con la carga hasta que se aplana: ese techo
  es la capacidad real.

El patrón a reconocer: latencia estable y throughput subiendo = todavía hay
margen. Throughput plano y latencia subiendo = saturado, la cola crece.

---

## Lo que salió medido

Catálogo real: 15.987 productos, 2026-09-22. `carga` = 20 usuarios, 3 min,
2653 requests, cero errores.

| endpoint | p95 baseline (1 usuario) | p95 con carga (20) | presupuesto |
|---|---:|---:|---:|
| `status` | 3 ms | 3 ms | 30 |
| `indices` | 2 ms | 3 ms | 30 |
| `marcas` | 7 ms | 6 ms | 40 |
| `outfits_builder` | 6 ms | 7 ms | 40 |
| `suplementos_builder` | 9 ms | 11 ms | 40 |
| `mejores` | 12 ms | 13 ms | 40 |
| `pcs_builder` | 21 ms | 23 ms | 50 |
| `recomendados` | 48 ms | 57 ms | 120 |
| `grupos` | 93 ms | 97 ms | 200 |
| `facets` | 142 ms | 150 ms | 300 |
| `data` | 152 ms | 160 ms | 350 |
| `data_filtrado` | 163 ms | 170 ms | 350 |
| `login` (POST) | — | 43 ms (10 usuarios) | 150 |

**La regla del presupuesto:** `max(2 × p95, p95 + 25 ms)`, redondeado. El factor
2 da aire para que una regresión chica no ponga todo rojo; el `+25 ms` es un piso
absoluto, porque el doble de 3 ms es ruido del reloj, no un presupuesto.

### ⚠ La intuición estaba al revés, y por eso se mide

La primera versión de esta suite le daba **2000–2500 ms** a los armadores y a
`/api/grupos` —"caros por diseño, corren un branch-and-bound"— y **300 ms** a
`/api/facets`, "facetas precalculadas, el barato". Medido:

- `pcs_builder` sale **23 ms** y `grupos` **97**: los techos estaban 100x y 20x
  arriba. Un presupuesto así no puede fallar nunca.
- `facets` sale **150 ms** y es el **tercero más lento** del catálogo.

Los caros son los que pegan a Postgres (`data`, `data_filtrado`, `facets`), no
los algoritmos en memoria. Un solver sobre 15.987 productos le gana 7x a una
consulta SQL con faceteo. Ninguna de las dos cosas era obvia leyendo el código.

### Lo que encontró la suite mientras se la ponía a punto

- **`GET /api/recomendados?page=0` tira 500.** `RecomendadosEndpoints.java:131`
  hace `Math.min((page - 1) * size, total)`: con `page=0` eso da `-24` y
  `subList(-24, …)` revienta con `IndexOutOfBoundsException`. El `Math.min`
  acota arriba y nada acota abajo. El contrato dice `page` base 1
  (`docs/openapi.yaml`), así que la request estaba fuera de contrato — pero
  `/api/data` recibe el mismo `page=0` y lo clampea sin drama. Dos endpoints
  con el mismo parámetro contestan distinto a la misma entrada inválida.
- **`/api/outfits/builder` exige `categorias` y `/api/suplementos/builder`
  exige `tipos`**; sin ellos son 400. No están marcados `required` en
  `docs/openapi.yaml`.
- **El login medido (43 ms p95, 10 usuarios concurrentes) desmintió los 76 ms
  de verify que documentaba `CLAUDE.md`**: un request entero no puede costar
  la mitad del verify que contiene. Re-medido con el mismo método y la misma
  máquina: **22 ms hash / 22 ms verify**. Corregido en las seis copias del
  número, incluidos los dos razonamientos que dependían de él (el oráculo de
  timing de `AuthEndpoints` y los "intentos por segundo" de
  `LoginRateLimiter`, que pasaron de 13/s a 45/s).

---

## Correrlo

Hace falta un backend vivo y una cuenta:

```bash
scripts/dev-db.sh up
tests/e2e/run-e2e.sh --api --keep-up      # levanta el backend y lo deja arriba

tests/perf/perf-user.sh                   # crea la cuenta por la API real
set -a; . tests/perf/.perf-credentials.env; set +a

cd tests/perf/locust && uv run pytest
```

`perf-user.sh` crea un VIEWER con `POST /api/usuarios` —la API real, no SQL— y
deja usuario y password en `tests/perf/.perf-credentials.env`, gitignored y modo
600. El username lleva un sufijo único por corrida: la API no expone cambiarle
la password a otra cuenta (deliberadamente), así que una cuenta fija sería
irrecuperable el día que se pierda el archivo.

Rol VIEWER y no ADMIN: todos los endpoints que se miden son `AUTHENTICATED`.
Una suite de carga no necesita poder borrar el catálogo.

`PERF_API_BASE_URL` apunta a otro backend si hace falta. Esta suite **no levanta
el backend**: `tests/e2e/run-e2e.sh` ya hace eso, y una segunda copia de 140
líneas de manejo de procesos es una que después se separa. Si no hay nadie
escuchando, la fixture falla diciendo qué correr, en vez de medir el vacío.

---

## Tres decisiones que vale la pena entender

**Un solo login, antes de la carga.** Si cada usuario virtual se loguea, el
número que sale no es la latencia del endpoint: es la de Argon2id, que en esta
app está medido en ~22 ms de verify. Es el error más común de una suite de perf, y hace
que el test deje de poder ver una mejora. Acá el token se pide una vez en
`test_start` y lo comparten todos. El costo de loguearse se mide aparte, con
`uv run pytest -k login`.

**`wait_time` es tiempo de lectura, no relleno.** Un usuario real mira la
pantalla entre click y click. Sin esa pausa, 20 usuarios virtuales generan el
tráfico de varios cientos reales, y "20 usuarios" deja de querer decir nada.

**Sólo lectura.** Ningún POST/PUT/DELETE salvo el login. Un test de carga que
escribe contamina el catálogo y hace que la segunda corrida ya no mida lo mismo
que la primera.

**Y una de plomería:** el `monkey.patch_all()` de gevent tiene que ser lo
primero de `conftest.py`, antes de cualquier otro import. Locust corre sobre
gevent, que reemplaza el socket, el threading y el sleep de la stdlib por
versiones cooperativas; si algo ya importó `socket` cuando el parche llega,
quedan dos mundos conviviendo y la carga se cuelga o serializa sin avisar.
pytest importa medio mundo apenas arranca, así que el parche no puede ir adentro
de un test.

---

## Agregar un endpoint

Una línea en `ENDPOINTS`, en `carga.py`:

```python
("mi_endpoint", "/api/lo-que-sea?param=1", 2, 800),
#  nombre        path                      peso  techo p95 en ms
```

El **peso** es cuán seguido le pega el mix (`data` tiene 5, `status` tiene 1: el
catálogo se pide mucho más que el status). El **techo** sale de correr
`uv run pytest -k baseline` y después `uv run pytest -m lento -k carga`, y
aplicar `max(2 × p95, p95 + 25 ms)`. No de la intuición: la sección "la
intuición estaba al revés" cuenta cómo salió esa apuesta la primera vez.
