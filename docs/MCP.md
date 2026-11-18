# MCP server del catálogo

> Un server MCP (`catalog-mcp/`) que le da a Claude Code —o a cualquier host
> MCP— acceso al catálogo a través de la API REST. Es otra puerta a lo mismo que
> usa el agente local (`docs/LLM_EMBED.md`), no un reemplazo.

<a id="para-llms"></a>
## Para LLMs: instalar Scrappy

Si sos un modelo y te piden instalar, levantar o arreglar Scrappy en esta
máquina, no leas los instaladores: usá el MCP `scrappy` (lo registra
`.mcp.json` en la raíz; no necesita credenciales ni backend).

1. Llamá a `quickstart`. Devuelve los pasos, cuáles están hechos y `siguiente`.
2. Hacé lo que diga `siguiente` y volvé a llamar a `quickstart`, hasta que diga
   "Listo". El orden es `instalar` → `estado_instalacion` → `configurar_admin`
   → `levantar_servicios` → `diagnosticar`.
3. Si algo falla, `diagnosticar` trae `a_resolver` (qué hacer en cada caso) y
   `logs_locales` muestra el log.

Reglas:

- No le pidas la password del admin al usuario ni la inventes: llamá a
  `configurar_admin` sin `password` y decile que la lee en `.env`
  (`ADMIN_BOOTSTRAP_PASSWORD`). El MCP nunca la devuelve.
- Un 401 del admin con la password de `.env` no se arregla tocando `.env`: la
  cuenta ya existía (ver la nota del seeder en `docs/GOTCHAS.md`).
- Si el MCP no está conectado, el camino manual es el instalador del SO:
  `./Ejecutar_instalar.sh` (Linux/macOS) o `INSTALAR_Y_CORRER.bat` (Windows),
  o `docker compose up`.

El detalle de cada paso está en [Instalar desde cero con el MCP](#instalar-desde-cero-con-el-mcp).

## Cómo está armado

```
Claude Code ──stdio──► catalog-mcp (Python, mcp v2) ──HTTP + Bearer──► backend :3000
```

- **Habla sólo con la API**, nunca con Postgres. Auth, roles y validaciones del
  backend aplican igual que para el dashboard.
- **Nada borra datos.** `ApiClient` (`scrappy_mcp/client.py`) es el único punto
  de salida y rechaza localmente, sin mandar nada: todo `DELETE` (incluye
  desactivar usuarios, borrar favoritos, crons, sitios, vaciar catálogo o ML) y
  `POST /api/scrape/discard`. También rechaza paths fuera de `/api/` y con `..`.
- **Auth:** login con usuario y contraseña → access token de 15 min. Ante un 401
  re-loguea una vez. No usa refresh cookie ni CSRF.

## Tools

| Tipo | Tools |
|---|---|
| Catálogo | `buscar_productos` (filas compactas, que conservan `mejorPromo` y `medios`; `completo=true` para la fila entera), `ver_producto` (por `key`, con historial y `promos` vigentes), `promos` (`GET /api/promos`: filtros `sitio` y `medio`, paginado), `facetas`, `comparar_entre_sitios`, `mejores`, `oportunidades_cuotas`, `tendencias`, `estado`, `sitios` |
| Armadores | `armar_pc`, `armar_outfit`, `armar_suplementos` |
| Acciones | `lanzar_scrape`, `cancelar_scrape`, `retomar_scrape`, `reclasificar` |
| Resto | `api_reference` (el `openapi.yaml`), `api_call` (cualquier GET/POST/PUT/PATCH sobre `/api/*`) |
| Instalación local | `quickstart`, `instalar`, `estado_instalacion`, `configurar_admin`, `levantar_servicios`, `bajar_servicios`, `diagnosticar`, `logs_locales` |

Las acciones tienen efecto real sobre el catálogo compartido.

## Instalar desde cero con el MCP

Las tools de instalación (`scrappy_mcp/local.py`) no usan la API ni
credenciales: trabajan sobre el repo donde vive `catalog-mcp/`
(`SCRAPPY_REPO_ROOT` lo pisa). Corren comandos fijos; ningún argumento llega a
un shell, y ninguna devuelve el valor de un secreto.

| Paso | Tool | Qué hace |
|---|---|---|
| 0 | `quickstart` | Diagnostica la máquina y dice cuál es el siguiente paso. Empezar siempre acá |
| 1 | `instalar` → `estado_instalacion` | Corre el instalador del SO con `SCRAPPY_INSTALL_ONLY=1`: sólo el toolchain, sin abrir el CLI interactivo |
| 2 | `configurar_admin` | `python -m cli --set-admin USER`: crea `.env` y fija el admin. Sin `password` la genera; queda sólo en `.env` |
| 3 | `levantar_servicios` | El CLI en modo `--plain` + `start`: compila si falta y levanta backend y frontend. Viven mientras viva el MCP |
| 4 | `diagnosticar` | Toolchain, secretos, jar, puertos, login del MCP y del admin, con el arreglo de cada falla |

| SO | Instalador | Prerrequisitos |
|---|---|---|
| Linux | `Ejecutar_instalar.sh` | java 21+, maven, node, python3, y postgres o Docker |
| macOS | `Ejecutar_instalar.sh` (baja uv `*-apple-darwin`) | `brew install openjdk@21 maven node postgresql@16` (o Docker) |
| Windows | `INSTALAR_Y_CORRER.bat` | Ninguno: vendoriza todo en `_tools/` |

macOS y Windows no se probaron todavía con una instalación real. La elección de
plataforma, el modo install-only y los arreglos que sugiere `diagnosticar` sí
están cubiertos por los tests.

Docker (`docker compose up`) es la otra vía y no necesita estas tools.

## Puesta en marcha

1. Sin variables, el MCP se loguea con la cuenta de servicio del CLI
   (`CLI_SERVICE_ACCOUNT_*` de `.env`, ADMIN). El server arranca igual sin
   ninguna credencial: las tools de la API fallan con "sin credenciales" y
   `quickstart` dice qué falta.
2. Para usar otra cuenta (ADMIN para las acciones; con VIEWER sólo las
   consultas), exportar las variables antes de abrir Claude Code. No van a
   ningún archivo versionado y ganan sobre `.env`:

   ```bash
   export SCRAPPY_API_USER=claude-mcp
   export SCRAPPY_API_PASSWORD='...'
   export SCRAPPY_API_URL=http://localhost:3000   # opcional, éste es el default
   ```

3. `.mcp.json` en la raíz ya registra el server como `scrappy`. Claude Code pide
   aprobarlo la primera vez; `/mcp` muestra si conectó.
4. Las tools de la API necesitan el backend levantado. Si no responde,
   devuelven `backend not reachable at <url>`: `quickstart` lo levanta.

## Tests

```bash
cd catalog-mcp && uv run pytest
```

Sin backend: un `httpx.MockTransport` hace de API y el server se ejercita con
el `Client` en memoria del SDK.
