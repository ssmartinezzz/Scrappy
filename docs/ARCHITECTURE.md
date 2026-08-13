# Arquitectura del Fashion Scraper

## Decisiones principales y su justificación

---

### ¿Por qué un fat JAR con todo incluido?

**Decisión**: Spring Boot fat JAR con Tomcat embebido, backend **API-only** (sin servir la SPA).

**Razón**: es una herramienta local mono-usuario en Windows, no un servicio desplegado. Para ese escenario, un `.bat` que descarga Java + Postgres portable + Node + Python y ejecuta `java -jar scraper.jar` es la UX más simple posible: cero-setup, sin infraestructura previa. No hay Dockerfile, no hay instalaciones previas, no hay conflictos de versiones.

**Actualización (docker-install-alternative, 2026-07-21)**: la afirmación "no hay Dockerfile" de arriba queda como contexto histórico de por qué el installer portable fue la primera opción, no como estado actual — ahora existe una alternativa Docker **aditiva** (`Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`) para quien prefiera `docker compose up` en vez del `.bat`/`Ejecutar_instalar.sh` portable. Es un camino de instalación adicional, no un reemplazo: el installer portable y `_tools/` siguen intactos y sin cambios (el launcher interactivo que el installer portable invoca en su tail pasó de `menu.ps1`/`menu.sh` al CLI nativo en `cli/` — ver `native-cli-installer` más abajo — sin afectar este camino Docker). El backend usa la imagen `mcr.microsoft.com/playwright/java:v1.44.0-jammy` (Chromium + libs ya matcheadas a `playwright.version=1.44.0`) con Temurin 21 instalado explícitamente encima (la imagen base trae JDK 17) y Python 3.11 + deps ML (`psycopg2-binary`, `torch`/`torchvision` CPU, `open_clip_torch`, `huggingface_hub`, `transformers`) para que `PythonRunner.detectarPython()` resuelva `python3` por PATH sin necesitar `_tools/`. El frontend usa un build multi-stage (`node:20-alpine` → `nginx:alpine`) con `VITE_API_BASE_URL` como build ARG. Volúmenes nombrados (`pgdata`, `models`, `logs`) preservan datos y pesos ML descargados entre `docker compose down`/`up`. Ver `docker.env.example` para la plantilla de variables de este modo (distinta de `.env.example`).

Topología del modo Docker (mapea 1:1 a los 3 servicios de abajo; el ML sigue
siendo un subprocess **dentro** del contenedor backend, no un servicio propio):

```
docker compose up
┌─────────────────────┐   CORS    ┌──────────────────────────────┐
│ frontend (nginx)    │  :8080    │ backend (Java+Python+Chromium)│
│  host :8080 → :80   │──────────►│  host :3000 → :3000           │
│  build ARG          │  fetch    │  ├─ ML subprocess (psycopg2)  │
│  VITE_API_BASE_URL  │           │  └─ Playwright/Chromium       │
└─────────────────────┘           └──────────────┬───────────────┘
        depends_on: backend                       │ DNS interna: "postgres"
                                   ┌──────────────▼───────────────┐
                                   │ postgres:16-alpine (healthcheck)│
                                   └──────────────┬───────────────┘
volúmenes nombrados:  pgdata (DB)  ·  models (pesos Marqo/HF, lazy)  ·  logs
```

`APP_CORS_ALLOWED_ORIGINS` (`:8080`) y `VITE_API_BASE_URL` (`:3000`) tienen que
cerrar entre sí. `DATABASE_URL` apunta a `postgres:5432` (nombre del servicio, no
`localhost`) o a un Postgres externo (ver `docker-compose.override.yml.example`).

**Actualización (decouple-services-postgres, Batch 3/D6)**: el backend dejó de servir `static/` (se retiró `SpaController`); el proyecto pasó de "monolito con SPA embebida" a **3 servicios independientes** (backend API, frontend Vite, ML Python subprocess lanzado por el backend), cada uno configurado 100% por variables de entorno (`spec` "Environment-Only Configuration"). Ver el diagrama de topología más abajo.

---

### ¿Por qué PostgreSQL y no SQLite/H2?

**Decisión** (decouple-services-postgres, Batch 1, design D1-D3): `postgresql` JDBC + HikariCP + Flyway, reemplazando `sqlite-jdbc`.

**Historia**: el proyecto arrancó con SQLite (un archivo `scraper.db`, cero configuración, visible/transferible) por su simplicidad para un usuario único en Windows. Esa elección tuvo un costo real: SQLite es single-writer, y a medida que se agregaron cronjobs + API + scraping concurrente, apareció `SQLITE_BUSY_SNAPSHOT` (escrituras solapadas pisándose commits) que se parcheó con un lock-dance de aplicación (`writeLock`/`readLock`/`refrescarSnapshot()` + `readConn` dedicada) — una solución cada vez más frágil para un problema que SQLite no está diseñado para resolver.

**Razón del swap**: Postgres da concurrencia real vía MVCC — múltiples escritores/lectores sin locks de aplicación. El write-path (upsert + historial + soft-delete) se movió a funciones `plpgsql` server-side (`sp_upsert_run`/`sp_soft_delete_ausentes`, design D2) para que la decisión "¿cambió el precio?" ocurra DENTRO de una sola sentencia SQL, eliminando la carrera de "leer precio actual → decidir → escribir" entre callers concurrentes. `UNIQUE(url, fecha)` + `ON CONFLICT DO NOTHING` hace el insert en `precio_historico` idempotente incluso con escritores solapados.

**Trade-off**: ya no hay un archivo único portable — Postgres corre como proceso (portátil bajo `_tools/pgsql`, provisionado por el installer, o un Postgres externo vía `DATABASE_URL`). A cambio, las migraciones son versionadas (Flyway `V1__baseline.sql`), no `ALTER TABLE` manual, y el problema de concurrencia queda resuelto estructuralmente en vez de parchado.

---

### ¿Por qué Python como subprocess en lugar de Java ML?

**Decisión**: `ml_pipeline.py` ejecutado como proceso separado desde `PythonRunner.java`.

**Razones**:
1. El ecosistema ML de Python (TF-IDF, clustering, historial) es mucho más expresivo
2. El script no tiene dependencias externas — usa solo stdlib Python (json, math, re, collections)
3. Si Python no está disponible, el scraper sigue funcionando sin ML (degradación elegante)
4. El script vive en `src/main/resources/ml/` y se extrae del JAR al directorio de trabajo en el primer run

**Trade-off**: adds ~5-15 segundos al pipeline. Aceptable dado que el scraping tarda minutos.

---

### ¿Por qué Playwright headless y no requests/BeautifulSoup?

**Decisión**: Playwright (Chromium headless) para todos los scrapers.

**Razón**: los sitios argentinos usan JavaScript intensivamente. Tiendanube en particular actualiza precios dinámicamente. Con `requests` simple obtendrías `$0,00` en todos los precios. Playwright ejecuta el JS completo y expone el DOM final.

**Optimización**: `BaseScraper` bloquea imágenes, CSS, fonts y videos durante el scraping para reducir bandwidth y tiempo.

---

### ¿Por qué JS heurístico para TiendaNube en lugar de la API?

**Decisión**: intentar API REST (`/api/v1/{storeId}/products`) y si falla, usar extractor JS.

**Razón**: la API de TN requiere `Authorization: bearer TOKEN` de OAuth. Sin el token, devuelve array vacío. Implementar OAuth completo requeriría que el usuario registre una app en TN y obtenga credenciales, lo cual es demasiada fricción.

**Fallback JS**: busca `data-price` (atributo TN nativo, siempre presente, entero sin formateo), luego scan manual del texto para precios, luego clase CSS. Funciona en la mayoría de temas pero solo captura la primera página a menos que `nextPageUrl()` encuentre el link de siguiente página.

---

### ¿Por qué upsert en lugar de truncate+insert?

**Decisión**: upsert por URL + soft-delete para productos ausentes.

**Razones**:
1. **Historial de precios**: si truncamos, perdemos la historia. El upsert solo registra en `precio_historico` cuando el precio cambia.
2. **Memoria estable**: la tabla `productos` nunca crece más allá del catálogo real (~5k-10k filas). Sin upsert, cada run duplicaría los datos.
3. **Soft delete**: un producto que desaparece temporalmente (stock agotado, sitio caído) se marca `activo=false` (columna `BOOLEAN` desde `V5`, `normalize-db-schema-fks-1nf`) pero mantiene su historial. Si vuelve, se reactiva.

---

### ¿Por qué cada FK a `productos` tiene una política de borrado distinta?

**Decisión** (`normalize-db-schema-fks-1nf`, V4): las cuatro tablas que
referencian `productos(url)` no comparten una política uniforme. Cada una recibe
la que le corresponde según qué tipo de dato guarda.

| Tabla | Política | Razón |
|-------|----------|-------|
| `precio_historico` | `ON DELETE CASCADE` | Dato derivado. No significa nada sin el producto que describe. |
| `precios_externos` | `ON DELETE CASCADE` | Ídem: comparativas de MercadoLibre atadas a un producto del catálogo. |
| `favoritos` | `ON DELETE RESTRICT` | Dato del usuario. Una limpieza de catálogo no tiene autoridad para destruirlo. |
| `agent_reclassify_audit` | **sin FK** | Un audit trail no se ata a datos mutables: si el borrado del producto se lleva puesto el registro de quién lo reclasificó, deja de ser auditoría. |

**Por qué no una política uniforme**: CASCADE en todo es lo cómodo, y borra en
silencio los favoritos del usuario y el rastro de reclasificaciones humanas cada
vez que alguien vacía el catálogo. RESTRICT en todo obliga a `limpiarProductos()`
a borrar tabla por tabla en orden explícito, lo que es correcto pero convierte
datos derivados en ceremonia. La distinción real no es técnica: es qué dato se
puede regenerar con un scrape y cuál no.

**Consecuencia visible**: `DELETE /api/db/productos` devuelve **409** si hay
favoritos vivos, y no borra nada. No hay `?force=` — un override reintroduce
exactamente el borrado silencioso que la política evita. El conteo y el DELETE
comparten transacción: chequear en la capa del endpoint dejaría una ventana
TOCTOU donde un favorito agregado en el medio convierte el 409 en un 500 crudo.

**`favoritos` se crea `NOT VALID`**: la constraint valida desde el primer día
los INSERT y UPDATE nuevos y dispara igual el RESTRICT al borrar el padre; lo
único diferido es el chequeo de backfill sobre filas históricas. Una instalación
vieja con huérfanos no puede quedar bloqueada, y la migración no tiene permiso
para borrar datos de usuario para satisfacerse a sí misma.

---

### ¿Por qué CHECK y no una tabla de lookup para genero/rubro/ml_segment?

**Decisión** (`normalize-db-schema-fks-1nf`, V6): `productos.genero`, `rubro`
y `ml_segment` son `TEXT` con tres CHECK enumerando el dominio exacto, no una
FK a una tabla `generos`/`rubros`/`segments` de tres o cuatro filas. Una
tabla de lookup para un enum que nunca gana una quinta columna (nombre para
mostrar, orden, metadata) es normalización de manual, no de datos reales — el
CHECK dice lo mismo con cero JOINs y cero tabla adicional que mantener.

Los tres dominios se verificaron **en vivo** contra el catálogo completo
(13543 productos, obs #839) antes de escribir la migración, no se
adivinaron: `rubro` y `ml_segment` no tuvieron ninguna violación; `genero`
tuvo exactamente una — una fila con `'Mujer'` con mayúscula. Por eso las tres
constraints se agregan **VALID** de una (no `NOT VALID` como `favoritos` en
V4): el riesgo que `NOT VALID` mitigaría —una instalación con huérfanos que
la migración no puede borrar— no existe acá, porque el propio dominio se
enumeró de forma exhaustiva.

**NULL pasa en las tres** porque ninguna de las tres columnas es `NOT NULL`
desde el baseline (`V1__baseline.sql:38,44,46`) — agregar esa restricción
ahora sería un cambio de contrato distinto, no parte de esta migración.
**El string vacío pasa solo en `genero`**: es el sentinel de abstención de
`GenderResolver` (`CODE-5` — "vacío es sin opinión, nunca malo"), y un CHECK
que lo rechazara convertiría "no sé" en un error de escritura cada vez que el
clasificador se abstiene, que es exactamente el caso que `CODE-5` prohíbe
penalizar.

**De dónde salió el `'Mujer'` con mayúscula, y por qué se validan DOS lugares**:
se rastrearon los caminos que persisten `genero` sin pasar por
`GenderResolver`, y resultaron ser dos, no uno.

El primero es `ProposeReclassifyTool` (la herramienta LLM de
`propose_reclassify`), que valida `categoria` contra su taxonomía canónica
pero dejaba pasar `genero` crudo. El diseño D7 lo señaló como *el* origen del
dato sucio. **No lo es**: esa herramienta nunca escribe — solo devuelve el
diff que el humano confirma.

El que escribe es `AgentEndpoints.agentApply` →
`aplicarReclasificacionAuditada`, y es alcanzable por HTTP **sin pasar jamás
por la herramienta**. Tomaba `genero` del body (`ReclassifyProposal.generoPropuesto()`)
sin ninguna validación, a diferencia de `categoria`, que el mismo método sí
valida contra la taxonomía unas líneas más arriba. Ese es el camino por el que
se coló el dato vivo, y validar solo la herramienta habría dejado el agujero
abierto mientras el CHECK convertía la anomalía silenciosa en un 500 opaco —
exactamente lo que D7 decía querer evitar.

Los dos validan ahora, contra **una sola definición del dominio**
(`ProposeReclassifyTool.VALID_GENEROS`, público por esta razón): dos copias de
la misma lista de cinco literales se desincronizan de V6 la primera vez que el
dominio cambia. Ambos **rechazan** en vez de normalizar en silencio — la
herramienta con `ToolResult.error`, el endpoint con un 400 que nombra el valor
ofensivo.

Una sutileza que el endpoint sí distingue y la herramienta no necesita: ahí un
`genero` **vacío o ausente significa "no lo cambies"** (cae a `previo.genero()`),
no un valor a escribir. Validarlo como valor rechazaría un no-op legítimo, así
que la validación se saltea el blanco a propósito. El `''` sigue siendo
admisible como valor real en el CHECK; son dos capas con dos preguntas
distintas.

---

### ¿Cómo se revierte `V5` (booleans + fechas) si hace falta?

**Por qué esto no vive dentro de `V5__boolean_and_date_column_types.sql`**:
cualquier byte agregado a una migración de Flyway ya aplicada rompe la
validación de checksum (`flyway_schema_history.checksum` queda desactualizado)
la próxima vez que esa base arranque — el backend directamente no bootea
(`FlywayValidateException`, "Migration checksum mismatch"). Verificado de
forma empírica, no asumido: se aplicó la cadena `V1..V5` completa contra un
`postgres:16-alpine` descartable vía el CLI oficial de Flyway, se le agregó
una sola línea de comentario a `V5` en disco, y tanto `flyway validate` como
`flyway migrate` fallaron con exactamente ese error. `V5` ya está commiteada;
agregarle un bloque de rollback ahí adentro reabre ese riesgo para cualquier
instalación que ya la haya corrido. La documentación del rollback vive acá en
cambio — este documento explica decisiones, no ejecuta SQL, así que no tiene
checksum que romper.

**La secuencia ingenua no alcanza.** `ALTER COLUMN col TYPE integer USING
col::int` falla con el mismo error que `V5` tuvo que sortear en el sentido
forward ("default for column ... cannot be cast automatically to type
integer") en cualquier columna booleana que conserve su `DEFAULT`. Hace falta
el mismo baile de tres pasos que `V5` ya usa, en reversa: `DROP DEFAULT` ->
`TYPE ... USING ...` -> `SET DEFAULT <literal original de V1>`.

```sql
-- 9 columnas booleanas -> INTEGER, con el DEFAULT original de V1 restaurado
ALTER TABLE productos ALTER COLUMN activo DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN activo TYPE integer USING (activo::int);
ALTER TABLE productos ALTER COLUMN activo SET DEFAULT 1;

ALTER TABLE productos ALTER COLUMN gymrat DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN gymrat TYPE integer USING (gymrat::int);
ALTER TABLE productos ALTER COLUMN gymrat SET DEFAULT 0;

ALTER TABLE productos ALTER COLUMN marca_premium DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN marca_premium TYPE integer USING (marca_premium::int);
ALTER TABLE productos ALTER COLUMN marca_premium SET DEFAULT 0;

ALTER TABLE productos ALTER COLUMN ml_oferta DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN ml_oferta TYPE integer USING (ml_oferta::int);
ALTER TABLE productos ALTER COLUMN ml_oferta SET DEFAULT 0;

ALTER TABLE cron_jobs ALTER COLUMN force_retrain DROP DEFAULT;
ALTER TABLE cron_jobs ALTER COLUMN force_retrain TYPE integer USING (force_retrain::int);
ALTER TABLE cron_jobs ALTER COLUMN force_retrain SET DEFAULT 0;

ALTER TABLE cron_jobs ALTER COLUMN use_gpu DROP DEFAULT;
ALTER TABLE cron_jobs ALTER COLUMN use_gpu TYPE integer USING (use_gpu::int);
ALTER TABLE cron_jobs ALTER COLUMN use_gpu SET DEFAULT 1;

ALTER TABLE cron_jobs ALTER COLUMN enabled DROP DEFAULT;
ALTER TABLE cron_jobs ALTER COLUMN enabled TYPE integer USING (enabled::int);
ALTER TABLE cron_jobs ALTER COLUMN enabled SET DEFAULT 1;

ALTER TABLE outfit_feedback_item ALTER COLUMN liked DROP DEFAULT;
ALTER TABLE outfit_feedback_item ALTER COLUMN liked TYPE integer USING (liked::int);
ALTER TABLE outfit_feedback_item ALTER COLUMN liked SET DEFAULT 0;

ALTER TABLE financiacion_presets ALTER COLUMN activo DROP DEFAULT;
ALTER TABLE financiacion_presets ALTER COLUMN activo TYPE integer USING (activo::int);
ALTER TABLE financiacion_presets ALTER COLUMN activo SET DEFAULT 0;

-- 2 columnas DATE -> TEXT (sin DEFAULT que restaurar, igual que en V1)
ALTER TABLE precio_historico ALTER COLUMN fecha TYPE text USING fecha::text;
ALTER TABLE precios_externos ALTER COLUMN fecha TYPE text USING fecha::text;

-- 2 columnas TIMESTAMPTZ -> TEXT (sin DEFAULT que restaurar, igual que en V1)
ALTER TABLE productos ALTER COLUMN touched_at TYPE text USING touched_at::text;
ALTER TABLE productos ALTER COLUMN created_at TYPE text USING created_at::text;

-- + restaurar sp_upsert_run (cuerpo de V3__manual_classification_lock.sql,
--   verbatim) y sp_soft_delete_ausentes (cuerpo de V1__baseline.sql, verbatim)
-- vía dos CREATE OR REPLACE FUNCTION, en su propia migración forward.
```

Verificado extremo a extremo contra un `postgres:16-alpine` descartable: se
aplicó `V1..V5` real vía Flyway, se corrió esta secuencia completa (los 13
`ALTER COLUMN` más las dos `CREATE OR REPLACE FUNCTION`), y `information_schema.columns`
confirmó que las 13 columnas volvieron exactamente al `data_type` y
`column_default` que `V1__baseline.sql` declara — no una aproximación, los
mismos literales (`1`, `0`, sin default en los 6 columns que nunca lo
tuvieron).

---

### ¿Por qué `talles` y `ml_badge` se fueron a tablas hijas con `posicion`?

**Decisión** (`normalize-db-schema-fks-1nf`, V7): `productos.talles` (un array
JSON serializado dentro de un `TEXT`) y `productos.ml_badge` (un string separado
por comas) se convirtieron en `producto_talle` y `producto_badge`, ambas con la
misma forma: `(url, posicion)` como PK, FK a `productos(url)` con `ON DELETE
CASCADE`, y una sola columna de valor. Las dos columnas viejas se **borraron**.

**Por qué `posicion` y no `PRIMARY KEY (url, badge)`**: `ml_badge` siempre fue
"comma-delimited, principal-first" y `badges().get(0)` **es** el badge principal
—`all_time_low` antes que `below_market`, y así hasta `fake_discount`. Una PK
sin ordinal deduplica en silencio y deja el orden de la lista a merced del plan
de ejecución: la misma consulta puede devolver otro badge principal mañana. Los
talles no tienen una semántica de orden demostrable, pero usar una sola forma
para las dos tablas no cuesta nada (`CODE-6`) y evita dos idiomas de lectura.

**Por qué DELETE + INSERT y no `ON CONFLICT`**: una lista que se **achica** es
el caso que importa. `ON CONFLICT` actualiza las posiciones que llegan y deja
vivas las que sobran — un producto que pasa de `S,M,L` a `S` seguiría ofreciendo
talle L. Que `talles` fuera OVERWRITTEN y no fill-only siempre significó eso;
ahora está escrito como tal en `sp_upsert_run` y en `ProductRepository`.

**Por qué las escrituras van adentro del loop de `sp_upsert_run`**: un rewrite
set-based sobre todo el array `p_rows` es medible­mente más barato, pero
reestructura la función lo suficiente como para que el test de drift
(`StoredProcedureDriftTest`) pierda su propiedad — "todo lo demás es idéntico a
la migración anterior" — justo en la migración más riesgosa del cambio. Ese
rewrite es un follow-up **cerrado sobre un número, no sobre una opinión**
(`CODE-3`): medir el upsert de una corrida completa y abrirlo solo si el costo
agregado supera ~15% del tiempo de DB de la corrida.

**Por qué el backfill incluye los productos inactivos**: `obtenerProducto()`
nunca filtró por `activo`, así que un backfill "solo lo vivo" vaciaba los talles
de todos los descontinuados — 6914 de 13543 filas en la base de desarrollo. La
lectura del catálogo (`cargarProductos`) sigue siendo de **3 sentencias
constantes**: las dos tablas hijas se leen enteras, planas y ordenadas antes del
loop y se mergean por url. Un lookup por producto serían 27086 round trips.

**Riesgo residual asumido**: el guard `talles ~ '^\s*\['` del backfill es un
filtro, no una prueba de validez. Un valor como `[oops` sigue abortando la
migración. Postgres no tiene try-cast, y manejar la excepción fila por fila
cambia una falla ruidosa por un backfill parcial silencioso.

---

### ¿Cómo se revierte `V7` (tablas hijas) si hace falta?

Mismo motivo que con `V5` para que esto viva acá y no dentro del `.sql`: una
migración ya aplicada es **byte-frozen** (Flyway valida checksums; agregarle un
comentario rompe el arranque). La reversión es **lossless justamente porque
existe `posicion`** — sin ordinal habría que inventar un orden al re-agregar.

El bloque de abajo no es prosa: `V7RollbackRoundTripTest` lo lee de este archivo
entre los marcadores `rollback:V7` y lo ejecuta contra el esquema real dentro de
una transacción que siempre se revierte. Si alguien lo edita mal, el test falla.

```sql
-- >>> rollback:V7
ALTER TABLE productos ADD COLUMN talles TEXT;
ALTER TABLE productos ADD COLUMN ml_badge TEXT DEFAULT '';

UPDATE productos p SET talles = COALESCE((
    SELECT json_agg(t.talle ORDER BY t.posicion)::text
    FROM producto_talle t WHERE t.url = p.url
), '[]');

UPDATE productos p SET ml_badge = COALESCE((
    SELECT string_agg(b.badge, ',' ORDER BY b.posicion)
    FROM producto_badge b WHERE b.url = p.url
), '');

DROP TABLE producto_badge;
DROP TABLE producto_talle;
-- <<< rollback:V7
```

Falta, fuera del bloque porque no se puede ejecutar dos veces contra el mismo
esquema de prueba: restaurar `sp_upsert_run` con el cuerpo de
`V5__boolean_and_date_column_types.sql` **verbatim**, vía un `CREATE OR REPLACE
FUNCTION` en su propia migración forward.

---

### ¿Por qué las 19 columnas `*_at` restantes pasaron a `TIMESTAMPTZ`, y por qué eso cambia la API?

**Decisión** (`normalize-db-schema-fks-1nf`, V8): todas las columnas de fecha/hora
que quedaban en `TEXT` pasaron a `TIMESTAMPTZ`. `V5` había retipado solo las dos
que escribe `sp_upsert_run`, porque Postgres no tiene redefinición parcial de
función y cargar el cuerpo entero es la parte cara; estas 19 se escriben desde
sentencias Java comunes, no cuestan ninguna recopia, y por eso viajaron en su
propio slice.

**Lo que rompe del lado de la escritura**: `ps.setString()` contra un parámetro
bindeado a `TIMESTAMPTZ` falla —`column "bloqueado_at" is of type timestamp with
time zone but expression is of type character varying`—, exactamente la misma
clase de falla que `date < character varying` en `V5`. Los nueve repositorios
tenían cada uno su propio `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")`:
nueve copias de una decisión que nadie había tomado una sola vez. Ahora hay un
único seam, `ar.scraper.db.Timestamps`, y los timestamps propios se bindean como
`OffsetDateTime`. Donde el valor entra como `String` por una API pública que no
valía la pena romper (`insertCronExecution`, `touchLastRunAt`, `updateNextRunAt`),
el cast es explícito en el SQL (`?::timestamptz`) — la conversión sigue siendo
visible en el lugar donde ocurre.

**Lo que cambia del lado de la lectura, a propósito**: la API ya no devuelve
`2026-08-11 17:15:00`. Devuelve **ISO-8601 UTC al segundo**,
`2026-08-11T20:15:00Z`. El cambio de payload estaba aceptado; la forma exacta es
una decisión de este slice, y la alternativa era peor: `rs.getString()` sobre un
`TIMESTAMPTZ` renderiza en la zona horaria de la JVM con offset de dos dígitos
(`2026-08-11 17:15:00.936768-03`), o sea filtra la zona del servidor dentro de la
API y no es ISO 8601 válido — `new Date(...)` devuelve `Invalid Date`. Se
descartó `OffsetDateTime.toString()` porque **omite los segundos en cero**
(`2026-08-11T20:15Z`): la forma del payload cambiaría según el dato.

**Consecuencia en el frontend**: había dos supuestos hardcodeados y contrarios
sobre el formato —los campos de cron asumían que el string CONTIENE una `T`
(`.replace('T',' ')`), los outfits guardados que contiene un ESPACIO
(`.replace(' ','T')`)— repartidos en seis lugares. Ahora hay un solo parser,
`frontend/src/lib/fechas.js`, que acepta la forma nueva y también la vieja: una
pestaña abierta durante la migración sigue teniendo payloads previos en memoria,
y "Invalid Date" es peor respuesta que parsear lo que sí se entiende.

**Y un consumidor que el audit de frontend no cubría**: el propio poller.
`CronJobService.parseAsZoned` hacía `LocalDateTime.parse` sobre `next_run_at`
—que ahora vuelve con offset—, así que sin ese arreglo el scheduler dejaba de
disparar todos los jobs. Acepta las dos formas por la misma razón: `computeNextRun`
sigue produciendo una hora LOCAL sin offset, y esa es la que se persiste.

---

### ¿Cómo se revierte `V8` (los `TIMESTAMPTZ` restantes) si hace falta?

Igual que `V5`/`V7`, vive acá y no dentro del `.sql`, porque una migración
aplicada es byte-frozen. El bloque de abajo lo ejecuta
`V8RollbackRoundTripTest` contra el esquema real dentro de una transacción que
siempre revierte.

**Volver a `TEXT` NO devuelve el string original**: `col::text` sobre un
`TIMESTAMPTZ` rinde `2026-08-11 20:15:00+00`, no `2026-08-11 20:15:00`. El
instante se conserva; la forma no. Por eso revertir el esquema sin revertir
además el commit de aplicación deja el código viejo parseando algo que no
espera — el rollback completo son las dos cosas.

> `outfit_feedback` ya no figura en este bloque: `V15` borró esa tabla (estaba
> muerta y era la última violación de 1FN del esquema). Revertir `V8` en una
> base que ya pasó por `V15` no puede retipar una columna que no existe —
> `V8RollbackRoundTripTest` lo cazó apenas se borró la tabla, que es para lo
> que este bloque es ejecutable y no prosa.

```sql
-- >>> rollback:V8
ALTER TABLE productos              ALTER COLUMN bloqueado_at    TYPE text USING bloqueado_at::text;
ALTER TABLE image_embeddings       ALTER COLUMN computed_at     TYPE text USING computed_at::text;
ALTER TABLE ml_output              ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE sitios_dinamicos       ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE categoria_stats        ALTER COLUMN updated_at      TYPE text USING updated_at::text;
ALTER TABLE favoritos              ALTER COLUMN added_at        TYPE text USING added_at::text;
ALTER TABLE favoritos              ALTER COLUMN last_checked_at TYPE text USING last_checked_at::text;
ALTER TABLE outfit_feedback_item   ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE categoria_dismiss      ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE financiacion_presets   ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE saved_outfits          ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE cron_jobs              ALTER COLUMN created_at      TYPE text USING created_at::text;
ALTER TABLE cron_jobs              ALTER COLUMN updated_at      TYPE text USING updated_at::text;
ALTER TABLE cron_jobs              ALTER COLUMN last_run_at     TYPE text USING last_run_at::text;
ALTER TABLE cron_jobs              ALTER COLUMN next_run_at     TYPE text USING next_run_at::text;
ALTER TABLE cron_executions        ALTER COLUMN started_at      TYPE text USING started_at::text;
ALTER TABLE cron_executions        ALTER COLUMN finished_at     TYPE text USING finished_at::text;
ALTER TABLE agent_reclassify_audit ALTER COLUMN applied_at      TYPE text USING applied_at::text;
-- <<< rollback:V8
```

---

### ¿Por qué `cron_jobs.sitios_json` se normalizó y `saved_outfits.slots_json` no?

**Decisión** (`normalize-db-schema-fks-1nf`, V9/V10): las dos eran columnas
`TEXT` con un JSON adentro, y sin embargo recibieron tratamientos opuestos. La
diferencia no es la forma del dato: es **quién lo interpreta**.

`sitios_json` lo parsea el backend y de esa lista salen los sitios que el job
scrapea. Es un grupo repetitivo que el dominio entiende, sobre el que tiene
sentido preguntar "qué jobs scrapean Freres" — hoy imposible sin un `LIKE`.
Va a `cron_job_sitio`, con la misma forma que las hijas de V7.

`slots_json`/`suplementos_json` los serializa el backend desde el cuerpo del
request y los devuelve verbatim; **nunca consulta adentro**. Son documentos del
cliente. Normalizarlos sería inventarle a la base un esquema que sólo el
frontend conoce y que puede cambiar sin que la base tenga voz. Lo único
exigible sin inventar nada es que sean JSON válido: como `TEXT`, un string roto
se guardaba feliz y explotaba al leerlo; como `jsonb`, la base lo rechaza en el
INSERT, que es donde todavía se puede hacer algo.

**La regla que queda**: normalizá lo que el dominio consulta. Un documento
opaco no se normaliza — se valida.

`outfit_feedback` es el tercer caso y tampoco se tocó: sus columnas
`torso_url`/`piernas_url`/`calzado_url` violan 1FN, pero la tabla está muerta
desde que `outfit_feedback_item` la reemplazó (la única referencia viva es un
`DELETE FROM`). Normalizar una tabla muerta es trabajo tirado, y borrarla
destruye historial del usuario: es una decisión de producto, no de esquema.

---

### `V23` — la FK del sitio, sobre la **clave** y no sobre el nombre

La FK que faltaba, y que el design E7 había especificado pero nunca se
implementó — lo encontró el verify, no el review. Sin ella `productos.sitio`
seguía siendo un string suelto contra una tabla que ya es la fuente
autoritativa de `plataforma`, `es_premium` y `rubro_forzado`.

**El design decía `productos.sitio → sitio(nombre)`, y estaba mal.** Se
implementó tal cual y reventó 28 tests con un mensaje que explica el problema
entero:

```
Key (sitio)=(VCP) is not present in table "sitio".
```

`V18` sembró ese sitio como `'Vcp'`. El scraper y los fixtures escriben
`'VCP'` y `'vcp'`. **Los tres son el mismo sitio**: `sitioKey()` los manda a
`'vcp'`. Una FK sobre `nombre` enforcea igualdad de string de display, o sea
sensibilidad a mayúsculas — que no es lo que significa identidad de sitio en
ningún otro lado de este sistema. `SiteClassification.sitioKey()` existe
precisamente porque el display **no** es la identidad, y por eso `V18` lleva
las dos columnas desde el principio. Así que la FK va sobre `sitio_key`, y
`productos.sitio` queda como lo que siempre fue: lo que reportó el scraper,
sin reescribir.

**La columna es generada, no mantenida.** `productos.sitio_key` es
`GENERATED ALWAYS AS ... STORED` con la misma expresión que corre `sitioKey()`
en Java. No hay camino de escritura que actualizarla ni forma de que se
desincronice de `sitio`. Una columna derivada mantenida a mano habría sido una
copia más para desalinear, que es justo el problema que este cambio entero vino
a cerrar. El `nullif(..., '')` hace que un sitio vacío o nulo dé `NULL`, y una
FK ignora los NULL, así que un producto sin sitio no rebota.

**Sola sería una trampa.** Un scrape de un sitio que todavía no está en
`sitio` la violaría dentro de `sp_upsert_run`, y ese error lo atrapa
`ProductRepository`, que loguea y devuelve `UpsertStats(0,0,0,0)`: sale como
`"0 nuevos"`, nunca como error. Por eso la FK viaja junto con un
**get-or-create** en `R__sp_upsert_run.sql` — la fila de `sitio` se crea antes
que el producto que la referencia. `SitioGetOrCreateTest` prueba las dos
mitades y afirma `nuevos()` antes que cualquier columna.

`ON CONFLICT DO NOTHING` **sin target** es deliberado: cubre las dos
restricciones únicas de la tabla (`nombre` PK y `sitio_key`), así que cuando
llega `'VCP'` y la clave `'vcp'` ya existe bajo `'Vcp'`, no inserta nada — y
eso ahora está **bien**, porque la FK mira la clave. Un sitio ya sembrado
tampoco se pisa: Harvey conserva su `es_premium`.

Este fue además el primer cambio declarado contra una migración **repetible**
en vez de una copia versionada nueva, y es lo que el movimiento a `R__`
compraba: editar el archivo puso el salto del drift test en rojo por su cuenta,
exigiendo la declaración.

> El bloque de abajo lo ejecuta `V23RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V23
ALTER TABLE productos DROP CONSTRAINT fk_productos_sitio;
-- <<< rollback:V23
```

Lossless para el esquema. **No** borra las filas `origen='historico'` que el
backfill haya creado: son identidades de sitio reales y observadas, no basura
de la migración, y borrarlas perdería información que nadie más tiene.

---

### Las funciones plpgsql pasan a migraciones repetibles (`R__`)

`sp_upsert_run` llegó a tener **siete copias** —`V1`, `V3`, `V5`, `V7`, `V17`,
`V21`, `V22`— de la misma función de ~90 líneas, y `sp_soft_delete_ausentes`
dos. No fue descuido: Postgres no tiene redefinición parcial de función, así
que cambiar una línea obliga a un `CREATE OR REPLACE` del cuerpo entero; y una
migración Flyway aplicada es byte-frozen, porque Flyway valida checksums. Las
dos reglas juntas hacen que cada cambio de una línea produzca una copia nueva.

El costo real no era el espacio. Cada copia es una oportunidad de introducir
una diferencia que nadie pidió, y ese riesgo es exactamente el que
`StoredProcedureDriftTest` vino a atajar: deshace las sustituciones declaradas
de cada salto y exige que el resultado sea el cuerpo anterior, carácter por
carácter. Funcionó —atajó el caso de `V22`, donde `rg marca_premium` encuentra
sólo dos de los tres sitios— pero era un test defendiéndonos de una duplicación
que no hacía falta tener.

**Una migración repetible (`R__`, sin número de versión) es la herramienta que
Flyway tiene para esto**, y su caso de uso declarado son funciones, vistas y
procedimientos. Dos propiedades la hacen correcta acá:

1. **Las repetibles corren después de todas las versionadas.** No es
   convención ni suerte de ordenamiento alfabético:
   `ResolvedMigrationComparator` ordena cualquier migración con versión antes
   que cualquiera sin versión. Verificado en el log de una corrida real:
   `... to version "22 - drop marca premium"` → `... with repeatable migration
   "sp upsert run"` → `Successfully applied 24 migrations, now at version v22`.
   Así que el cuerpo del `R__` es siempre la última palabra, incluso en una
   instalación desde cero que aplica `V1`..`V22` y crea la función siete veces
   antes de llegar ahí.
2. **Se re-aplica sola cuando cambia su checksum.** Editar el archivo YA es la
   migración; no hay que escribir una versionada nueva que vuelva a copiar
   todo.

Las siete copias históricas quedan donde están —son inmutables por
definición— y sirven como registro de cómo llegó el cuerpo hasta acá.
`StoredProcedureDriftTest` gana dos saltos finales, `V22 → R__` y `V5 → R__`,
con **cero sustituciones declaradas**: un salto sin sustituciones no afloja la
aserción de igualdad, la deja sola, así que exige que el cuerpo del `R__` sea
idéntico al de la última copia versionada. Eso es precisamente lo que hay que
probar al mover una definición: que mover no cambió nada. Se verificó que la
aserción muerde perturbando el archivo a propósito y viendo el rojo antes de
revertir.

De acá en adelante, cuando una de las dos funciones cambie de verdad, ese
salto se pone en rojo y **el diff de git es la declaración del cambio** — que
es como debería haber funcionado desde el principio, en vez de con siete
copias y una tabla de sustituciones.

---

### Decisiones de `V3`, `V4`, `V6`, `V11`, `V13` y `V14`

Vivían en `CLAUDE.md`, que pasó a ser guía/índice. El razonamiento es el que
sigue aplicando cuando aparece un caso parecido.

**`V3` no agrega tablas.** Marca en `productos` la clasificación fijada a mano
para que el pipeline no la pise, y extiende `agent_reclassify_audit`. El guard
es `bloqueado_por IS NULL`, que aparece dentro de `sp_upsert_run` sobre las
cinco columnas que un humano puede corregir.

**`V4`: la política `ON DELETE` se decidió por tabla, no de forma uniforme.**
`precio_historico.url` y `precios_externos.producto_url` en `CASCADE` (VALID —
cero orfandades verificadas en vivo); `favoritos.url` en `RESTRICT`, y
deliberadamente `NOT VALID`: enforcea igual en inserts y deletes nuevos pero no
exige que el historial completo de una instalación existente esté sano.
`agent_reclassify_audit.url` **sin FK** — un audit trail no puede depender de
que el dato mutable siga existiendo. Ese último criterio se repite después en
`saved_outfit_item` (V14) y es el que decide cualquier tabla histórica.
Consecuencia visible: `DELETE /api/db/productos` devuelve **409** con la
cantidad bloqueante si algún favorito referencia un producto vivo, y no hay
`?force=` — decisión explícita para no reabrir el borrado silencioso.

**`V6`: CHECK, no tabla, y el porqué del corte.** Tres CHECK VALID sobre
`productos` (`genero`, `rubro`, `ml_segment`). `NULL` pasa en las tres (ninguna
es `NOT NULL`); el string vacío pasa sólo en `genero`, que es el sentinel de
abstención de `GenderResolver`. El criterio contra `V13`: con **pocos** valores
estables un CHECK alcanza, con **muchos** obliga a una migración por valor
nuevo y ahí gana la tabla de lookup.

**`V11` valida `fk_favoritos_url`, pero sólo si no hay huérfanos.** Un
`VALIDATE` incondicional es exactamente la migración que no se quiso escribir:
la que le rompe el arranque a alguien por datos que la migración misma no puede
borrar sin decidir por él.

**`V13`: clave natural, no `categoria_id`.** El nombre ya es único y estable, y
es lo que devuelve la API — un id sustituto costaría un JOIN por lectura y
plomería de ids por toda la API a cambio de nada. **No lleva columna `rubro`**:
`categoria → rubro` NO es una dependencia funcional, `RubroResolver` deriva el
rubro de (sitio, categoría, rubro previo), y en datos vivos `Conjunto` es
`tecnologia` en Fullh4rd e `indumentaria` en Sporting.

> ⚠️ La FK obligó a corregir **192 literales en 53 archivos de test**: los
> fixtures escribían categorías en plural (`"Remeras"`, `"Buzos"`) que
> producción nunca produce. Dos lugares quedaron en plural a propósito porque
> ahí SÍ es válido: los nombres de producto de `CategoryClassifierTest`
> ("Zapatillas Running Hombre" es un nombre, no una categoría) y
> `FacetCalculatorTest`, que es puro en memoria y no lo alcanza la FK.

**`V14` corrige a `V10`.** `slots_json`/`suplementos_json` **sí eran dato del
dominio**, no documentos opacos: cada elemento traía la `url` del producto —la
misma clave que ya lleva FK en tres tablas— más copias congeladas de su fila.
El argumento que lo decidió: *la estructura de las tablas condiciona al
frontend, no al revés*. Con un blob no se puede preguntar qué outfits contienen
un producto, ni relacionar prendas con un futuro `user_uuid`. Semántica **foto
+ precio actual**: la fila guarda lo que el producto ERA al guardarse, y la
`url` permite traer el precio de HOY por LEFT JOIN. Por eso esa `url` **no
lleva FK**, mismo criterio que el audit trail de `V4`: un producto
discontinuado no puede borrar un outfit del usuario.

---

### Por qué el esquema decía estar en 1FN sin estarlo

Vale la pena dejarlo escrito, porque la afirmación falsa sobrevivió varias
migraciones sin que nadie la cuestionara, y el motivo es una media verdad muy
fácil de repetir.

**1FN pide DOS cosas, no una.** Sin grupos repetitivos, sí — pero también
valores **atómicos** por celda. La primera mitad se venía cerrando con
disciplina: `talles`/`ml_badge` salieron a tablas hijas en `V7`, `sitios_json`
en `V9`, `slots_json`/`suplementos_json` en `V14`, y `V15` borró las cuatro
columnas `*_url` de `outfit_feedback`, que eran un grupo repetitivo desplegado
en columnas, la forma más clásica que hay.

Y ahí se declaró la victoria. Pero la segunda mitad seguía rota:
`categoria_stats.payload` era un `TEXT` con un registro entero serializado
adentro — un valor compuesto en una sola celda. Ningún grupo repetitivo, y aun
así no atómico.

La lección que queda: *"no quedan columnas multivaluadas"* **no** es lo mismo
que *"está en 1FN"*, y confundirlas es cómodo porque los grupos repetitivos son
visibles de un vistazo mientras que un blob serializado parece una columna
normal. `V16` cerró esa segunda mitad, y recién ahí la afirmación se volvió
cierta.

---

### `close-1nf-and-3nf-foundation` (V16-V19): cerrar 1FN de una vez

Cuatro migraciones, una por concern, mismo patrón que V4-V8: la más
riesgosa (V17, re-copia `sp_upsert_run`) queda sola y chica. El orden
V16→V17→V18→V19 **no es load-bearing** — las cuatro son mutuamente
independientes, Flyway se detiene en la primera que falla y una migración
detenida rompe el arranque de Spring de cualquier forma. El orden se
mantuvo porque es el que la propuesta le hizo esperar a quien revisa el PR,
no porque exista una dependencia real entre ellas.

#### `categoria_stats`: de blob JSON a 12 columnas tipadas (V16)

**Decisión**: `categoria_stats.payload` (un `TEXT` con un JSON adentro) pasa
a 12 columnas tipadas — `n` (`INTEGER`, un conteo, nunca redondeado por
Python) y diez campos de plata en `BIGINT` (`round()` sin `ndigits` en
Python devuelve un `int` sin límite, y `fence_high` en una categoría tech
no tiene techo — `INTEGER` no ahorra nada acá) más `cv` en
`DOUBLE PRECISION` (`round(cv, 1)`). Es la misma clase de violación que
`talles`/`ml_badge` (V7) y `sitios_json` (V9), un nivel más adentro: en vez
de una columna con una lista, una columna entera con un registro
estructurado serializado en un TEXT, sin poder tipar ni un campo.

**Sin backfill, tabla vaciada primero.** Cada clave existente es la salida
de `norm_cat` (`"medias"`); `categoria(nombre)` (V13) tiene el canon en
Title Case (`"Medias"`). Una FK sobre las filas actuales rechazaría todas.
La tabla es upsert-only, tope de 20 categorías por corrida, y se
regenera entera en el próximo run de ML — traducir las claves es más
trabajo que borrarlas. **Consecuencia declarada**: `distribucionCategorias`
sale `{}` hasta el próximo run de ML. Se verificó (no se asumió) que los
tres call sites del frontend que leen `catStats?.[...]` ya usan optional
chaining desde antes de este cambio — no hay pantalla que reviente.

**El filtro de claves no canónicas es por-clave, no por-batch.**
`CategoriaStatsRepository.guardarCategoriaStats` descarta contra
`CategoryGroups.canonicalCategories()` **antes** de bindear — nunca llega
a la base una clave inválida — así que una categoría inventada por el
pipeline no puede volver a tirar abajo el batch entero como el upsert
principal ya sabe hacer en silencio (`upsert-swallows-sql-errors`, la
misma clase de bug, una tabla al lado).

**Productor Python** (`ml_pipeline.py`, el loop que arma `cats_precios`):
la clave pasa de `norm_cat((categoria or 'General').strip() or 'General')`
a `(categoria or '').strip() or 'Otros'` — la categoría CANÓNICA, no
`norm_cat`, y el fallback de vacío es `'Otros'` (el bucket de abstención de
V12, que SÍ está en el canon de V13; `'general'` nunca estuvo). `norm_cat`
se mantiene intacto para todo lo demás: sigue siendo la clave de
`grupos_precios`/`elegir_cat`, que es el scoring por producto (percentil,
z-score, badges), un concern distinto. El fallback de ese scoring hacia
`stats_cats.get(cat_nc)` es en la práctica inalcanzable — `stats_grupos`
ya cubre a cualquier producto con la clave que él mismo aportó al
construir `grupos_precios`, con la misma condición de umbral (`>= 20`)
evaluada dos veces sobre el mismo `cat_counts` — así que este cambio de
clave no le pega al scoring, sólo a lo que se persiste.

> El bloque de abajo lo ejecuta `V16RollbackRoundTripTest` contra el
> esquema real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V16
ALTER TABLE categoria_stats DROP CONSTRAINT fk_categoria_stats_categoria;
ALTER TABLE categoria_stats ADD COLUMN payload TEXT;
UPDATE categoria_stats SET payload = json_build_object(
    'n', n, 'mean', mean, 'median', median, 'mode', mode, 'std', std, 'cv', cv,
    'q1', q1, 'q3', q3, 'iqr', iqr, 'mad', mad,
    'fence_low', fence_low, 'fence_high', fence_high)::text;
ALTER TABLE categoria_stats ALTER COLUMN payload SET NOT NULL;
ALTER TABLE categoria_stats
    DROP COLUMN n,   DROP COLUMN mean, DROP COLUMN median, DROP COLUMN mode,
    DROP COLUMN std, DROP COLUMN cv,   DROP COLUMN q1,     DROP COLUMN q3,
    DROP COLUMN iqr, DROP COLUMN mad,  DROP COLUMN fence_low, DROP COLUMN fence_high;
-- <<< rollback:V16
```

Lossless en **forma** (los 12 campos vuelven adentro de un blob JSON); NO
lossless en **vocabulario de clave** — vuelven en canon V13, no en salida
de `norm_cat`. Inocuo: la tabla se regenera entera en la próxima corrida
de ML.

#### `precio_orig`: de TEXT crudo a `double precision` (V17) — la migración riesgosa

**Decisión**: `productos.precio_orig` pasa de `TEXT` (lo que el sitio haya
escrito: `"$ 1.249.999,99"`, `"ARS 45.000"`, cualquier cosa) a
`double precision`. Un valor no interpretable es `NULL`, nunca un string
centinela ni `0` (D1) — esa era la causa real del bug `-666x` en el orden
`% Oferta`: un parser regex del lado SQL confundía el separador de miles con
un decimal (`"$45.000"` → `45.0`) y el `(precio_orig - precio) / precio_orig`
resultante explotaba a un número absurdo.

**Un solo parser canónico, tres verificaciones cruzadas (DD2).**
`ar.scraper.aggregator.text.PrecioParser` (Java, corre en el momento del
scrape — ver el retype de `Product.precioOriginal` más abajo) y
`sp_parse_precio_ar` (SQL, sólo para el backfill de esta migración) implementan
el MISMO contrato de 8 reglas, verbatim de `ml_pipeline.py:354-389`
(`safe_price`, que era la spec de la que se portaron y que después se borró al
quedar sin callers — ver `docs/ML_PIPELINE.md`). Los dos se prueban contra el MISMO
fixture, `scraper/src/test/resources/price-parser-cases.tsv` — `PrecioParserTest`
corre el Java, `V17BackfillParityTest` extrae `sp_parse_precio_ar` del archivo
de migración y lo corre dentro de una transacción que siempre se revierte.
Ninguno de los dos puede quedar verde si sus semánticas divergen.

**`sp_parse_precio_ar` es de un solo uso.** Existe ÚNICAMENTE para el
`ALTER COLUMN ... USING sp_parse_precio_ar(precio_orig)` de este archivo, y se
dropea inmediatamente después. El camino de escritura ONGOING
(`sp_upsert_run`) NO lo llama — para cuando una fila llega al upsert,
`PrecioParser` ya corrió en Java al momento del scrape, así que el JSON que
llega es un número limpio o `null`, y un cast directo alcanza.

**La re-copia de `sp_upsert_run` es de UNA sola línea editada**, no dos como
suponía la propuesta original: el `INSERT ... VALUES` gana
`(r->>'precioOrig')::DOUBLE PRECISION` (antes `r->>'precioOrig'`, texto crudo);
la línea del `ON CONFLICT` (`precio_orig = EXCLUDED.precio_orig`) no necesita
tocarse — `EXCLUDED` ya lleva el valor tipado en cuanto el `INSERT VALUES`
lo castea. `StoredProcedureDriftTest` prueba mecánicamente que esa es la
ÚNICA diferencia entre el cuerpo de V7 y el de V17 (un solo `Substitution`
declarado, sin DB).

**Verificación en dos capas, porque el modo de falla conocido es el silencio.**
Un bind mal tipado alguna vez se manifestó como "0 nuevos" y 78 tests
fallando en otro lado, nunca como un error. Por eso
`SpUpsertRunPrecioOrigRoundTripTest` asegura primero
`UpsertStats.nuevos == 3` (la firma exacta de un upsert que rollbackeó
completo) **antes** de mirar ninguna columna — sólo entonces compara los
tres valores (número / `NULL` / `NULL`). Invertir el orden dejaría pasar un
upsert totalmente fallido como "no hay filas, no hay discrepancia".

**`% Oferta` (D6): `NULLS LAST`, no `ELSE 0.0`.** La expresión vieja
(`CASE ... ELSE 0.0 END`) trataba "no sé" como si fuera "0% de descuento" —
un producto con un descuento NEGATIVO genuino (el precio subió) terminaba
ordenado DESPUÉS de un producto sin ningún dato, porque `0.0 > -0.25`. La
nueva expresión, `(p.precio_orig - p.precio) / p.precio_orig`, propaga `NULL`
sola cuando no hay precio original — sin `CASE`, sin regex — y
`ORDER BY ... DESC NULLS LAST` manda lo desconocido al final SIEMPRE, incluso
detrás de un descuento negativo conocido. `CatalogOrdenTest` lo pinea.

> El bloque de abajo lo ejecuta `V17RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V17
ALTER TABLE productos ALTER COLUMN precio_orig TYPE text
    USING CASE WHEN precio_orig IS NULL THEN NULL
               ELSE to_char(precio_orig, 'FM999999999.99') END;
-- <<< rollback:V17
```

**No lossless, y el doc no lo disimula.** Los strings de origen (`"$ 1.249.999,99"`,
`"ARS 45.000"`) ya no existen — esto devuelve strings numéricos canónicos.
Fuera del bloque (no puede correr dos veces contra el mismo esquema):
restaurar `sp_upsert_run` con el cuerpo de V7 **verbatim** vía un
`CREATE OR REPLACE FUNCTION` en su propia migración hacia adelante — misma
salvedad que el bloque de rollback de V7.

#### `sitio`: tabla de identidad de sitio, sembrada pero leída por nadie (V18)

**Decisión** (DD3): tabla `sitio` con clave natural `nombre` (forma de
display, `"Harvey"`) más una columna separada `sitio_key`
(`SiteClassification.sitioKey()`, `"harvey"`) — son valores genuinamente
distintos y una sola columna recrearía el mismo mismatch que esta tabla
existe para dejar de esconder. `plataforma`/`rubro_forzado`/`origen` van por
`CHECK` (9, 2 y 3 valores — el criterio de V6, no el de V13) en vez de tabla
de lookup.

**Sembrada pero leída por nadie en este slice.** `es_premium`,
`rubro_forzado` y la mitad por nombre de `plataforma` siguen viviendo en
`SiteClassification`/`ScraperFactory` — moverlos es la extracción 3FN que
sigue, no esta migración. Lo que esta migración compra es que el seed ya NO
es una cuarta copia sin verificar: `SitioSeedSyncTest` (classpath, sin DB)
parsea las filas `INSERT` de este archivo y las compara, en las dos
direcciones, contra `SITIOS_PREMIUM`, `TECH_SITIOS`/`SUPPL_SITIOS` (nombres
bare — las variantes con dominio como `compragamer.com` colapsan sobre el
mismo `sitio_key`) y los 8 name-sets de `ScraperFactory.crear` — las cinco
copias que hoy pueden desalinearse sin que nada avise dejan de poder hacerlo
sin romper un build.

**Cero FKs hacia `sitio` (DD4), y por razones distintas según la tabla.**
`productos.sitio`/`favoritos.sitio`/`cron_job_sitio.sitio`: `POST
/api/sitios` escribe `sitios_dinamicos` y `ScraperConfig` lee
`config.properties` — ninguno de los dos hace upsert en `sitio` — así que
una FK acá rompería el primer scrape de un sitio agregado desde el
dashboard, adentro del path de error silenciado de `ProductRepository`
(se leería como "0 nuevos", nunca como un error). `saved_outfit_item.sitio`
no lleva FK **de forma permanente** — mismo criterio que
`agent_reclassify_audit.url`: una foto histórica no puede depender de que
el dato mutable siga vivo.

**El fix de rubro de `foreverbstrd` no es cosmético (DD5).** Sacarlo de
`TECH_SITIOS` no autocura los productos ya persistidos: `RubroResolver.resolver`
cae a `rubroExistente` cuando ningún sitio/categoría fuerza un rubro, y una
fila releída del snapshot de DB (`fromDBParcial`) arrastra el `'tecnologia'`
viejo para siempre. Por eso el `UPDATE` vive en esta misma migración, no
como una optimización aparte.

> El bloque de abajo lo ejecuta `V18RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V18
ALTER TABLE productos DROP CONSTRAINT IF EXISTS fk_productos_sitio;
DROP TABLE sitio;
-- <<< rollback:V18
```

⚠️ **Este bloque dejó de ser trivial cuando llegó `V23`.** Cuando se escribió,
`sitio` no tenía FKs entrantes y un `DROP TABLE` pelado alcanzaba. `V23` le
agregó `fk_productos_sitio`, así que ahora hay que soltar la restricción
primero — `DROP TABLE` falla con *"constraint fk_productos_sitio on table
productos depends on table sitio"*. Se prefiere el `DROP CONSTRAINT` explícito
antes que un `CASCADE`, que soltaría en silencio cualquier otra cosa que
llegue a depender de la tabla más adelante. El `IF EXISTS` deja el bloque
válido también en una base que quedó en `V18` sin llegar a `V23`.

Que esto se haya detectado es mérito de `V18RollbackRoundTripTest`, que
**ejecuta** el bloque en vez de sólo mostrarlo: un rollback documentado que ya
no corre es peor que no documentar ninguno.

Fuera de eso el rollback no pierde comportamiento downstream — sólo la tabla
misma. El `UPDATE` de rubro de `foreverbstrd` NO es reversible desde el
esquema (el valor previo no se guardó) pero sí es re-derivable
determinísticamente: revertir el edit de `TECH_SITIOS` y volver a scrapear.

#### `marca` abstiene en vez de caer al nombre del sitio (V19)

**Decisión** (DD8, declarado como cambio de comportamiento — `CODE-2`):
`BrandExtractor.extraer` devuelve `""` cuando ninguna marca curada matchea,
en vez de devolver `sitio`. Un producto sin marca reconocida no es "de la
tienda que lo vende" — esa era la mentira que `marca="Bullbenny"` en un jean
sin marca real dejaba escrita en el dato.

**El backfill no puede ser `WHERE marca = sitio` a secas.** Tres marcas
curadas SON también nombre de sitio: `Bulks`, `Fuark`, `Harvey` están en
`BrandExtractor.MARCAS` (matcheadas por `\b`, no por igualdad de string) y
son tiendas configuradas en `sitio`. Un producto "Remera Bulks Oversize"
vendido por la tienda Bulks tiene `marca='Bulks'` **legítimamente** — un
`UPDATE` ciego por igualdad la destruiría junto con el fallback real que se
quiere limpiar. Rechazado: portar el `\b` de Java a `\m`/`\M` de Postgres con
los literales de marca escapados — reabre el mismo riesgo de drift que
`PrecioParser`/`sp_parse_precio_ar` existen para cerrar, por un `UPDATE` de
una sola vez. Elegido: `WHERE marca = sitio AND marca NOT IN ('Bulks','Fuark','Harvey')`.
El costo es conservador en la dirección segura — un puñado de `marca` con
nombre de sitio genuino sobrevive en esas tres tiendas y converge en el
próximo scrape, contra destruir dato de marca real.

`MarcasSiteIntersectionTest` (classpath, sin DB) prueba que la intersección
de `MARCAS` con `sitio.nombre` es EXACTA y ÚNICAMENTE esos tres literales —
si una marca nueva o un sitio nuevo agrandara esa intersección, el build se
pone rojo en vez de que la lista de excepción quede desactualizada en
silencio. También resuelve, por construcción, la duda que el artefacto de
tasks había marcado sobre el orden `V18`→`V19`: este test nunca corre Flyway
ni toca una base viva — parsea `V18__sitio_lookup_table.sql` como texto de
classpath, igual que `SitioSeedSyncTest` — así que el orden de aplicación de
las migraciones le es irrelevante.

`MarcasPicksEndpoints`'s tres capas de workaround (`marca==sitio` exacto, un
set hardcodeado de 18 sitios, un mínimo de 2 caracteres) quedan muertas y se
borran: con `BrandExtractor` abstiniendo, `marca` sólo puede ser `""`
(filtrada) o una entrada real de `MARCAS` — nunca un nombre de sitio.

Sin rollback: la regla vieja era literalmente `marca = sitio`, no hay nada
que reconstruir desde el esquema.

#### `sitio` pasa de sembrada-pero-leída-por-nadie a única fuente de `plataforma` (V20)

close-1nf-and-3nf-foundation, extensión 3FN (design E1). `V18` dejó
`sitio.plataforma` sembrada pero leída por nadie (DD3) — `plataforma` seguía
viviendo, en paralelo, en `sitios_dinamicos.plataforma` y en los 8 name-sets
de `ScraperFactory` + `PLATAFORMA_NOMBRES`. `V20` cierra esa duplicación:
`SiteRegistry` (bean único, cargado al boot, refrescado por
`POST`/`DELETE /api/sitios`) pasa a ser el único lector de `sitio`, y los 8
name-sets + `PLATAFORMA_NOMBRES` de `ScraperFactory` junto con
`SiteClassification.TECH_SITIOS`/`SUPPL_SITIOS`/`SITIOS_PREMIUM` se BORRAN,
no se mantienen en paralelo (`CODE-6`).

**Antes de soltar la columna, un `UPDATE` de re-sync, no un `DROP` a secas.**
`V18` sembró `sitio.plataforma` desde `sitios_dinamicos` en el instante en que
corrió esa migración; un sitio agregado desde el dashboard *después* de `V18`
y *antes* de `V20` pudo haber cambiado de plataforma sin que `sitio` se
enterara (nada lo leía todavía, así que nada lo mantenía sincronizado). El
`UPDATE ... FROM sitios_dinamicos` re-absorbe cualquier drift de esa ventana
antes de que la columna deje de existir. `sitios_dinamicos` conserva
`(nombre, url, created_at)` — su único trabajo que queda es "la URL a
scrapear"; `plataforma` se lee ahora vía `SitiosRepository.cargarSitiosDinamicos`
con un `LEFT JOIN sitio` + `COALESCE(..., 'tiendanube')` — el mismo default de
abstención que un nombre no matcheado siempre tuvo, en vez de un `INNER JOIN`
que podría hacer desaparecer una fila de la respuesta.

**El backfill de `rubro_forzado` que NO viaja en `V20` (design E3).** El
`RubroResolver` que hasta acá comparaba por substring
(`sitioKey.contains(token)`) pasa a comparar por igualdad contra
`SiteRegistry.rubroForzado`. La pregunta que importa no es si el código
cambia — es si algún `sitio` real cambia de rubro bajo esa igualdad. Medido
contra la base de dev real (23 filas en `sitio`, TODAS `origen='config'` —
no existe ninguna fila `dinamico`/`historico` en esa base para ejercer el
riesgo hacia adelante que sigue sin medir): **0 filas**. Los cuatro tokens
(`compragamer`, `fullh4rd`, `maximus`, `entreno`) son iguales a su propio
`sitio_key` exactamente; las variantes con dominio
(`compragamer.com`, `fullh4rd.com.ar`, `maximus.com.ar`, `entreno.com.ar`)
eran, y siguen siendo, inalcanzables — `sitioKey()` saca los puntos, así que
sólo podrían matchear un sitio literalmente llamado así, y ninguno existe.
`V20` no manda ningún `UPDATE` de rubro: uno vacío se vería igual a uno que sí
hizo algo, y esa distinción **sí** importa para el próximo que lea el diff.
`RubroResolverEqualityParityTest` (classpath, sin DB) prueba mecánicamente
que la igualdad nueva concuerda con el substring viejo sobre 23 sitios × 81
categorías × 5 rubros previos — 9.315 triples — con el substring viejo
conservado ÚNICAMENTE en el archivo de test, nunca en `main` (`CODE-6`).

**Medido, no estimado (`CODE-3`).** Mismo harness (jshell, JIT calentado con
20 pasadas antes de medir, 500 pasadas × 9.315 combinaciones = 4.657.500
llamadas) antes/después del cambio: el substring viejo (`stream().anyMatch` +
`replaceAll` por elemento del set, por llamada) da **~1.288 ns/llamada**; el
lookup de igualdad nuevo (un `HashMap.get`) da **~139 ns/llamada** — ~9,3x
más rápido. Esperable y no el objetivo del cambio (el objetivo es cerrar el
riesgo de substring, E3), pero se reporta el número real en vez de asumirlo.

**`marcaPremium` también migra de lectura.** `NormalizerService` leía
`SiteClassification.SITIOS_PREMIUM.contains(sitioKey)` directamente; ahora lee
`SiteRegistry.esPremium(sitioKey)` — mismo dato (`harvey` → `true`), un solo
dueño. `productos.marca_premium` en sí **no se toca** en esta migración — eso
es `V22`, condicionado a la medición de `EXPLAIN` de esa fase.

**El write path que DD4 dejaba pendiente queda resuelto en este slice.**
`SitiosRepository.guardarSitio` ahora hace upsert en `sitio` ADEMÁS de
`sitios_dinamicos` — con la `plataforma` recibida validada contra el mismo
dominio cerrado del `CHECK` (si no matchea, cae a `'tiendanube'`, igual que
`V18`'s propio `INSERT ... SELECT` desde `sitios_dinamicos`) — y
`eliminarSitio` marca `sitio.origen='historico'` en vez de borrar la fila
(un sitio retirado sigue siendo un nombre históricamente válido, el mismo
criterio que le dio origen a la columna `origen`). Ambos métodos terminan con
`SiteRegistry.reload()`, así que un alta o baja desde el dashboard es visible
sin reiniciar el proceso.

> El bloque de abajo lo ejecuta `V20RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V20
ALTER TABLE sitios_dinamicos ADD COLUMN plataforma TEXT;
UPDATE sitios_dinamicos d SET plataforma = s.plataforma
FROM sitio s WHERE s.nombre = d.nombre;
UPDATE sitios_dinamicos SET plataforma = 'tiendanube' WHERE plataforma IS NULL;
ALTER TABLE sitios_dinamicos ALTER COLUMN plataforma SET NOT NULL;
-- <<< rollback:V20
```

Lossless para toda fila `dinamico` que siga en `sitio` (la inmensa mayoría):
su `plataforma` se reconstruye exactamente desde la copia que `sitio` ya
tiene. El único caso no perfectamente reversible es una fila `sitios_dinamicos`
cuyo `sitio` correspondiente ya no exista (no debería pasar — `guardarSitio`
escribe las dos en la misma llamada — pero si pasara, el `UPDATE` de default
la deja en `'tiendanube'`, la misma abstención que un nombre nunca visto
siempre obtuvo).

#### `marca` pasa a tabla de lookup con FK, clave natural (V21)

close-1nf-and-3nf-foundation, extensión 3FN (design E4) — el equivalente de
`V13`, pero para marca. `CREATE TABLE marca (nombre TEXT PRIMARY KEY)`,
sembrada con **58 filas** medidas parseando `BrandExtractor.MARCAS`
(`CODE-3` — el design doc había estimado 59 antes de contar; se corrige acá,
no se lo hace coincidir en silencio). Clave natural, no un `marca_id`: el
nombre ya es único, estable, y es lo que la API devuelve — un id sustituto
cuesta un JOIN por lectura y plomería de ids por toda la API a cambio de
nada. Tabla y no `CHECK`: con más de 50 valores es territorio de `V13`, no
de `V6`.

**El sentinel `""`: `NULL` en la DB, `""` en el borde Java — y por qué el
precedente de `V6` no aplica.** `V6` dejó pasar `genero=''` porque un
`CHECK` restringe un VALOR; una FK afirma una REFERENCIA, y no hay marca a
la que referenciar cuando `BrandExtractor` abstiene. Sembrar una fila
`('')` habría convertido el string vacío en una marca más — la faceta de
marca y `/api/marcas-browser` la habrían enumerado. Guardar `NULL` mantiene
la abstención significando exactamente lo mismo que en el resto del
esquema. El radio de impacto es casi nulo porque el código de lectura ya
era null-safe en esta columna: `ProductRowMapper.java:76`
(`marca != null ? marca : ""`), `CatalogQueryRepository.java:141`/`:201` —
ninguno necesitó cambio.

**Desvío del diseño original, descubierto a mitad de la aplicación, no
anticipado.** El diseño planeaba UN solo re-copy de `sp_upsert_run` en `V23`,
juntando las tres sustituciones de esta extensión (E4/E2/E7). Al aplicar
`V21` en TDD, la suite completa se rompió: `BrandExtractor` en Java YA emite
`marca:""` (nunca omite la clave) para un producto abstenido, así que
`COALESCE(r->>'marca', '')` —el `sp_upsert_run` heredado de `V17`— sigue
escribiendo `''`, y `''` no es `NULL`: viola `fk_productos_marca` en el
PRIMER upsert de cualquier producto sin marca reconocida. El razonamiento
original ("las cuatro migraciones aplican juntas en un solo boot de Flyway,
antes de que el proceso pueda aceptar un scrape") es cierto para producción,
pero falso para la suite de tests: decenas de tests existentes hacen upsert
de fixtures con marca abstenida contra el esquema YA migrado a `V21`, en el
mismo commit — exactamente lo que `TEST-1` (suite verde en CADA commit)
exige cubrir. La solución: la sustitución `nullif(r->>'marca','')` se
adelanta a ESTE mismo archivo — un cuarto re-copy de `sp_upsert_run`
(`V17`→`V21`), no el tercero que el diseño había planeado consolidar en
`V23`. `V23` sigue existiendo, pero con sólo dos sustituciones (la baja de
`marca_premium` y el get-or-create de `sitio`), no tres.
`StoredProcedureDriftTest` gana el hop `V17`→`V21` con esta única
sustitución declarada.

**Segundo hallazgo del mismo tipo, misma sesión: `marca` tenía un
`DEFAULT ''` a nivel de columna** (`V1__baseline.sql:47`), invisible al
grep-verify que E4 hizo sobre los sitios de LECTURA. Cualquier `INSERT`
crudo que omite `marca` — y varios fixtures de test lo hacen, porque sólo
fijan las columnas que les importan — caía en `''` por default, no `NULL`,
violando la FK igual que el caso de `sp_upsert_run`. `ALTER TABLE productos
ALTER COLUMN marca DROP DEFAULT` cierra el mismo agujero desde el otro lado:
una columna omitida ahora se comporta exactamente como un `NULL` explícito,
que es la semántica de abstención que la FK ya asume. Medido, no asumido:
9 clases de test (`CatalogSqlEquivalenceTest`, `CheckDomainTest`,
`CategoriaLookupTableTest`, y 6 más bajo `ar.scraper.web`) fallaban con
`fk_productos_marca` antes de este fix — la re-ejecución completa de la
suite después de ambos cambios (el `nullif` de `sp_upsert_run` y el `DROP
DEFAULT`) es la prueba, no una inspección de código.

**Agregar una marca curada sin migración.** Igual que `sitio`, el seed
estático de `V21` sólo existe para que la FK pueda ser VALID al momento de
migrar. `MarcaSeeder` (`ApplicationRunner`, `@Order(HIGHEST_PRECEDENCE)`)
re-siembra `marca` desde `BrandExtractor.MARCAS` en cada boot con
`ON CONFLICT DO NOTHING` — agregar una marca es una línea en `MARCAS`, la
fila aparece en el próximo boot, ninguna migración nueva. `MarcaSeedSyncTest`
(classpath, sin DB) prueba una sola dirección: el seed de `V21` ⊆ `MARCAS`
— nunca la inversa, porque `V21` queda congelado el día que se aplica y
`MARCAS` puede crecer después sin que este test tenga que tocarse.

> El bloque de abajo lo ejecuta `V21RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V21
ALTER TABLE productos DROP CONSTRAINT fk_productos_marca;
ALTER TABLE productos ALTER COLUMN marca SET DEFAULT '';
DROP TABLE marca;
-- <<< rollback:V21
```

Lossless para el esquema (la FK y la tabla vuelven exactamente a como
estaban). NO intenta deshacer el `UPDATE marca = NULL WHERE marca = ''` —
no es reversible (no queda registro de qué fila era `''` contra cuál ya
era `NULL` antes) y tampoco hace falta: el borde de lectura ya trata ambos
casos de forma idéntica (`marca != null ? marca : ""`), así que revertir el
`DROP` no cambia ningún comportamiento observable.

### `V22` — `marca_premium` sale de `productos` (3FN)

`productos.marca_premium` dependía transitivamente de `sitio`, no de la
clave. A pesar del nombre nunca se derivó de la marca: `NormalizerService`
lo calculaba como `SITIOS_PREMIUM.contains(sitioKey)`, así que la
dependencia funcional real era `url → sitio → es_premium`. Eso es la
violación de 3FN de manual, y `sitio.es_premium` (`V18`) ya tiene el valor
autoritativo.

**Por qué se resuelve en Java y no con un JOIN.** El design planteó una
disyuntiva: leer el valor por `LEFT JOIN sitio`, o quedarse con la columna
denormalizada. Las dos se midieron contra el catálogo de dev (6540
productos, `cargarProductos()` como query de gate, 5 rondas intercaladas
para que el ruido de máquina pegue igual en los dos lados):

| Implementación | `cargarProductos()` | vs. baseline |
|---|---|---|
| `LEFT JOIN sitio` | 10,004 ms | **+28,03 %** |
| Resuelto en Java | 7,858 ms | **−3,76 %** |

El umbral de aborto pre-comprometido era 5 %, así que el JOIN quedaba
afuera — pero quedarse con la columna habría sido una denormalización
justificada por un número que sólo valía para **una** forma de leerla.
`marca_premium` no es filtro, ni orden, ni faceta: `CatalogQueryRepository`
no lo menciona y `CatalogoEndpoints` sólo lo emite en el JSON. El tercer
camino no cuesta nada — `SiteRegistry` ya tiene el mapa de sitios en
memoria (≤30 filas, cargado una vez) y `ProductRowMapper` resuelve premium
con un lookup de hash donde ya está armando la fila, **después** de tener
el `ResultSet` y por lo tanto fuera del plan de query.

**La re-copia de `sp_upsert_run` viaja en la MISMA migración.** La función
escribe `marca_premium`, así que dropear la columna la rompe. Postgres
**no** valida cuerpos plpgsql cuando se dropea una columna —son
late-bound— de modo que una migración que dropea sin re-copiar aplica
perfecto y el problema aparece recién en el primer upsert, donde
`ProductRepository` lo traga y devuelve `UpsertStats(0,0,0,0)`: sale como
`"0 nuevos"`, nunca como error. Un scrape entero escribiendo nada, en
silencio. El plan tenía esto como `V22` (drop) + `V23` (re-copia); juntarlas
elimina la ventana en la que una función viva referencia una columna
borrada, y baja la cuenta de re-copias de esta extensión de dos a una.

**Tres sitios cambian, y el tercero es la razón por la que
`StoredProcedureDriftTest` los declara uno por uno en vez de confiar en el
review:** `rg marca_premium` encuentra sólo **dos**. La expresión del
`INSERT VALUES` lee la clave JSON en camelCase, `(r->>'marcaPremium')`, así
que una limpieza guiada por grep saca la columna de la lista y del `SET`,
deja el valor, y produce un `INSERT` con 26 columnas y 27 valores — que,
otra vez, falla en silencio en runtime.

> El bloque de abajo lo ejecuta `V22RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V22
ALTER TABLE productos ADD COLUMN marca_premium BOOLEAN DEFAULT false;
-- <<< rollback:V22
```

Devuelve la columna con exactamente el tipo y el default que tenía tras
`V5`. **No** restaura el cuerpo de `sp_upsert_run`, igual que el rollback de
`V21` tampoco lo hace: la convención acá es revertir el *esquema*, no la
función. En la práctica no cambia nada observable, porque después de `V22`
ningún camino de lectura consulta esa columna — `marcaPremium` sale de
`SiteRegistry`, así que una columna revivida y nunca escrita queda en
`false` sin que nadie la mire.

Tampoco restaura `sp_upsert_run` a su forma de `V17` — mismo precedente que
`V17`'s propio bloque: una función no se puede recrear dos veces contra el
mismo esquema dentro de un round-trip de test. Fuera del bloque: restaurar
`COALESCE(r->>'marca', '')` requiere un `CREATE OR REPLACE FUNCTION` con el
cuerpo de `V17` verbatim, en su propia migración hacia adelante.

---

## Non-goals de `close-1nf-and-3nf-foundation` (explícitos, con motivo)

Los tres primeros items de esta lista **dejaron de ser non-goals**: el usuario
decidió plegar el trabajo de 3FN dentro del mismo PR, así que `V20` hizo el
swap de `RubroResolver` a igualdad, `V21` le dio a `marca` su tabla de lookup y
`V22` sacó `marca_premium` de `productos`. Quedan dos, y los dos son "se queda
así, y acá está el motivo", no "no llegamos".

| Item | Se queda como está porque |
|---|---|
| `ml_output.payload` | Set de claves dinámico por corrida (scores, clusters de tendencia) — no tiene una forma fija que tipar; los badges ya se normalizaron a `producto_badge` desde `V7`; se poda a 10 filas, nunca se consulta adentro y siempre se lee entero. Es un log de corrida, no dato de dominio: falla el test de `V14` en vez de pasarlo |
| `precios_externos` | Sus columnas `sitio`/`titulo`/`precio`/`condicion` dependen de `externo_url`, no del `id` sustituto — es una dependencia transitiva real. Pero cada fila es una **captura fechada** de una publicación de MercadoLibre: el título y el precio de esa URL cambian, y la fila registra lo que ERA el día que se comparó. Normalizarla a una tabla de publicaciones destruiría exactamente el dato que se guarda. Mismo carve-out que `saved_outfit_item` (V14) y `agent_reclassify_audit` (V4): un registro histórico no se normaliza contra una entidad mutable |

**El único blob que sobrevive en el esquema es `ml_output.payload`.** Los
`jsonb` de `saved_outfits` no cuentan: `V14` los borró (`slots_json` y
`suplementos_json`, ambos `DROP COLUMN`).

`close-1nf-and-3nf-foundation` empezó siendo una base y terminó cerrando
también la extracción — pero 3FN sigue sin estar "completa" en el sentido
formal, y `precios_externos` es la razón, documentada arriba a propósito para
que la próxima sesión no la trate como un olvido.

---

### ¿Por qué `/api/data` filtra en SQL y el resto del catálogo no?

**Decisión** (`sql-catalog-filtering`): `/api/data` y `/api/facets` consultan la
base; `/api/grupos`, `/api/mejores`, los armadores de outfits, el feed y el
agente siguen leyendo el snapshot en memoria.

El corte no es arbitrario. `/api/data` **filtra y pagina**: es exactamente lo
que un motor relacional hace mejor que un `stream()`, y sus dos filtros más
caros —`talle` y `badge`— eran justamente los que no se podían expresar en SQL
hasta que V7 sacó esas listas a tablas hijas. Los otros consumidores hacen otra
cosa: `/api/grupos` agrupa por similitud Jaccard sobre nombres normalizados
(no es un `GROUP BY`), y los armadores corren MCKP con branch-and-bound sobre el
catálogo entero. Esos algoritmos quieren el set completo en memoria por diseño;
pasarlos por SQL no compra nada y arriesga bastante.

**Lo que sí cambia de contrato**: el dashboard ya no devuelve 204 sobre 13543
productos sólo porque en esta sesión nadie scrapeó. `senal` y
`senalFinanciacion` no se persisten —se calculan— y pasaron de calcularse para
todo el catálogo en la agregación a calcularse para los ~24 productos de la
página: menos trabajo, no más. Los errores por sitio siguen saliendo del
snapshot, porque describen la última corrida y no el catálogo.

**Cómo se hizo sin romper nada**: cada filtro corre dos veces sobre el mismo
dataset —una por los predicados en memoria originales, copiados al test como
oráculo, y otra por SQL— y se exige el mismo set de URLs. El oráculo cubre los
FILTROS, no el orden, que es lo que permitió arreglar de paso dos bugs viejos
sin que el propio oráculo los defendiera: `orden=ml_score` mostraba los peores
scores primero, y `orden=desc_pct` filtraba adentro del comparador, así que
cambiar el orden cambiaba el total y la cantidad de páginas.

---

### ¿Por qué el aggregator está modularizado en collaborators de responsabilidad única?

**Decisión**: `ar.scraper.aggregator` se organiza como orquestadores delgados (`NormalizerService`, `GroupingService`, `ResultAggregator`) que secuencian collaborators de responsabilidad única, agrupados en subpaquetes por tema (`normalize/`, `grouping/`, `text/`), más `FacetCalculator` como utility estática en la raíz del paquete.

**Razón**: antes de esta modularización, `NormalizerService` (categoría, talles, género, marca, pack/combo, subcategoría, rubro, gymrat) y `ResultAggregator` (validación, dedup, pipeline ML, persistencia, facets) eran clases monolíticas: cada regla de negocio nueva crecía el mismo archivo y era imposible testear una regla sin arrastrar todas las demás. La modularización es **behavior-preserving** — cero cambios observables end-to-end, con la suite existente pasando sin editar un solo test. El historial slice por slice se retiró de `docs/` una vez completada; queda en el historial de git.

**Estructura resultante**:

| Paquete | Responsabilidad | Clases |
|---------|------------------|--------|
| `aggregator` (raíz) | Orquestación de la agregación completa + utility de facets | `ResultAggregator` (orquestador: validar → dedup → pipeline ML → persistir → facets), `FacetCalculator` (cálculo puro y estático de facets) |
| `aggregator.normalize` | Normalización de un `Product`, orquestada por `NormalizerService` | `PackQuantityDetector`, `CategoryClassifier`, `BrandExtractor`, `GenderResolver`, `SizeNormalizer`, `SubcategoryResolver`, `RubroResolver`, `GymratTagger` + holders estáticos de datos/predicados: `GarmentTaxonomy`, `CategoryGroups`, `SiteClassification`, `NonTextileGuard` |
| `aggregator.grouping` | Agrupación de productos equivalentes entre sitios, orquestada por `GroupingService` | `ProductIdentity`, `JaccardSimilarity`, `ProductGroup` |
| `aggregator.text` | Utilidades de texto compartidas entre `normalize` y `grouping` | `AccentStripper` |

**Patrones aplicados**:
- **Orquestadores puros**: `NormalizerService.normalizarProducto` y `ResultAggregator.agregar` son el único lugar donde se reconstruye el record `Product` o se arma el `AggregatedResult` — secuencian sus collaborators (inyectados por constructor) y no contienen lógica de negocio propia. Ningún collaborator conoce a los demás.
- **Holders estáticos de datos/predicados**: `GarmentTaxonomy`, `CategoryGroups`, `SiteClassification` y `NonTextileGuard` no tienen estado ni dependencias — se consumen vía static import dentro de los collaborators que los necesitan, en vez de inyectarse como beans adicionales en `NormalizerService`.
- **`FacetCalculator` como utility estática, no bean**: a diferencia de los collaborators de `normalize`/`grouping` (todos `@Component`), `FacetCalculator` es `final` con constructor privado y un único método estático — refleja que el cálculo de facets no tiene estado ni dependencias. `ResultAggregator.calcularFacets` se mantiene como delegate público porque ~10 tests fuera del paquete (`ar.scraper.web`) construyen fixtures de `AggregatedResult` contra esa firma exacta.
- **Test factory para tests de orquestación**: `NormalizerService` requiere 8 collaborators por constructor, así que los tests que ejercitan la normalización end-to-end usan `NormalizerServiceTestFactory.create()` (solo en `src/test/java`) en lugar de instanciar los 8 collaborators a mano en cada test.

---

### ¿Por qué React + Vite en el frontend?

**Decisión**: SPA en React 18 + Vite 5, servida como su **propio servicio** (no más static resource embebido en el JAR).

**Nota histórica**: este documento describía el frontend como "HTML/JS vanilla servido como static resource desde Spring Boot" — eso dejó de ser preciso mucho antes de `decouple-services-postgres` (el frontend ya era React/Vite, buildeando a `scraper/src/main/resources/static/`). El swap de `decouple-services-postgres` (Batch 3, design D6) es un cambio distinto y posterior: dejar de embeber el build de Vite en el JAR — el backend ahora es API-only (`SpaController` removido) y el frontend corre como servicio independiente, hablándole al backend por CORS (`APP_CORS_ALLOWED_ORIGINS`) usando `VITE_API_BASE_URL` como base de sus fetches (`frontend/src/api.js`).

---

### ¿Por qué el LLM vive fuera del backend y no puede escribir solo?

**Decisión**: el modelo corre en un proceso aparte (Ollama por defecto) al que Java le habla por HTTP detrás de la costura `ChatProvider`; el agente tiene tres herramientas, **todas de solo lectura**, y la única escritura real ocurre fuera de su loop, tras confirmación humana explícita y con re-validación server-side.

**Por qué**: embeber un runtime de inferencia en el JAR ataría el proyecto a un modelo y a un backend de hardware. Como proceso externo, el LLM es una dependencia **opcional**: si no responde, solo falla el chat del agente. Y como el loop autónomo no puede escribir, el tool-calling poco confiable de un modelo local de 14B degrada, en el peor caso, a *una propuesta rechazable* — nunca a una escritura corrupta.

📄 **Detalle completo en [`LLM_EMBED.md`](./LLM_EMBED.md)**: topología de la integración, la costura `ChatProvider` y su único adapter, el loop acotado (`MAX_ITERATIONS`), las tres herramientas, y las **ocho reglas** que gobiernan al agente — empezando por la Regla 0, que el system prompt es guía y no un control de seguridad. Para instalar y configurar, ver [`LLM_AGENT_SETUP.md`](./LLM_AGENT_SETUP.md).

---

### ¿Por qué los armadores de outfits puntúan con pesos y nunca con filtros?

**Decisión**: hay dos armadores con objetivos distintos —`OutfitService.armar` (aleatorio ponderado, superficie Gym) y `OutfitBudgetBuilder` (MCKP con branch-and-bound, superficie de presupuesto)— y **toda** señal que incorporan (oportunidad ML, likes del usuario, coherencia visual) entra como multiplicador acotado, nunca como descarte.

**Por qué dos armadores**: son dos preguntas distintas. "Mostrame un outfit" quiere variedad entre recargas, así que muestrea; "armame el mejor outfit con $X" quiere el óptimo global bajo una restricción dura, que es literalmente un Multi-Choice Knapsack. Un solo algoritmo haría mal las dos.

**Por qué pesos y no filtros**: el catálogo argentino es chico y desparejo por categoría. Un filtro convierte "este short combina mal" en un **slot vacío**, y un outfit incompleto es peor producto que uno un poco ruidoso. Un peso degrada el candidato malo y lo deja alcanzable.

**Por qué el neutro se ancla en 1.0**: cada factor se normaliza contra el valor que produce una señal ausente — `mlFactor` divide por `baseMlScore(MlScore.EMPTY)`, la coherencia devuelve 1.0 cuando no hay atributos. Así, un catálogo sin datos de ML o sin clasificación visual produce **exactamente** los pesos previos. Es lo que permite agregar una señal nueva sin invalidar la suite existente como red de regresión.

**Por qué la coherencia visual no rompe el óptimo del MCKP**: la penalización se aplica como **resta de un monto no-negativo** al score del candidato, así que `aporte ≤ score` siempre y la cota superior del branch-and-bound —construida con scores sin penalizar— sigue siendo válida. Ninguna rama óptima se poda. Además cada par de slots se evalúa exactamente una vez, cuando se asigna el segundo de los dos, así que el total no depende del orden en que el solver recorre los slots.

**Por qué la regla de color es una rueda de tonos y no una tabla de pares**: una lista escrita a mano de "estos colores combinan" es un conjunto de opiniones que nadie puede revisar y que crece cada vez que alguien discrepa con una entrada. Un orden circular de tonos con un umbral de distancia es una estructura chequeable y un solo parámetro que tunear. Los neutros no tienen posición en la rueda — por eso combinan con todo, sin necesidad de enumerarlo.

**Por qué un atributo visual vacío no penaliza**: vienen de un clasificador zero-shot que se abstiene cuando duda, así que buena parte del catálogo no los tiene. Una regla que castigara el dato faltante no estaría coordinando outfits: estaría degradando en silencio a todo producto que el clasificador salteó.

---

## Diagrama de capas y topología de servicios

**Topología (decouple-services-postgres, Batch 3, design D6)**: 3 servicios independientes, cada uno arrancable solo con env vars — ninguno requiere que los otros estén corriendo para bootear (spec "Independent Service Startup").

```
┌───────────────────────────┐        ┌───────────────────────────┐
│   Frontend (Vite/React)   │  CORS  │   Backend (Spring Boot)    │
│   VITE_API_BASE_URL ──────┼───────►│   APP_CORS_ALLOWED_ORIGINS │
│   propio proceso/puerto   │  fetch │   API-only (sin SPA)       │
└───────────────────────────┘        └──────────────┬──────────────┘
                                                      │ lanza subprocess
                                      ┌───────────────▼───────────────┐
                                      │  Python ML (subprocess)        │
                                      │  DATABASE_URL (psycopg2 DSN,   │
                                      │  traducido desde el jdbc: de   │
                                      │  Java por toPsycopgDsn)         │
                                      │  SCRAPER_MODELS_ROOT / HF_HOME │
                                      └───────────────┬───────────────┘
                                                      │
                                      ┌───────────────▼───────────────┐
                                      │      PostgreSQL (DATABASE_URL) │
                                      │  Flyway V1__baseline.sql +     │
                                      │  sp_upsert_run/                │
                                      │  sp_soft_delete_ausentes       │
                                      └────────────────────────────────┘
```

Capas internas del backend (sin cambios de forma, solo el datasource):

```
┌───────────────────▼─────────────────────┐
│         ApiController.java              │  Spring MVC (+ CorsConfig)
├─────────────────────────────────────────┤
│         ScraperService.java             │  Orquestación async
├──────────────┬──────────────────────────┤
│  Scrapers    │  ResultAggregator        │  Scraping + merge +
│  *Page.java  │  (aggregator.normalize/  │  normalizar + agrupar
│              │   .grouping/.text)       │
├──────────────┴──────────────────────────┤
│         DatabaseService.java            │  PostgreSQL (HikariCP pool),
│                                          │  write-path via plpgsql
├─────────────────────────────────────────┤
│   PythonRunner → ml_pipeline.py         │  ML subprocess (psycopg2)
└─────────────────────────────────────────┘
```

---

### Launcher: CLI nativo (`native-cli-installer` 2026-07-25 + `cli-command-console` 2026-08-05)

Supersede el launcher `menu.ps1`/`menu.sh` (`interactive-cli-launcher`, PR
#108) — ambos scripts, y sus tests (`tests/menu.Tests.ps1`/
`tests/menu_test.sh`), fueron **retirados** (borrados).

**El seam se movió:** antes, `INSTALAR_Y_CORRER.bat`/`Ejecutar_instalar.sh`
compilaban el proyecto (`npm install`/`npm run build`, `mvn clean package`),
generaban `.env` con un bloque `echo`/`cat` hardcodeado, y en su tail
invocaban `menu.ps1`/`menu.sh`. Ahora:

- Los installers **solo aprovisionan el toolchain**: JDK, Maven, Node, el
  Python 3.11 embeddable + deps ML (torch/scikit-learn/Marqo, sin cambios),
  PostgreSQL portable, y — nuevo — `uv` + un `_tools/cli-venv` dedicado.
- El **CLI nativo** (`cli/`, Python) posee todo lo que antes hacía el
  installer post-toolchain: build (`npm`+`mvn` vía las rutas vendorizadas
  en `_tools/`), generación/reconciliación de `.env` (template-driven desde
  `.env.example` — crea si falta, nunca pisa valores existentes salvo
  `--regenerate`/`--force`), y la orquestación de backend (`:3000`) +
  frontend (`npm run preview` en `:5173`), incluyendo el mismo teardown
  limpio en `Q`/`Ctrl+C` y la carga JVM `-DDATABASE_PASSWORD=<valor, incluso
  vacío>` que evita el bug de Windows con variables de entorno vacías.
- **Invariante (bloqueado por diseño):** el installer nunca compila el
  proyecto; el CLI nunca descarga ni instala un componente del toolchain
  bajo `_tools/`.

**Arquitectura del CLI** (headless core + presentadores, no una app Textual
monolítica):

```
cli/
├── __main__.py        # entry point: detección de capacidad + routing
├── core/               # HEADLESS — cero imports de textual/rich, testeable con pytest
│   ├── config.py        #   repo-root, paths de _tools/, puertos
│   ├── env_file.py       #   .env template-driven (crea/reconcilia/--force)
│   ├── builder.py        #   npm+mvn, ordenamiento de VITE_API_BASE_URL
│   ├── rest.py            #   cliente REST de la API existente (json.dumps, sin shell)
│   ├── processes.py       #   lifecycle backend+frontend + teardown funnel
│   ├── commands.py        #   registro ÚNICO de verbos (autocompletado + help + menú plano)
│   ├── logs.py            #   archivos de log de servicios + tail acotado + scrub de ANSI
│   └── errors.py          #   excepciones tipadas con mensaje accionable
├── tui/                 # PRESENTADOR — Textual App, solo presentación
└── plain/                # FALLBACK — driver de texto plano sobre el MISMO core
```

`__main__.py` decide UNA vez, antes de construir cualquier presentador, si
la terminal soporta la TUI interactiva (`isatty`, `NO_COLOR`, `TERM=dumb`,
`--plain`, `cmd.exe` legacy sin ANSI, o Textual no instalable) — si no,
degrada al runner de texto plano sobre el mismo `core/`. Nunca crashea.

**Consola por comandos, no menú (`cli-command-console`, 2026-08-05):** la TUI
es una franja de health de una línea + consola + prompt — tres filas de chrome,
corre en 60×18 sin maximizar. Las operaciones se tipean; el vocabulario vive
en `core/commands.py`, del que salen el autocompletado, el `help` y el menú del
runner plano, así que no pueden divergir. Se eliminaron los bindings de una
sola letra (se comían caracteres tipeables); quedan `ctrl+c` y `ctrl+l`.

**Por qué el stdio de los hijos está atado (el bug de fondo):** Spring Boot y
Vite escribían ANSI crudo sobre el mismo TTY que Textual estaba pintando, y el
frame quedaba destruido — Textual no se entera, así que ningún repaint lo
arregla. Parecía un problema de layout y era de stdio. Hoy los **tres** streams
de todo hijo están atados: stdout+stderr a `scraper/logs/{backend,frontend}.log`,
stdin a `DEVNULL` (si no, el hijo compite por las teclas del prompt). `PIPE` no
es opción: nadie lo drena y el hijo se cuelga cuando se llena el buffer. La
salida se lee con el comando `logs`, cuyo tail **escapa las secuencias de
control** antes de renderizarlas — una línea de log de un sitio scrapeado es
input no confiable y podría inyectar escapes en la terminal.

**Aislamiento del venv del CLI (`_tools/cli-venv`):** construido por `uv`
sobre un **CPython 3.11.9 administrado por uv** (`uv python install` +
`uv venv --managed-python`), **NO** sobre el Python embeddable de ML
(`_tools/python`). El CLI nunca importa librerías ML, así que este venv no
comparte nada con el pipeline de imágenes y no arriesga el guard de versión
de `torch` que el bloque ML del `.bat` ya protege. Esto también elimina por
construcción el riesgo de bootstrap que tendría reusar el embeddable (su
`python311._pth` congela `sys.path` de una forma que podría filtrarse a un
venv construido encima). `import textual` es el acceptance check del
installer — si falla tras el aprovisionamiento, el install aborta con un
mensaje accionable (Python es ahora load-bearing en Windows, igual que ya
lo era en POSIX).

**Invocación:** `_tools/cli-venv/{Scripts/python.exe,bin/python} -m cli`,
corrido con cwd = raíz del repo — **no** `cli/__main__.py` como ruta de
script directa, que falla con `ModuleNotFoundError: No module named 'cli'`
porque los módulos del CLI usan imports absolutos `cli.*` que solo resuelven
cuando la raíz del repo está en `sys.path` (el caso de `-m cli`, no el de
invocar el archivo directamente).

**Tests:** `tests/cli/` (pytest) — unit tests del `core/` headless, tests
`Pilot` de Textual para el `tui/`, tests de degradación del routing, un
test de **injection-safety** (`json.dumps` con input hostil `a"b;$(x)`
round-tripea como un único campo JSON bien formado, sin invocar shell) que
reemplaza estructuralmente a los viejos `tests/menu.Tests.ps1`/
`tests/menu_test.sh`, y un test con un **subproceso real** que escupe
stdout/stderr y verifica vía `capfd` que ni un byte llega a nuestra terminal.

---

### ¿Por qué Allure declarativo para el reporte de tests?

**Decisión**: reporte declarativo Allure sobre TODA la suite de tests backend (66 clases / 556 `@Test`), como **capa de reporte pura** — sin tocar assertions ni lógica de test. Anotaciones `@Epic`/`@Feature`/`@Story`/`@DisplayName` a nivel clase, `@Step` locales privados extraídos del setup/arrange ya existente, y `Allure.parameter(...)` en tests de boundary.

**Wiring clave (`scraper/pom.xml`)**:
- `allure-bom` 2.29.1 gestiona la versión de `allure-junit5`; `aspectjweaver` 1.9.24 es el javaagent que captura los `@Step` en runtime. Ambas son `scope=test` — NO entran al fat JAR (`spring-boot:repackage` corre en `package`, después de `test`).
- surefire usa `<argLine>@{argLine} -javaagent:"...aspectjweaver..."</argLine>` con **late-binding `@{...}`** (NO `${argLine}`). Esto es lo más frágil de todo el wiring: `jacoco:prepare-agent` (fase `initialize`) escribe su propio javaagent en la property Maven `argLine`; con `${argLine}` (interpolación *eager* en parse-time del POM) esa property todavía está vacía y JaCoCo **deja de recolectar cobertura silenciosamente** mientras los tests siguen pasando. `@{...}` es expansión *tardía*: surefire la resuelve en fase `test`, ya con la property poblada, concatenando el agente de JaCoCo + el de AspectJ.
- `allure-maven` 2.15.2 wirea `mvn allure:serve` / `allure:report`, pero el render HTML es **opcional**: el entregable CI-crítico es solo `target/allure-results/*.json`.

**`@Step` locales, no un god-class compartido**: cada `@Step` se extrae del ARRANGE de su propia clase → cada slice queda auto-contenido y revertible (fue clave para entregarlo como cadena de PRs encadenados sin conflictos cruzados). El único helper compartido, `testsupport/AllureSteps.java`, se reserva para pasos genuinamente cross-cutting (hoy solo `toJson`).

**CLI bundleado en el toolchain**: `INSTALAR_Y_CORRER.bat` baja el Allure CLI a `_tools/allure` y lo agrega al PATH de la sesión (mismo patrón que `jdk21`/`maven`/`node`; descarga no-fatal — si falla, la app igual corre). Flujo de uso:
```
mvn -f scraper/pom.xml test        REM genera target/allure-results/
allure serve scraper/target/allure-results
```

**Trade-off**: la versión del CLI (`allure-commandline` 2.29.0) se versiona **aparte** de las libs Java (`allure-bom` 2.29.1) — no existe un `allure-commandline` 2.29.1; el formato de `allure-results` es estable entre versiones de CLI, así que la diferencia es inocua. La coexistencia del `-javaagent` de AspectJ con el inline-mock-maker de Mockito 5 se verificó explícitamente en PR0.
