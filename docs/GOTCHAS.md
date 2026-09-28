# Gotchas

> Trampas que costaron al menos una sesión, agrupadas por cuándo te las cruzás. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Gotchas

> Cada uno de estos costó al menos una sesión. Están agrupados por **cuándo te
> los cruzás**, no por subsistema, porque la pregunta que traés no es "¿de qué
> módulo es esto?" sino "¿por qué no anda lo que acabo de tocar?".
>
> | Si estás tocando… | Andá a |
> |---|---|
> | auth, CORS, cookies, sesión, el status de una corrida | [Frontend ↔ backend](#frontend--backend-sesión-orígenes-y-status) |
> | retomar/descartar una corrida, un scrape parcial, cronjobs | [Corridas parciales y retomas](./GOTCHAS.md#corridas-parciales-y-retomas) |
> | el toolchain, un jar, el venv, la base de dev, arrancar los servicios | [Entorno y procesos](#entorno-procesos-y-config) |
> | un scraper, una page, una URL de catálogo o de imagen | [Leer un sitio](#leer-un-sitio) |
> | keywords, categorías, el guard no-textil, normalización | [Taxonomía y clasificación](#taxonomía-y-clasificación) |
> | IPC, dólar, el deflactor, la señal de compra | [Índices y señales](./GOTCHAS.md#índices-y-señales) |
> | un picker, una tarjeta que scrollea, chips animados | [Frontend: layout](#frontend-layout) |
> | `docker-compose.yml`, el Dockerfile, los orígenes | [Docker](#docker) |

### Corridas parciales y retomas

⚠️ **`agregar` sólo conoce los sitios que le pasaron, así que su lista de
productos ES el subconjunto — y asignarla a `lastResult` borraba el catálogo.**
Era correcto mientras toda corrida cubriera los 29 sitios y falso en cuanto
dejó de hacerlo: un cronjob de tecnología, o una retoma con un solo sitio
pendiente, dejaban en memoria únicamente esos productos. Medido contra la dev
DB (2026-09-24): la corrida 22 (5 sitios tech) cerró con **951** productos y la
retoma de la 16 con **1022**, sobre **15.907** activos que la base nunca dejó
de tener — `0 desactivados` en las dos, o sea que el borrado era **sólo en
memoria**. Y eso alcanza: `/api/grupos`, `/api/mejores`, outfits, suplementos,
PCs, recomendados, marcas y el total de `/api/status` leen el snapshot, no SQL.
Hoy `ScraperService.catalogoEntero` recarga los activos y los pasa por
`fromDBParcial` —lo mismo que el refresco progresivo ya hacía por sitio— y
conserva del batch sólo `erroresPorSitio` y `statsPorSitio`, que son hechos de
esa corrida y no se derivan de la base.

⚠️ **El guard de "ya hay un scrape corriendo" era check-then-set, y abrió dos
corridas en el mismo segundo.** No es teórico: el 2026-09-24 11:16:59 se
abrieron la 21 (cron: entreno, morashop) y la 22 (5 sitios tech) a la vez.
`runState` es UNA referencia, así que la segunda pisó a la primera: los sitios
de la 21 nunca se marcaron, nadie la cerró, y quedó `RUNNING` para siempre — la
corrida fantasma que después no se podía ni retomar ni descartar. `iniciarScraping`
y `reanudar()` entran ahora por `tomarElTurno()`, un `compareAndSet`.

**Descartar una corrida interrumpida es un endpoint** (`POST /api/scrape/discard`),
no un botón que esconde el cartel. Cierra como `CANCELLED` **todas** las
`INTERRUPTED`, porque `ultimaInterrumpida()` nombra sólo la más reciente y
descartar de a una destaparía la siguiente en el próximo arranque.
`POST /api/scrape/cancel` no sirve para esto: exige `RUNNING`, que es
exactamente lo que una corrida interrumpida no está.

**Una retoma que no resuelve ningún sitio se cierra sola, no revienta.**
`pendientes` trae `sitio_key` (normalizado: sin puntos, sin espacios) y
`buildSiteList` filtra por `nombre`, así que un sitio dinámico con un punto en
el nombre no matchea ninguno. Con la lista vacía,
`Executors.newFixedThreadPool(0)` tira `IllegalArgumentException`, `agregar`
nunca corre y la corrida recién adoptada queda abierta otra vez. Hoy se cierra
como `CANCELLED` sin tocar el catálogo.

⚠️ **Una ventana de tiempo no es una corrida, y el barrido final confundía las
dos.** `ProductRepository.alcanceDelRun` leía `touched_at >= started_at` a
secas, y el `started_at` de una corrida RETOMADA puede ser de hace días: todo
sitio que **otra** corrida hubiera tocado en esa ventana entraba a `p_sitios`,
aunque ésta no lo hubiera mirado nunca — y ahí "ausente" vuelve a significar
algo sobre un sitio que nadie visitó, que es justo lo que el header de
`R__sp_soft_delete_ausentes` prohíbe y lo que el 2026-08-15 desactivó 5806
productos de 19 sitios en una sentencia. Medido: la corrida 16 arrancó el 22 a
las 16:49 y se retomó el 24 a las 14:54, con cinco corridas en el medio —una
completa—, así que su ventana nombraba los 28 sitios del catálogo para una
corrida que había mirado cinco. Hoy la unión se acota además a los sitios que
la corrida tiene enrolados en `scrape_run_site`, en **la misma query** (la
invariante es que `p_urls` y `p_sitios` no puedan ensancharse por separado, así
que no pueden salir de dos lecturas).

| | |
|---|---|
| **Es un angostamiento puro** | Un sitio entra sólo si la corrida lo enroló **y** escribió filas suyas en la ventana. Un sitio enrolado cuyo scraper se rompió llega con 0 productos y sigue quedando afuera por el lado del tiempo — "se rompió" no es "se vació", y eso lo detecta `SiteYieldGuard`, no el barrido |
| **El join va por `sitio_key`, no por `sitio`** | `productos.sitio` es la forma de display (`Vcp`) y `scrape_run_site.sitio_key` es identidad (`vcp`). Compararlos directo no matchea nada y **vacía el alcance en silencio** — el mismo par de vocabularios que documenta el header de `V29` |
| **El puerto recibe la corrida, no un reloj** | `ProductPort.upsertProductos(List, CorridaEnCurso)`; `CorridaEnCurso(runId, startedAt)` vive en `scrape/`. Pasar sólo el `Instant` era la forma exacta del bug: un reloj no puede decir qué sitios miró una corrida |

### Frontend ↔ backend: sesión, orígenes y status

**El entorno de desarrollo NO tiene la forma de ninguna instalación real, y eso
esconde bugs de auth.** `vite dev` proxea `/api`, así que el frontend queda
**same-origin** con el backend. Las dos vías que se instalan de verdad son
**cross-origin**: portable/POSIX es `:5173 → :3000` y Docker es `:8080 → :3000`.
Cualquier cosa que dependa de la relación entre orígenes —`Origin`,
`Sec-Fetch-*`, `SameSite`, si el browser guarda una cookie— se comporta distinto
en dev y en producción, **y dev es la topología que nunca se instala**.

Esto ya costó dos veces. Primero se recomendó exigir `Sec-Fetch-Site:
same-origin` para el refresh de bootstrap, que habría dado 403 en las dos
instalaciones reales y sólo habría andado en dev. Después, y peor: el login se
mandaba sin `credentials: 'include'`, así que el browser descartaba la cookie de
refresh y **la recuperación de sesión al recargar nunca funcionó** en ninguna
instalación real — con 1570 tests de backend y 148 de frontend en verde encima.

Por eso `tests/e2e/run-e2e.sh` corre siempre contra `npm run preview` y **falla
ruidosamente si se descubre same-origin** en vez de pasar callado. Si tocás auth,
CORS o cookies, esa suite no es opcional: los tests unitarios no pueden ver esta
clase de bug, por construcción.

**`fetchStatus` devuelve `null` si la respuesta no es ok, pero *rechaza* si no
hay nadie escuchando:** `authedFetch` llama a `fetch` pelado, así que un backend
muerto nunca llega al `if (!st)` — la callback muere con una promesa rechazada y
el último `RUNNING` bueno queda congelado en pantalla mientras la pestaña siga
abierta. Todo lector de `api.js` tiene que cubrir **las dos formas**: `null` y
excepción. Las colapsa en "no hay status" **un solo lector**,
`frontend/src/lib/readStatus.js`, y todo call site pasa por ahí.

Vivió un tiempo adentro de `useScrapeStatusPolling.js`, y ahí estaba el
problema: **hay DOS pollers y tres lecturas de montaje**, y el fix sólo llegó a
uno. Los otros tres seguían llamando `fetchStatus` pelado. `AppLayout` tenía la
copia idéntica del bug original —`await` sin try/catch adentro de un
`setInterval`, muriendo cada 1800 ms contra un backend caído sin limpiar nunca
el intervalo— y `RootGate` era peor: `.then()` sin `.catch()` en la ruta `/`,
así que un backend que no escucha dejaba `gate` en `'checking'` y **la puerta de
entrada de la app renderizaba el fallback para siempre**. Los 239 tests del
frontend estaban en verde.

Aparte de eso, `useScrapeStatusPolling` expone un `backendUnreachable`: "no lo
puedo contactar" y "sigue corriendo" son frases distintas, y la pantalla tiene
que decir la correcta. Ese estado **no** se mete en `scrapeStatus`, que espeja el
`ScraperStatus` del backend; lo que se apaga es el progreso, no el campo.

**El poller del splash no se arma solo salvo por una bandera de un solo tiro:**
sólo `handleScrape` armaba el intervalo, así que aterrizar en `/splash` con una
corrida ya `RUNNING` —lo que pasa al **retomar** una corrida interrumpida, y
también tras un reload a mitad de corrida— dejaba el status congelado sin
progreso ni final. Lo dispara `pollingNeeded`, que **levanta la lectura de
montaje y nadie más**: si espejara el status vivo, el efecto que la observa
re-armaría el intervalo en cada render que viera una corrida en curso. Al
tocarlo, acordate de que el test correspondiente **no puede vivir en
`App.test.jsx`** — necesita fake timers y la cadena de bootstrap de auth no
drena bajo ellos, así que la baseline lee cero y la aserción pasa midiendo la
lectura de montaje en vez del poller. Vive en `src/SplashRoute.test.jsx`, que
mockea `useAuth` y fija la baseline en 1 antes de medir.

**Trampas que dejó `user-accounts-and-roles` (todas cobraron al menos una vez):**

- **`PostgresTestBase.truncateAll` es una lista a mano, no un barrido del
  esquema.** Toda tabla nueva hay que agregarla ahí. Si te la olvidás no falla:
  contamina otros tests y se ve como un bug en otro lado. `rol` está excluida a
  propósito — es dato semilla de la migración, y truncarla deja el esquema sin
  vocabulario de roles.
- **Un test de esquema afirma el SQLState, no `SQLException`.** Un INSERT contra
  una tabla que todavía no existe también tira `SQLException`, así que la versión
  floja se pone verde ANTES de escribir la migración. `23514` = CHECK,
  `23505` = UNIQUE.
- **Los fixtures se escriben contra el esquema de HOY, no contra `V1`.**
  `saved_outfits.slots_json` la borró `V14`; `outfit_feedback_item.liked` es
  BOOLEAN desde `V5`. Mirar el baseline es mirar una foto vieja.
- **El placeholder `cambiame-por-una-password-real` vive en dos lados** y tienen
  que coincidir byte a byte: `.env.example` y `AdminSeeder.PLACEHOLDER`. Si se
  separan, el backend deja de negarse a sembrar con la password de ejemplo.
- **`AUTH_JWT_SECRET` y `CLI_SERVICE_ACCOUNT_PASSWORD` son pegajosos**: el CLI
  los genera una vez y NO los rota aunque regeneres el `.env` (`GENERATED_KEYS`
  en `cli/core/env_file.py`). Rotarlos cierra todas las sesiones o rompe todos
  los cronjobs contra una config que se ve perfecta, porque el seeder nunca pisa
  un hash existente.
- **`@WebMvcTest` registra los `Filter` pero no los `@Component` comunes.** Un
  test del slice de seguridad necesita importar `SecurityConfig`, `JwtAuthFilter`
  **y** `TokenService`, o el contexto no carga.
- **Un fixture tiene que sembrar el mismo rol que pone en el contexto de
  seguridad.** El rol se lee de la BASE en cada request —el token no lo lleva—
  así que decir ADMIN en el contexto y escribir VIEWER en la tabla da un sujeto
  que la app trata como VIEWER, correctamente, y un test que falla por algo que
  no tiene que ver con lo que quería probar.
- **Los relojes fijos de los tests caen en segundos exactos.** Por eso los 1540
  tests no vieron que `iat` (segundos) y `password_changed_at` (microsegundos)
  se comparaban directo, rechazando el token del usuario que acababa de cambiar
  su contraseña. **Todo cambio de auth se verifica además contra un proceso
  real**: la verificación manual encontró tres bugs que la suite no podía ver
  —dos que impedían arrancar y este—.
- **Convención de commits de la cadena**: subject conventional (`COMMIT-1`) y
  `Fase N — ...` como primera línea del body. El formato `fase:n - "msj"` lo
  rechaza `scripts/hooks/commit-msg`, y `--no-verify` apagaría también el chequeo
  de `COMMIT-3`.

### Entorno, procesos y config

**Toolchain de esta máquina (Linux):** el Java está partido — compila con JDK 24,
corre los tests con JRE 21. El comando completo está en
[`CONTRIBUTING.md`](../CONTRIBUTING.md). `clean` no es opcional: sin él `mvn test`
puede pasar contra clases viejas y fingir verde.

**Jar stale:** `cli/core/builder.py` saltea el build si `scraper/scraper.jar`
existe. Tras recompilar a mano: copiar `scraper/target/fashion-scraper-1.0.0.jar`
→ `scraper/scraper.jar`, o borrar el jar y correr `build` desde el CLI.

**`DATABASE_URL` tiene DOS formatos según el consumidor:** Java/Spring necesita
el prefijo `jdbc:` (`jdbc:postgresql://…`); psycopg2 **no** lo entiende, solo
`postgresql://…`. `PythonRunner.toPsycopgDsn` traduce antes de pasarlo al
subproceso. Si se agrega otro consumidor de `DATABASE_URL`, revisar esto.

**Fail-fast de env vars:** el backend no tiene defaults silenciosos para
`DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`/`APP_CORS_ALLOWED_ORIGINS`
en el profile default — `RequiredEnvVarsGuard` aborta el arranque nombrando cada
variable faltante. Un `DATABASE_PASSWORD` **vacío** (trust-auth local) cuenta como
presente; solo una var totalmente ausente cuenta como faltante. Fallbacks de dev
en `application-dev.properties` (`SPRING_PROFILES_ACTIVE=dev`); los tests activan
el profile `test` vía surefire, no por anotación.

**Logs de los servicios lanzados por el CLI:** backend y frontend **no** escriben
en la terminal (romperían el render de la consola). Van a
`scraper/logs/{backend,frontend}.log` y se leen con el comando `logs`. Esto es
aparte del logback del backend (`scraper.log`/`error.log`, rolling diario).

**Python embeddable:** `python311._pth` congela `sys.path` (no agrega el dir del
script ni respeta `PYTHONPATH`); `ml_pipeline.py` inserta su propio dir antes de
importar `ml_embeddings`. Esto es **solo** del embeddable de ML (`_tools/python`) —
`_tools/cli-venv` es un venv uv normal y no tiene el problema, por diseño.

**El CLI se autentica solo, y falla fuerte si no puede:** desde
`user-accounts-and-roles` fase 1, `RestClient` lee
`CLI_SERVICE_ACCOUNT_USERNAME`/`_PASSWORD` del `.env`, hace `POST /api/auth/login`
y adjunta `Authorization: Bearer`. Ante un 401 reautentica **una** vez y
reintenta; si el segundo intento también da 401, levanta `RestError` — nunca un
skip silencioso ni un loop de logins contra la cuenta que ya está fallando.
**Nunca** toca `/api/auth/refresh` ni una cookie: esa superficie es del browser.
Sin esas dos claves en el `.env` (instalación previa al cambio) el cliente se
comporta exactamente como antes: sin login y sin header.

**CLI (`_tools/cli-venv`):** si `import textual` falla, el instalador aborta con
mensaje accionable. Para reprovisionar: borrar `_tools/uv` y `_tools/cli-venv` y
re-correr el instalador. Se invoca `python -m cli` con cwd = raíz del repo —
**no** `cli/__main__.py` directo, que falla por los imports absolutos `cli.*`.

**El Postgres de dev corre con `trust` — sin password — así que el bind importa
más que de costumbre.** `scripts/dev-db.sh` mapea `127.0.0.1:5432` a propósito
(`PG_BIND`): con el `-p 5432:5432` que tenía antes, Docker publicaba en
`0.0.0.0` y cualquiera en la misma red entraba a la base entera sin credencial —
usuarios y hashes incluidos. El único consumidor es el backend, que corre en el
host, así que loopback no le saca nada a nadie. **El mapeo se fija al crear el
contenedor**: cambiar la variable no alcanza, hay que recrearlo (`down` + `up`;
el volumen es nombrado y los datos sobreviven).

**`start lan` levanta todo solo**: detecta la IP de la LAN, genera el
certificado (mkcert si está, autofirmado si no), levanta el terminador TLS en un
contenedor y deriva los orígenes. `stop` lo baja. **Necesita Docker** — `local`,
que es el default, no. El backend sigue sirviendo HTTP: el TLS lo termina el
proxy, igual que en un deploy, para no agregar otra divergencia dev/prod.

**El origen del backend se elige al arrancar, no al compilar.** `start` acepta
`local` (default) o `lan`, y `cli/core/runtime_config.py` reescribe
`frontend/dist/config.js` con ese origen; `api.js` lee `window.__API_BASE__` y
cae a `VITE_API_BASE_URL` sólo si está vacío. **El mismo `dist/` sirve los dos
modos** — cambiar de modo no rebuildea. El modo **no se persiste**: `apply_mode`
muta el `.env` ya parseado, nunca el archivo. `lan` deriva el origen de la IP
de la LAN detectada cuando `SCRAPPY_*_ORIGIN` no está seteada — nunca cae a
`localhost`, que desde otro dispositivo se estaría llamando a sí mismo.
⚠️ Para pisar esa derivación, las dos variables se leen **del entorno del
proceso y de ningún otro lado**: `resolve_origins` mira `os.environ`, y el CLI
nunca carga el `.env` en su propio proceso — sólo lo escribe. Ponerlas adentro
del `.env` no tiene **ningún** efecto sobre `start lan`.

**Los orígenes del `.env` ya no están clavados en `localhost`.**
`SCRAPPY_FRONTEND_ORIGIN` y `SCRAPPY_BACKEND_ORIGIN` (leídas por
`cli/core/env_file.py` al generar) fijan `APP_CORS_ALLOWED_ORIGINS`,
`VITE_API_BASE_URL` y `APP_OPEN_URL`. La primera acepta lista separada por
comas; `APP_OPEN_URL` toma la primera. Sin ellas, todo se comporta igual que
antes. Ojo con `VITE_API_BASE_URL`: es **build-time**, así que cambiarla exige
rebuildear el frontend, y la generación del `.env` es create-if-absent — sobre
un `.env` que ya existe no pisa nada.

**Postgres portable:** vive en `_tools/pgsql` (binarios) + `_tools/pgdata`
(`initdb -A trust`, sin password local). Queda corriendo entre ejecuciones;
`pg_ctl status` chequea antes de re-arrancar. Para dev sin el instalador:
`scripts/dev-db.sh`.

**Tests contra Postgres:** `PostgresTestBase` auto-selecciona Testcontainers (si
hay Docker) o el portable local, y se skipea con mensaje si no hay ninguno —
nunca hace fallar la suite por falta de infra.

**Una migración que escribe en `productos`/`producto_talle`/`producto_badge` y
después le hace `ALTER TABLE` a esa misma tabla, en la MISMA migración, falla**
con `cannot ALTER TABLE ... because it has pending trigger events`: los
triggers de `catalog_version` (`V40`) son `DEFERRABLE INITIALLY DEFERRED`, y
Flyway corre cada `.sql` como una transacción. Arreglo: `SET CONSTRAINTS ALL
IMMEDIATE;` antes del DDL, o partir el fix de datos y el `ALTER` en dos
migraciones — detalle en `docs/DATABASE.md` § `V40`.

### Leer un sitio

**`page.content()` sirve el DOM re-serializado, no el HTML crudo del servidor:**
descubierto escribiendo `OsCommercePage` — un fixture construido a partir de
`curl` (comillas simples en un atributo `onclick`, JSON con comillas dobles
literales adentro) parseaba perfecto en test y rendía **0 productos en un run
real**. Chromium normaliza los atributos a comillas dobles y escapa las
comillas internas como `&quot;` al serializar `document.documentElement.outerHTML`
(que es lo que `page.content()` devuelve). Cualquier parser que lea un
atributo con JS/JSON embebido tiene que aceptar las dos formas (o normalizar
entidades antes de matchear) — no alcanza con probarlo contra un `curl`.

**En Tiendanube, "todo lo de esta página ya lo vi" es lo normal, no un loop:**
`scrollToBottom()` dispara el scroll infinito, así que el DOM de p1 ya trae
p2..pk y `?page=2` no aporta nada nuevo. Un guard que cortaba ahí dejó
foreverbstrd en 72 y Harvey en 108 (2026-09-28). Que el server ignore el
parámetro se ve de otra forma: la página es **idéntica** a la anterior
(`TiendanubePage.repiteLaAnterior`).

**La API Legacy de VTEX da HTTP 400 pasado `_from` ≈ 2550, por consulta:** el
header `resources` dice el total real (7151 en Sporting), pero una sola consulta
nunca pasa de ~2550. `VtexPage` parte por el árbol de categorías (`fq=C:`).
Medido: 250 → 2540 → 7134.

**Una página vacía a mitad de catálogo no es el fin:** Fullh4rd cortó en 623 de
1918 por una página sin cards. Si el listado declara un total, sólo se termina
después de pasarlo, y el orden se fija explícito (`sort=name_asc`): el default
repite cards entre requests.

**Las URLs de imagen se absolutizan en UN solo lugar (`ar.scraper.pages.ImageUrl`):**
cada reader tenía su propia junta inline y cada una se quedaba en un punto
distinto — casi todas manejaban sólo la forma protocol-relative `//host/...`, así
que un sitio que sirve `src="/img/..."` guardaba un path pelado en
`productos.imagen_url` en **todas** sus filas. Un path relativo no es una imagen
peor: no es una imagen. `ImageUrl.absolutize` devuelve `""` cuando no puede
resolver, que es lo que el pipeline ya lee como abstención (`CODE-5`).

**Una clave del feed no es una URL:** Compragamer y Maximus exponen el
identificador de la imagen, no su dirección. Los dos necesitan que se reconstruya
la ruta del bucket alrededor de ese valor. Antes de dar por sentado que un sitio
"no tiene imágenes", buscar en el payload la clave con la que el propio sitio
arma su `<img>` — en Maximus el comentario del código afirmaba que no existía y
sí existía (`item_code4web`), y eso dejó 745 productos sin imagen.

**Un índice no es un catálogo, y `/productos/` no siempre es el catálogo:**
en Tiendanube la convención es que `/productos/` liste todo, pero el tema
puede pisarla. En Morashop `/productos/` es una landing de "8 CATEGORÍAS" con
**cero** productos y `/suplementos/` es un índice de subcategorías, también
cero; el catálogo entero vive un nivel más abajo. Configurar cualquiera de las
dos rinde 0 productos sin error, sin página vacía y sin nada que un operador
pueda ver — la clase de bug que cerró `V24`. Antes de dar por buena una URL de
catálogo, contá los productos que sirve en crudo (`curl | rg -c data-product-id`),
no asumas la convención. Y cuando el catálogo se descubre en runtime, que la
falta de resultados **tire excepción**: `SiteYieldGuard` no puede cubrir el caso
porque sólo alerta cuando un sitio **cae** contra la corrida anterior, así que
un sitio que rinde cero en su primera corrida nunca lo despierta.

**El tope de páginas de Tiendanube es configurable, y tenía DOS copias:**
`MAX_PAGINAS_DEFAULT` (60) en `TiendanubePage`, con override opcional
`sitio.<n>.max_paginas`. Era 25 hardcodeado y le cortaba el catálogo a entreno
por la mitad. Lo importante para la próxima vez: el `25` estaba en **dos**
lugares —el bound del loop y el fallback que construye la URL de la página
siguiente— y tocar sólo el primero deja el arreglo a medias en silencio, porque
sin URL nueva el loop se queda sin `nextUrl` y corta igual. El tope sigue siendo
cinturón de seguridad; quien corta de verdad es el chequeo de dos páginas vacías
seguidas, que en Tiendanube funciona porque pasado el final sirve una página
vacía en vez de repetir la última como hace osCommerce.

### Taxonomía y clasificación

**`AccentStripper` es hot path:** lo usan 10 clases, en el path de normalización
por scrape Y en el de `/api/grupos` por request. `/api/grupos` re-agrupa todo el
catálogo filtrado en **cada** request, paginación incluida — nada se cachea entre
páginas. Ignora a propósito acentos en mayúscula y circunflejo/cedilla/tilde;
ampliarlo cambiaría la clasificación de productos, no solo la velocidad.

**En la taxonomía de categorías, el ESPACIO es el word boundary — y un keyword
sin él se come palabras enteras en silencio.** `GarmentTaxonomy.anyMatch` es un
`contains()` pelado sobre un texto que `CategoryClassifier` ya padeó con
espacios. Un keyword declarado `"ram "` en vez de `" ram "` matchea adentro de
cualquier palabra terminada en ram: *D*ram, *S*ram, In*gram*, Mono*gram*. Lo
mismo `"malla"` con "Mallado", `"bra "` con "Hem*bra*" (adaptadores HDMI
archivados como corpiños), `"bano "` con "Urb*ano*", `"hat "` con "T*hat*"
(zapatillas de básquet como Gorra), `"rx "` con "Me*rx*"/"Hype*rX*", y
`"set "`/`"kit "`/`"pack "` con Sun*set*/Wind*kit*/Doy*pack*. Nada falla, nada se
loguea: el producto entra al catálogo con otra categoría **y con la distribución
de precios de otra categoría**, que es de lo que se alimenta el pipeline ML.

El barrido que los encontró es mecánico y se repite igual: buscar en los arrays
`KW_*` los keywords que terminan en espacio pero **no** empiezan con uno, y
contar los nombres reales donde el token aparece como substring pero no como
palabra. Padear es un angostamiento, así que sólo se padea lo que tiene
misclasificación **medida** — una forma padeada deja de matchear pegada a
puntuación (`"(pack de 4)"`).

**`NonTextileGuard` corre ANTES que todo y devuelve `""`, que el llamador no
distingue de "ningún keyword matcheó".** Puede vetar una clasificación correcta
sin dejar rastro. Tenía `"red "` para redes deportivas y mira los primeros 35
caracteres: "Mouse Logitech M110 Silent Red" entra entero en esa ventana, así
que un mouse **rojo** quedaba sin clasificar. En el catálogo no hay una sola red
deportiva.

Lo más caro de esa clase de bug no fue la contaminación sino la **ausencia**:
hasta `richer-category-taxonomy`, `KW_TECLADO` no tenía la palabra `teclado`
pelada —sólo `"teclado gamer"`/`"teclado mecanico"`— y 453 teclados vivían en
`Otros`. Un set demasiado angosto no se ve como un bug; se ve como un catálogo
con muchos productos raros.

**El orden del bloque tech es dato medido, no prolijidad.** El contenedor gana
sobre lo que contiene, y cada posición tiene un producto real detrás: Gabinete
antes que Fuente (23 gabinetes traen fuente), Fuente antes que Cooler (27
fuentes nombran su cooler), Gabinete antes que Cooler (268 nombran sus fans),
Cooler antes que CPU (**321 de 646 filas de `CPU` eran disipadores**), Cámara
antes que Monitor ("Camara Wifi Ezviz Baby Call *Monitor*"), Mousepad antes que
Mouse. `Cable` no se detecta por aparición sino por **sustantivo líder**: "Fuente
Segotep 500W ATX *Cables* Largos" nombra los suyos y no es un cable.

**Y el guard tampoco es el lugar para frenar lo que ya tiene categoría.**
`NonTextileGuard` listaba `"router "`, `"teclado mecanico"`, `"mouse gamer"`,
`"monitor led"` y `"fuente atx"` — los cinco productos que nombra tienen
categoría tech propia y el bloque TECH corre antes que el de ropa, así que no los
protegía de nada: les bloqueaba la clasificación correcta. El guard existe para
que un producto no-textil no entre como **ropa**, no para dejarlo sin clasificar.
Antes de agregar algo ahí, preguntarse si el producto tiene dónde ir.

**El sustantivo líder que ya usaba `Cable` se generalizó a Cooler/CPU/PC y a
Gabinete, y las tres veces encontró plata (`pc-builder-deep-taxonomy`, fase
7).** `startsWithAny` ahora pela un `"outlet"` líder antes de comparar contra
cualquier `*_LIDER` — hacía falta para `"Outlet Procesador Intel Core i5
13600KF..."`. Con `KW_CPU_LIDER` (`procesador`/`microprocesador`/`micro
amd`/`micro intel`) corriendo antes que `KW_COOLER`: **146 de 470 filas de
`Cooler` eran CPUs** (`"Procesador AMD Ryzen 9 9950X3D ... (no incluye
cooler)"`, 85 de gama alta) — `cooler` sólo aparecía mencionado como
accesorio. Con `KW_PC_LIDER` al tope de todo `clasificarTech` (antes corría
después de `KW_GPU` y seis checks más): **67 PCs armadas enteras vivían en
`CPU`** (`"PC AMD Ryzen 3 3200G 16GB 1TB SSD WIFI"` competía por el slot cpu)
y **16 más en `GPU`** (`"PC Powered by MSI Ultimate ... RTX 5060 ..."` entraba
al slot gpu como si fuera una placa de video suelta) — 83 PCs enteras
compitiendo como componentes sueltos antes de que el líder cubriera las dos
formas de nombrarlas. Y el slot Gabinete elegía un **service**:
`"service instalación de armado de pc"` ($2.050) ganaba porque `KW_GABINETE`
matchea `"para gabinete"` sin mirar qué nombra el producto — el líder
`bracket|filtro|service|kit|fan|soporte` + `"para gabinete"` como destino, no
como categoría, lo saca.

**El líder tuvo que dejar de pedir permiso: `bracket` y `armado` (fase 8).** El
guard de fase 7 exigía `" para gabinete "` en el mismo título, así que sólo
frenaba a los accesorios que nombraban su destino. `"Bracket Disco SSD para
Xigmatek Gaming X"` ($3.300) se escapaba a `Almacenamiento` y ganaba el slot
del disco por ser lo más barato del pool; `"Bracket Cooler Master Soporte Para
Fan Cooler LGA1700"` hacía lo mismo en `Cooler`. Las **tres** filas del
catálogo que lideran con `bracket` son accesorios, así que `KW_ACCESORIO_LIDER`
abstiene incondicionalmente — los otros tres líderes de
`KW_GABINETE_ACCESORIO_LIDER` siguen condicionales, porque `"Kit de RAM"` y
`"Soporte de Monitor"` sí son productos. Y `armado` entró a
`KW_SERVICIO_LIDER`: las **10** filas que lideran con él son mano de obra
(`"ARMADO DE PC ESPECIAL (No incluye instalación de sistema operativo)"`), ocho
ya estaban en `Otros` y dos se habían ido a `GPU`, donde competían por el slot
gpu del armador. Una PC armada de verdad lidera con `PC`, que es `KW_PC_LIDER`.

⚠️ **Un arreglo de clasificación no se ve hasta el próximo scrape, y eso se
parece exactamente a que no esté arreglado.** La categoría se fija al scrapear
y vive en `productos.categoria`; el armador lee el snapshot de la base, no
reclasifica. El guard de gabinete de la fase 7 funcionaba perfecto en los tests
mientras `/pcs` seguía mostrando el bracket, porque la dev DB traía 205 filas
clasificadas con el código viejo (73 `Cooler→CPU`, 59 `CPU→PC`, 16 `GPU→PC`, 8
`Monitor→PC`, 7 de `Gabinete`). Antes de diagnosticar un bug de taxonomía,
correr el clasificador de HOY sobre los nombres de la base y comparar: si el
drift lo explica, lo que falta es un scrape.

**`Conjunto` es ropa, y corría antes que todo: se llevaba 348 filas de
tecnologia.** `KW_CONJUNTO` incluye `"combo"`, `" kit "`, `" pack "` y `" set "`,
ubicuos en SKUs de hardware, y `CategoryClassifier` lo evaluaba antes de OFICINA
y TECH (correcto *dentro* de ropa, por ADR-4: que un conjunto no quede
first-matched como Musculosa). Contra 212 filas de indumentaria legítimas había
**348 de tecnologia**: 77 bundles mother+CPU invisibles a los slots `mother` y
`cpu`, 41 PCs enteras que `KW_PC_LIDER` nunca veía —corría después—, y 17 RAM, 8
declarando el kit `NxMGB` que la preferencia `ramDual` busca. El arreglo mueve
las dos reglas después de TECH: **316 de 348 se recuperan** (84 Motherboard, 68
Teclado, 49 PC, 17 CPU, 16 GPU) y **212/212 de indumentaria siguen en
`Conjunto`**. Los 32 restantes son gaps de vocabulario aparte (kits de
ventiladores, `"Gaming Kit Tec+Mouse"` abreviado, `"Acces Point"` con el typo de
origen, sets de valijas).

⚠️ Lo encontró el **set de evaluación** ([`ml-tests/eval/`](../ml-tests/eval/README.md)),
no un test: `TechCategoryClassifierTest` **afirmaba el bug** como correcto
(`"Gabinete Gamer Kit c/Fuente 500W"` → `Conjunto`, con el comentario *«"kit "
gana, ver ADR-4»*), contradiciendo el encabezado de su propia sección. Un test
puede congelar un defecto; una segunda opinión sobre el catálogo real, no.

**Un keyword de comida sin padear vivía adentro de dos marcas, y ahí era un
acabado, no un sabor.** `"mate"` sin padear en `KW_COMIDA` matcheaba dentro de
*Xigmatek* y *Ultimate* — en 5 de 7 nombres reales es un acabado (*matte*), no
yerba mate. Se sacó de `KW_COMIDA`; `"yerba"` sigue cubriendo la yerba real.

### Índices y señales

**Los meses se inventaban de una CUENTA, no de una fecha.**
`SenalEnricher` calculaba `mesesAtras = historial.size()/4` y `SenalCalculator`
trataba `size()-13` como "hace 12 meses" — pero `precio_historico` registra
CAMBIOS de precio, no muestras mensuales (ver `DATABASE.md`). Un producto con 4
cambios en una semana y uno con 1 cambio en 8 meses compartían la misma cuenta.
Desde `indices-service` el "hace cuánto" se resuelve por FECHA:
`IndiceService.deflactorParaRubro(rubro, desde, hasta)` toma `desde`/`hasta`
de las fechas reales del historial, nunca de una posición en la lista.

**`rubro=tecnologia` deflacta por dólar oficial, el resto por IPC.**
`DeflactorPorRubro.resolver(rubro)` es la única regla: `"tecnologia".equals(rubro)
→ USD_OFICIAL`, cualquier otro valor → `IPC`. Deflactar una GPU por la canasta
del IPC responde la pregunta equivocada — una GPU sube y baja con el dólar, no
con la inflación general.

**La fuente primaria de IPC (`argentinadatos.com/v1/finanzas/indices/inflacion`)
publica la TASA mensual, no un nivel.** `valor` puede ser negativo y la serie
arranca en 1943 muy por debajo de 100 — es variación porcentual, no el índice
de INDEC. `ArgentinaDatosIpcFuente.parsear` la integra a un nivel sintético
**anclado en diciembre 2016 = 100** (la base real del IPC nacional de INDEC) y
**descarta todo lo anterior**: componer los 80 años completos, con la hiper del
'89 adentro, da `1.1e10` ya en 1984 y desborda `NUMERIC(14,4)` — y como el
upsert es un solo batch, **abortaba entero y no se persistía ni un punto de
IPC**, en silencio (encontrado arrancando el backend de verdad, 2026-09-18; 2077
tests en verde con fixtures de 5 puntos no lo vieron). `Deflactor` necesita un
NIVEL para el cociente `valorEn(hasta)/valorEn(desde)`. Consecuencia para quien
lea `GET /api/indices`: el `ultimoValor` de IPC no es el número de INDEC, es una
base 100 propia — sólo las RAZONES entre dos puntos son comparables contra la
realidad, el valor absoluto no.

**El fallback de IPC (`datos.gob.ar`, series id `148.3_INIVELGENERAL_DICI_M_26`)
está muerto hoy** (`{"errors": [...]}`) — ya lo estaba en `InflacionService`,
antes de este cambio. `DatosGobIpcFuente` lo trata como una falla ordinaria de
la cadena (`FuenteIndiceException`, no una NPE), pero en la práctica la cadena
de IPC hoy tiene una sola fuente viva. Reemplazar el id es trabajo pendiente
(ver Problemas conocidos), no algo que este cambio resolviera.

**Un factor nunca viaja sin marcar.** `Confianza` (`observado` / `extrapolado`
/ `sin_datos`) sale de `IndiceService.deflactor` y llega hasta la UI por dos
caminos: `SenalCompra.confianzaDeflactor` (badge de producto) y
`GET /api/recomendacion` (campos `confianza` + `diasExtrapolados`). Serie
vacía → `Deflactor.NEUTRO` (`factor=1.0`, `SIN_DATOS`), nunca una tasa
hardcodeada — los `3.5%`/`150% interanual` de 2024 que `InflacionService`
servía indistinguibles de un dato real ya no existen en `main/`. En el
frontend, `ui/ipc-badge.jsx` (montado en `Topbar`) pinta el punto de confianza:
ámbar para `extrapolado`, gris sin valor para `sin_datos`.

`GET /api/inflacion` no existe más; es `GET /api/indices`
(`{ ipc: ResumenIndice, usd: ResumenIndice, actualizado }`) — contrato completo
en [`docs/API_REFERENCE.md`](./API_REFERENCE.md).

### Frontend: layout

**El selector de suplementos scrollea adentro de su tarjeta, y el header fijo
depende de un `bg-s1` que no se ve.** Con 33 subtipos, dejar crecer el picker
empuja presupuesto, botón y resultados abajo de todo — en un teléfono son ~1000px
de chips que hay que recorrer de nuevo en cada "Regenerar". `SuplementosPanel` le
pasa `max-h-[min(56vh,440px)] overflow-y-auto bg-s1` y `stickySelected`.
Ese `bg-s1` **no es decorativo**: la fila "Seleccionados" usa `bg-inherit`, que
hereda el color **computado** del padre, así que sin fondo propio en esa raíz
resuelve a transparente y los chips pasan por debajo a la vista. `stickySelected`
es opt-in en `MultiSelectTags` por la misma razón: un `sticky` sin contenedor con
scroll se pega al viewport de la página, que no es lo que nadie quiere.

**El picker de categorías del outfit scrollea adentro de su tarjeta, y sus chips
tienen que ser únicos entre grupos.** `OutfitsPanel` usa el mismo
`MultiSelectTags` que el armador de suplementos, con `stickySelected` y
`max-h-[min(56vh,440px)] overflow-y-auto bg-s1`. Antes eran cuatro acordeones
colapsables, que cambiaban un problema por otro: colapsados no se veía qué estaba
seleccionado sin abrir cada grupo; expandidos, 43 chips empujaban presupuesto,
botón y outfit abajo del fold. Medido en un viewport de 430×860: 693px de
contenido dentro de 440px de picker, y la página **no** scrollea.

Dos cosas que se rompen en silencio si se tocan:

- **`bg-s1` va en el picker, no sólo en la tarjeta.** La fila "Seleccionados" usa
  `bg-inherit`, que hereda el color **computado** del padre: sin fondo propio en
  esa raíz resuelve a transparente y los chips se ven pasar por debajo. Verificado
  con `getComputedStyle`: tiene que dar un color, no `rgba(0,0,0,0)`.
- **`MultiSelectTags` anima con `layoutId={tag}`**, que exige que cada tag esté
  montado en **exactamente un** lugar. `PICKER_GROUPS` se **deriva** de
  `BUILDER_GROUPS` en vez de escribirse a mano, y hoy ninguna categoría se repite
  entre grupos. Duplicar una rompe el invariante sin error: el síntoma es un chip
  que deja de animar. Los dos `OutfitPanel` (gym/casual) no colisionan porque la
  barra de tabs monta uno solo (`tab === 'outfit' && ...`).

### Docker

**Docker:**
- `VITE_API_BASE_URL` es **build-time** (Vite lo hornea en el bundle) → cambiarlo exige `docker compose up --build`.
- En `DATABASE_URL` el host es **`postgres`** (nombre del servicio), no `localhost`.
- Triángulo que tiene que cerrar: `APP_CORS_ALLOWED_ORIGINS` (`:8080`) ↔ `VITE_API_BASE_URL` (`:3000`) ↔ los port mappings.
- `pgdata`/`models`/`logs` son volúmenes nombrados → sobreviven a `docker compose down`.
- Sin Docker en el sandbox de dev: el smoke real se valida en CI (`.github/workflows/docker-smoke.yml`).

---
