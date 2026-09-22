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

- [ ] **T1 — nav-config.** `Explorar`→`Armadores` con los tres armadores,
  `Marcas` como link de primer nivel, `Guardados` como link a `/favoritos`.
  Verificación: `npm test`.
- [ ] **T2 — `/favoritos` absorbe outfits y PCs guardadas.** Secciones nuevas en
  `FavoritosPanel`, wiring en `AppLayout`. Verificación: `npm test`.
- [ ] **T3 — borrar `/armadores`.** Ruta, panel, test y lazy import.
  Verificación: `npm test`.
- [ ] **T4 — docs.** `CLAUDE.md` (bloque de rutas del frontend).

## Progreso

Sin empezar.
