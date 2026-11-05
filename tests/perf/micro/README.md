# Micro-benchmarks JMH del scraper

Mide, sin backend ni base de datos, el costo de las clases que corren sobre el
catálogo entero. Cada benchmark procesa un payload capturado de la API pública
de VTEX de easy.com.ar: **1.987 productos** de las 17 categorías de primer nivel
(120 por categoría, salvo Electrodomésticos con 70 por un 500 del sitio en la
captura). Un "op" es **el lote completo de 1.987**, no un producto.

```
micro/
├── pom.xml                  módulo Maven independiente (sin parent), release 21
├── run.sh                   instala el scraper, compila el módulo y corre JMH
└── src/main/
    ├── resources/easy-payload.json.gz   el payload (2,3 MB, sólo lecturas GET)
    └── java/ar/scraper/perf/micro/
        ├── EasyCatalog.java             carga el payload y arma las clases reales
        └── ScraperMicroBenchmarks.java  los benchmarks
```

## Cómo correrlo

```bash
tests/perf/micro/run.sh all                         # scraper + build + corrida completa (~35 min)
tests/perf/micro/run.sh run -f 1 -wi 1 -w 1 -i 2 -r 1   # humo de 1 minuto
tests/perf/micro/run.sh run ScraperMicroBenchmarks.groupingServiceAgrupar
```

`all` hace tres cosas, que también se pueden pedir por separado (`scraper`,
`build`, `run`). Todo corre bajo `systemd-run --scope -p MemoryMax=6G` y `timeout`.

**Por qué no depende del jar normal del scraper.** `scraper/target/fashion-scraper-1.0.1.jar`
es el fat jar de Spring Boot: las clases viven bajo `BOOT-INF/classes` y nadie
las puede importar. `spring-boot-maven-plugin` deja además el jar plano como
`fashion-scraper-1.0.1.jar.original`; `run.sh scraper` lo instala en el `~/.m2`
con classifier `plain` y el `pom.xml` del scraper, y el módulo depende de
`ar.scraper:fashion-scraper:1.0.1:plain`, que trae las dependencias transitivas
correctas. El fat jar no se toca. Tras cambiar código del scraper hay que
repetir `run.sh scraper` (el módulo mide lo que está instalado, no el árbol).

JMH corre con el classpath de `dependency:build-classpath`, sin shade: menos
piezas que se rompen con los jars firmados.

## Qué mide cada benchmark

| Benchmark | Qué llama | Notas |
|---|---|---|
| `vtexPageToProduct` | `VtexPage.fromVtex` (→ `toProduct`) por reflexión | es `private`; la página se construye con `Page = null`, que el mapeo no toca |
| `categoryClassifierClasificar` | `CategoryClassifier.clasificar(String)` por `MethodHandle` | es `private`; sólo el nombre |
| `categoryClassifierNormalizarCategoria` | `CategoryClassifier.normalizarCategoria(raw, nombre)` | la entrada pública; hace dos `clasificar` en el peor caso |
| `rubroResolverResolver` | `RubroResolver.resolver` | sitio `easy` sin fila en el registro |
| `normalizerServiceNormalizar` | `NormalizerService.normalizar(lista)` | el camino completo por producto |
| `groupingServiceAgrupar` | `GroupingService.agrupar(lista, false)` | sobre los productos ya normalizados |
| `accentStripperStrip` | `AccentStripper.strip(nombre)` | |

## Método

JMH 1.37, modo `SampleTime` (da percentiles). Por benchmark: 5 forks, 5 warmup de
2 s y 10 mediciones de 5 s, heap fijo `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` en cada
fork, JRE 21. El benchmark más lento (`normalizerServiceNormalizar`) junta 1.378
muestras; los demás, entre 4 mil y 4,3 millones. Los resultados quedan en
`target/jmh-result.json` y `target/jmh-result.txt` (gitignored).

Para comparar dos commits (A/B), correr el mismo comando sobre cada uno
intercalando (`run.sh all` en A, luego en B, luego otra vez A) y comparar
`score ± error` y p99; una diferencia que no supera el error es ruido.

## Baseline

Master `7376582d`, 1.987 productos de Easy, ms por lote de 1.987 (el costo por
producto sale de dividir).

| Benchmark | muestras | media ± error | p50 | p99 | µs / producto (media) |
|---|---:|---:|---:|---:|---:|
| `vtexPageToProduct` | 19.917 | 12,554 ± 0,056 | 11,239 | 18,022 | 6,3 |
| `categoryClassifierClasificar` | 6.078 | 41,290 ± 0,199 | 39,846 | 52,377 | 20,8 |
| `categoryClassifierNormalizarCategoria` | 3.937 | 63,844 ± 0,334 | 64,094 | 80,428 | 32,1 |
| `rubroResolverResolver` | 4.351.398 | 0,057 ± 0,001 | 0,048 | 0,101 | 0,03 |
| `normalizerServiceNormalizar` | 1.378 | 183,544 ± 0,785 | 182,190 | 208,142 | 92,4 |
| `groupingServiceAgrupar` | 20.096 | 12,446 ± 0,061 | 11,125 | 19,137 | 6,3 |
| `accentStripperStrip` | 1.258.992 | 0,198 ± 0,001 | 0,181 | 0,273 | 0,10 |

`NormalizerService` es el costo dominante, y casi todo es el clasificador de
categorías (`normalizarCategoria` ~64 de 184 ms). `RubroResolver` y
`AccentStripper` no son un problema por sí solos.

## Baseline de carga (mismo master, base de dev con 38.872 productos)

Backend arrancado desde el jar de master, 2026-11-03, con `tests/perf/jmeter/run.sh smoke|carga`
y `uv run pytest` en `tests/perf/locust`. Latencias en ms, cero errores en todo.

| endpoint | JMeter smoke p50 / p95 | JMeter carga (20 usuarios) p50 / p95 / p99 | Locust baseline p95 |
|---|---:|---:|---:|
| `/api/data` | 22 / 42 | 21 / 36 / 42 | 37 |
| `/api/facets` | 3 / 11 | 2 / 8 / 11 | 10 |
| `/api/grupos` | 3 / 15 | 2 / 7 / 9 | 5 |
| `/api/recomendados` | 55 / 68 | 52 / 65 / 71 | 65 |
| `/api/mejores` | 5 / 73 | 3 / 8 / 11 | 8 |

El smoke tiene 20-100 muestras por endpoint: su p95 y p99 son casi el máximo y
la primera request de cada endpoint paga el calentamiento. Para comparar, usar
la columna de carga. El login de Locust dio p95 47 ms (techo 90).
