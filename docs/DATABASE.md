# Base de datos — referencia

> **Este archivo es el dueño de todo lo que se sabe de la base**: esquema,
> migraciones, semántica del upsert, estado de normalización y el *porqué* de
> cada decisión, con su SQL de rollback.
>
> El reparto entre los documentos es deliberado:
> [`CLAUDE.md`](../CLAUDE.md) = guía · [`CONTRIBUTING.md`](../CONTRIBUTING.md) = proceso ·
> [`ARCHITECTURE.md`](./ARCHITECTURE.md) = por qué del resto del sistema, e
> **índice** hacia acá para todo lo de base · este archivo = la base.
>
> Los bloques `-- >>> rollback:VN` de acá abajo **los ejecutan los tests**
> (`V*RollbackRoundTripTest`, vía `DocumentedRollback`). No son ilustrativos:
> si el SQL de un bloque deja de correr, la suite se pone roja. Ese es todo el
> motivo por el que viven en un documento y no adentro del `.sql`.
>
> ⚠️ Los headers de `V16`, `V22`, `V23` y `V24` siguen diciendo que su rollback
> está en `docs/ARCHITECTURE.md`. **Es la ruta vieja, y se queda así**: una
> migración aplicada es byte-frozen y corregir el comentario rompería
> `flyway validate`. Este archivo es el dueño; ese puntero es historia.

---

## Regla de admisión: toda tabla nueva cumple 1FN y 3FN

**No es una aspiración, es una precondición.** Una tabla que no está en 1FN y
3FN no entra al esquema; se rediseña antes de escribir la migración.

**1FN pide DOS cosas, no una** — y ese matiz es el que hizo que este esquema
se declarara normalizado durante varias migraciones sin estarlo:

1. Sin grupos repetitivos (nada de `talles`/`ml_badge` como lista en una celda).
2. **Valores atómicos por celda** — un registro entero serializado en una sola
   columna falla 1FN aunque no haya ninguna lista a la vista. Fue exactamente
   el caso de `categoria_stats.payload`, que pasó desapercibido porque sólo se
   miraba la primera mitad de la regla.

**3FN**: ningún atributo no-clave depende de otro atributo no-clave. El caso
canónico acá fue `productos.marca_premium` (`url → sitio → es_premium`), que
`V22` sacó de la tabla.

Tres criterios que ya están decididos y no se re-discuten en cada tabla nueva:

| Pregunta | Criterio |
|---|---|
| ¿Tabla de lookup o CHECK? | CHECK para un vocabulario cerrado y chico que no lleva atributos propios; tabla de lookup cuando el valor **tiene** atributos (`sitio`, `categoria`, `marca`) o cuando se administra desde la app |
| ¿Lleva FK? | Sí, salvo que la fila sea un **registro histórico**: un registro de lo que pasó nunca depende de que el dato mutable siga existiendo (`saved_outfit_item`, `agent_reclassify_audit`, `precios_externos`) |
| ¿Cómo se dice "no hay valor"? | Con `NULL` o `""`, nunca con un centinela. `0` no es "no se pudo parsear" |

La única excepción viva es `ml_output.payload`, y está argumentada abajo en
**Non-goals**: es un log de corridas, no dato del dominio.
## Esquema

```
productos            -- Catálogo canónico (upsert por URL; cols ML, rubro, gymrat, pack, visual attrs)
producto_talle       -- Talles por producto, ordenados (url+posicion PK) (V7)
producto_badge       -- Badges ML por producto, principal primero (url+posicion PK) (V7)
cron_job_sitio       -- Sitios de cada cronjob, ordenados (job_id+posicion PK) (V9)
precio_historico     -- Precio por fecha (UNIQUE url+fecha)
ml_output            -- Último output JSON del pipeline
image_embeddings     -- Cache de embeddings Marqo (url PK, bytea, model_version)
categoria_stats      -- Stats de precio por categoría, 12 columnas tipadas + FK a categoria (V16)
sitios_dinamicos     -- Sitios agregados desde el dashboard
sitio                -- Identidad de sitio: plataforma, es_premium, rubro_forzado (V18, leída por SiteRegistry desde V20)
favoritos            -- Productos guardados
precios_externos     -- Comparativas MercadoLibre
outfit_feedback_item -- Likes/dislikes por ítem (la tabla legacy por-outfit se borró en V15)
saved_outfits        -- Outfits persistidos
categoria_dismiss    -- Categorías "no me interesa" del feed
financiacion_presets -- Presets de cuotas/recargo
cron_jobs / cron_executions -- Scraping programado + historial
agent_reclassify_audit      -- Auditoría de reclasificaciones humanas (V2)
```

### Migraciones

Flyway, en `scraper/src/main/resources/db/migration/`. **Una migración aplicada
es byte-frozen**: Flyway valida checksums y hasta agregar un comentario rompe
`flyway validate`. Por eso el SQL de rollback vive **en este archivo**, más
abajo, donde además lo **ejecutan** los `V*RollbackRoundTripTest` (vía
`DocumentedRollback`) para que el documento no pueda desincronizarse.

| | Qué hace |
|---|---|
| `V1` | Baseline: 15 tablas + `sp_upsert_run`/`sp_soft_delete_ausentes` |
| `V2` | `agent_reclassify_audit` |
| `V3` | Lock de clasificación manual |
| `V4` | FKs hacia `productos(url)`, política `ON DELETE` por tabla |
| `V5` | 8 columnas INTEGER-boolean → `BOOLEAN`; 2 fechas → `DATE` |
| `V6` | Tres CHECK de dominio sobre `genero`/`rubro`/`ml_segment` |
| `V7` | `talles` y `ml_badge` → `producto_talle` / `producto_badge` |
| `V8` | Las 19 columnas `*_at` restantes → `TIMESTAMPTZ` |
| `V9` | `cron_jobs.sitios_json` → `cron_job_sitio` |
| `V10` | `saved_outfits.*_json` → `jsonb` (revertido por `V14`) |
| `V11` | Valida `fk_favoritos_url`, sólo si no hay huérfanos |
| `V12` | Cierra el vocabulario de `categoria`, con bucket `Otros` |
| `V13` | Tabla `categoria(nombre PK)` + FK, clave natural |
| `V14` | `saved_outfit_item`: los blobs SÍ eran dato del dominio |
| `V15` | Borra `outfit_feedback` (legacy) |
| `V16` | `categoria_stats.payload` → 12 columnas tipadas + FK |
| `V17` | `precio_orig` → `double precision`, tres parsers colapsan en uno |
| `V18` | Tabla `sitio` (identidad de sitio), sembrada |
| `V19` | `BrandExtractor` abstiene en vez de caer al nombre del sitio |
| `V20` | `sitio` pasa a ser la fuente de `plataforma`; `RubroResolver` por igualdad |
| `V21` | Tabla `marca` + FK, clave natural |
| `V22` | Dropea `productos.marca_premium` (3FN) |
| `V23` | `productos.sitio_key` (generada) + FK a `sitio(sitio_key)` |
| `V24` | `sitio.plataforma` 9→11 valores (`qloud`, `oscommerce`) + seed Rockethard/Venex |
| `V25` | `productos.producto_key` (generada) + índice único — handle corto para rutas |
| `R__sp_upsert_run` | **La** definición de la función. Repetible: se edita acá |
| `R__sp_soft_delete_ausentes` | Ídem |

> ⚠️ **Las dos funciones plpgsql se editan en su archivo `R__`, y en ningún
> otro lado.** No agregues una migración versionada para tocarlas. Flyway
> re-aplica una repetible cuando cambia su checksum, y las corre después de
> todas las versionadas. Las copias históricas en `V1`/`V3`/`V5`/`V7`/`V17`/
> `V21`/`V22` son inmutables y quedan sólo como registro.

**El porqué de cada una está más abajo, en *Decisiones y su justificación*.**
Los criterios que se repiten —cuándo tabla de lookup y cuándo CHECK, qué lleva
FK y qué no, cómo se dice que un valor no está— están arriba, en **Regla de
admisión**, porque aplican a toda tabla nueva y no sólo a las que ya existen.

### Estado normal

El esquema está en **1FN** y **2FN**. 3FN está parcialmente alcanzada: `V22`
cerró `marca_premium`, que era la violación más filosa (`url → sitio →
es_premium`), y `V23` le puso integridad referencial al sitio. Queda
`ml_output.payload` como **único** blob del esquema, deliberado: es un log de
corridas —se poda a 10 filas, nunca se consulta adentro, siempre se lee
entero— no dato del dominio.

> El matiz de 1FN que hizo falsa la afirmación anterior durante varias
> migraciones está arriba, en **Regla de admisión**. El desarrollo completo
> está más abajo, en *Por qué el esquema decía estar en 1FN sin estarlo*.

**Upsert:** URL nueva → INSERT + historial · precio igual → `touched_at` ·
precio cambió → UPDATE + historial · ausente en el run → soft-delete
(`activo=false`) · **vuelve tras un soft-delete → se reactiva y nada más**: se
lo trata por su precio, como a cualquier fila existente, no como a una URL
nueva. Corre **server-side** en `sp_upsert_run`/`sp_soft_delete_ausentes`. La
concurrencia la resuelve Postgres MVCC: no hay locks de aplicación.

⚠️ **`precio_historico` registra cambios de precio, no avistajes.** Por eso
`sp_upsert_run` lee el precio previo **sin** filtrar por `activo` y usa `FOUND`
para separar "no existe" de "existe pero está inactivo". Con el filtro puesto,
la fila inactiva no matcheaba, el precio previo salía `NULL` —indistinguible de
una URL nunca vista— y la reactivación escribía un punto de historial aunque el
precio no se hubiera movido. El `UNIQUE(url, fecha)` tapaba el síntoma mientras
el producto volviera el mismo día; recién se veía cuando volvía días después,
que es el caso real.

⚠️ **El soft-delete está acotado a los sitios del batch, y esa cota no es
opcional.** `sp_soft_delete_ausentes` recibe `p_sitios` y sólo desactiva dentro
de esos sitios. "Ausente" únicamente significa algo dentro de un sitio que se
miró: para uno que no se miró no hay evidencia de nada, y `activo=false` es una
afirmación, no la falta de una. Sin esa cota, scrapear un rubro solo daba por
desaparecido el catálogo entero — pasó de verdad (2026-08-15): un run de solo
tecnología desactivó 5806 productos de 19 sitios no visitados, Sporting de 1860
a 0. **El alcance nunca sale de la lista de sitios pedidos**: un sitio cuyo
scraper se rompió llega con 0 productos, y "se rompió" no es "se vació" — para
eso está `SiteYieldGuard`. Un batch vacío no desactiva nada.

**De dónde sale el alcance, desde `scrape-run-persistence-and-resume` (D4).**
Con una corrida persistida, los dos arrays salen de una sola consulta dentro de
la transacción del upsert, después de `sp_upsert_run`:

```sql
SELECT url, sitio FROM productos WHERE touched_at >= <scrape_run.started_at>
```

Eso es "todo lo que esta corrida vio", y abarca las dos mitades de un resume
porque `upsertParcial` va commiteando cada sitio a medida que termina. Sin
corrida —un llamador sin `started_at`— el alcance vuelve a derivarse del batch,
que es el comportamiento previo exacto.

⚠️ **Los dos arrays se ensanchan juntos o ninguno.** Ensanchar `p_sitios` a
todos los sitios de la corrida dejando `p_urls` con las URLs de una sola mitad
haría que *todos* los productos de los demás sitios parezcan ausentes: los
desactiva **enteros**, estrictamente peor que el bug que se quería arreglar. Por
eso `ProductRepository` los devuelve en un único `record Alcance` y no como dos
búsquedas separadas — el error deja de ser representable en vez de quedar
desaconsejado en un comentario.

**La cota es inclusiva (`>=`) a propósito.** `touched_at` se escribe con formato
de segundo entero (`"yyyy-MM-dd HH:mm:ss"`) y `ScrapeRunRepository.crear`
trunca `started_at` al segundo para que coincida. Las filas escritas durante el
primer segundo de la corrida comparan iguales y quedan **dentro**. Con una cota
exclusiva —o con un `started_at` con precisión sub-segundo— esas filas se leerían
como ausentes y se soft-deletearían productos que la corrida acababa de escribir.
Es la dirección opuesta a la cota del lector (`touched_at < started_at`), y la
asimetría es deliberada: el barrido tiene que proteger de más, el lector puede
mostrar una fila fresca de más.

`R__sp_soft_delete_ausentes.sql` **no se tocó**: su contrato ya era correcto,
lo que era demasiado angosto era la noción de "esta corrida" del llamador.

⚠️ **El upsert se traga los errores SQL**: `ProductRepository` loguea y
devuelve `UpsertStats(0,0,0,0)`, que sale como `"0 nuevos"` y nunca como error.
Todo test de round-trip afirma `nuevos()` **antes** que cualquier valor de
columna, porque `0` es la firma exacta de un fallo tragado.

### Lecturas

**`/api/data` y `/api/facets` consultan SQL** desde `sql-catalog-filtering`:
los 18 filtros, el orden y la paginación son `WHERE`/`ORDER BY`/`LIMIT`,
`talle` y `badge` salen por `EXISTS` contra las tablas hijas, y las facetas son
un `GROUP BY` cada una. `senal` y `finan` no se persisten: se calculan sobre
los ~24 productos de la página. El resto de las superficies (`/api/grupos`,
`/api/mejores`, outfits, recomendados, agente) lee el snapshot en memoria.

---

## Decisiones y su justificación

### ¿Por qué PostgreSQL y no SQLite/H2?

**Decisión** (decouple-services-postgres, Batch 1, design D1-D3): `postgresql` JDBC + HikariCP + Flyway, reemplazando `sqlite-jdbc`.

**Historia**: el proyecto arrancó con SQLite (un archivo `scraper.db`, cero configuración, visible/transferible) por su simplicidad para un usuario único en Windows. Esa elección tuvo un costo real: SQLite es single-writer, y a medida que se agregaron cronjobs + API + scraping concurrente, apareció `SQLITE_BUSY_SNAPSHOT` (escrituras solapadas pisándose commits) que se parcheó con un lock-dance de aplicación (`writeLock`/`readLock`/`refrescarSnapshot()` + `readConn` dedicada) — una solución cada vez más frágil para un problema que SQLite no está diseñado para resolver.

**Razón del swap**: Postgres da concurrencia real vía MVCC — múltiples escritores/lectores sin locks de aplicación. El write-path (upsert + historial + soft-delete) se movió a funciones `plpgsql` server-side (`sp_upsert_run`/`sp_soft_delete_ausentes`, design D2) para que la decisión "¿cambió el precio?" ocurra DENTRO de una sola sentencia SQL, eliminando la carrera de "leer precio actual → decidir → escribir" entre callers concurrentes. `UNIQUE(url, fecha)` + `ON CONFLICT DO NOTHING` hace el insert en `precio_historico` idempotente incluso con escritores solapados.

**Trade-off**: ya no hay un archivo único portable — Postgres corre como proceso (portátil bajo `_tools/pgsql`, provisionado por el installer, o un Postgres externo vía `DATABASE_URL`). A cambio, las migraciones son versionadas (Flyway `V1__baseline.sql`), no `ALTER TABLE` manual, y el problema de concurrencia queda resuelto estructuralmente en vez de parchado.

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

### `V24` — el dominio de `plataforma` pasa de 9 a 11 valores (`qloud`, `oscommerce`)

`fix-zero-yield-tech-sites`: Rockethard y Venex scrapeaban 0 productos porque
nunca estuvieron registrados — sin fila en `config.properties`, sin fila en
`sitio` (el seed de `V18` es de 23 filas, ninguna de las dos). El mismo
criterio de `V6` aplica de nuevo: `sitio.plataforma` es un dominio cerrado y
chico (9 valores antes de esto), así que sigue siendo `CHECK`, no una tabla —
`V13` sólo se justifica pasado el orden de las decenas.

**Por qué migración nueva y no editar `V18`.** `V18` está aplicada y
Flyway-checksummeada: hasta un comentario agregado ahí rompe `flyway
validate`. La única forma de ampliar un `CHECK` ya aplicado es un `DROP
CONSTRAINT` + `ADD CONSTRAINT` en una migración versionada nueva.

**El nombre del constraint se confirmó contra el esquema real antes de
escribir esto** (`pg_constraint`/`information_schema.table_constraints`), no
se asumió: el `CHECK` inline y sin nombre de `V18` lo auto-nombró Postgres
`sitio_plataforma_check`. Se mantiene ese nombre — renombrarlo haría que el
rollback de abajo dejara de ser literal.

**Sólo dos valores, no tres.** La exploración original consideraba también
`logg` para un tercer sitio (Logg). Su fuente de hidratación (grid renderizado
por JS, sin endpoint identificado) nunca se aisló — ver "Bloqueo conocido:
Logg queda fuera de `fix-zero-yield-tech-sites`" más abajo — así que no hay
`V25` en este cambio y el dominio no lleva `logg`. Sembrar una plataforma sin
scraper sería exactamente el bug que este cambio existe para cerrar: un sitio
registrado que scrapea 0 en silencio.

Las dos filas de seed llevan `rubro_forzado='tecnologia'`, mismo criterio que
`maximus`/`fullh4rd`/`compragamer` en `V18`.

> El bloque de abajo lo ejecuta `V24RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte. El orden importa —
> primero las filas de seed, después el `CHECK` — y el test lo prueba:
> revertir en el otro orden dejaría, por un instante, un `CHECK` más angosto
> que datos que todavía lo violan.

```sql
-- >>> rollback:V24
DELETE FROM sitio s WHERE s.plataforma IN ('qloud','oscommerce')
  AND NOT EXISTS (SELECT 1 FROM productos p WHERE p.sitio_key = s.sitio_key);
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer'));
-- <<< rollback:V24
```

El `NOT EXISTS` existe porque `V23` le puso una FK a `productos.sitio_key ->
sitio(sitio_key)`: si ya corrió un scrape de Rockethard o Venex, borrar su
fila de `sitio` rompería esa referencia. Consecuencia honesta, documentada en
vez de escondida: **antes** del primer scrape el rollback es lossless;
**después**, retirar el sitio es `origen='historico'` (soft), no angostar el
dominio.

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
ALTER TABLE productos       DROP CONSTRAINT IF EXISTS fk_productos_sitio;
ALTER TABLE scrape_run_site DROP CONSTRAINT IF EXISTS fk_scrape_run_site_sitio;
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

Y **volvió a dejar de ser trivial con `V29`**, que le agregó a `sitio` su
segunda FK entrante (`fk_scrape_run_site_sitio`, desde `scrape_run_site`). El
patrón se repite y conviene nombrarlo: **un rollback documentado no es
estable, es una función del esquema que exista cuando corra.** Cada migración
nueva que apunte a una tabla puede romper el rollback de la migración que la
creó, y el rojo aparece en el test de la vieja, no en el de la nueva — que es
justo donde nadie lo está buscando. Por eso `V29` bautiza su restricción en vez
de dejar que Postgres la nombre: este bloque la suelta por nombre, y un nombre
autogenerado metería un detalle de implementación del motor adentro de un
documento que un test ejecuta.

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

---

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

### `V25` — `producto_key`, un handle corto para direccionar un producto

**Decisión**: `productos` gana una columna `producto_key TEXT GENERATED ALWAYS
AS (substr(md5(url), 1, 16)) STORED`, con índice único.

**Qué problema resuelve, y cuál no.** La vista de historial se direccionaba con
la URL entera como query param — ilegible, hay que encodearla en cada borde, y
mete el dominio del sitio scrapeado adentro de nuestra propia ruta.

Esto **no** es una clave primaria nueva y **no** cambia la normalización.
`productos.url` sigue siendo la PK. `url → producto_key` es un atributo
no-clave dependiendo de la clave, que es exactamente lo que 3FN permite; no hay
dependencia transitiva. Es un alias de presentación con un índice atrás.

Vale aclararlo porque la intuición habitual es la contraria: que "traer por id"
está más normalizado. No lo está. Las formas normales hablan de dependencias
funcionales, no del tipo de dato de la clave, y `url` ya era clave candidata —
única, `NOT NULL`, con todo lo demás dependiendo de ella. Un `id BIGSERIAL` no
habría movido ni una forma normal; habría agregado una segunda clave candidata
y obligado a reescribir las 5 FKs, `sp_upsert_run` y el frontend entero.

**Por qué generada.** `GENERATED ALWAYS AS ... STORED` significa que no hay
camino de escritura que actualizarla ni forma de desincronizarla de `url` —
mismo argumento que `V23` ya hizo para `sitio_key`. `md5()` y `substr()` son
IMMUTABLE, que es lo que una columna generada exige; `sha256()` habría
necesitado castear a `bytea`, y ese cast no lo es.

No es un uso criptográfico: es un identificador opaco, y se le pide ser
determinístico y estar bien distribuido, nada más.

**Por qué 16 caracteres y por qué UNIQUE.** 16 hex = 64 bits. Con n productos,
P(existe alguna colisión) ≈ n²/2^65: con 100.000 da ~2,7e-10, y el catálogo
real ronda los 10.000. El `UNIQUE` no baja esa probabilidad — elige **dónde**
falla si alguna vez ocurre. Con él, una colisión rompe fuerte y temprano, al
aplicar la migración sobre los datos existentes. Sin él, dos productos
compartirían handle y la vista mostraría el producto equivocado en silencio.
Entre un fallo ruidoso improbable y un dato incorrecto callado, el ruidoso.

**La misma expresión corre en Java** (`ar.scraper.web.ProductKey.of`), porque
el frontend necesita el handle sin ir a la base. Que no puedan divergir lo
prueba `ProductKeyParityTest` contra un Postgres real, sobre un corpus de URLs
con no-ASCII, espacios, `%` ya encodeados, comillas y emoji — mismo criterio
que `PrecioParser` / `sp_parse_precio_ar`.

```sql
-- >>> rollback:V25
DROP INDEX IF EXISTS idx_productos_producto_key;
ALTER TABLE productos DROP COLUMN IF EXISTS producto_key;
-- <<< rollback:V25
```

El rollback es total: la columna es derivada, así que soltarla no pierde ningún
dato que `url` no tenga ya.

---

### `V26` — cuentas, roles, tokens y el dueño de los datos personales

**Decisión**: cinco tablas de identidad (`usuario`, `rol`, `usuario_rol`,
`refresh_token`, `password_reset_token`) y una columna `usuario_id` **nullable**
en las cuatro tablas de datos personales (`favoritos`, `saved_outfits`,
`outfit_feedback_item`, `categoria_dismiss`).

**Esta migración no cierra ninguna puerta.** Al terminar la fase 1 no existe
ningún `SecurityFilterChain` y todos los endpoints siguen tan abiertos como
hoy. Sin estas tablas no hay a quién autenticar, pero tenerlas no protege nada
por sí solo.

**Por qué el dueño es nullable.** Hay un orden que no se puede invertir: Flyway
corre `V26` durante el arranque y la cuenta admin se siembra después, en un
`ApplicationRunner` — la única etapa garantizada tras el refresh completo del
contexto. En el momento en que la columna se crea todavía no existe ninguna
fila de usuario a la que las favoritos existentes puedan pertenecer. De ahí
salen las dos consecuencias que más chocan al leer el esquema:

1. `usuario_id` es nullable porque no hay id que poner.
2. La clave compuesta de `favoritos` viaja como `UNIQUE (usuario_id, url)`,
   **no como PRIMARY KEY**: una PK prohíbe NULLs. Promoverla a PK compuesta
   real, cuando toda fila ya tenga dueño, es una migración posterior.

La adopción de las filas huérfanas la hace el seeder en código, en la misma
transacción en que confirma la cuenta admin. Por eso el archivo no contiene ni
una credencial ni un id literal — y hay un test que lo verifica sobre el texto
del `.sql`, no sobre la base: una migración aplicada queda congelada byte a
byte, así que un secreto escrito ahí no se puede sacar nunca más, y este
repositorio es público.

**El índice parcial no es decoración.** `favoritos.url` era PRIMARY KEY, así que
la misma url no se podía guardar dos veces. Al soltar esa PK,
`UNIQUE (usuario_id, url)` **no** alcanza para conservar la garantía: en SQL dos
NULL son distintos entre sí, y mientras la aplicación siga escribiendo
`usuario_id = NULL` los dos inserts de la misma url pasarían. Sería una
regresión silenciosa introducida por la migración que se suponía aditiva.
`uq_fav_unowned_url` conserva la garantía exactamente durante la ventana en que
se puede violar. `NULLS NOT DISTINCT` de Postgres 15 lo diría en una cláusula,
pero la versión del Postgres portable de `_tools/pgsql` no está fijada.

**Consecuencia para todo upsert contra `favoritos`**: `ON CONFLICT (url)` no
infiere un índice parcial solo. La cláusula tiene que repetir el
`WHERE usuario_id IS NULL` del índice, o Postgres rechaza la sentencia entera —
el primer insert incluido, no sólo el que conflictúa. `FavoritosRepository`
loguea y se traga la excepción, así que ese error se ve como "no hay favoritos",
no como un error.

**Requisito de versión**: `gen_random_uuid()` es núcleo desde Postgres 13. No se
usa `pgcrypto` para no depender de una extensión que puede pedir superusuario.

```sql
-- >>> rollback:V26
-- 1. `favoritos` vuelve a su clave natural. La de-duplicación va PRIMERO y no
--    es opcional: con dos dueños sobre la misma url, restaurar
--    PRIMARY KEY (url) es imposible sin elegir qué fila sobrevive. Se conserva
--    la más vieja.
DELETE FROM favoritos f
      USING favoritos otra
      WHERE f.url = otra.url AND f.id > otra.id;

DROP INDEX IF EXISTS uq_fav_unowned_url;
ALTER TABLE favoritos DROP CONSTRAINT IF EXISTS uq_fav_owner_url;
ALTER TABLE favoritos DROP COLUMN IF EXISTS id;
ALTER TABLE favoritos ADD PRIMARY KEY (url);

-- 2. Las columnas de dueño y sus índices.
DROP INDEX IF EXISTS idx_fav_usuario;
DROP INDEX IF EXISTS idx_saved_outfits_usuario;
DROP INDEX IF EXISTS idx_ofi_usuario;
DROP INDEX IF EXISTS idx_catdismiss_usuario;
ALTER TABLE favoritos            DROP COLUMN IF EXISTS usuario_id;
ALTER TABLE saved_outfits        DROP COLUMN IF EXISTS usuario_id;
ALTER TABLE outfit_feedback_item DROP COLUMN IF EXISTS usuario_id;
ALTER TABLE categoria_dismiss    DROP COLUMN IF EXISTS usuario_id;

-- 3. Identidad.
DROP TABLE IF EXISTS password_reset_token;
DROP TABLE IF EXISTS refresh_token;
-- `V29` le agregó a `usuario` una FK entrante desde `scrape_run`. Se suelta
-- por nombre, no con CASCADE: un CASCADE acá arrastraría en silencio lo que
-- llegue a depender de la tabla más adelante.
ALTER TABLE scrape_run DROP CONSTRAINT IF EXISTS fk_scrape_run_usuario;

DROP TABLE IF EXISTS usuario_rol;
DROP TABLE IF EXISTS usuario;
DROP TABLE IF EXISTS rol;
-- <<< rollback:V26
```

**El rollback no es total, y la parte que pierde datos es el primer bloque.**
Las cinco tablas de identidad se sueltan enteras; las columnas de dueño también.
Lo que no se puede deshacer es la de-duplicación: si dos personas llegaron a
marcar el mismo producto, volver a `PRIMARY KEY (url)` exige quedarse con una
sola fila. Por eso este es el primer rollback de este cambio que hay que
**ejecutar** antes de creerle — lo corre `V26RollbackRoundTripTest` contra un
Postgres real, sobre un dataset que incluye ese caso.
### `V27` — `oficina` entra al dominio de `rubro`, e `inpro` al de `plataforma`

`add-inpro-office-store`: INPRO (`inpro.ar`) vende sillas ergonómicas,
standing desks, brazos de monitor e iluminación de escritorio. Ninguna de esas
cosas es indumentaria, ni tecnología, ni suplemento, y forzarla a uno de los
tres sería precisamente lo que `V6` vino a impedir — un valor de dominio que
miente sobre el producto. Así que el dominio se abre a un cuarto rubro en vez
de reetiquetar el catálogo.

Tres `CHECK` se amplían acá, y siguen siendo `CHECK` y no tabla de lookup por
el criterio de `V6`/`V18`/`V24`: dominios chicos y cerrados (3, 2 y 11 valores
antes de esto). La inversión de `V13` —81 valores, pasa a tabla— sigue sin
aplicar.

| Constraint | Antes | Después |
|---|---|---|
| `chk_productos_rubro_domain` | 3 valores | `+ 'oficina'` |
| `sitio_rubro_forzado_check` | 2 valores | `+ 'oficina'` |
| `sitio_plataforma_check` | 11 valores | `+ 'inpro'` |

**Los tres nombres se confirmaron contra un Postgres real antes de escribir la
migración** (`pg_constraint`), no se asumieron — mismo criterio que `V24`.
`V6` nombra el suyo explícitamente; los dos de `V18` son `CHECK` inline y sin
nombre, y Postgres los auto-nombró `sitio_plataforma_check` y
`sitio_rubro_forzado_check`. Se mantienen esos nombres: renombrarlos haría que
el bloque de rollback de abajo dejara de ser literal.

**Por qué `inpro` es una plataforma propia y no `tiendanube`.** Los datos que
sirve INPRO *son* los objetos crudos de la API de Tiendanube —`variants[]`,
`compare_at_price`, `promotional_price`, `stock`, `sku`, imágenes en
`acdn-us.mitiendanube.com`— pero la vidriera no lo es: es un Next.js propio
hosteado en Vercel, y el storefront clásico no es alcanzable
(`inpro.mitiendanube.com` redirige a **otra** tienda, `inproindumentaria.com.ar`;
los slugs candidatos dan 410). Sembrarlo como `tiendanube` lo rutearía a
`TiendanubeScraper`, que iría a buscar un DOM que en `inpro.ar` no existe: 0
productos, en silencio. Es exactamente el bug que `V24` cerró para Rockethard y
Venex, y la razón por la que la plataforma es un dato del sitio y no una
heurística de URL.

**Por qué `V27` y no `V26`.** La rama `feature/user-accounts-and-roles`, abierta
en paralelo, ya tiene su propia `V26`. Este cambio sale de `master`, donde la
última migración es `V25`, así que `V26` queda **reservada** para esa rama.
El hueco es inocuo —Flyway aplica por orden de versión y tolera faltantes— pero
el orden de merge no lo es: si este cambio entra primero, la `V26` de la otra
rama queda *out-of-order* y `validateOnMigrate` la rechaza. **Mergear este
cambio después de `user-accounts-and-roles`, o renumerar.**

> El bloque de abajo lo ejecuta `V27RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte. El orden importa y
> el test lo prueba: primero la fila de seed, después los tres `CHECK` —
> revertir en el otro orden dejaría, por un instante, un `CHECK` más angosto
> que datos que todavía lo violan.

```sql
-- >>> rollback:V27
DELETE FROM sitio s WHERE s.plataforma = 'inpro'
  AND NOT EXISTS (SELECT 1 FROM productos p WHERE p.sitio_key = s.sitio_key);
UPDATE productos SET rubro = NULL WHERE rubro = 'oficina';
UPDATE productos SET categoria = NULL WHERE categoria IN
    ('Silla','Escritorio','Soporte Monitor','Soporte Laptop',
     'Iluminación','Mat Escritorio','Organización');
DELETE FROM categoria WHERE nombre IN
    ('Silla','Escritorio','Soporte Monitor','Soporte Laptop',
     'Iluminación','Mat Escritorio','Organización');
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce'));
ALTER TABLE sitio DROP CONSTRAINT sitio_rubro_forzado_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_rubro_forzado_check
    CHECK (rubro_forzado IN ('tecnologia','suplementos'));
ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos'));
-- <<< rollback:V27
```

**El rollback sólo aplica antes del primer scrape, y eso está probado, no
prometido.** El `NOT EXISTS` del `DELETE` existe por la FK que `V23` le puso a
`productos.sitio_key`: con productos de INPRO vivos protege la fila de `sitio`
— y entonces angostar `sitio_plataforma_check` choca contra esa misma fila y el
bloque **falla entero**. `V27RollbackRoundTripTest` tiene un test por cada uno
de los dos estados: round-trip limpio sin productos, y fallo ruidoso con
productos. Pasado el primer scrape, retirar el sitio es `origen='historico'`
(soft), no angostar el dominio — igual que en `V24`.

El `UPDATE ... SET rubro = NULL` cubre el otro caso, el que sí sobrevive al
`DELETE`: un producto de **otro** sitio que quedó en `rubro='oficina'` por una
reclasificación manual del agente LLM. Degradarlo a `NULL` —la abstención que
el propio dominio ya admite— lo deja pasar el `CHECK` angostado sin borrar la
fila, a costa de la clasificación.

---

### `V28` — el dominio de `plataforma` suma `morashop`

Morashop es un Tiendanube genuino: el extractor compartido lee sus cards sin un
solo cambio. El valor propio de plataforma no está por la extracción sino por el
**ruteo**. Desde `V20` `ScraperFactory` elige la clase leyendo
`sitio.plataforma` vía `SiteRegistry`, y los name-sets en código se borraron
(`CODE-6`). Morashop necesita page propia —descubre las categorías hoja en
runtime, porque la tienda no tiene URL de catálogo: su `/productos/` es una
landing del tema con cero productos— y rutear eso por clave de sitio
reintroduciría exactamente lo que `V20` sacó. Mismo criterio que `monkyforce` y
que `inpro` en `V27`.

`rubro_forzado='suplementos'` como Entreno, el único otro sitio del rubro. No se
toca `chk_productos_rubro_domain` ni `sitio_rubro_forzado_check`: `suplementos`
ya es válido en los dos, y re-listarlos les borraría el `oficina` que agregó
`V27`.

**El dominio pasa de 12 a 13**, no de 11 a 12: esta migración se escribió contra
un baseline sin `V27` y se rebaseó sobre el dominio ya mergeado al aterrizar.
Es lo que su header pedía — rebasear la lista, nunca renumerar, porque todas
estas migraciones hacen `DROP` + `ADD` del dominio completo y la colisión es de
contenido.

> El bloque de abajo lo ejecuta `V28RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte. El orden importa —
> primero la fila de seed, después el `CHECK`— por lo mismo que en `V24` y
> `V27`: al revés dejaría, por un instante, un `CHECK` más angosto que datos
> que todavía lo violan. Y **restaura los doce de `V27`, no los once viejos**:
> un rollback que devolviera el dominio de antes de `V27` borraría `inpro` de
> abajo de una fila viva.

```sql
-- >>> rollback:V28
DELETE FROM sitio s WHERE s.plataforma = 'morashop'
  AND NOT EXISTS (SELECT 1 FROM productos p WHERE p.sitio_key = s.sitio_key);
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro'));
-- <<< rollback:V28
```

El `NOT EXISTS` es el mismo de `V24` y `V27`, por la FK que `V23` le puso a
`productos.sitio_key -> sitio(sitio_key)`.

**Los rollbacks componen al revés, y ya van dos veces que eso obliga a tocar un
test ajeno.** `V27` tuvo que editar el de `V24`; `V28` tuvo que editar los dos.
Hoy `V24RollbackRoundTripTest` ejecuta `V28` → `V27` → `V24`, de más nuevo a más
viejo, porque cada bloque encuentra vivas las filas que sembraron los de arriba.
Editar un test ajeno es legítimo bajo una regla precisa, y conviene tenerla
escrita para que no se degrade en "editá lo que esté rojo": **una aserción de
dominio cerrado puede ir de `n` a `n+1` si toda aserción de comportamiento
alrededor queda idéntica.** El olor inverso —ablandar un
`containsExactlyInAnyOrder` a un `contains`— es justo lo que la regla atrapa.

---

## `V29` — `scrape_run` + `scrape_run_site`

Una corrida deja de ser un estado en memoria y pasa a ser una entidad
persistida y direccionable. De ese único hecho cuelga todo lo demás del cambio:
`started_at` es la cota de aislamiento de lectura, las filas de
`scrape_run_site` son el conjunto autoritativo de sitios para un resume, y una
fila que quedó `RUNNING` con `finished_at IS NULL` es la señal de que el
proceso anterior se murió a mitad.

| Columna | Por qué |
|---|---|
| `scrape_uuid UUID UNIQUE` | Cómo se direcciona una corrida entre procesos |
| `started_at TIMESTAMPTZ NOT NULL` | La cota. **Truncada al segundo**, ver abajo |
| `finished_at TIMESTAMPTZ NULL` | `NULL` es "sigue viva", y el CHECK lo hace vinculante |
| `triggered_by → usuario(id)` | Nullable: una corrida de cron no tiene humano |
| `cron_job_id → cron_jobs(id)` | Nullable: una corrida manual no tiene job |
| `status` | Dominio cerrado por CHECK |

**`elapsed_time` no existe**, y no es un olvido: se deriva de
`finished_at - started_at`. Guardarla sería una dependencia funcional sobre
no-clave —3FN— y, peor que la teoría, una segunda cosa que mantener
sincronizada: cualquier corrección de `finished_at` dejaría la duración
mintiendo sin que nada lo señale.

### El CHECK apareado es toda la detección de interrupciones

```sql
CHECK ((status = 'RUNNING') = (finished_at IS NULL))
```

En las **dos** direcciones, a propósito. La detección al arranque es "buscá una
corrida `RUNNING` sin `finished_at`", y esa pregunta deja de significar algo en
cuanto una sola escritura desalinea las columnas: un `COMPLETED` sin
`finished_at` se lee como interrumpido en **cada** reinicio, para siempre, y un
`RUNNING` con `finished_at` se esconde de la detección justo cuando hay que
encontrarlo. Una sola dirección deja pasar el segundo caso, que es exactamente
lo que produce un proceso muerto a mitad.

### `started_at` está truncado al segundo, y eso no es cosmética

`productos.touched_at` es `timestamptz` —microsegundos— pero **todo** valor que
entra viene de `LocalDateTime.now().format("yyyy-MM-dd HH:mm:ss")`
(`ProductRepository:44`, usado en `:71` y `:213`), así que en la práctica la
columna sólo contiene `.000000`. Medido: 19 769 filas en la base real, cero con
parte fraccionaria.

Si `started_at` conservara precisión sub-segundo, `touched_at >= started_at`
**dejaría afuera toda fila tocada durante el primer segundo de la propia
corrida** — y la unión del soft-delete construida sobre ese predicado las leería
como ausentes y las desactivaría. Por eso `ScrapeRunRepository.crear` trunca
siempre, adentro, y **el llamador no tiene por dónde saltearlo**: la regla
original ("el mismo reloj Java, nunca `DEFAULT now()`") se puede obedecer al pie
de la letra con `Timestamp.from(Instant.now())` y llevarse el bug igual. Una
regla que se puede cumplir y fallar no es una regla.

Dos consecuencias, las dos aceptadas explícitamente:

- **Ensancha la unión, no la angosta.** Con los dos lados en `:00.000000`, el
  `>=` también captura filas tocadas por lo que haya corrido antes en ese mismo
  segundo. Para el soft-delete esa es la dirección segura: protege de más, nunca
  barre de más.
- **El lector las OCULTA, y está bien.** La cota de lectura es
  `touched_at < started_at`, y una fila del primer segundo tiene
  `touched_at == started_at`, así que no se sirve. Los dos predicados coinciden:
  esa fila es de la corrida en vuelo, que es justo lo que la unión tiene que
  barrer y lo que el lector tiene que esconder. **Ocultarla es el aislamiento
  funcionando, no un agujero** — no lo "arregles".
- **La asimetría real** es que ensanchar alcanza también a lo escrito en ese
  mismo segundo *antes* de que la corrida abriera: para la unión eso protege de
  más, y para el lector significa que una escritura de menos de un segundo de
  antigüedad queda invisible hasta que la corrida termina. Nadie pierde datos.
  La alternativa era una columna `scrape_run_id` en `productos`, descartada por
  tocar la tabla más caliente del esquema.

### La FK de sitio va sobre la clave, no sobre el nombre

`scrape_run_site.sitio_key → sitio(sitio_key)`, igual que `V23`, y por el mismo
motivo: `sitio.nombre` es display (`'Vcp'`, `'Freres'`) y `sitio_key` es
identidad (`'vcp'`, `'freres'`). Acá pesa todavía más, porque el valor sale de
`buildSiteList` → `SiteConfig.nombre()`, que es la clave de config en minúscula
(`ScraperConfig:66`): contra `sitio(nombre)` **cada insert violaría la FK en el
arranque de la corrida**, antes de scrapear un solo producto.

⚠️ **Esto NO es lo mismo que la unión del soft-delete**, que saca sus sitios de
`productos.sitio` —la forma de display— porque `sp_soft_delete_ausentes` compara
contra esa columna. Dos tablas, dos formas, las dos correctas donde están.
Unificarlas por consistencia rompe una de las dos.

Y hay una diferencia con `V23` que muerde: `productos.sitio_key` es
`GENERATED ALWAYS AS ... STORED`, pero **`sitio.sitio_key` (`V18:29`) es una
columna común**. Nadie la calcula sola. `ScrapeRunRepository` suministra el
valor normalizándolo **en SQL**, con la misma expresión de
`R__sp_upsert_run.sql:97`, y no con `SiteClassification.sitioKey()`: las dos
copias **no** son equivalentes —Java baja a minúscula y después filtra contra
`[a-z0-9]`, el SQL filtra contra `[a-zA-Z0-9]` y después baja— así que bajo
locale turco `"INPRO"` da `npro` en Java e `inpro` en SQL. Usar la expresión SQL
no evita "una tercera copia": hace **estructuralmente imposible** que
`scrape_run_site.sitio_key` discrepe de `productos.sitio_key`.

Además, esas filas se insertan **antes** de que corra ningún scrape, así que el
get-or-create de `sitio` que hace `sp_upsert_run` todavía no pasó: el
repositorio hace el suyo, o un sitio nuevo de `/api/sitios` impide arrancar la
corrida.

### `SET NULL` y no `CASCADE`, contra lo que hace todo `V26`

Todas las FK a `usuario` de `V26` son `ON DELETE CASCADE`, y está bien: son los
datos **personales** del usuario —favoritos, outfits, tokens— y dar de baja la
cuenta tiene que borrarlos. Un `scrape_run` no es dato personal: es el registro
**operativo** de lo que hizo el sistema. Cascadearlo borraría el historial de
corridas porque se dio de baja a un admin. Se pierde la procedencia, se conserva
el hecho, que es lo que se le pide a una bitácora. Igual con `cron_job_id`:
borrar un job no puede borrar las corridas que produjo.

La regla sigue la semántica de propiedad del dato, no un estilo de la casa.

### El índice sobre `touched_at`: medido y NO agregado

El diseño (D1) argumentó contra indexar `touched_at`, pero un argumento no es un
número, así que `V29` ships con la **medición** en vez del índice.
`ScrapeRunIndexBenchmarkTest` la corre: 20 000 filas, 60% tocadas después de la
cota, 50 warmup + 200 medidas, y el datasource **envuelto en `HikariDataSource`**
—no negociable: sin pool los tiempos de base salen inflados 31x en este repo, y
un número así no es lento, es ficticio—.

| Brazo | p50 | vs. sin cota |
|---|---|---|
| Sin cota | 5,911 ms | — |
| Con cota, **sin** índice | 5,550 ms | **−6,1%** |
| Con cota, **con** índice | 3,403 ms | −42% |

`EXPLAIN (ANALYZE, BUFFERS)` con el índice presente: `Bitmap Index Scan on
idx_prod_touched_at (rows=8000)`.

**Decisión: sin índice.** La regla se fijó ANTES de medir y es conjuntiva —se
agrega sólo si la cota es >10% más lenta **Y** el índice lo recupera **Y** el
planner efectivamente lo elige—. Falla la primera condición, y por eso el índice
no entra.

⚠️ **Pero las dos premisas de D1 resultaron falsas, y eso importa más que la
conclusión.** D1 decía que la cota sería de baja selectividad y que *"un btree
que el planner no va a elegir es puro impuesto sobre el write-path"*. Medido:

- **El planner SÍ lo elige** (`Bitmap Index Scan`, arriba).
- **El índice SÍ ayuda**: 5,550 → 3,403 ms, 39% más rápido.
- **Y la cota no cuesta nada**: es 6% más *rápida* que no tenerla, porque
  descarta el 60% de las filas antes del `ORDER BY`/`LIMIT` — hay menos que
  ordenar.

O sea que el índice se rechaza **no porque no funcione, sino porque el problema
que resolvería no existe**. Quien lea la justificación de D1 y quiera
verificarla se va a encontrar con lo contrario, y quien agregue el índice
"porque el planner lo usa" va a pagar ~20 000 actualizaciones de índice × 26
sitios en cada corrida —`sp_upsert_run` toca `touched_at` en toda fila aunque el
precio no haya cambiado (`V1:212`)— para acelerar una consulta que no está
lenta.

Si alguna vez la cota **sí** empieza a costar, los tres números están acá y la
regla sigue siendo la misma. Re-medir, no deducir.

> El bloque de abajo lo ejecuta `V29RollbackRoundTripTest` contra el esquema
> real, dentro de una transacción que siempre se revierte.

```sql
-- >>> rollback:V29
DROP TABLE scrape_run_site;
DROP TABLE scrape_run;
-- <<< rollback:V29
```

La hija primero: `ON DELETE CASCADE` gobierna filas, no `DROP TABLE`, así que
soltar `scrape_run` con `scrape_run_site` viva falla. Y `V29` **obligó a editar
el rollback de `V18`**, que hace `DROP TABLE sitio` y ahora se topa con una
segunda FK entrante — la tercera vez en este esquema que una migración nueva
rompe el rollback de una vieja. El rojo aparece en el test de la vieja, no en el
de la nueva.

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

## `V30` — seed de Zentra y MMartinez

Dos sitios Tiendanube genuinos. **No hay plataforma nueva ni CHECK tocado**:
`'tiendanube'` es válido desde `V6` y `'oficina'` desde `V27`, así que la
migración es un solo `INSERT`. Re-listar cualquiera de esos dominios acá
borraría lo que agregaron `V27` y `V28`, porque cada una hace `DROP` + `ADD`
del dominio **completo**.

| Sitio | `plataforma` | `rubro_forzado` | Por qué |
|---|---|---|---|
| Zentra | `tiendanube` | `oficina` | Sillas ergonómicas y standing desks, el mismo catálogo que INPRO |
| Mmartinez | `tiendanube` | `NULL` | Calzado — cae en el default de indumentaria, como Harvey o Midway |

Medido contra los sitios en vivo antes de escribir la migración, que es
exactamente el paso que se salteó el bug que cerró `V24`: Zentra sirve 44
productos en una sola página y `?page=2` viene vacía (el corte por dos vacías
seguidas de `TiendanubePage` la ve y para); MMartinez sirve 37 de a 12 y
`?page=N` pagina de verdad, mientras `?mpage=N` devuelve la página 1 — marcador
client-side, igual que en `entreno`.

### Rollback

```sql
-- >>> rollback:V30
DELETE FROM sitio s WHERE s.sitio_key IN ('zentra','mmartinez')
  AND NOT EXISTS (SELECT 1 FROM productos p WHERE p.sitio_key = s.sitio_key);
-- <<< rollback:V30
```

El `NOT EXISTS` es el mismo de `V24`, `V27` y `V28`, por la FK que `V23` le puso
a `productos.sitio_key -> sitio(sitio_key)`. No hay bloque de CHECK que
restaurar porque esta migración no angostó ni ensanchó ninguno.

**Y van tres veces que los rollbacks al revés obligan a tocar un test ajeno.**
`V27` editó el de `V24`; `V28` editó los dos; `V30` vuelve a editar esos dos.
Hoy `V24RollbackRoundTripTest` ejecuta `V30` → `V28` → `V27` → `V24`. El motivo
es concreto y vale anotarlo porque no es el mismo que las veces anteriores: la
fila de Zentra lleva `rubro_forzado='oficina'`, así que el bloque de `V27` —que
angosta `sitio_rubro_forzado_check` sacando justo ese valor— es rechazado por
Postgres mientras esa fila siga viva. No es que el test se ponga rojo por
gusto: sin sacar la fila primero, el `ALTER` no puede aplicarse en ninguna base.
Sigue valiendo la misma regla acotada para editar el test de otro: **una
aserción de dominio cerrado puede ir de `n` a `n+1` si toda aserción de
comportamiento alrededor queda idéntica.**

---

## `V31` — quince categorías nuevas de tecnología y deporte

Un solo `INSERT` a la tabla lookup de `V13`. **No toca ningún CHECK ni ningún
dominio**, así que no hereda el problema de `V24`/`V27`/`V28`, donde re-listar
un dominio completo borra lo que agregó la migración anterior.

**Por qué hace falta la migración y no alcanza con el código:**
`productos.categoria` tiene FK a `categoria(nombre)` desde `V13`. Sin estas
filas, todo producto que el clasificador mande a una de ellas viola la FK en el
upsert — y como `ProductRepository` **se traga los errores SQL** y devuelve
`UpsertStats(0,0,0,0)`, el síntoma no sería un error sino `"0 nuevos"` en una
corrida que se ve perfectamente sana.
`CategoriaLookupTableTest.laTablaYElCanonDeJavaNoPuedenDiverger` exige que esta
tabla y `CategoryGroups.canonicalCategories()` sean el **mismo** conjunto,
exactamente para que esto no se pueda olvidar.

**De dónde salió la lista: de contar, no de imaginar.** Medido sobre las 16.830
filas activas, `Otros` tenía 2.974 productos —14% del catálogo— y adentro había
453 teclados, 302 mouses, 285 fuentes, 231 discos, 161 productos de red, 130
cables, 101 de impresión, 89 pelotas y 88 mousepads. No estaban mal
clasificados: **ningún keyword los nombraba**. `KW_TECLADO` no tenía la palabra
`teclado` pelada, sólo `"teclado gamer"`/`"teclado mecanico"`. El criterio de
alta fue ≥20 productos reales, sustantivo propio, y ninguna categoría existente
donde entren sin mentir.

| Categoría | Rubro | De dónde salió |
|---|---|---|
| `Cooler` | tecnología | **De adentro de `CPU`**, no de `Otros` — ver abajo |
| `Fuente` · `Motherboard` · `Red` · `Cable` · `Impresión` · `Mousepad` · `Joystick` · `Micrófono` · `UPS` · `Tablet` · `Cámara` · `Reloj` | tecnología | `Otros` |
| `Pelota` · `Paleta` | deporte | `Otros` |

El piso de 20 no es arbitrario y se eligió mirando el consumidor, no la
estética: `ml_pipeline.py` usa `MIN_GROUP = 10` para decidir si calcula stats
sobre la categoría o cae al padre, y `MIN_SAMPLE = 3` para z-score y cercos de
Tukey. Una categoría de 20 productos entra con margen sobre los dos; una de 5
habría entrado al vocabulario para producir estadística de ruido.

`Almacenamiento` **no está** en `V31`: entró al canon y a esta tabla en `V13`.
Lo que le faltaba era un keyword que la produjera, y eso es código, no esquema —
231 discos vivían en `Otros` mientras la categoría existía y estaba vacía.

### `Cooler` es la que más importaba, y no venía de `Otros`

De las 646 filas de `CPU`, **321 eran disipadores** — la mitad de la categoría.
`KW_CPU` declaraba `"cpu "` sin espacio adelante, así que `"Cooler CPU
ID-Cooling SE-214-XT"` matcheaba igual que `"Procesador Intel Core i5"`.

Media categoría a un orden de magnitud de precio de la otra media no le miente a
un filtro del dashboard: le miente a la **distribución** de la que vive el
pipeline ML. Mediana, IQR, percentiles y cercos de Tukey de `CPU` se calculaban
sobre dos poblaciones distintas mezcladas, y de ahí salen los badges.

### Las categorías nuevas no son de indumentaria, y eso es deliberado

`Pelota` y `Paleta` viven en su propio set (`CATEGORIAS_DEPORTE`), **fuera** de
`INDUMENTARIA_O_CALZADO_EXTRA`. Si entraran ahí, `GymratTagger` las taggearía y
los tres armadores de outfits considerarían una pelota una prenda vestible.

### Rollback

```sql
-- >>> rollback:V31
UPDATE productos SET categoria = 'Otros'
 WHERE categoria IN ('Cooler','Fuente','Motherboard','Red','Cable','Impresión',
                     'Mousepad','Joystick','Micrófono','UPS','Tablet','Cámara',
                     'Reloj','Pelota','Paleta');
DELETE FROM categoria_stats
 WHERE categoria IN ('Cooler','Fuente','Motherboard','Red','Cable','Impresión',
                     'Mousepad','Joystick','Micrófono','UPS','Tablet','Cámara',
                     'Reloj','Pelota','Paleta');
DELETE FROM categoria
 WHERE nombre IN ('Cooler','Fuente','Motherboard','Red','Cable','Impresión',
                  'Mousepad','Joystick','Micrófono','UPS','Tablet','Cámara',
                  'Reloj','Pelota','Paleta');
-- <<< rollback:V31
```

**El orden de las tres sentencias es obligatorio, no estético.** `productos.categoria`
(`V13`) y `categoria_stats.categoria` (`V16`) tienen FK a esta tabla: borrar la
fila de lookup con productos todavía apuntándole falla, y el `UPDATE` a `'Otros'`
es lo que los suelta primero. Es el mismo motivo por el que el `DELETE` de
sitios de `V24`/`V27`/`V28`/`V30` lleva su `NOT EXISTS`.

A diferencia de esos cuatro, **este rollback no obliga a tocar ningún test
ajeno**: `V31` no angosta ni ensancha un dominio cerrado, así que ninguna
migración anterior deja de aplicar mientras sus filas sigan vivas.

