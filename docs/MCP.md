# MCP server del catálogo

> Un server MCP (`catalog-mcp/`) que le da a Claude Code —o a cualquier host
> MCP— acceso al catálogo a través de la API REST. Es otra puerta a lo mismo que
> usa el agente local (`docs/LLM_EMBED.md`), no un reemplazo.

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
| Catálogo | `buscar_productos` (filas compactas; `completo=true` para la fila entera), `ver_producto` (por `key`, con historial), `facetas`, `comparar_entre_sitios`, `mejores`, `oportunidades_cuotas`, `tendencias`, `estado`, `sitios` |
| Armadores | `armar_pc`, `armar_outfit`, `armar_suplementos` |
| Acciones | `lanzar_scrape`, `cancelar_scrape`, `retomar_scrape`, `reclasificar` |
| Resto | `api_reference` (el `openapi.yaml`), `api_call` (cualquier GET/POST/PUT/PATCH sobre `/api/*`) |

Las acciones tienen efecto real sobre el catálogo compartido.

## Puesta en marcha

1. Una cuenta para el MCP. Para las acciones tiene que ser **ADMIN**; con
   VIEWER funcionan sólo las consultas. Se crea desde el dashboard o con
   `POST /api/usuarios`.
2. Exportar las variables antes de abrir Claude Code (no van a ningún archivo
   versionado):

   ```bash
   export SCRAPPY_API_USER=claude-mcp
   export SCRAPPY_API_PASSWORD='...'
   export SCRAPPY_API_URL=http://localhost:3000   # opcional, éste es el default
   ```

3. `.mcp.json` en la raíz ya registra el server como `scrappy`. Claude Code pide
   aprobarlo la primera vez; `/mcp` muestra si conectó.
4. El backend tiene que estar levantado. Si no responde, cada tool devuelve
   `backend not reachable at <url>`.

## Tests

```bash
cd catalog-mcp && uv run pytest
```

Sin backend: un `httpx.MockTransport` hace de API y el server se ejercita con
el `Client` en memoria del SDK.
