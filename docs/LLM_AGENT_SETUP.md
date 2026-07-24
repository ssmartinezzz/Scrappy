# LLM Catalog Agent — Setup

> Feature `llm-catalog-nlp`: un agente LLM con tools sobre el catálogo, accesible
> desde el botón **"Ask Agent"** en el panel ML. El agente puede **buscar**, **ver**
> y **proponer reclasificaciones** de productos reales (`productos`), pero **nunca
> escribe solo**: propone, y vos confirmás con [Sí]/[No]. La única escritura es
> `POST /api/agent/apply`, re-validada server-side.

## 1. Prerequisito: un endpoint OpenAI-compatible

Por defecto apunta a **Ollama local** en `http://localhost:11434`. Si no tenés Ollama:

```bash
# instalar ollama (https://ollama.com) y luego bajar al menos un modelo
ollama pull qwen3:14b       # default del proyecto (llena una RTX 3080 10GB)
ollama pull qwen2.5:7b      # más liviano (~5GB), convive incluso durante scrapes
```

El agente descubre los modelos instalados en runtime vía `GET /v1/models` — cuando
hacés `ollama pull <modelo>`, aparece solo en el `<select>` del panel, **sin tocar
ni código ni `.env`**.

> **VRAM (gotcha):** `qwen3:14b` ocupa casi toda la placa y pelea VRAM con el modelo
> visual Marqo-FashionSigLIP **durante un scrape**. Por eso `POST /api/agent/chat` y
> `POST /api/agent/apply` están **gateados con 409** mientras hay un scrape corriendo
> (`ScraperService` en `RUNNING`). `GET /api/agent/models` NO se gatea (es metadata,
> no toca la GPU). Si querés usar el agente durante un scrape, elegí `qwen2.5:7b` en
> el selector — coexiste sin problema.

## 2. Variables de entorno

Todas son **opcionales**: tienen defaults locales sanos y **NO** están en
`RequiredEnvVarsGuard.REQUIRED_VARS`, así que el backend arranca sin ellas. Solo
las tocás si querés apuntar a otro provider/modelo/host.

| Variable | Default | Qué es |
|---|---|---|
| `LLM_PROVIDER` | `openai_compat` | Adapter activo. Hoy solo `openai_compat` (Ollama + cualquier endpoint OpenAI-compatible). `AnthropicProvider` es follow-up. |
| `LLM_BASE_URL` | `http://localhost:11434/v1` | Base URL del endpoint OpenAI-compatible. |
| `LLM_MODEL` | `qwen3:14b` | Modelo **default**. El selector de la UI lo puede pisar por-request (sin estado mutable en el server). |
| `LLM_API_KEY` | *(vacío)* | Opcional. Ollama local no necesita key; un remoto (OpenAI/Groq/etc.) sí. |

## 3. Cómo agregarlas a tu `.env`

Copiá este bloque en tu `.env` (raíz del repo). Descomentá y editá solo lo que
quieras cambiar del default:

```dotenv
# ─── LLM Catalog Agent (llm-catalog-nlp) — all optional, local defaults ──
# LLM_PROVIDER=openai_compat
# LLM_BASE_URL=http://localhost:11434/v1
# LLM_MODEL=qwen3:14b
# LLM_API_KEY=
```

### Ejemplos

**Ollama local con el modelo liviano por default:**
```dotenv
LLM_MODEL=qwen2.5:7b
```

**Endpoint OpenAI-compatible remoto (ej. otra máquina o un servicio con key):**
```dotenv
LLM_BASE_URL=https://mi-endpoint.example.com/v1
LLM_MODEL=algún-modelo-disponible
LLM_API_KEY=sk-...
```

> El override por-request desde el `<select>` de la UI se valida contra el set de
> modelos disponibles: si mandás un modelo que no existe, el backend responde
> **400 nombrando el modelo inválido** — nunca cae en silencio a otro.

## 4. Verificar que anda

1. Con Ollama arriba y al menos un modelo bajado, arrancá el backend.
2. `curl http://localhost:3000/api/agent/models` → debería devolver
   `{ "available": [...], "default": "qwen3:14b" }`.
3. En el dashboard, panel ML → botón **"Ask Agent"** (al lado de "Re-entrenar").
4. Elegí el modelo en el `<select>`, escribí algo tipo
   *"clasificaste mal La Remera SAD de Adidas, fijate"*, y el agente debería
   buscar → ver → proponer una reclasificación con [Sí]/[No].

Ver los tres endpoints (`/api/agent/chat`, `/api/agent/apply`, `/api/agent/models`)
en `docs/API_REFERENCE.md`.
