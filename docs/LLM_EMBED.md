# LLM Catalog Agent — arquitectura de integración y reglas

> Cómo se conecta el LLM desde Java y qué reglas gobiernan lo que puede hacer.
> Para instalar Ollama y configurar las variables de entorno, ver
> [`LLM_AGENT_SETUP.md`](LLM_AGENT_SETUP.md). Este documento asume que eso ya
> está resuelto y se ocupa del diseño.

---

## 1. Dónde vive el LLM

El modelo **no corre dentro del backend**. Es un proceso aparte (por defecto
Ollama en `localhost:11434`) al que Java le habla por HTTP. No hay bindings
nativos, ni JNI, ni un runtime de inferencia embebido en el JAR.

```
navegador                backend Java                    proceso LLM
─────────                ────────────                    ───────────
AgentChatPanel  ──POST──►  ApiController
                           /api/agent/chat
                                │
                                ▼
                        CatalogAgentService  ──HTTP──►  /v1/chat/completions
                        (loop acotado)       ◄──JSON──  (Ollama u otro
                                │                        endpoint compatible)
                                ▼
                          ToolRegistry
                                │
                    ┌───────────┼───────────┐
                    ▼           ▼           ▼
            search_products view_product propose_reclassify
                    └───────────┴───────────┘
                                │
                                ▼
                      ScraperService.getLastResult()
                          (catálogo en memoria)
```

La consecuencia práctica: el LLM es una **dependencia externa opcional**. Si el
endpoint no responde, el resto de la aplicación funciona igual — solo falla el
chat del agente.

---

## 2. La costura `ChatProvider`

`ar.scraper.agent.ChatProvider` es una interfaz de dos métodos:

```java
ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model);
List<String>  listModels();
```

Todo lo que cruza esa interfaz son **records de dominio propios**
(`ChatMessage`, `ToolSpec`, `ToolCall`, `ToolResult`, `ChatResponse`, `Role`).
Ninguna forma específica de OpenAI o Anthropic — ni `choices[0].message`, ni
`tool_calls[].function.arguments` — se filtra hacia los llamadores.

**Por qué importa**: `CatalogAgentService` no sabe con qué proveedor está
hablando. Cambiar de Ollama a Anthropic es escribir un segundo adapter, no
tocar el loop ni las herramientas.

Hoy existe **un solo adapter**: `OpenAiCompatProvider`.

### `OpenAiCompatProvider`

| Aspecto | Detalle |
|---|---|
| Transporte | `java.net.http.HttpClient` + Jackson — mismo patrón que `InflacionService`, sin cliente HTTP extra |
| Endpoints | `POST {baseUrl}/chat/completions` · `GET {baseUrl}/models` |
| Timeout de conexión | 10 s |
| Timeout de chat | 120 s (inferencia local en CPU/GPU puede ser lenta) |
| Timeout de listado | 15 s |
| Autenticación | `Authorization: Bearer` solo si hay `LLM_API_KEY`; para Ollama local se omite |

Los tres timeouts son distintos a propósito: listar modelos debe fallar rápido
(alimenta un selector de UI), mientras que una inferencia real necesita margen.

---

## 3. El loop acotado

`CatalogAgentService.run(userHistory, model)` es el corazón. En pseudocódigo:

```
history = [system(systemPrompt())] + userHistory
repetir hasta MAX_ITERATIONS (6):
    response = provider.next(history, tools, model)
    si response.done()          → devolver texto final + propuestas juntadas
    history += assistant(texto, toolCalls)
    para cada toolCall:
        result = registry.execute(toolCall)
        history += toolResult(result)
        si la tool fue propose_reclassify y no dio error → juntar la propuesta
si se agotan las iteraciones → devolver un mensaje claro al usuario, no una excepción
```

Tres propiedades que no son accidentales:

1. **El límite es duro.** `MAX_ITERATIONS = 6` acota costo y tiempo. Agotarlo no
   es un error: devuelve un mensaje pidiendo reformular. Un modelo local que se
   queda en loop degrada a una respuesta útil, no a un timeout del navegador.
2. **El historial se reconstruye por request.** No hay estado mutable de
   conversación en el servidor: el frontend manda el historial completo. Dos
   pestañas no se pisan, y reiniciar el backend no pierde nada que el cliente
   no tenga.
3. **Las propuestas se juntan aparte del texto.** `AgentChatResponse` lleva
   `assistantText` y `List<ReclassifyProposal>` por separado, así que la UI
   renderiza tarjetas accionables en vez de pedirle al modelo que emita un
   formato que después haya que parsear.

---

## 4. Las herramientas

Son **exactamente tres, todas de solo lectura**. Están declaradas en
`ToolRegistry` y cada una implementa `CatalogTool` (`spec()` + `execute(args)`).

| Tool | Qué hace | Escribe |
|---|---|---|
| `search_products` | Busca en el catálogo en memoria con filtros combinables: `query`, `categoria` (enum del canon), `genero`, `excluir`, `precioMin`/`precioMax` | No |
| `view_product` | Devuelve la clasificación actual de un producto por URL | No |
| `propose_reclassify` | Valida un cambio y devuelve un diff *actual → propuesto* | **No** |

Que `propose_reclassify` no escriba es el punto central del diseño, no un
detalle de implementación. Ver la sección siguiente.

---

## 5. Las reglas

Esta es la parte que hay que entender antes de tocar nada.

### Regla 0 — el prompt no es un control de seguridad

El system prompt le dice al modelo qué categorías son válidas y en qué orden
usar las herramientas. **Eso es guía, no garantía.** Un modelo puede ignorarlo,
alucinar una categoría o llamar a las tools fuera de orden. Todos los controles
reales viven en código y se aplican aunque el modelo se porte mal.

Si alguna vez alguien propone "arreglar" un comportamiento del agente
únicamente editando el prompt, esa es la señal para revisar si el control
correspondiente existe en código.

### Regla 1 — la taxonomía canónica se inyecta desde el código

`systemPrompt()` construye la lista de categorías válidas llamando a
`CategoryGroups.canonicalCategories()`, no con una lista escrita a mano en el
texto. Agregar una categoría al dominio la propaga al prompt sola; no hay dos
fuentes de verdad que puedan divergir.

### Regla 2 — propuesta y confirmación son dos fases separadas

Dentro del loop autónomo del modelo, **nada escribe**. `propose_reclassify`
valida y devuelve un diff. La única escritura real es
`POST /api/agent/apply`, fuera del loop, disparada por un click humano
explícito en la UI.

El efecto: un modelo local de 14B con tool-calling poco confiable, en el peor
caso, produce *una propuesta rechazable* — nunca una escritura corrupta.

### Regla 3 — tipar no es validar

`/api/agent/apply` recibe un `ReclassifyProposal` tipado. Eso **no** es
evidencia de que el cliente haya validado algo. El endpoint re-valida por su
cuenta, siempre:

- que la URL exista en el catálogo;
- que la categoría pertenezca a `CategoryGroups.canonicalCategories()`.

Ambas validaciones existen también dentro de `propose_reclassify`. La
duplicación es deliberada: el cliente puede ser modificado, la propuesta puede
venir de `sessionStorage`, y el servidor no confía en ninguno de los dos.

### Regla 4 — guard de desfasaje contra la base

Entre que el agente propone y el humano confirma, el producto pudo cambiar. El
apply compara la `categoriaActual` de la propuesta contra la categoría **leída
de PostgreSQL** — no contra el snapshot en memoria, porque la propuesta se
construyó desde ese mismo snapshot y compararlos no detectaría nada.

Si difieren: `422` con `codigo: "conflicto_stale"` y los valores vigentes. La UI
retira el botón de confirmar para que la propuesta vieja no pueda reenviarse.

### Regla 5 — ninguna escritura sin rastro, ningún rastro sin escritura

El `UPDATE` de normalización y el `INSERT` en `agent_reclassify_audit` van en
**una sola transacción**. Si falla la auditoría, se revierte la
reclasificación. El tradeoff está elegido a conciencia: se prefiere perder un
click repetible antes que persistir un cambio sin registro.

Los valores viejos de la auditoría se leen del servidor, nunca se aceptan del
cliente.

### Regla 6 — el éxito se reporta solo si ocurrió

El write path devuelve un booleano que **siempre** se chequea, y verifica la
cantidad de filas afectadas. Un `UPDATE` que no toca ninguna fila no es un
éxito. (Esto corrige un defecto real: la versión anterior tragaba excepciones
en un `LOG.warn` y respondía `200 "Reclasificación aplicada."` sin haber
escrito nada.)

### Regla 7 — el agente cede la VRAM al scraping

`POST /api/agent/chat` y `POST /api/agent/apply` devuelven `409` mientras hay un
scraping corriendo: el modelo de visión Marqo-FashionSigLIP compite por la
misma memoria de GPU. `GET /api/agent/models` **no** está gateado, así que el
selector de modelos de la UI sigue funcionando.

### Regla 8 — la salida del modelo nunca se convierte en DOM

El frontend renderiza el markdown de las respuestas (`**negrita**`, links) a
**elementos de React**, jamás a un string de HTML, y sin
`dangerouslySetInnerHTML`. React escapa cada nodo de texto, así que el markup
que emita el modelo se ve como caracteres, no se ejecuta. Los `href` se
restringen a `http`/`https`, de modo que un `javascript:` o `data:` degrada a
texto plano. Ver `frontend/src/lib/richText.jsx`.

---

## 6. Configuración

Todo por variables de entorno, todas **opcionales** con default a Ollama local
— por eso no están en `RequiredEnvVarsGuard`: la ausencia de un LLM no debe
impedir que arranque el backend.

| Variable | Default | Para qué |
|---|---|---|
| `LLM_PROVIDER` | `openai_compat` | Selecciona el adapter |
| `LLM_BASE_URL` | `http://localhost:11434/v1` | Endpoint del proveedor |
| `LLM_MODEL` | modelo local | Default cuando el request no especifica uno |
| `LLM_API_KEY` | *(vacío)* | Solo para proveedores remotos |

El modelo se puede elegir **por request**: el frontend descubre los disponibles
con `GET /api/agent/models` (sin lista hardcodeada) y manda `model` opcional en
cada chat. No hay estado mutable de selección en el servidor.

Detalle de instalación y ejemplos: [`LLM_AGENT_SETUP.md`](LLM_AGENT_SETUP.md).

---

## 7. Dónde tocar para cambiar algo

| Quiero… | Archivo |
|---|---|
| Agregar otro proveedor (Anthropic, OpenAI real) | Nueva clase que implemente `ChatProvider` |
| Cambiar el límite de pasos | `CatalogAgentService.MAX_ITERATIONS` |
| Cambiar qué puede hacer el agente | `ToolRegistry` + una clase `CatalogTool` |
| Ajustar la guía del modelo | `CatalogAgentService.systemPrompt()` — recordá la Regla 0 |
| Endurecer una validación | `ApiController.agentApply` **y** `ProposeReclassifyTool` |
| Cambiar el write path | `DatabaseService.aplicarReclasificacionAuditada` |
| Tocar la UI del chat | `frontend/src/components/AgentChatPanel.jsx` |

---

## 8. Límites conocidos

- **El catálogo que ve el agente es el snapshot en memoria**, no la base. Las
  tools leen de `ScraperService.getLastResult()`. Solo el guard de desfasaje y
  el write path van a PostgreSQL.
- **La calidad de las respuestas depende del modelo local.** El system prompt
  *sugiere* el orden search → view → propose en prosa, no lo impone; y
  `subCategoria`/`marca`/`genero` viajan como texto libre sin validarse contra
  una taxonomía, a diferencia de `categoria`. Son mejoras identificadas y
  diferidas, no defectos activos.
- **`MAX_ITERATIONS = 6`** deja unos dos pasos de margen para que el modelo se
  auto-corrija más allá del flujo canónico de cuatro.
