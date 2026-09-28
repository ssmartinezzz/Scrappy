# Frontend: rutas y vistas

> Rutas, nav, /favoritos, /apidocs. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Frontend (rutas)

Catálogo `/catalogo` · Picks `/picks(/:categoria)` · Para ti `/recomendados` ·
Cronjobs `/cronjobs` · Marcas `/marcas` · Suplementos `/suplementos` ·
Análisis `/analisis/mercado` · `/analisis/oportunidades(/:badge)` ·
Comparar `/grupos` · Cuotas `/financiacion` · Favoritos `/favoritos` ·
Outfits `/outfits` · PCs `/pcs` · Historial de precios `/historial/:key`.
`/tendencias` redirige a `/analisis/mercado`.

**El nav tiene dos menús y cuatro links, y la división es semántica**
(`nav-guardados-armadores`, 2026-09-22): el menú **Armadores** nombra los tres
armadores y nada más (`/outfits` · `/suplementos` · `/pcs`); el menú
**Análisis** las cuatro vistas de análisis; y **Guardados** es un *link*, no
un menú, porque hay un solo destino. `Marcas` salió a primer nivel: es una
vista de exploración del catálogo, no un armador.

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
| ⚠️ **Hay UN solo panel de detalle, y un llamador flaco lo dibuja a medias** | `DetailPanel` es el mismo archivo para `/catalogo` y para un ítem de un outfit o una PC guardada. Pero un ítem guardado es la **foto** de `saved_outfit_item`/`saved_pc_item` (`slot, sitio, nombre, precio, url, img, marca`): sin `ml` no hay gauge, segmento, percentil ni z-score; sin `categoria` no hay box plot; y "Ver historial completo" está gateado por `p?.key`. Se veía como otro panel, y era el mismo con menos datos. Los dos repositorios ya hacían `LEFT JOIN productos` para `precioActual`, así que mandan también `producto_key` (`null` si el producto ya no existe) y el panel se hidrata solo con `GET /api/producto/{key}` cuando le falta el `ml`. **La foto gana sobre el catálogo vivo** (`{ ...vivo, ...item }`): el panel muestra el mismo nombre y precio que la tarjeta desde la que se abrió |

El estado no se movió: `savedOutfits`/`savedPcs` ya vivían en el reducer de
`AppLayout` y ya los cargaba esta misma ruta.
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
