# Fashion Scraper Argentina — Guía

> ⛔ **NO SOBRESCRIBIR ESTE ARCHIVO.** Ningún agente, skill ni flujo automático
> (SDD, ODD, `/init`, archive, review) puede reescribir, regenerar ni agregarle
> contenido a `CLAUDE.md` **salvo que el usuario lo pida explícitamente**.
> El conocimiento nuevo va al doc temático que corresponde (tabla de abajo); si
> ninguno encaja, se propone uno nuevo y se pregunta antes de crearlo.

> **Este archivo es SÓLO un índice**: qué hay y dónde leerlo. No es changelog,
> ni estado detallado, ni justificación. Tope: **250 líneas**.
>
> Reparto de los documentos raíz:
> **`CLAUDE.md` = guía · [`CONTRIBUTING.md`](./CONTRIBUTING.md) = proceso ·
> [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) = por qué ·
> [`docs/DATABASE.md`](./docs/DATABASE.md) = la base, entera ·
> [`SKILL.md`](./SKILL.md) = índice completo de documentación.**

---

## Qué es

Scraper headless de tiendas online argentinas (indumentaria, gym, suplementos,
hardware/PC y oficina) con dashboard web: filtros, comparador multi-sitio, feed
personalizado, armadores (outfits, suplementos, PCs), cuotas/inflación y
tendencias ML con clasificación de imagen zero-shot.

**Tres vías de instalación**, todas soportadas:

1. **Windows portable** — `INSTALAR_Y_CORRER.bat` vendoriza el toolchain en `_tools/` e invoca el CLI nativo.
2. **POSIX** — `Ejecutar_instalar.sh` (java/mvn/node/python3 del sistema; vendoriza `uv` + `cli-venv`).
3. **Docker** — `docker compose up`: postgres + backend + frontend.

---

## Stack

| Capa | Tecnología |
|------|-----------|
| Backend/Scraper | Java 21 + Spring Boot 3.2 + Playwright 1.44 — **API-only**, `localhost:3000` |
| Frontend | React 18 + Vite 8 (`frontend/`), habla al backend por CORS (`VITE_API_BASE_URL` / `window.__API_BASE__`) |
| Base de datos | PostgreSQL + Flyway (`V1`..`V40` + dos `R__`), HikariCP |
| ML | Python 3.11 embeddable como subprocess: estadístico + TF-IDF + zero-shot (Marqo-FashionSigLIP) |
| CLI nativo | `cli/` — Python sobre `_tools/cli-venv` (Textual + fallback texto plano) |
| Config | Env-only. `.env` generado por `cli/core/env_file.py` desde `.env.example` |

---

## Índice de documentación

### Empezar acá

| Doc | Leelo cuando… |
|-----|---------------|
| [`CONTRIBUTING.md`](./CONTRIBUTING.md) | Antes de escribir código o commitear. Reglas con ID citable (`COMMIT-1`, `CODE-3`, `TEST-1`, `DOC-1`…) |
| [`SKILL.md`](./SKILL.md) | Buscás un doc, una suite de test o un directorio que no está acá |
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | Vas a proponer un cambio estructural. Incluye el modelo `Product` y el flujo de un run |
| [`docs/STRUCTURE.md`](./docs/STRUCTURE.md) | Necesitás el árbol comentado de paquetes (`catalog/`, `pcs/`, `scrape/`, `db/`, `web/`…) |

### Por área

| Si vas a tocar… | Leé |
|---|---|
| El esquema, una migración, el upsert, el rollback | [`docs/DATABASE.md`](./docs/DATABASE.md) |
| Un sitio, una plataforma, una URL de catálogo | [`docs/SITES.md`](./docs/SITES.md) + [`docs/ADD_SCRAPER.md`](./docs/ADD_SCRAPER.md) |
| Endpoints REST | [`docs/openapi.yaml`](./docs/openapi.yaml) (contrato) + [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md) (semántica) |
| Auth del browser, cookies, sesión | [`docs/FRONTEND_AUTH_CONTRACT.md`](./docs/FRONTEND_AUTH_CONTRACT.md) |
| Scoring, badges, clustering, stage 1b, taxonomía de `categoria` | [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md) |
| Armador de outfits o combo de suplementos | [`docs/OUTFITS.md`](./docs/OUTFITS.md) |
| Armador de PCs (`ar.scraper.pcs`, fases 1–10) | [`docs/PC_BUILDER.md`](./docs/PC_BUILDER.md) + `odd/tasks/pc-builder-*.md` |
| El agente LLM (`ar.scraper.agent`) | [`docs/LLM_EMBED.md`](./docs/LLM_EMBED.md) · setup: [`docs/LLM_AGENT_SETUP.md`](./docs/LLM_AGENT_SETUP.md) |
| Rutas, nav, `/favoritos`, `/apidocs` | [`docs/FRONTEND.md`](./docs/FRONTEND.md) |
| Servir a otro dispositivo por HTTPS (`start lan`) | [`docs/LAN_HTTPS_SETUP.md`](./docs/LAN_HTTPS_SETUP.md) |
| Algo que "no anda" y no sabés por qué | [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) |
| Bugs abiertos, pendientes, banda de precios | [`docs/KNOWN_ISSUES.md`](./docs/KNOWN_ISSUES.md) |

### Gotchas por síntoma → [`docs/GOTCHAS.md`](./docs/GOTCHAS.md)

| Si estás tocando… | Sección |
|---|---|
| auth, CORS, cookies, sesión, status de una corrida | Frontend ↔ backend |
| retomar/descartar una corrida, scrape parcial, cronjobs | Corridas parciales y retomas |
| toolchain, jar, venv, base de dev, arrancar servicios | Entorno, procesos y config |
| un scraper, una page, URL de catálogo o de imagen | Leer un sitio |
| keywords, categorías, guard no-textil, normalización | Taxonomía y clasificación |
| IPC, dólar, deflactor, señal de compra | Índices y señales |
| un picker, una tarjeta que scrollea, chips animados | Frontend: layout |
| `docker-compose.yml`, Dockerfile, orígenes | Docker |

### Trabajo en curso y artefactos

| Dónde | Qué |
|---|---|
| [`odd/tasks/`](./odd/tasks/) | Documentos de feature ODD: objetivo, tareas, evidencia medida |
| [`openspec/`](./openspec/) | SDD: `changes/` activos, `changes/archive/` cerrados, `specs/` vigentes |
| [`ml-tests/eval/`](./ml-tests/eval/README.md) | Set de evaluación del clasificador sobre el catálogo real |

---

## Skills

Antes de responder, chequeá si alguna skill disponible aplica y cargala.

| Situación | Skill |
|---|---|
| Commits como unidades revisables | `work-unit-commits` |
| Abrir un PR | `branch-pr` |
| PR > 400 líneas o PRs apilados | `chained-pr` |
| Escribir o reestructurar docs | `cognitive-doc-design` |
| Comentarios de PR / issues | `comment-writer` |
| Crear o triagear issues | `issue-creation`, `systemic-issue-triage` |
| Review adversarial doble | `judgment-day` |
| Review del diff actual | `code-review`, `simplify` |
| Docs de librerías (Spring, React, Playwright…) | `context7-mcp` |
| Levantar la app y verla andar | `run` |
| SDD explícito | `gentle-sdd-*` (sólo si el usuario lo pide) |

---

## Comandos esenciales

| Qué | Cómo | Detalle |
|---|---|---|
| Tests backend | JDK 24 compila, JRE 21 corre; **siempre con `clean`** | [`CONTRIBUTING.md`](./CONTRIBUTING.md) |
| Postgres de dev | `scripts/dev-db.sh up\|down\|status` | [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) → Entorno |
| CLI | `python -m cli` con cwd = raíz (nunca `cli/__main__.py`) | [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) → Entorno |
| E2E (API + browser) | `tests/e2e/run-e2e.sh` — nunca contra `vite dev` | [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) → Frontend ↔ backend |
| Perf | `tests/perf/locust`: `uv run pytest` · `tests/perf/jmeter`: `mvn verify` | [`SKILL.md`](./SKILL.md) |
| Hooks de commit | `git config core.hooksPath scripts/hooks` | [`CONTRIBUTING.md`](./CONTRIBUTING.md) |
| Jar stale tras recompilar | copiar `scraper/target/fashion-scraper-1.0.0.jar` → `scraper/scraper.jar` | [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) → Entorno |

---

## Reglas que no se rompen

Una línea cada una; el porqué está en el doc enlazado.

### Base de datos → [`docs/DATABASE.md`](./docs/DATABASE.md)

| Regla | |
|---|---|
| Toda tabla nueva cumple 1FN y 3FN | Precondición, no aspiración |
| Una migración aplicada es byte-frozen | Ni un comentario: rompe `flyway validate`. El rollback se documenta |
| `sp_upsert_run` y `sp_soft_delete_ausentes` se editan en su `R__` | Nunca una versionada nueva |
| El soft-delete se acota a los sitios que la **corrida** miró | Ventana de tiempo ∩ `scrape_run_site`, nunca sólo el reloj |
| El upsert se traga los errores SQL | Síntoma: `"0 nuevos"`. Todo test afirma `nuevos()` primero |
| Un centinela de abstención no es un valor de FK | `marca=''`, `DESCONOCIDA`, `NINGUNA` → NULL en la base |
| `favoritos`: todo `ON CONFLICT (url)` repite el `WHERE` del índice parcial | Si no, Postgres rechaza la sentencia |
| `PostgresTestBase.truncateAll` es una lista a mano | Toda tabla nueva se agrega ahí |

### Clasificación y taxonomía → [`docs/GOTCHAS.md`](./docs/GOTCHAS.md) · [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md)

| Regla | |
|---|---|
| Una categoría nueva son **dos** cambios | Keyword + migración. Sin migración: `"0 nuevos"` |
| En los keywords, el espacio es el word boundary | `" ram "`, no `"ram "` |
| El sustantivo líder gana sobre el orden de keywords | Ver `*_LIDER` en `CategoryClassifier` |
| `NonTextileGuard` veta en silencio | Devuelve `""`, igual que "no matcheó" |
| Un arreglo de clasificación no se ve hasta el próximo scrape | Correr el clasificador de hoy sobre la base antes de diagnosticar |
| Una marca preferida sólo gana si está en `BrandExtractor.MARCAS` | Las dos listas viajan juntas |

### Armadores → [`docs/OUTFITS.md`](./docs/OUTFITS.md) · [`docs/PC_BUILDER.md`](./docs/PC_BUILDER.md)

| Regla | |
|---|---|
| `baseMlScore` es un percentil de **precio** | Nunca como objetivo de calidad de un armador |
| La abstención nunca dispara una regla… | …salvo cuando el usuario **pidió** esa preferencia (gama, DDR, marca…) |
| Todo término nuevo en `aporte` del MCKP debe ser ≤ 1.0 | Si no, el branch-and-bound poda el óptimo en silencio |
| Medí sobre filas activas | `activo IS NOT FALSE`; el catálogo entero describe otra cosa |

### Procesos y entorno → [`docs/GOTCHAS.md`](./docs/GOTCHAS.md)

| Regla | |
|---|---|
| El scoring ML no es reentrante | Un solo slot (`conReservaDeScoring`); `/api/ml/aplicar` da 409 |
| Una corrida parcial no reemplaza el catálogo en memoria | `ScraperService.catalogoEntero` recarga los activos |
| `DATABASE_URL`: `jdbc:` para Java, sin prefijo para psycopg2 | `PythonRunner.toPsycopgDsn` traduce |
| Dev (`vite dev`) es same-origin; las instalaciones reales no | Auth/CORS/cookies se verifican con `run-e2e.sh` |
| `VITE_API_BASE_URL` es build-time | Cambiarla exige rebuild |
| Todo cambio de auth se verifica contra un proceso real | La suite no ve esa clase de bug |
| `ml_*.py` junto al jar son artefactos runtime | Fuente de verdad: `scraper/src/main/resources/ml/` |

---

## Dónde va cada cosa nueva

| Aprendiste… | Va a |
|---|---|
| Una trampa que costó una sesión | `docs/GOTCHAS.md`, en la sección por síntoma |
| Un bug abierto o un pendiente | `docs/KNOWN_ISSUES.md` |
| Una decisión y su porqué | `docs/ARCHITECTURE.md` |
| Cualquier cosa de la base | `docs/DATABASE.md` |
| Un sitio nuevo o un cambio de plataforma | `docs/SITES.md` + `docs/ADD_SCRAPER.md` |
| Una fase del armador de PCs | `docs/PC_BUILDER.md` + `odd/tasks/<feature>.md` |
| Un cambio de proceso | `CONTRIBUTING.md` |
| Un doc nuevo | Agregarlo a `SKILL.md` — **no** a este archivo sin pedido del usuario |

---

## Cómo continuar en una sesión nueva

1. Leé este índice.
2. Leé [`CONTRIBUTING.md`](./CONTRIBUTING.md) antes de escribir código o commitear.
3. Abrí sólo el doc temático de lo que vas a tocar (tabla "Por área").
4. Si es un clon nuevo: `git config core.hooksPath scripts/hooks`.
5. Si hay problemas, pedí `scraper/logs/scraper.log` y `scraper/logs/error.log`.
