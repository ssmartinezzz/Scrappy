# Performance testing con JMeter

Suite de carga sobre la API de Scrappy. Los planes se escriben en Java con
[jmeter-java-dsl](https://abstracta.github.io/jmeter-java-dsl/) y corren sobre
el motor de JMeter: lo que se construye acá **es** un plan de JMeter, y
`./run.sh jmx` lo exporta como `.jmx` para abrirlo en la GUI.

```
jmeter/
├── pom.xml                         módulo Maven independiente del backend
├── run.sh                          las formas de carga + el export a .jmx
└── src/
    ├── main/java/ar/scraper/perf/
    │   ├── Config.java             qué endpoints, con qué peso y qué presupuesto
    │   └── ExportarJmx.java        escribe los planes .jmx para la GUI
    └── test/java/ar/scraper/perf/
        ├── SmokeIT.java            1 hilo — la baseline
        ├── CargaIT.java            20 usuarios — el tráfico esperado
        ├── StressIT.java           escalones a 200 — dónde se rompe
        ├── SpikeIT.java            150 de golpe — ¿se recupera?
        ├── LoginIT.java            Argon2id, aparte
        └── Veredicto.java          el presupuesto por endpoint decide el rojo
```

**Por qué el Java y no el `.jmx` directo.** Un `.jmx` es XML generado por la GUI:
ilegible en un diff, imposible de revisar en un PR y nada fácil de compartir
entre planes. El Java se lee, se versiona y se reusa; el `.jmx` sigue estando a
un comando de distancia cuando hace falta la GUI. La dirección es de ida: el
`.jmx` es una salida, no una fuente.

---

## Los cuatro tipos de test de carga

No son cuatro intensidades de lo mismo: son cuatro preguntas distintas.

| Clase | La pregunta | Qué se espera |
|---|---|---|
| `SmokeIT` | ¿Cuánto tarda cada endpoint cuando nadie más molesta? | Verde. Es la **baseline** — el número contra el que se escriben todos los presupuestos |
| `CargaIT` | Con el tráfico esperado, ¿seguimos dentro del presupuesto? | Verde. Un rojo acá es un problema **hoy** |
| `StressIT` | ¿Dónde se rompe? | **Se espera que rompa.** Por eso no llama al veredicto: el dato no es verde/rojo, es *en qué escalón* se dio vuelta |
| `SpikeIT` | Un pico de golpe, ¿se recupera? | Lo que se mira es la meseta tranquila **después** del pico |

El orden importa. Sin la baseline del smoke, los presupuestos de `Config` son
números inventados y un rojo no distingue "la app está lenta" de "el techo
estaba mal puesto".

---

## Cómo leer los números

JMeter deja un `.jtl` por corrida en `target/jtl/` (una fila por request) y el
resumen por consola.

**El promedio es el número menos útil.** Una request de 4 s escondida entre mil
de 20 ms lo mueve apenas — y es esa request la que un usuario ve. Lo que se mira:

- **p95** — el 5% peor. Es el presupuesto que `Veredicto` hace cumplir.
- **p99** — la cola larga. Si p95 y p99 están lejos, hay algo intermitente
  (un lock, el pool de conexiones, un GC).
- **errores** — un error bajo carga no es "lento", es roto. Y un endpoint que
  falla rápido tiene un p95 excelente, así que se chequea aparte del tiempo.
  Tolerancia: 1%.
- **throughput** — sube con la carga hasta que se aplana: ese techo es la
  capacidad real.

El patrón a reconocer: latencia estable y throughput subiendo = todavía hay
margen. Throughput plano y latencia subiendo = saturado, la cola crece.

---

## Lo que salió medido

Catálogo real: 23.217 productos, 2026-10-05. `carga` = 20 usuarios, 3 min,
2795 requests, cero errores. Cada techo sale del p95 más alto de las dos columnas.
El login es la excepción: Locust lo mide con 1-3 s de pausa entre intentos (41 ms)
y esta suite sin pausa (137 ms), así que cada una tiene su propio techo.

| endpoint | p95 baseline (1 usuario) | p95 con carga (20) | presupuesto |
|---|---:|---:|---:|
| `status` | 3 ms | 3 ms | 30 |
| `indices` | 3 ms | 3 ms | 30 |
| `marcas` | 4 ms | 4 ms | 30 |
| `facets` | 4 ms | 4 ms | 30 |
| `grupos` | 4 ms | 3 ms | 30 |
| `mejores` | 7 ms | 7 ms | 40 |
| `suplementos_builder` | 12 ms | 13 ms | 40 |
| `outfits_builder` | 13 ms | 9 ms | 40 |
| `data` | 24 ms | 22 ms | 50 |
| `pcs_builder` | 37 ms | 39 ms | 80 |
| `data_filtrado` | 41 ms | 39 ms | 90 |
| `recomendados` | 66 ms | 66 ms | 140 |
| `login` (POST) | — | 137 ms (10 hilos sin pausa) | 280 |

**Stress y spike (2026-10-05)**, analizando los JTL por cantidad de hilos:

| hilos | p50 | p95 | p99 | errores |
|---:|---:|---:|---:|---:|
| 1–25 | 57 ms | 204 ms | 301 ms | 0 |
| 26–50 | 109 ms | 348 ms | 476 ms | 0 |
| 51–100 | 198 ms | 705 ms | 982 ms | 0 |
| 101–200 | 324 ms | 1318 ms | 1869 ms | 0 |

Cero errores en 112.773 requests: con cada escalón la latencia se duplica, pero
no hay ni un rechazo. El p95 pasa 1 s entre 100 y 200 hilos, con el cliente, el
backend y Postgres en la misma máquina. Spike (150 hilos de golpe): p95 75 ms
antes del pico, 989 ms durante, 67 ms después; vuelve a la normalidad.

**La regla del presupuesto:** `max(2 × p95, p95 + 25 ms)`, redondeado. El factor
2 da aire para que una regresión chica no ponga todo rojo; el `+25 ms` es un piso
absoluto, porque el doble de 3 ms es ruido del reloj, no un presupuesto.

### ⚠ La intuición estaba al revés, y por eso se mide

La primera versión de esta suite le daba **2000–2500 ms** a los armadores y a
`/api/grupos` —"caros por diseño, corren un branch-and-bound"— y **300 ms** a
`/api/facets`, "facetas precalculadas, el barato". Medido el 2026-09-22:

- `pcs_builder` sale **23 ms** y `grupos` **97**: los techos estaban 100x y 20x
  arriba. Un presupuesto así no puede fallar nunca.
- `facets` sale **150 ms** y es el **tercero más lento** del catálogo.

Los caros son los que pegan a Postgres (`data`, `data_filtrado`, `facets`), no
los algoritmos en memoria. Un solver sobre 15.987 productos le gana 7x a una
consulta SQL con faceteo. Ninguna de las dos cosas era obvia leyendo el código.

### Y un techo medido también se vence

Entre el 2026-09-22 y el 2026-10-05 los endpoints SQL bajaron de 4x a 40x
(`facets` 150 → 4 ms, `grupos` 97 → 4, `data` 160 → 24) y el catálogo creció
45%, con lo que los armadores subieron (`pcs_builder` 23 → 39). La caída de
`grupos`, `mejores` y `marcas` la explica el A/B de backend-hardening
(`odd/tasks/release-performance.md`: snapshot caches); la de `data` y `facets` es
anterior a esa base y no está atribuida. Con los techos viejos, `facets` podía
empeorar 50x y la suite seguía en verde. Re-medir cuando cambia el catálogo o el
backend.

### Lo que encontró la suite mientras se la ponía a punto

- **`GET /api/recomendados?page=0` tiraba 500 (resuelto).** `RecomendadosEndpoints`
  hacía `Math.min((page - 1) * size, total)`: con `page=0` eso daba `-24` y
  `subList(-24, …)` reventaba con `IndexOutOfBoundsException`, mientras `/api/data`
  recibía el mismo `page=0` y lo clampeaba. Hoy `page` es base 0 en los dos
  endpoints (`docs/openapi.yaml`): `page=0` es la primera página y un valor
  negativo se acota a 0 en vez de reventar. Queda como ejemplo de por qué la
  suite sondea el mismo parámetro en ambos endpoints.
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

Un backend vivo, y nada más:

```bash
scripts/dev-db.sh up
tests/e2e/run-e2e.sh --api --keep-up      # backend en :3000, lo deja arriba

tests/perf/jmeter/run.sh smoke
```

No hay que exportar nada. La cuenta se resuelve sola: si no hay
`PERF_USERNAME`/`PERF_PASSWORD` en el entorno ni un
`tests/perf/.perf-credentials.env`, el runner corre `perf-user.sh`, que crea un
VIEWER con `POST /api/usuarios` —la API real, no SQL— y deja las credenciales en
ese archivo (gitignored, modo 600). Rol VIEWER y no ADMIN: todo lo que se mide
es `AUTHENTICATED`, y una suite de carga no necesita poder borrar el catálogo.

Acordarse de exportar dos variables antes de cada corrida es exactamente el tipo
de paso que hace que una suite se deje de correr.

```
./run.sh smoke | carga | stress | spike | login
./run.sh jmx                      # exporta target/jmx/*.jmx para la GUI
```

`PERF_USERNAME`/`PERF_PASSWORD` en el entorno ganan, por si querés medir con otra
cuenta. `PERF_API_BASE_URL` apunta a otro backend.

---

## Tres decisiones que vale la pena entender

**Un solo login, antes del plan.** `Config.token()` se loguea una vez con el
cliente HTTP del JDK y el token lo comparten todos los hilos. Si cada hilo se
logueara, el número que sale no sería la latencia del endpoint: sería la de
Argon2id, que en esta app está medido en ~22 ms de verify. Es el error más común de una
suite de perf, y hace que el test deje de poder ver una mejora. El costo de
loguearse se mide aparte, en `LoginIT`.

**El presupuesto es por endpoint, no global.** Un `p95 < 500 ms` para todo sería
mentira en los dos sentidos: deja a `/api/facets` subir 20x sin encender una luz,
y deja a `/api/grupos` rojo desde el día uno. `/api/grupos` re-agrupa el catálogo
entero por request y `/api/pcs/builder` corre un branch-and-bound: son caros por
diseño, no por regresión.

**Sólo lectura.** Ningún POST/PUT/DELETE salvo el login. Un test de carga que
escribe contamina el catálogo y hace que la segunda corrida ya no mida lo mismo
que la primera.

---

## Agregar un endpoint

Una línea en `Config.ENDPOINTS`:

```java
Endpoint.de("mi_endpoint", "/api/lo-que-sea?param=1", 2, 800),
//           nombre         path                      peso  techo p95 en ms
```

El **peso** es cuántas veces aparece en cada iteración (`data` tiene 5,
`status` tiene 1: el catálogo se pide mucho más que el status). El **techo**
sale de correr `SmokeIT` y mirar el p95, no de la intuición.

> Los presupuestos de `Config` están **medidos** (ver la tabla arriba). Si
> agregás un endpoint, el suyo sale de correr `SmokeIT` y `CargaIT`, no de la
> intuición — la sección de arriba cuenta cómo salió al revés la primera vez.
