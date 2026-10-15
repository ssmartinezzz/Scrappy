# Fashion Scraper — Índice de Documentación Técnica

Índice de todo lo que se puede leer en este repo: docs, suites de test y
directorios con identidad propia. Cada entrada es independiente.

---

## Empezar acá

| Doc | Qué cubre | Cuándo leerlo |
|-----|-----------|---------------|
| [`CLAUDE.md`](./CLAUDE.md) | **Guía/índice**: qué hay y dónde leerlo. No se sobrescribe sin pedido del usuario | Siempre — inicio de sesión |
| [`CONTRIBUTING.md`](./CONTRIBUTING.md) | **Proceso**: commits, PRs, TDD, contrato de refactor, qué doc actualizar con cada cambio. Reglas con **ID citable** (`COMMIT-2`, `CODE-3`…) para referenciarlas en un review | Antes de escribir código o abrir un PR |
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | **Por qué**: decisiones estructurales y su justificación. Para la base es sólo un índice hacia `DATABASE.md` | Antes de proponer cambios estructurales |
| [`docs/DATABASE.md`](./docs/DATABASE.md) | **La base, entera**: esquema, las migraciones `V1`..`V24` + las `R__`, semántica del upsert, estado de normalización y el SQL de rollback que ejecutan los tests. Manda la regla de admisión: **toda tabla nueva cumple 1FN y 3FN** | Antes de tocar el esquema, escribir una migración o agregar una tabla |

El reparto entre los cuatro es deliberado: estado / proceso / por qué / base.
Si un dato responde "¿qué hay hoy?" va al primero, "¿cómo se trabaja?" al
segundo, "¿por qué así?" al tercero, y **cualquier cosa sobre la base va al
cuarto** — ni `CLAUDE.md` ni `ARCHITECTURE.md` guardan una segunda copia.

## Referencia por área

| Doc | Qué cubre | Cuándo leerlo |
|-----|-----------|---------------|
| [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md) | Todos los endpoints REST con params y responses | Al modificar la API o integrar con externos |
| [`docs/FRONTEND_AUTH_CONTRACT.md`](./docs/FRONTEND_AUTH_CONTRACT.md) | Qué consume el frontend para autenticar, y las trampas ya conocidas. Punto de entrada del SDD de frontend |
| [`docs/ADD_SCRAPER.md`](./docs/ADD_SCRAPER.md) | Paso a paso para agregar un sitio nuevo | Al agregar soporte para una tienda nueva |
| [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md) | Pipeline ML: scoring, badges, clustering, stage 1b visual | Al modificar scoring, badges o atributos visuales |
| [`docs/LLM_EMBED.md`](./docs/LLM_EMBED.md) | El agente desde Java: costura `ChatProvider`, loop acotado, tools, y las 8 reglas que lo gobiernan | Al tocar `ar.scraper.agent`, el write path de reclasificación o la UI del chat |
| [`docs/LLM_AGENT_SETUP.md`](./docs/LLM_AGENT_SETUP.md) | Instalar Ollama y configurar las variables `LLM_*` | Al levantar el agente por primera vez o cambiar de proveedor |
| [`docs/LAN_HTTPS_SETUP.md`](./docs/LAN_HTTPS_SETUP.md) | Servir el dashboard a un celular u otro dispositivo de la red, por HTTPS: certificado, proxy que termina TLS, los **dos** `.env` de orígenes | Al probar desde otro dispositivo, o antes de armar el TLS de un deploy real |
| [`docs/EMAIL_ALERTS.md`](./docs/EMAIL_ALERTS.md) | Puesta en marcha de los avisos de baja de precio por mail: proveedores y contraseñas de aplicación, qué hace cada `NOTIFICACIONES_*`, cómo verificar con el mail de prueba y qué hacer según la línea de log | Al activar los avisos, al cambiar de proveedor SMTP o cuando un aviso no llega |
| [`docs/STRUCTURE.md`](./docs/STRUCTURE.md) | Árbol comentado de archivos y paquetes | Al ubicar dónde vive algo |
| [`docs/SITES.md`](./docs/SITES.md) | Sitios configurados, plataformas, rubros, detección de plataforma | Al tocar un sitio o scraper |
| [`docs/GROUPING.md`](./docs/GROUPING.md) | Agrupado de `/api/grupos`: criterio de mismo producto, guardas, medición | Al tocar `ar.scraper.aggregator.grouping` |
| [`docs/OUTFITS.md`](./docs/OUTFITS.md) | Armadores de outfits: pesos, MCKP, coherencia visual, vetos | Al tocar `OutfitService`, `OutfitBudgetBuilder` o `OutfitRules` |
| [`docs/SUPPLEMENTS.md`](./docs/SUPPLEMENTS.md) | Combo de suplementos: subtipos, marca preferida, rotación de "Regenerar" | Al tocar `SupplementCombo` |
| [`docs/PC_BUILDER.md`](./docs/PC_BUILDER.md) | Armador de PCs, fases 1–12 | Al tocar `ar.scraper.pcs` |
| [`docs/FRONTEND.md`](./docs/FRONTEND.md) | Rutas, nav, `/favoritos`, `/apidocs` | Al tocar rutas o vistas |
| [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) | Trampas conocidas, por síntoma | Cuando algo no anda y no sabés por qué |
| [`docs/KNOWN_ISSUES.md`](./docs/KNOWN_ISSUES.md) | Bugs abiertos, pendientes, banda de precios | Antes de abrir un bug o cambiar la banda |

## Código con identidad propia

| Dónde | Qué es | Cuándo entrar |
|-------|--------|---------------|
| [`cli/`](./cli/) | CLI nativo en Python: consola por comandos (Textual) + fallback texto plano + `core/` headless (build, `.env`, REST, procesos, logs). Arranca backend y frontend. Reemplazó a `menu.ps1`/`menu.sh` | Al modificar el launcher, el build, la generación de `.env` o el arranque de servicios |
| [`scripts/dev-db.sh`](./scripts/dev-db.sh) | Launcher on-demand del Postgres de desarrollo (`up`/`down`/`status`) | Al levantar la DB local sin el instalador completo |
| [`scripts/benchmarks/blender_cpu.py`](./scripts/benchmarks/blender_cpu.py) | Genera `pcs/benchmarks/cpu.csv` desde Blender Open Data (stdlib, `python3 -I`). Ver [`docs/PC_BUILDER.md`](./docs/PC_BUILDER.md) → Fase 11 | Al refrescar los benchmarks de CPU del armador |
| [`scripts/benchmarks/gaming_cpu.py`](./scripts/benchmarks/gaming_cpu.py) | Genera `pcs/benchmarks/cpu_gaming.csv` desde la jerarquía de CPUs de Tom's Hardware: encadena tablas viejas y aplica gemelos/cocientes medidos (stdlib, `python3 -I`). Ver [`docs/PC_BUILDER.md`](./docs/PC_BUILDER.md) → Fase 12 | Al refrescar el índice gaming de CPU del armador |
| [`scripts/hooks/`](./scripts/hooks/) | Hook `commit-msg` que bloquea `COMMIT-1` y `COMMIT-3`. Se activa una vez por clon: `git config core.hooksPath scripts/hooks` | Al clonar el repo, o si un commit te rebota |

## Suites de test

| Suite | Qué cubre | Cómo se corre |
|-------|-----------|---------------|
| `scraper/src/test/` | Backend Java: normalización, aggregator, DB (Postgres real), API, agente, armadores | `mvn -f scraper/pom.xml clean test` — ver `CONTRIBUTING.md` para el `JAVA_HOME` de esta máquina |
| [`ml-tests/`](./ml-tests/) | Pipeline Python: clustering, embeddings, clasificación zero-shot, cache Postgres | `pytest ml-tests` |
| [`tests/cli/`](./tests/cli/) | CLI nativo: `core/` headless, Textual vía `Pilot`, routing de degradación, injection-safety | `pytest tests/cli` |
| [`tests/scripts/`](./tests/scripts/) | Utilidades de `scripts/` (hoy, `blender_cpu.py` contra el fixture compartido `claves.csv`, y `gaming_cpu.py` contra páginas HTML recortadas). No corre en ningún workflow de CI | `uv run --with pytest pytest tests/scripts` |
| [`tests/observability/`](./tests/observability/) | Provisioning de Grafana: cada query de panel y alerta corre como `grafana_ro` contra el esquema real; `grafana_ro` no lee datos personales ni escribe. Sin `OBS_TEST_DSN` corre sólo la mitad estática. No corre en CI | `OBS_TEST_DSN=postgresql://postgres:postgres@localhost:5432/scraper uv run --no-project --with pytest --with psycopg2-binary --with pyyaml pytest tests/observability` |
| [`tests/perf/outbox/`](./tests/perf/outbox/README.md) | Performance del outbox de avisos de baja: costo de `sp_upsert_run` (ABBA) y ráfaga del relay contra Mailpit (p50/p95/p99). Base descartable; no corre en CI | `tests/perf/outbox/abba.sh A.sql B.sql` · `tests/perf/outbox/rafaga-abba.sh a.jar b.jar` |
| `frontend/src/**/*.test.jsx` | Componentes React (Vitest) | `cd frontend && npm test` |

## Instalación y despliegue

| Dónde | Qué es |
|-------|--------|
| [`INSTALAR_Y_CORRER.bat`](./INSTALAR_Y_CORRER.bat) | Windows: aprovisiona el toolchain completo en `_tools/` (JDK, Maven, Node, Python, Postgres portable, `uv` + `cli-venv`) e invoca el CLI nativo |
| [`Ejecutar_instalar.sh`](./Ejecutar_instalar.sh) | Mirror POSIX. Asume java/mvn/node/python3 del sistema; sí vendoriza `uv` + `cli-venv` |
| [`docker-compose.yml`](./docker-compose.yml) + [`docker.env.example`](./docker.env.example) | Vía **aditiva** por Docker: postgres + backend + frontend. No reemplaza el flujo portable |
| [`scripts/observability.sh`](./scripts/observability.sh) + [`docker-compose.observability.yml`](./docker-compose.observability.yml) | **Opcional**: Grafana en `127.0.0.1:3001` leyendo Postgres con un rol de sólo lectura. Una vez activado (primer `up`), `Ejecutar_instalar.sh` lo levanta solo, el verbo `grafana` del CLI muestra URL y credenciales, y salir del CLI lo apaga (`SCRAPPY_GRAFANA=0` lo saltea; no hay paso equivalente en el `.bat`). Dashboards y alertas en [`observability/`](./observability/). Trampas: [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) → Docker |

---

## Convenciones

Viven en [`CONTRIBUTING.md`](./CONTRIBUTING.md), con ID citable cada una —
commits, tamaño de PR, TDD, contrato de refactor, "medir no estimar",
abstención, y la tabla de qué doc actualizar con cada tipo de cambio.

**Al clonar**: `git config core.hooksPath scripts/hooks`.

Dos que conviene tener presentes al leer código de este repo:

- **Escaping en strings Java con JS embebido**: regex `\d`/`\s` van como
  `\\d`/`\\s`; comillas simples en el JS; `\?`, `\$`, `\,`, `\.` son escapes
  ilegales en Java.
- **Agregar un sitio de una plataforma ya soportada** (Shopify/TN/VTEX/Vaypol/
  Woo) **no es una edición de código**: desde `V20` la plataforma sale de la
  columna `sitio.plataforma` vía `SiteRegistry`, así que son `config.properties`
  + una fila de seed. Los `Set.of(...)` de `ScraperFactory` fueron **borrados**
  (`CODE-6`). Una plataforma nueva sí suma `*Page.java` + `*Scraper.java` + un
  `if`. Detalle en `docs/ADD_SCRAPER.md`.
