# Fashion Scraper — Índice de Documentación Técnica

Índice de todo lo que se puede leer en este repo: docs, suites de test y
directorios con identidad propia. Cada entrada es independiente.

---

## Empezar acá

| Doc | Qué cubre | Cuándo leerlo |
|-----|-----------|---------------|
| [`CLAUDE.md`](./CLAUDE.md) | **Estado** del proyecto: stack, sitios, API, base de datos, problemas conocidos | Siempre — inicio de sesión |
| [`CONTRIBUTING.md`](./CONTRIBUTING.md) | **Proceso**: commits, PRs, TDD, contrato de refactor, qué doc actualizar con cada cambio. Reglas con **ID citable** (`COMMIT-2`, `CODE-3`…) para referenciarlas en un review | Antes de escribir código o abrir un PR |
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | **Por qué**: decisiones estructurales y su justificación | Antes de proponer cambios estructurales |

El reparto entre los tres es deliberado: estado / proceso / por qué. Si un dato
responde "¿qué hay hoy?" va al primero, "¿cómo se trabaja?" al segundo, "¿por
qué así?" al tercero.

## Referencia por área

| Doc | Qué cubre | Cuándo leerlo |
|-----|-----------|---------------|
| [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md) | Todos los endpoints REST con params y responses | Al modificar la API o integrar con externos |
| [`docs/ADD_SCRAPER.md`](./docs/ADD_SCRAPER.md) | Paso a paso para agregar un sitio nuevo | Al agregar soporte para una tienda nueva |
| [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md) | Pipeline ML: scoring, badges, clustering, stage 1b visual | Al modificar scoring, badges o atributos visuales |
| [`docs/LLM_EMBED.md`](./docs/LLM_EMBED.md) | El agente desde Java: costura `ChatProvider`, loop acotado, tools, y las 8 reglas que lo gobiernan | Al tocar `ar.scraper.agent`, el write path de reclasificación o la UI del chat |
| [`docs/LLM_AGENT_SETUP.md`](./docs/LLM_AGENT_SETUP.md) | Instalar Ollama y configurar las variables `LLM_*` | Al levantar el agente por primera vez o cambiar de proveedor |

## Código con identidad propia

| Dónde | Qué es | Cuándo entrar |
|-------|--------|---------------|
| [`cli/`](./cli/) | CLI nativo en Python: consola por comandos (Textual) + fallback texto plano + `core/` headless (build, `.env`, REST, procesos, logs). Arranca backend y frontend. Reemplazó a `menu.ps1`/`menu.sh` | Al modificar el launcher, el build, la generación de `.env` o el arranque de servicios |
| [`openspec/`](./openspec/) | Artefactos SDD: `changes/<nombre>/` los activos, `changes/archive/<fecha>-<nombre>/` los cerrados, `specs/` las specs vigentes | Al retomar un cambio en curso o buscar por qué se especificó algo así |
| [`scripts/dev-db.sh`](./scripts/dev-db.sh) | Launcher on-demand del Postgres de desarrollo (`up`/`down`/`status`) | Al levantar la DB local sin el instalador completo |
| [`scripts/hooks/`](./scripts/hooks/) | Hook `commit-msg` que bloquea `COMMIT-1` y `COMMIT-3`. Se activa una vez por clon: `git config core.hooksPath scripts/hooks` | Al clonar el repo, o si un commit te rebota |

## Suites de test

| Suite | Qué cubre | Cómo se corre |
|-------|-----------|---------------|
| `scraper/src/test/` | Backend Java: normalización, aggregator, DB (Postgres real), API, agente, armadores | `mvn -f scraper/pom.xml clean test` — ver `CONTRIBUTING.md` para el `JAVA_HOME` de esta máquina |
| [`ml-tests/`](./ml-tests/) | Pipeline Python: clustering, embeddings, clasificación zero-shot, cache Postgres | `pytest ml-tests` |
| [`tests/cli/`](./tests/cli/) | CLI nativo: `core/` headless, Textual vía `Pilot`, routing de degradación, injection-safety | `pytest tests/cli` |
| `frontend/src/**/*.test.jsx` | Componentes React (Vitest) | `cd frontend && npm test` |

## Instalación y despliegue

| Dónde | Qué es |
|-------|--------|
| [`INSTALAR_Y_CORRER.bat`](./INSTALAR_Y_CORRER.bat) | Windows: aprovisiona el toolchain completo en `_tools/` (JDK, Maven, Node, Python, Postgres portable, `uv` + `cli-venv`) e invoca el CLI nativo |
| [`Ejecutar_instalar.sh`](./Ejecutar_instalar.sh) | Mirror POSIX. Asume java/mvn/node/python3 del sistema; sí vendoriza `uv` + `cli-venv` |
| [`docker-compose.yml`](./docker-compose.yml) + [`docker.env.example`](./docker.env.example) | Vía **aditiva** por Docker: postgres + backend + frontend. No reemplaza el flujo portable |

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
  Woo) son **2 archivos**: `config.properties` + el name-set en
  `ScraperFactory`. Una plataforma custom suma hasta 2 más: `*Page.java` +
  `*Scraper.java`. Detalle en `docs/ADD_SCRAPER.md`.
