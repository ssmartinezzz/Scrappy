# nav-guardados-armadores — un menú de armadores, un lugar para lo guardado

> Pedido del usuario (2026-09-22): "Yo sacaría la vista armadores, que es la
> misma vista que /guardados/favoritos. En el nuevo guardados/favoritos, sin
> tocar la vista, tendría como ya se tiene favoritos; outfits y ahora sumarles
> las PC guardadas. Con /Explorar - renombraría a /Armadores y dejaría como
> opciones lo de /outfits lo de /suplementos y /pcs".

## Objetivo

Que "Armadores" nombre los tres armadores y que todo lo guardado —productos,
outfits y PCs— viva en una sola pantalla.

## Problema

`/armadores` y `/favoritos` son dos rutas para la misma idea. Hoy:

- `/favoritos` → productos favoritos (carrusel + lista).
- `/armadores` → outfits guardados + PCs guardadas. Los outfits guardados
  vivían en `/favoritos` hasta `saved-pcs-armadores`, que los mudó acá
  (`FavoritosPanel.jsx:52`).
- El menú `Explorar` agrupa `Marcas`, `Suplementos`, `PCs` y `Armadores` — tres
  armadores y una vista de análisis, con `Outfits` colgando del menú
  `Guardados`, donde `/outfits` es el **armador**, no lo guardado.

## Decisiones

- **D1 — `Guardados` pasa a ser link directo a `/favoritos`**, con tres
  secciones: productos, outfits guardados y PCs guardadas. Elegido por el
  usuario sobre la alternativa de tres destinos separados.
- **D2 — `/armadores` se elimina**: ruta, `ArmadoresPanel.jsx`,
  `ArmadoresPanel.test.jsx` y la entrada del menú. `SavedOutfitCard` y
  `SavedPcCard` se reusan tal cual dentro de `FavoritosPanel` — "sin tocar la
  vista" es literal, no se rediseña nada.
- **D3 — `Explorar` se renombra `Armadores`** y queda con `/outfits`,
  `/suplementos`, `/pcs`, en ese orden.
- **D4 — `Marcas` sale como link de primer nivel**, al lado de Catálogo, Picks y
  Para ti. Elegido por el usuario: no es un armador, y el menú renombrado sólo
  puede tener armadores.
- **D6 — una PC guardada se trata EXACTAMENTE como un outfit** (pedido del
  usuario, 2026-09-22: *"las PC guardadas quiero que sigan de estar en el
  carrousel, como los outfits, literalmente que sean iguales a los outfits"*).
  El carrusel ya tenía el slide de **colección** (`kind:'outfit'`): un collage
  de sus miembros más una tira expandible debajo. No es algo propio de la
  ropa, es "una cosa guardada que tiene partes", así que una PC lo usa tal
  cual. Sus `picks` ya traen `{nombre, img, sitio, precio}`, exactamente la
  forma que `OutfitCollage` y la tira consumen: no hace falta adaptar nada.
  Los slides de outfit habían salido del carrusel en `saved-pcs-armadores`
  (el comentario de `FavoritosPanel` lo decía); esto los devuelve.
- **D7 — una sola tira abierta a la vez**, identificada por colección + id
  (`{coleccion:'outfit'|'pc', id}`). Dos estados separados dejarían dos tiras
  apiladas debajo del mismo carrusel.
- **D8 — renombrar y eliminar viven en la vista de LISTA**, no en el carrusel:
  no hay dónde ponerlos en un slide sin taparle el collage. Es el mismo
  reparto que tenía la vista antes de `saved-pcs-armadores`.
- **D5 — el estado no se toca.** `savedOutfits`/`savedPcs` ya viven en el
  reducer de `AppLayout` y ya los carga la misma ruta; sólo cambia quién los
  renderiza.

## Alcance autorizado

`frontend/src/components/nav/**` · `frontend/src/components/FavoritosPanel.jsx`
· `frontend/src/components/AppLayout.jsx` · `frontend/src/App.jsx` · sus tests ·
`CLAUDE.md`. **Fuera de alcance**: el backend, y los arreglos del armador de PCs
(van en `odd/tasks/pc-builder-top-tier.md`).

## TDD

Modo estricto. Runner: `npm test` en `frontend/`. RED observado antes de cada
implementación.

## Tareas

- [x] **T1 — nav-config.** `Explorar`→`Armadores` con los tres armadores,
  `Marcas` como link de primer nivel, `Guardados` como link a `/favoritos`.
  Verificación: `npm test`.
- [x] **T2 — `/favoritos` absorbe outfits y PCs guardadas.** Secciones nuevas en
  `FavoritosPanel`, wiring en `AppLayout`. Verificación: `npm test`.
- [x] **T3 — borrar `/armadores`.** Ruta, panel, test y lazy import.
  Verificación: `npm test`.
- [x] **T4 — docs.** `CLAUDE.md` (bloque de rutas del frontend) + `ARCHITECTURE.md`.
- [x] **T5 — las PCs al carrusel, iguales a los outfits** (D6/D7/D8).
  Verificación: `npm test`.

## Progreso

**4/4 completas**, rama `feat/nav-guardados-armadores` sobre `master` `1f22100`.
Commit único: `05aa4f8` (+ este doc).

Verificación observada, no inferida:

- `npx vitest run`: **355/355**, 42 archivos. RED observado antes de cada
  implementación (T1 4 fallos en `nav-config.test.js` · T2 8 fallos en el
  `FavoritosPanel.test.jsx` nuevo).
- `vite build` limpio con `VITE_API_BASE_URL` seteada (es build-time y el
  config falla a propósito sin ella).
- **Chequeo visual real** en un viewport de 390×860, con el componente montado
  sin backend y un fixture de 3 productos + 2 outfits + 1 PC. El carrusel
  emite **6 slides**, y los tres de colección (2 outfits + 1 PC) son los
  únicos con `aria-expanded`. Activando el slide de la PC: `aria-expanded`
  pasa a `true`, `aria-controls` apunta a `favoritos-pc-miembros-1`, y la tira
  lista sus tres componentes con sitio y precio (`Biostar B650M DDR5 AM5 ·
  venex · $72.200` …). `scrollWidth == innerWidth` en todo momento y la única
  entrada de consola es un `favicon.ico` 404 del propio preview. Los tests
  unitarios no pueden ver layout ni el comportamiento real del coverflow, así
  que esto no es opcional.
- `grep` sobre `frontend/src` y `frontend/e2e`: no queda una sola referencia
  viva a `/armadores` fuera de los comentarios que explican por qué se fue.
