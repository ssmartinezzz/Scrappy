# api-performance-tests — dos suites de performance sobre la API

**Creado:** 2026-09-22 · **Rama:** pendiente
**Engram mirror:** `odd/api-performance-tests/tasks` (proyecto `scrappy`)

## Objetivo

Estructura de performance testing sobre la API, pensada tanto para medir como
para **entender qué es cada tipo de test**. Pedido textual: "una estructura
simple para poder entender performance testing", y después "hacelo tanto jmeter
y locust", "como si fuesen independientes".

## Problema

El repo mide correctitud en cinco suites (backend, frontend, cli, ml, e2e) y
**no mide tiempo en ninguna**. Las únicas mediciones de latencia que existen
están escritas a mano en `CLAUDE.md` (los 0,64 ms de `/api/outfits`, los 13,5 ms
de la trampa del `SimpleDriverDataSource`): hallazgos de una sesión, no algo
repetible. Hoy nadie puede contestar "¿esto quedó más lento?" sin volver a
instrumentar a mano.

## Scope autorizado

`tests/perf/jmeter/` y `tests/perf/locust/`, tres líneas en `.gitignore` y la
línea de estructura en `CLAUDE.md`. No se toca backend, frontend ni las suites
existentes.

Fuera de scope: CI (un job de perf en cada PR mide el ruido del runner, no la
app), persistencia de resultados, dashboards.

## Decisiones

- **D1 — dos suites, no una.** El usuario eligió JMeter y Locust. Van
  **independientes a propósito**: cada una se para sola, sin archivos
  compartidos y sin comentarios que referencien a la otra. El punto es poder
  leer las dos estructuras al lado y compararlas. El costo aceptado es que el
  catálogo de endpoints y los presupuestos están escritos dos veces; se
  intentó una fuente única (`endpoints.json`) y se descartó por pedido
  explícito.
- **D2 — JMeter se escribe en Java, no en `.jmx`.** Un `.jmx` es XML generado
  por la GUI: ilegible en un diff e imposible de revisar en un PR, que es lo
  contrario del pedido. `jmeter-java-dsl` corre sobre el motor de JMeter y
  `ExportarJmx` escribe el `.jmx` cuando hace falta la GUI. La dirección es de
  ida: el `.jmx` es salida, no fuente.
- **D3 — el módulo de JMeter es un pom independiente**, no un módulo de
  `scraper/pom.xml`. Si viviera en `scraper/src/test/java`, cada `mvn test` del
  backend dispararía una corrida de carga contra un backend que probablemente
  no esté levantado. Las clases se llaman `*IT` y las corre Failsafe en
  `mvn verify`.
- **D4 — ninguno de los dos runners levanta el backend.**
  `tests/e2e/run-e2e.sh --api --keep-up` ya hace eso; duplicar 140 líneas de
  manejo de procesos es una copia que después se separa. Los `run.sh` exigen un
  backend vivo y credenciales por entorno, y si falta algo imprimen qué.
- **D5 — el presupuesto de latencia es POR ENDPOINT.** `/api/grupos` re-agrupa
  el catálogo entero en cada request y `/api/pcs/builder` corre un
  branch-and-bound; un p95 global sería o vacío para los baratos o rojo por
  diseño para los caros.
- **D6 — sólo endpoints de lectura.** Un test de carga que escribe contamina el
  catálogo de dev y hace que la segunda corrida ya no mida lo mismo que la
  primera. La única excepción es `POST /api/auth/login`, con escenario propio
  porque el costo *es* el punto (Argon2id memory-bound: ~22 ms de verify).
- **D7 — un solo login, fuera del plan, compartido por todos los hilos.** Si
  cada hilo o cada usuario virtual se loguea, todo escenario mide Argon2id y no
  el endpoint. Es el error más común de una suite de perf y está escrito como
  tal en los dos README.
- **D8 — `StressIT` y `stress` NO llaman al veredicto.** Se espera que rompan:
  el dato que se busca es en qué escalón se da vuelta el sistema, no verde/rojo.
  Mezclarlo con `carga`, donde el rojo sí es un defecto, haría que un rojo deje
  de significar algo.
- **D9 — los presupuestos están MEDIDOS** (catálogo real de 15.987 productos,
  2026-09-22), con la regla `max(2 × p95, p95 + 25 ms)`. El factor 2 da aire a
  una regresión chica; el piso de 25 ms existe porque el doble de 3 ms es ruido
  del reloj, no un presupuesto.
- **D10 — la intuición de D5 estaba al revés, y la medición la desmintió.** Se
  les había dado 2000–2500 ms a los armadores y a `/api/grupos` por "caros por
  diseño" y 300 ms a `/api/facets` por "precalculado". Medido: `pcs_builder`
  23 ms, `grupos` 97 ms, `facets` **150 ms y tercero más lento**. Los caros son
  los que pegan a Postgres, no los algoritmos en memoria. Queda escrito en los
  dos README como el ejemplo de por qué un presupuesto no se estima.
- **D11 — el smoke de Locust recorre TODOS los endpoints, no el mix aleatorio.**
  `CatalogoUser` tira el dado con peso, que es correcto para imitar tráfico, pero
  en 30 s hizo 23 requests y dejó tres endpoints con cero muestras — y un
  endpoint sin muestras no tiene baseline. `BaselineUser` los recorre en orden.
  En JMeter no hizo falta: el plan ya emite todos los samplers por iteración.

## Tareas

- [x] T1 — `tests/perf/locust/`: `locustfile.py`, `requirements.txt`, `run.sh`, `README.md`
- [x] T2 — `tests/perf/jmeter/`: `pom.xml`, `Config`, `Veredicto`, `Smoke/Carga/Stress/Spike/LoginIT`, `ExportarJmx`, `run.sh`, `README.md`
- [x] T3 — `.gitignore`: venv, reportes y `target/`
- [x] T4 — `CLAUDE.md`: la línea de `tests/perf/` en la estructura
- [x] T5 — correr las dos suites contra un backend vivo y fijar los
      presupuestos medidos
- [x] T6 — arreglar tres definiciones de endpoint que la corrida real desmintió
      (`recomendados` page base 1; `categorias` y `tipos` obligatorios) y el
      smoke aleatorio de Locust
- [x] T7 — arreglar los defectos encontrados: el 500 de `/api/recomendados`
      (TDD: 5 tests, RED antes del fix), los `required` faltantes del OpenAPI y
      las seis copias del costo de Argon2id

## Verificación (medida)

### Corridas reales contra el catálogo (15.987 productos, 2026-09-22)

| corrida | resultado |
|---|---|
| Locust `smoke` (1 usuario, 30 s) | 591 requests, ~50 por endpoint, 0 errores, verde |
| Locust `carga` (20 usuarios, 3 min) | **2653 requests, 0 errores**, verde |
| Locust `login` (10 usuarios, 1 min) | 285 requests, p95 **43 ms**, verde |
| JMeter `SmokeIT` (1 hilo, 20 iter) | BUILD SUCCESS, verde |
| JMeter `LoginIT` (10 hilos, 1 min) | BUILD SUCCESS, verde |

**Control negativo, las dos suites.** Un presupuesto que no puede ponerse rojo
no es un presupuesto:
- Locust, `data` con techo 5 ms → exit 1, `✗ data: p95 150 ms > presupuesto 5 ms`
- JMeter, `pcs_builder` con techo 2 ms → BUILD FAILURE,
  `✗ pcs_builder: p95 18 ms > presupuesto 2 ms`

Los dos revertidos después de comprobarlo.

### T7 — los arreglos, verificados

| qué | evidencia |
|---|---|
| Suite completa del backend | **2767 tests, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS** (`clean test`, JDK 24 compila / JRE 21 corre) |
| TDD del 500 | `ApiControllerRecomendadosPaginacionTest`: **RED primero** — 2 de 5 fallaban con `IndexOutOfBoundsException: fromIndex = -24` y `-144`. Verde tras el fix |
| Contra un backend REAL (jar reconstruido) | `?page=0` y `?page=-5` → **200**, `page: 1`, 24 items · `?size=0` y `?size=-3` → **200**, `size: 1` · `page=1` y `page=2` sin cambios · los tres 400 del OpenAPI contestan 400 |
| Las dos suites de perf | Locust smoke verde · JMeter `SmokeIT` BUILD SUCCESS, contra el jar nuevo |
| Arranque del backend | Sólo dos WARN, los dos preexistentes (corrida 16 interrumpida, usuario por defecto de Spring) |

El guard bidireccional del OpenAPI y el de byte-identidad del recurso del
classpath pasan: `copy-resources` levantó el `docs/openapi.yaml` nuevo.

### Defectos encontrados (del backend, no de la suite)

- `GET /api/recomendados?page=0` → **HTTP 500**.
  `RecomendadosEndpoints.java:131` hace `Math.min((page - 1) * size, total)`;
  con `page=0` da `-24` y `subList(-24, …)` tira `IndexOutOfBoundsException`.
  Acota arriba y no abajo. El contrato dice base 1, pero `/api/data` recibe el
  mismo `page=0` y lo clampea: dos endpoints con el mismo parámetro contestan
  distinto a la misma entrada inválida. **Arreglado** (T7): se acota
  `Math.max(1, page)` y también `Math.max(1, size)`, que tenía la imagen espejo
  del mismo defecto (`hasta = desde + size` cae por debajo de `desde`).
- `/api/outfits/builder` exige `categorias` y `/api/suplementos/builder` exige
  `tipos`; sin ellos, 400. `docs/openapi.yaml` no los marcaba `required`.
  **Arreglado** (T7). De paso apareció un tercero: `presupuesto` de
  `/api/outfits/builder` estaba documentado con `default: 0` y el endpoint
  rechaza todo `presupuesto <= 0` — o sea que cualquier llamada que confiara en
  el default tenía un 400 garantizado. Ahora es `required` con
  `exclusiveMinimum: 0`, y las dos rutas documentan su `400`.
- El login medido (43 ms p95) desmintió los **76 ms de verify** que documentaba
  `CLAUDE.md`: un request entero no puede costar la mitad del verify que
  contiene. Re-medido con el mismo método y la misma máquina (20 iteraciones
  tras warmup): **22,0 ms hash / 21,3 ms verify de mediana**. **Corregido** en
  las seis copias del número, incluidos los dos razonamientos que dependían de
  él: el oráculo de timing de `AuthEndpoints` y los "intentos por segundo" de
  `LoginRateLimiter`, que pasaron de 13/s a 45/s por core — la conclusión del
  rate limiter no cambia, se refuerza.

### Entorno usado

Usuario `perf-user` (rol VIEWER) sembrado a mano en la dev DB con un hash
Argon2id generado con los mismos parámetros que `PasswordHasher`. El
`e2e-admin` de `tests/e2e/.e2e-secrets.env` no existe en este volumen de
Postgres, así que la vía documentada en los README no funcionó tal cual.

### Verificación de construcción

- `mvn -B test-compile` en `tests/perf/jmeter/` → **BUILD SUCCESS**. Compila de
  verdad contra `jmeter-java-dsl 2.2.1`, así que la API que usa el código
  (`byLabel`, `sampleTime().perc95()`, `rampToAndHold`, `uniformRandomTimer`,
  `saveAsJmx`) está verificada, no supuesta.
  - Hallazgo: sin un parent que maneje versiones de plugins, Maven usa
    `maven-compiler-plugin` 3.1, que apunta a source/target **5** y no compila.
    Hay que pinearlo explícitamente.
- Locust 2.32.4 instalado en su venv; `locustfile.py` importa y expone los 12
  endpoints y las dos clases de usuario.
- `bash -n` sobre los dos `run.sh`.
- Los dos `run.sh` corrieron end-to-end contra un backend vivo.
