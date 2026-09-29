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
              ┌───────────┬─────┴─────┬───────────┐
              ▼           ▼           ▼           ▼
      search_products view_product propose_reclassify propose_pc
              └───────────┴───────────┴───────────┘
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
si el pedido toca usuarios/roles/permisos/cron → negativa fija, sin llamar al modelo
history = [system(systemPrompt())] + userHistory
repetir hasta MAX_ITERATIONS (6):
    response = provider.next(history, tools, model)
    si response.done():
        si hubo búsqueda vacía y todavía no se reintentó → un aviso de relajar criterios, seguir
        si el turno fue solo de búsqueda → respuesta armada en el servidor con las filas
        si no → texto final del modelo + propuestas juntadas
    history += assistant(texto, toolCalls)
    para cada toolCall:
        result = registry.execute(toolCall)
        history += toolResult(result)
        si la tool fue propose_reclassify y no dio error → juntar la propuesta (sin repetidas)
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

Son **exactamente cuatro, todas de solo lectura**. Están declaradas en
`ToolRegistry` y cada una implementa `CatalogTool` (`spec()` + `execute(args)`).
`ToolRegistryTest` fija ese conjunto: agregar una quinta rompe el build a propósito.

| Tool | Qué hace | Escribe |
|---|---|---|
| `search_products` | Busca en el catálogo en memoria, rankeado por relevancia, con filtros combinables: `query`, `categoria` (familia del canon), `genero`, `excluir`, `precioMin`/`precioMax`, `enOferta`. Rechaza cualquier otro argumento | No |
| `view_product` | Devuelve la clasificación actual de un producto por URL | No |
| `propose_reclassify` | Valida un cambio y devuelve un diff *actual → propuesto*; rechaza un diff que no cambia nada | **No** |
| `propose_pc` | Arma una PC con `PcBuilder` sobre el snapshot (`presupuesto`, `conGpu`, `excluir` urls); mismo JSON que `GET /api/pcs/builder` | No |

Que `propose_reclassify` no escriba es el punto central del diseño, no un
detalle de implementación. Ver la sección siguiente.

### Cómo busca `search_products`

| Paso | Qué hace | Dónde |
|---|---|---|
| Tokenizar | Minúsculas y sin acentos; pega número y unidad (`1 tb` → `1tb`); saca palabras vacías y muletillas ("de", "tenés", "algún", "busco"); plural → singular en la consulta **y** en el producto | `QueryTokenizer` |
| Puntuar | BM25F sobre `marca` (×2.0), `categoria`+`subCategoria` (×1.5) y `nombre` (×1.0). Por término: exacto 1.0, prefijo 0.8, unidad tras dígitos (`tb` ↔ `1tb`) 0.8, typo 0.5. **Nunca** substring suelto | `RelevanceRanker` |
| Seleccionar | Estricto: todos los términos. Si no hay nada, relajado: al menos la mitad, cada fila marcada `coincidencia: "parcial"` con `terminosFaltantes` | `SearchProductsTool` |
| Ordenar | Por relevancia; con `enOferta`, por el % de descuento redondeado y después por relevancia | `SearchProductsTool` |

Tres decisiones que no son obvias:

- **La lista de palabras vacías es propia de la búsqueda.** `aggregator/grouping/StopWords`
  no sirve: está hecha para identidad de producto y descarta colores, talles y
  género, que en una búsqueda son criterios. "sin" tampoco se descarta ("sin mangas").
- **`categoria` es una familia, no igualdad exacta.** `"Zapatilla"` trae también
  "Zapatilla Running" y "Zapatilla Urbana" (igual, o empieza con el valor + espacio).
  Con igualdad exacta, "zapatillas Nike de hombre" respondía "no encontré" teniendo 2.
- **El descuento se compara redondeado.** Con la fracción cruda, el ruido de
  redondeo de cada sitio (9,0907 % contra 9,0912 %) le ganaba a la relevancia y
  sacaba del top los mejores resultados.

Cada fila trae `url`, `nombre`, `sitio`, `categoria`, `subCategoria`, `marca`,
`genero`, `precio`, `relevancia`, y `precioOrig` + `descuentoPct` cuando hay
descuento real. El índice se arma una vez por snapshot del catálogo (~0,4 s sobre
22.000 productos) y las consultas siguientes tardan 10–30 ms.

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

### Regla 9 — el agente nunca toca usuarios, roles ni cron

No es una convención de las herramientas actuales: es estructural.

- **ArchUnit** (`agentNoTocaUsuariosRolesNiCron`): `ar.scraper.agent` no puede
  depender de `security`, `identity`, `scheduling` ni `db`.
- **Conjunto cerrado de herramientas**: `ToolRegistryTest` fija las cuatro.
- **Negativa determinista**: `RestrictedIntents` detecta pedidos de crear,
  modificar o borrar usuarios, roles o permisos, o de programar o lanzar
  cronjobs o scrapes, y responde una negativa fija **antes** de llamar al modelo
  (`outcome: capability`). Exige un verbo de acción con un objeto restringido a pocas palabras, o un verbo de scrapeo, así
  que "juegos de rol" o "mouse para usuario zurdo" siguen siendo búsquedas.

Antes de la negativa determinista, el modelo se negaba bien pero la barrera de
grounding descartaba su respuesta (no había usado herramientas) y el usuario
veía un "no pude responder" genérico.

### Regla 10 — una lista de productos la escribe el servidor, no el modelo

Si en el turno no hubo `propose_reclassify` ni `propose_pc`, la respuesta sale de
`SearchAnswerRenderer` a partir de las filas de la última búsqueda con
resultados: cantidad, los filtros aplicados ("Filtré por: …") y una línea por
producto con link, sitio, precio y descuento. La prosa del modelo se descarta.

Corolario de la Regla 0: se probó primero pedirlo en el prompt, y ni
`qwen3:14b` (respondía preguntas o consejos ignorando las filas) ni `qwen2.5:7b`
lo cumplieron. La línea de filtros existe porque los modelos chicos inventan
**valores** (un `precioMax` que nadie pidió) y así quedan a la vista.

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
| Cambiar qué puede hacer el agente | `ToolRegistry` + una clase `CatalogTool` — y `ToolRegistryTest`, a conciencia (Regla 9) |
| Ajustar palabras vacías o plurales | `QueryTokenizer` |
| Ajustar pesos o niveles de match | `RelevanceRanker` |
| Cambiar cómo se ve una lista de productos | `SearchAnswerRenderer` |
| Ampliar lo que el agente se niega a hacer | `RestrictedIntents` + `RestrictedIntentsTest` (casos a favor **y** en contra) |
| Ajustar la guía del modelo | `CatalogAgentService.systemPrompt()` — recordá la Regla 0 |
| Endurecer una validación | `ApiController.agentApply` **y** `ProposeReclassifyTool` |
| Cambiar el write path | `DatabaseService.aplicarReclasificacionAuditada` |
| Tocar la UI del chat | `frontend/src/components/AgentChatPanel.jsx` |

---

## 8. Límites conocidos

- **El catálogo que ve el agente es el snapshot en memoria**, no la base. Las
  tools leen de `ScraperService.getLastResult()`. Solo el guard de desfasaje y
  el write path van a PostgreSQL.
- **La calidad de las respuestas depende del modelo local.** Medido el
  2026-09-29 sobre el catálogo real: `qwen3:14b` resolvió bien las tres
  consultas de prueba (40–100 s cada una); `qwen2.5:7b` es más rápido pero a
  veces no llama a ninguna herramienta e inventa valores de filtro. Para el
  agente, `qwen3:14b`.
- `subCategoria`/`marca`/`genero` viajan como texto libre sin validarse contra
  una taxonomía, a diferencia de `categoria`. Mejora identificada y diferida.
- En un resultado parcial, "faltan: …" es la unión sobre todas las filas: puede
  nombrar un término que las primeras filas sí tienen.
- **`MAX_ITERATIONS = 6`** deja unos dos pasos de margen para que el modelo se
  auto-corrija más allá del flujo canónico de cuatro.

---

<!-- Movido desde CLAUDE.md (2026-09-28) -->
## LLM Catalog Agent (`ar.scraper.agent`)

Agente de chat con tool-use, provider-pluggable, para revisar y corregir la
clasificación de productos por lenguaje natural. Seam `ChatProvider` con un
adapter hoy: `OpenAiCompatProvider` (Ollama).

**Exactamente 4 herramientas, TODAS de solo lectura**, dentro de un loop acotado
(`MAX_ITERATIONS=6`): `search_products`, `view_product`, `propose_reclassify`,
`propose_pc`. La cuarta corre `PcBuilder.armar` sobre el snapshot vivo
(`presupuesto`, `conGpu`, `excluir` urls) y devuelve el mismo JSON que
`GET /api/pcs/builder` — `PcBuildJson`, en `pcs/`, es la única serialización
para los dos. El agente narra los picks; guardar sigue siendo cosa de `/pcs`.
`PcBuilder` no es bean (lo instancia `ApiController` a mano) y `agent/` no
puede nombrar `web/`, así que la tool construye el suyo con `RecommendationService`.

**`search_products` filtra en el catálogo, no en la prosa del modelo.** Acepta `query` (texto libre, tokenizado y rankeado con BM25F — ver §4), `categoria` (enum cerrado contra el canon, matchea su familia), `genero`, `excluir` (lista de términos vetados en el nombre), `precioMin`/`precioMax` y `enOferta`; todos se aplican en conjunción, hace falta al menos uno además de `excluir`/`enOferta`, y cualquier otro argumento es error. Dos razones para que sean parámetros y no texto: (1) **la categoría no es una palabra del nombre** — una "Remera sin mangas Dry Fit" clasificada `Musculosa` era invisible a `query=musculosa`, y un producto cuyo nombre no coincide con su categoría es justo el que hay que revisar, así que el punto ciego se superponía con el propósito del tool; y (2) si el modelo filtra en su respuesta en vez de en la llamada, **la barrera de grounding no lo puede ver**: hubo una tool call real con filas reales, así que el turno pasa igual. Una llamada vacía es error, no el catálogo entero cortado a 10.
La reclasificación es **two-phase propose/confirm** — `propose_reclassify` valida
y devuelve un diff, nunca escribe. El único write real es `POST /api/agent/apply`,
fuera del loop, tras confirmación humana explícita y re-validando server-side.

**Continuidad del chat:** cada mensaje assistant carga su `trace` (las tool calls
que el modelo **pidió**, nunca lo que el catálogo respondió), el cliente lo
reenvía, y el servidor **re-ejecuta** esas llamadas contra el snapshot vivo antes
de contactar al proveedor. Un `trace` manipulado no puede inyectar un dato falso,
y la evidencia replayada está al día. Bounds: `MAX_REPLAY_CALLS=12`.

**Write path:** `aplicarReclasificacionAuditada` hace UPDATE + INSERT de auditoría
en una sola transacción con rollback completo, y su booleano de retorno **siempre**
se chequea. Staleness guard: compara `categoriaActual` contra la DB (no contra el
snapshot en memoria) y devuelve `422 conflicto_stale` en vez de sobrescribir a ciegas.
Tras escribir, parchea el catálogo en memoria y recalcula facetas.

Config por env (`LLM_PROVIDER`/`LLM_MODEL`/`LLM_BASE_URL`/`LLM_API_KEY`), todas
opcionales — **no** están en `RequiredEnvVarsGuard`.

📄 Detalle: [`docs/LLM_EMBED.md`](./LLM_EMBED.md) · setup: [`docs/LLM_AGENT_SETUP.md`](./LLM_AGENT_SETUP.md).

---
