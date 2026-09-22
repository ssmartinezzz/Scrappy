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

Hace falta un backend vivo y un usuario:

```bash
scripts/dev-db.sh up
tests/e2e/run-e2e.sh --api --keep-up          # levanta el backend y lo deja arriba

# Las credenciales: cualquier cuenta sirve. Si ya corriste la suite e2e,
# el archivo que generó tiene una.
set -a; . tests/e2e/.e2e-secrets.env; set +a
export PERF_USERNAME="$ADMIN_BOOTSTRAP_USERNAME"
export PERF_PASSWORD="$ADMIN_BOOTSTRAP_PASSWORD"

tests/perf/jmeter/run.sh smoke
```

```
./run.sh smoke | carga | stress | spike | login
./run.sh jmx                      # exporta target/jmx/*.jmx para la GUI
```

`PERF_API_BASE_URL` apunta a otro backend si hace falta.

**`mvn test` acá no corre nada.** Las clases se llaman `*IT` y las ejecuta
Failsafe en `mvn verify`, que es la convención de Maven para "esto necesita algo
vivo del otro lado". Y este módulo no es parte del build del backend: si viviera
en `scraper/src/test/java`, cada commit dispararía una corrida de carga contra
un backend que probablemente no esté levantado.

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
