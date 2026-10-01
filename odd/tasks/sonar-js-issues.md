# Sonar JS issues

**Objetivo**: cerrar los 287 issues `javascript:*` abiertos en SonarCloud
(`ssmartinezzz_Scrappy`, master `a59cef2`) en un solo PR, sin cambiar lo que
el usuario ve salvo donde el issue *es* el defecto (teclado, errores de red).

**Problema**: 41 promesas sin rama de rechazo (S9383) terminan en
`unhandledrejection` y dejan la UI con datos viejos sin avisar; 23 elementos
clickeables no se pueden usar con teclado (S1082). El resto son smells.

**Fuera de alcance**: `authSession.js:46` (S2245) — falso positivo aceptado:
`crypto.randomUUID()` exige contexto seguro y rompe LAN por HTTP.

**Constraints**
- TDD estricto (sesión). Runner: `cd frontend && npx vitest run`.
  Cambio de comportamiento (T1, T2) → test rojo observado antes del arreglo.
  Refactor (T3, T5, T6) → los tests existentes pasan **sin editar** (CODE-2).
- Cada task: `npx vitest run` verde + `VITE_API_BASE_URL=http://localhost:3000 npm run build` OK.
- Un commit por task (COMMIT-4), conventional, sin atribución de IA.
- Un `<div onClick>` pasa a `<button type="button">` con estilos reseteados
  para que el layout no cambie; nunca `role="button"` sobre un div (S6848).
- Fuente de verdad del inventario: `GET /api/issues/search?componentKeys=ssmartinezzz_Scrappy&languages=js&resolved=false`.

## Tareas

- [x] **T1** Promesas flotantes — S9383 (41)
- [x] **T2** Accesibilidad — S1082, S6848, S6819, S6772, S6853, S6845, S6847, S6850, S9379 (55)
- [x] **T3** React — S6478, S6479, S6481 (32)
- [x] **T4** Tests — S9020, S9027, S7763 en tests (42)
- [x] **T5** Legibilidad — S3358, S3776, S4624 (47)
- [x] **T6** Limpiezas mecánicas — resto de reglas (69 listados, 67 arreglados)
- [x] **T7** Verificación final: suite + build + e2e browser + re-análisis Sonar del PR

## Criterios de aceptación

- El análisis de Sonar sobre el PR no deja issues JS abiertos salvo S2245
  y los que se registren abajo como no arreglables, con motivo.
- Suite vitest y build verdes; e2e browser sin regresiones contra master.

## Progreso y evidencia

**T1** (41/41 sitios tratados). Línea base 536 tests; después 543 (57 -> 59 archivos), build OK.
- Rechazo realmente posible (`authedFetch` rechaza sin backend) -> `.catch` con comentario y la UI queda como estaba: CronjobsPage, CronJobCard x3, SplashPanel, MarcasPanel, PicksPanel x2, CategoryPicksPage, AgentChatPanel (modelos), ProductCard/AppLayout (favoritos optimista), ProductGrid, authSession x2 (revalidación best-effort).
- `reload()` de FinanPanel y MlStatusPanel atrapan adentro; los llamadores usan `void`.
- Cadena que no puede rechazar (`readStatus`, `.catch(() => null)` previo, `fetchTendencias`, IIFE de `eventStream`, `runTurn`/`send`) -> `void` explícito: App, AppLayout x2, EventStreamProvider x3, useInterruptedRun, useScrapeStatusPolling x2, Topbar, Oportunidades/TrendsPanel, AgentChatPanel x3.
- Único cambio de comportamiento: `AuthProvider` sale de `booting` aunque `bootstrap()` rechace (antes: puerta colgada).
- Tests: `src/rejectedReads.test.jsx` (6) y `src/auth/AuthProvider.test.jsx` (1), con `src/test/unhandledRejections.js`. Rojo observado (6/6 y 1/1) antes del arreglo; verde después.

**T2** Línea base 543 tests; después 549, build OK. Ningún test existente editado.
- `div onClick` -> `<button type="button" class="btn-reset">` (clase de cero especificidad en `styles.css`): PickCard, OportunidadesPanel (preview), MarcasPanel (MarcaCard y productos del detalle). El hover por JS suma `onFocus`/`onBlur`. `RubroCard` pasa de `role="button"` a `<button>` nativo (se quitó su `onKeyDown`).
- Hover a CSS: GroupCard (`.group-card:hover`) y la fila de FavoritosPanel (`.fav-row:hover`); los links de GroupCard suman focus/blur.
- `role` -> elemento: `region` -> `<section>` (Favoritos, tilt-carousel, CategoryPicksCarousel), `group` -> `<fieldset aria-label>` (Favoritos, PcsPanel x2), `status` -> `<output>` (UsuariosAdminPanel, ForgotPassword, ResetPassword). `CompareModal`: backdrop e interior con `role="presentation"` (el teclado ya tiene el botón de cerrar). `CardTitle` renderiza `children`. Label del selector de modelo con `htmlFor`/`useId`. S6772: `{' '}` explícito en FinanPanel x3, PcsPanel, SplashPanel, Topbar.
- Tests: `src/keyboardOperable.test.jsx` (6: Tab + Enter/Espacio sobre PickCard, Oportunidades, MarcasPanel). Rojo observado 6/6 (no había rol button) y verde después.
- **No arreglados** (motivo): ProductCard:168, FavoritosPanel:276 (S1082+S6848) y SavedOutfitCard:47 (S1082+S6848) son filas clickeables que contienen botones propios (favorito, comparar, borrar, renombrar): un `<button>` anidado es HTML inválido y rehacerlas como "stretched link" cambia el layout. SavedOutfitCard:52 y SavedPcCard:52 (S9379): el `autoFocus` es el foco deliberado del input de renombrar al entrar en edición. CategoryPicksCarousel:116 S6847 y :121 S6845: el viewport del carrusel es un único tab-stop con flechas (patrón de región desplazable); se arregló el S6819. ui/label.jsx:9 (S6853): es el primitivo genérico, la asociación la hace cada llamador con `htmlFor`.

**T3** (28/32). Línea base 549 tests; después 549, build OK. Ningún test existente editado (CODE-2).
- S6481 (1): `AuthProvider` memoiza `value` con `useMemo([state, logout])`.
- S6478 (16): `CronjobsPage` y `UsuariosAdminPanel` -> `COLUMNS` a nivel de módulo; handlers y `runningId`/`ocupado` viajan por `table.options.meta`. `CompareComponents` -> `ROWS` a módulo (`hl(p, minPrecio)`). `CatalogoFilterBar` -> `CatGroup` a módulo con `selected`/`onToggle`.
- S6479 (11): clave por dato (`url`, `key`, `text`, `icon`, `slot-url`) en CategoryPicksCarousel, DetailPanel x2, FavoritosPanel, PcsPanel, SuplementosPanel, SavedOutfitCard x2, SavedPcCard, TrendsPanel, outfit-collage.
- **No arreglados** (motivo): AgentChatPanel:631 (transcripción append-only sin id; la posición *es* la identidad) y richText.jsx x3 (líneas/tokens de un string: la posición es la identidad, y el texto repite líneas en blanco).

**T4** (41/41 listados; el inventario no traía S7763). Suite 549 -> 549 tests (60 archivos), build OK. Ningún test añadido ni quitado.
- S9020 (40 sitios, 39 listados + uno idéntico): `await waitFor(() => expect(screen.getByX(..)).toBeInTheDocument())` -> `await screen.findByX(..)` en App, AuthGate, ApiDocsPanel, OutfitsPanel, PriceHistoryPage, ForgotPassword, Login, ResetPassword. Mismo timeout por defecto (1000 ms), misma aserción.
- S9027 (1): `ProductCard.test.jsx` `queryByText` -> `getByText` en la aserción de presencia.

**T5** (47/47). Suite 549 -> 549 tests (60 archivos), build OK. Ningún test existente editado (CODE-2).
- Verificado con `eslint-plugin-sonarjs` (`no-nested-conditional`, `cognitive-complexity` en 15, `no-nested-template-literals`) sobre `frontend/src`: reproduce los 47 sobre la base y deja 0 después.
- S3358 (35): if/else o helpers de módulo (`scoreColor`/`gaugeColor`, `gaugeLabel`, `zScoreText`, `mlBannerText`, `rowTone` de GroupCard, `chipSelectedBackground`/`chipTextColor` de SplashPanel, tabla `TENDENCIA_VISUAL`); `HeaderContent` en CronjobsPage y UsuariosAdminPanel; el cuerpo de CronjobsPage pasa de ternario anidado a tres condiciones excluyentes.
- S3776 (9): DetailPanel `PriceContext` 38 -> seis funciones `*Item`; `reducer` y `buildParams` de AppLayout (`toggleIn`, `filterParams` con el mismo orden de claves y `precioMin: 0` incluido); OutfitsPanel (`buildNoFitMessage`, `NoFitNotice`); PcsPanel `resumenSpecs` en tres tramos; CronJobCard (`SitioChip`, `ExecutionHistory`, `buildPayload`, `initialSelection`); `fetchPcsBuilder` por tabla `[param, viaja, valor]` en el orden de siempre; BuySignal.
- S4624 (3): api.js, ProductCard, CategoryPicksCarousel.
- S3782 de DetailPanel (T6) cae acá: `diffPct`/`diffMed` pasan a `Number`; el texto del caso `> 15` conserva el `toFixed(1)` original.
- `groupByRubro` nuevo en `lib/rubros.js` (usado por CronJobCard y SplashPanel; cierra también sus S1121 de T6).

**T6** (67/69). Suite 549 -> 599 tests (62 archivos; +50 nuevos), build OK, `playwright test --list` parsea (31 tests). Único test existente tocado: el import sin usar `waitFor` de `AuthProvider.test.jsx` (test propio de T1); ninguna aserción editada.
- Mecánicos: imports sin usar (AppLayout, CronjobsPage, OutfitsPanel, RecomendadosPanel, Topbar), imports duplicados (AppLayout, Topbar), variables sin usar (FavoritosPanel, GpuTrainingOverlay), cadena opcional x15, `.some`, `export…from` (e2e/helpers, ui/chart), `node:path`/`node:url`, `{}` inútil (api x2, EventStreamProvider x2), `Set` en AuthGate, `startsWith`, `replaceAll` x7, `codePointAt`, `=== undefined` y cuerpos con comentario en `test/setup.js`, S1121 vía `groupByRubro`, S3782 de DetailPanel (ver T5).
- S1940 (AgentChatPanel): el operador opuesto `>=` cambia el resultado con `NaN` (el `savedAt` corrupto debe contar como vencido); se reescribió como `Number.isNaN(age) || age >= TTL`, mismo comportamiento.
- S8786 (4/4), cada uno con pin previo observado en verde sobre el original (`src/lib/slowRegexPins.test.jsx`, `src/lib/inlineMatches.test.js`):
  - `MlStatusPanel` -> `lib/mlTrainingMsg.js`: `(?<!\d)(\d+(?:\.\d*)?)\s*%`. Era cúbico: 4000 dígitos sin `%` tardaban 16 s; ahora lineal.
  - `cat.js` `slugify`: `/^-+|-+$/` -> `/^-|-$/` (el paso anterior ya colapsa cada corrida a un solo guion).
  - `richText` viñeta `^\s*[-*]\s+(.*)$` -> `bulletText()` a mano (mismo resultado, incluidos los terminadores de línea).
  - `richText` INLINE (alternancia de 4 patrones, cuadrática con aperturas sin cerrar: 420k caracteres tardaban 6,6 s) -> `lib/inlineMatches.js`, un escáner de una pasada con memo. Equivalencia contra la regex original con 30.000 líneas aleatorias deterministas + casos fijos; el adversarial queda por debajo de 1 s.
- **No arreglados** (motivo): `eventStream.js:173` S9382 (`await sleep` dentro del `while` de reconexión: el reintento es secuencial por diseño, la espera ES el backoff); `brandLogos.js:82` S7760 (el parámetro por defecto sólo cubre `undefined`, y `getBrandColor(marca.marca)` puede recibir `null`: pasaría de gris por defecto a un `TypeError`).
- `S2245` de `authSession.js:46` sigue fuera de alcance (falso positivo aceptado).

**T7** Verificación final (sin cambios de código).
- `npx vitest run`: 62 archivos, 599 tests verdes. Build OK.
- `tests/e2e/run-e2e.sh --browser` contra la rama (dist recién construido, crons 3 y 4 apagados y restaurados sin deriva): **31/31**.
- Visual, master vs rama, mismo backend y misma base, capturas 1366x900 de `/catalogo`, `/picks`, `/marcas`, detalle de marca, `/analisis/oportunidades`, `/favoritos`: diferencia de 0 a 23 píxeles por pantalla, que varía entre corridas (antialiasing de texto). Ningún cambio de layout por `btn-reset`.
- Hallazgo preexistente, fuera de alcance: la barra de precio de las tarjetas (`catStats`) a veces no aparece nunca, en master y en la rama; el efecto de `fetchTendencias` se cancela cuando `readStatus` cambia `scrapeStatus` antes de que llegue la respuesta. En una corrida de la rama apareció y en las otras 8 (4 master, 4 rama) no.
- Análisis de Sonar sobre el PR #267: 12 issues nuevos introducidos por la limpieza, arreglados en un commit aparte:
  6× S2699 (`findBy*` sin `expect` tras T4: vuelven a `expect(await screen.findBy…).toBeInTheDocument()`, lo que afirmaban en master); 2× S1940 (`!(precioComp > 0)` → helper `esPositivo`, misma semántica con NaN/null/undefined); S2310 (`inlineMatches`: `for` con `i = end - 1` → `while`; el fuzz de 30.000 líneas sigue verde); 2× S6819 (`role="presentation"` del modal de comparación → botón nativo `compare-modal-scrim` "Cerrar comparación" detrás del panel).
  Test nuevo `CompareModal.test.jsx`: rojo observado contra el código anterior (no había botón), verde después. En el browser (1366 y 390 px), el click afuera cierra y el click en el panel no.
  Queda S6847 en `CategoryPicksCarousel` (la misma región con flechas ya declarada no arreglable; Sonar lo cuenta nuevo porque cambió el elemento).
- Hallazgo preexistente, fuera de alcance: la tabla del modal de comparación dibuja cada rótulo debajo de sus valores, así que se lee como si estuviera corrida una fila (la fila PRECIO muestra la tienda). Igual en master.
- Suite: 63 archivos, 601 tests. Build OK.

## Próximo paso

Abrir el PR y leer el análisis de Sonar del PR.
