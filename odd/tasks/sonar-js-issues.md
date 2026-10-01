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
- [ ] **T3** React — S6478, S6479, S6481 (32)
- [ ] **T4** Tests — S9020, S9027, S7763 en tests (42)
- [ ] **T5** Legibilidad — S3358, S3776, S4624 (47)
- [ ] **T6** Limpiezas mecánicas — resto de reglas (~69)
- [ ] **T7** Verificación final: suite + build + e2e browser + re-análisis Sonar del PR

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

## Próximo paso

T3.
