# Frontend: rutas y vistas

> Rutas, nav, /favoritos, /apidocs. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Frontend (rutas)

Catálogo `/catalogo` · Picks `/picks(/:rubro(/:categoria))` · Para ti `/recomendados` ·
Cronjobs `/cronjobs` · Marcas `/marcas` · Suplementos `/suplementos` ·
Análisis `/analisis/mercado` · `/analisis/oportunidades(/:badge)` ·
Comparar `/grupos` · Cuotas `/financiacion` · Favoritos `/favoritos` ·
Outfits `/outfits` · PCs `/pcs` · Historial de precios `/historial/:key`.
`/tendencias` redirige a `/analisis/mercado`.

El panel "Más filtros" del catálogo tiene una sección **Deporte** con un chip por deporte
presente en `facets.deportes` (single-select: otro clic lo desmarca). Manda
`?deporte=<codigo>` a `/api/data`, suma al contador del panel y lo limpia "Limpiar".
Las etiquetas viven en `lib/deportes.js`; un código desconocido se muestra tal cual.

**`/picks` tiene una card "Gymrat" al final del carrusel de rubros** (y una solapa
equivalente en la barra de la galería). No es un rubro: `lib/rubros.js` sigue
siendo el dominio de la base. La card pide `fetchMejores('', { gymrat: true })`
→ `GET /api/mejores?gymrat=true`, que junta los productos `gymrat` de las marcas
gym (Bulks, Fuark, Fursten, Baesics, Monkyforce y Under Armour; ver
`SiteClassification.esMarcaGymPicks`); `'gymrat'` es sólo la clave interna de estado/cache del panel y nunca
viaja como `rubro`. Dentro de la galería de Gymrat hay un selector **Hombre | Mujer** (arranca en
Hombre): pide `fetchMejores('', { gymrat: true, genero })` → `GET /api/mejores?gymrat=true&genero=hombre|mujer`
y cachea cada género por separado (`gymrat:hombre`, `gymrat:mujer`); la card de la entrada sigue pidiendo
Gymrat sin género.

**Cada paso de `/picks` es una ruta**: `/picks` (rubros) · `/picks/<rubro>` (galería; el neutro es
`/picks/todos`) · `/picks/<rubro>/<categoria>` (detalle, slug de `lib/cat.js` `slugify`) ·
`/picks/gymrat/<hombre|mujer>[/<categoria>]`. `PicksPanel` lee todo de la URL y `App.jsx` lo monta en
**una sola** ruta `picks/*`: rutas separadas lo remontarían y perdería la cache por rubro. Un link viejo
`/picks/<categoria>` (primer segmento que no es rubro) se redirige con `replace` al rubro dueño de la categoría.
El detalle muestra **todos** los productos de la categoría como carrusel **o** como lista, nunca ambos; el
toggle se recuerda en `localStorage` (`picks:viewMode`), igual que `/favoritos`.

**`/picks` en `perfumeria`**: la galería suma tres cards grises "Próximamente" (Maquillaje, Cuidado de la piel, Cabello) que NO son categorías: son un mock de frontend, sin filas en la base ni keywords, definido en
`PERFUMERIA_PROXIMAMENTE` (`lib/perfumeria.js`; borrar la entrada cuando exista la categoría real: `Desodorante` ya lo es y salió del mock). Dentro de la categoría
`Perfume` hay chips por subcategoría (`PERFUME_SUBCATEGORIAS`) que filtran en el cliente por el `subCategoria` de cada pick.

**`/analisis/oportunidades` se filtra por rubro y juzga con `/api/data`.** Una fila de chips
(`RUBROS` de `lib/rubros.js`) elige el rubro y lo deja en la URL como `?rubro=`, así que se
comparte y sobrevive al drill-down y al "Volver" (`lib/oportunidades.js`: `rubroDeQuery`,
`rutaOportunidades`). El preview, las barras de la distribución y el drill-down
(`/analisis/oportunidades/:badge`) leen de `GET /api/data` con el mismo `rubro` y la misma
`excluirCategoria` fija (`EXCLUIR_CATEGORIAS` = `Otros`, `Alimentos`, un único dueño en
`lib/oportunidades.js`), de modo que una barra y la lista detrás coinciden. El preview ordena `all_time_low` por `baja_historica` (la mayor caída relativa contra su
máximo histórico), `verified_deal` por `desc_pct` (descuento real) y `below_market` por `mercado_asc` (el más barato
contra su mercado: `ml_score` ascendente; `composite`/`ml_score` ordenan DESC, el menos barato
primero). Las tarjetas del preview se deduplican por (nombre, sitio) —el catálogo puede tener el mismo
producto bajo varias urls—, conservando la primera en el orden del servidor; por eso se piden 16
y se muestran hasta 8. El drill-down no deduplica. El porcentaje de cada barra
es contra el total del mismo rubro y exclusión sin badge.

**Logos de `/marcas` son archivos del repo** (`frontend/public/brand-logos/`,
mapa marca → archivo en `src/lib/brandLogos.js`, fuentes y licencias en el
`README.md` de la carpeta). Clearbit, que se usaba antes, ya no resuelve
(NXDOMAIN, 2026-10-22). Una marca sin entrada cae al badge de iniciales; un
logo blanco va en `LOGOS_SOBRE_OSCURO` para que se dibuje sobre placa oscura.

**Un solo header fijo** (`.app-header` en `AppLayout`): Topbar + nav, `position: fixed`
con el safe-area de iPhone, y `.app-shell` reserva su alto con `padding-top:
var(--sticky-offset)` (medido por ResizeObserver sobre el header entero). No es
`sticky` a propósito: el catálogo scrollea un contenedor interno (`.grid-wrap`) y el
detalle de picks scrollea la ventana; dentro de un shell de `100dvh` un header sticky
se iba con la ventana. Toda barra sticky de página va a `top: var(--sticky-offset)`.
En mobile el nav es la **misma fila** que en escritorio, scrolleable de costado
(`.primary-nav-scroll`, lleva el destino activo a la vista), más el ☰ con la lista entera.

**La marca es Scrappy** y vive en un solo archivo: `public/favicon.svg` es la pestaña y
lo que dibujan `BrandMark`/`BrandLockup` (`components/brand/Brand.jsx`) en Topbar,
splash y login. `public/apple-touch-icon.png` (180×180, iOS no acepta SVG) se renderiza
desde ese SVG sin esquinas redondeadas; si cambia el SVG, se regenera.

**El Topbar es una sola fila** (logo · rubros · ML/IPC · usuario). La fila de
chips por sitio se sacó (2026-10-22): ya nada setea `sitioFiltro` (queda en
`''`); una tienda de marca propia se filtra por marca.

**El nav tiene dos menús y cuatro links, y la división es semántica**
(`nav-guardados-armadores`, 2026-09-22): el menú **Armadores** nombra los tres
armadores y nada más (`/outfits` · `/suplementos` · `/pcs`); el menú
**Análisis** las cuatro vistas de análisis; y **Guardados** es un *link*, no
un menú, porque hay un solo destino. `Marcas` salió a primer nivel: es una
vista de exploración del catálogo, no un armador.

**`/historial/:key` dibuja una curva suave, pero el dato sigue siendo un escalón.**
`precio_historico.fecha` es un `DATE` y el upsert escribe una fila sólo cuando
el precio cambia: el precio rige hasta el próximo registro. Por eso el eje X es
temporal (no un slot por registro), hay **una fila por día** (`serieDiaria`),
una ventana que empieza entre registros abre con el precio vigente, toda
ventana cierra hoy, y el **promedio se pondera por tiempo**. La curva
(`monotone` + `connectNulls`) sólo se ancla en los cambios y en los extremos de
los datos, así que entre dos cambios muestra precios que no existieron: el
tooltip y el punto de hover leen `vigente`, el precio que rigió ese día. Un
solo registro ya alcanza (es una recta hasta hoy); sólo una ventana de cero
días no se dibuja. Los rangos (`1D`…`1A`, `Todo`) acotan gráfico **y** stats,
pero **la vista no se recorta al primer registro**: `Todo` muestra como mínimo
12 meses (la historia entera si es más larga), y los datos arrancan adentro en
`desdeDatos`; las stats cuentan sólo desde ahí. Si no, un producto visto hace
una semana estiraba esa semana a todo el ancho. El sparkline de `DetailPanel`
usa la misma serie pero acotada a los datos: en 280px, un año vacío no deja
ver nada.
El eje Y es el del **producto**, nunca uno fijo por categoría: una escala de
categoría aplasta un -20% a unos pocos píxeles. La categoría entra como
contexto — mediana y banda q1–q3 de `distribucionCategorias` (`/api/tendencias`,
precios **unitarios**, se multiplican por `cantidadUnidades`) — y la mediana se
mete en la escala sólo si está a menos de un rango propio de la serie; más
lejos, un aviso "fuera de escala". Sin stats o con `n < 5`, se abstiene.
Lógica pura en `lib/serieHistorial.js`.

⚠️ **`/armadores` NO existe más.** Era la misma idea que `/favoritos` en otra
ruta, y el reparto no cerraba: los outfits guardados habían salido de
`/favoritos` hacia `/armadores` en `saved-pcs-armadores`, mientras `Outfits`
colgaba del menú `Guardados` apuntando al **armador**, no a lo guardado.
`/favoritos` junta ahora las tres colecciones, y **una PC guardada se trata
exactamente como un outfit**: el carrusel ya tenía el slide de *colección*
(`kind:'outfit'` en `TiltCarousel` — un collage de sus miembros más una tira
expandible debajo), que no es algo propio de la ropa sino "una cosa guardada
que tiene partes". Los `picks` de una PC ya traen `{nombre, img, sitio,
precio}`, la misma forma que `OutfitCollage` y la tira consumen, así que el
slide de PC no adapta nada. Los slides de outfit habían salido del carrusel en
`saved-pcs-armadores`; esto los devuelve.

| | |
|---|---|
| **Una sola tira abierta a la vez** | El estado es `{coleccion:'outfit'\|'pc', id}`, no dos banderas: abrir una PC cierra el outfit que estuviera abierto. Dos estados separados dejarían dos tiras apiladas debajo del mismo carrusel |
| **Renombrar y eliminar viven en la vista de LISTA** | `SavedOutfitCard`/`SavedPcCard`, reusadas sin redibujar. En un slide no hay dónde ponerlos sin taparle el collage — el mismo reparto que la vista tenía antes de `saved-pcs-armadores` |
| **El carrusel aparece con CUALQUIER cosa guardada**, no sólo con productos | Antes el cuerpo entero colgaba de `items.length`, así que borrar el último favorito habría hecho desaparecer una PC guardada de la pantalla |
| **El contador del header cuenta SÓLO productos** | Dice "N productos guardados"; sumarle outfits y PCs haría la frase falsa. Hay un test que lo fija |
| ⚠️ **Hay UN solo panel de detalle, y un llamador flaco lo dibuja a medias** | `DetailPanel` es el mismo archivo para `/catalogo` y para un ítem de un outfit o una PC guardada. Pero un ítem guardado es la **foto** de `saved_outfit_item`/`saved_pc_item` (`slot, sitio, nombre, precio, url, img, marca`): sin `ml` no hay gauge, segmento, percentil ni z-score; sin `categoria` no hay box plot; y "Ver historial completo" está gateado por `p?.key`. Se veía como otro panel, y era el mismo con menos datos. Los dos repositorios ya hacían `LEFT JOIN productos` para `precioActual`, así que mandan también `producto_key` (`null` si el producto ya no existe) y el panel se hidrata solo con `GET /api/producto/{key}` cuando le falta el `ml` (y lo pide siempre que haya `key`, por las promos: ver § Promos de pago). **La foto gana sobre el catálogo vivo** (`{ ...vivo, ...item }`): el panel muestra el mismo nombre y precio que la tarjeta desde la que se abrió |

El estado no se movió: `savedOutfits`/`savedPcs` ya vivían en el reducer de
`AppLayout` y ya los cargaba esta misma ruta.

**El opt-out de los mails de baja de precio vive en `/favoritos`**, bajo la cabecera
(`AvisosBajaPrecio`, entrado a `FavoritosPanel` por la prop `avisos` desde `FavoritosRoute`): un
checkbox "Avisarme por mail cuando un favorito baje más de 5%" contra
`GET`/`PUT /api/notificaciones/preferencia`. No se renderiza mientras la preferencia es
desconocida. Una cuenta sin email ve en cambio "Para recibir avisos, pedile a un administrador que
cargue tu email" (sin formulario: el email lo carga sólo un ADMIN desde `/admin/manage/users`). Si el guardado falla conserva el
valor anterior y lo dice. Es el destino del enlace del pie del mail.
`/apidocs` — **Consola API**, pública y **sin entrada en el nav**: no hay
botón ni link en ninguna parte de la app, para ningún rol. Se llega tipeando
la URL. Es una **página standalone**: se rutea en `App.jsx` como hermana de
`/splash`, fuera del árbol de `AppLayout`, así que swagger-ui se queda con el
viewport entero y no hereda sidebar ni topbar. Su único adorno es un link
"← Volver" (un visitante anónimo que lo clickea cae en `/login`, que es lo
correcto: la app sí está gateada).

⚠️ **Lo que protege la superficie administrativa es el BODY, no la ruta.**
`GET /api/openapi.yaml` es `PERMIT` en `ApiRoutePolicy`, y
`OpenApiDocumentController` **filtra al servir**: borra toda operación con
`x-access: ADMIN` y descarta entera la path que se queda sin ninguna. De **86**
operaciones documentadas viajan **51** — las 8 `PERMIT` + las 43
`AUTHENTICATED`, exactamente lo que alcanza un VIEWER. Las 35 `ADMIN`
(`DELETE /api/db/productos`,
`/api/agent/**`, `/api/usuarios/**`, `POST /api/scrape`…) **nunca cruzan el
cable**. Filtrar en el frontend sería teatro: el documento completo igual
viajaría y se leería en la pestaña Network.

El recurso del classpath **no se toca** — `OpenApiRouteCoverageTest` lo afirma
byte-idéntico a `docs/openapi.yaml`, y esa garantía es sobre el artefacto, no
sobre la respuesta. El deny-list de try-it-out
(`frontend/src/lib/apiDocs/nonExecutableOperations.js`) bajó de 10 a **3**
entradas por lo mismo: las siete que se fueron eran `ADMIN` y ya no llegan a
la página; quedan las tres de auth, que mutan la sesión de quien llama.
`MlStatusPanel`, `GpuTrainingOverlay` y `AgentChatPanel` son componentes montados
a nivel `AppLayout`, no rutas.

---

## Promos de pago: badge, iconos y detalle

Las promos llegan hechas del servidor (ver [`API_REFERENCE.md`](./API_REFERENCE.md): `mejorPromo` y `medios` en cada fila de `/api/data`, `promos` en
`/api/producto/{key}`). El frontend **sólo formatea**: no decide cuál es la mejor ni inventa lo que no llegó (CODE-5: sin dato, silencio).

| Pieza | Qué hace |
|---|---|
| `lib/promos.js` | Dueño único del texto: `textoPromoCorta(mejorPromo)` (`"15% Transferencia"`, `"6 sin interés Santander"`, y con red `"12 sin interés Macro Visa"`: medio, espacio, red), `textoPromoLarga(promo)` (el beneficio sin el medio), `vigenciaTexto` (`Del 10/11 al 16/11`, sin pasar por `Date`). Un recargo o una promo sin cifra devuelve `''` y no se pinta |
| `PromoBadge` (`ProductCard`) | Cupón + `mejorPromo`, junto a `StockBadge`. Sólo si la fila trae `mejorPromo`. Una línea con elipsis; el texto entero va en el `title` |
| `MediosPago` (`PromoBadge.jsx`) | Fila de iconos de `p.medios` en el orden del servidor (Mercado Pago, Transferencia y Santander primero), tope `MEDIOS_VISIBLES = 4` y un `+N` con el resto en el `title` |
| `MedioPagoIcon` | **Monogramas propios**, dibujados a mano (forma, color de marca e iniciales); no son los logos oficiales ni se descarga nada. La clave es el nombre canónico del seed `medio_pago`, con tildes (`Nación`, `Ualá`). Un medio sin monograma cae al **glifo genérico de tarjeta**: nunca se inventa una marca. El color de cada uno es un token `--medio-*` de `styles.css` (17 monogramas) |
| `PromosDetalle` (`DetailPanel`) | Sección "Promos y medios de pago": icono, beneficio, `medio` (+ red), alcance (`Todo el sitio` / `Este producto`) y vigencia. Sin promos no hay sección |

**`DetailPanel` pide `GET /api/producto/{key}` siempre que la fila trae `key`**, aunque ya venga entera: las promos viven sólo en el detalle, no en la fila. Antes sólo
pedía cuando faltaba el `ml`. El costo es **+1 request por apertura del panel**; el producto vivo sólo se usa si la fila llegó flaca (`!product.ml`), las promos
se toman siempre. Una fila sin `key` (un ítem guardado cuyo producto ya no existe) no pide nada y no muestra promos.

---

## Estado de la corrida y del ML: un stream, no intervalos

Nada en el frontend pollea el estado. `EventStreamProvider` (`hooks/`, montado en
`App.jsx` dentro de `AuthGate`) mantiene UNA conexión a `GET /api/events` mientras
haya sesión y la cierra al desloguearse o desmontar. La lectura del stream vive en
`lib/eventStream.js` (puro, con `fetch`/`sleep`/`random`/reloj inyectables): parser
SSE incremental, reconexión con jitter completo (1 s → 30 s, se resetea con el primer
frame), watchdog de 45 s sin bytes, y corte definitivo ante 401 tras el refresh o 403.

| Se lee con | Devuelve |
|---|---|
| `useScrapeStatus()` | el status de la corrida (forma de `GET /api/status`), `null` antes del primer `snapshot` |
| `useMlStatus()` | `{ estado, training, backfill, resultado }`; `resultado` es lo que respondería `/api/ml/resultado` |
| `useStreamState()` | `{ phase: 'idle'\|'connecting'\|'live'\|'reconnecting'\|'closed' }` — la salud de la conexión; `idle` fuera del provider |
| `useStreamEvent(nombre, fn)` | suscripción a un evento: `db.changed`, `resync`, `ml.status`, y `training` (snapshot + eventos + relectura de `resync`) |

Fuera del provider los hooks devuelven "sin stream" y cada pantalla sigue funcionando
con su lectura de montaje: por eso los tests que montan un componente suelto no
necesitan el provider. Quién consume qué: el splash (`useScrapeStatusPolling`, que ya
no pollea y mantiene su nombre), `AppLayout` (overlay GPU, banner ML, `scrapeStatus` del
reducer), `Topbar` (indicador "Entrenando ML"), `MlStatusPanel` (progreso y toast de
fin), `CronjobsPage` y `CronJobCard` (releen al llegar `db.changed` de `cron_execution`
o un `resync`). Lo único con reloj que queda es cosmético: el `tick` de 2 s que
refresca el "tiempo transcurrido" de `MlStatusPanel`; no hace un request. El porcentaje del
splash **no** tiene reloj: sale de `sitiosTerminados(progreso)` (`lib/scrapeProgress.js`:
`done`+`error`+`skipped` sobre el total de `sitios`), se muestra como `x/N sitios` y puede
bajar al resetear o retomar una corrida. Ver [`GOTCHAS.md`](./GOTCHAS.md) → "El estado llega
por un stream".
