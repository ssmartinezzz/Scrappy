# saved-pcs-armadores — phase 4 of the PC builder: persist builds + `/armadores`

**Created:** 2026-09-18 · **Branch:** `feat/saved-pcs-armadores` (stacked on `feat/pc-builder-ui`, #203 → #202 → #201 → #200 → `master`)
**Engram mirror:** `odd/saved-pcs-armadores/tasks` (project `scrappy`)
**Phase 3:** [`pc-builder-ui.md`](./pc-builder-ui.md) — the `/pcs` page that produces the build this phase saves.

## Objective

Save a PC build from `/pcs` (own rows only, like saved outfits) and give every
assembler's saved output one home: a new `/armadores` page listing saved
outfits (moved out of `/favoritos`) and saved PCs. `/outfits`, `/suplementos`
and `/pcs` stay as they are, each with its own Guardar button.

Decided with the user (2026-09-18): `/armadores` holds only what was saved;
it is not a hub replacing the three assembler pages.

## Scope (authorized)

Backend — mold is `saved_outfits` end to end (`SavedOutfitsPort` →
`SavedOutfitsRepository` → `OutfitsEndpoints` → `ApiController`):

- `V34__saved_pcs.sql`: `saved_pcs` (id BIGSERIAL PK, usuario_id UUID NULL
  REFERENCES usuario(id) ON DELETE CASCADE, nombre TEXT NOT NULL,
  presupuesto DOUBLE PRECISION NOT NULL, con_gpu BOOLEAN NOT NULL,
  total_estimado DOUBLE PRECISION NOT NULL, created_at TIMESTAMPTZ NOT NULL
  DEFAULT now(); index on usuario_id) + `saved_pc_item` (pc_id BIGINT NOT NULL
  REFERENCES saved_pcs(id) ON DELETE CASCADE, posicion SMALLINT NOT NULL,
  slot TEXT NOT NULL, url TEXT NOT NULL, sitio, nombre, precio DOUBLE
  PRECISION, img, marca, socket, ddr, form_factor, watts INT, capacidad_gb
  INT, tipo_memoria — PK (pc_id, posicion)). No owner column and no FK to
  `productos.url` on the item table (historical snapshot carve-out, same as
  `saved_outfit_item`). Rollback SQL in `docs/DATABASE.md` inside
  `-- >>> rollback:V34` / `-- <<< rollback:V34`, exercised by the existing
  round-trip test mechanism; V34 row in the migrations table; schema list;
  carve-out list.
- `ar.scraper.pcs.SavedPcsPort`: `int guardarPc(UUID usuarioId, String nombre,
  List<PcPick> picks, double presupuesto, boolean conGpu, double
  totalEstimado)` (-1 on failure) · `List<Map<String,Object>>
  obtenerPcsGuardadas(UUID usuarioId)` (items in one query, `LEFT JOIN
  productos` for `precioActual`) · `boolean eliminarPcGuardada(UUID, int)` ·
  `boolean renombrarPc(UUID, int, String)`. Foreign row and missing row are
  both `false`.
- `ar.scraper.db.SavedPcsRepository` (package-private `@Repository`),
  `DatabaseService.pcsGuardadas()` accessor, `PostgresTestBase.truncateAll`
  gains `saved_pcs` only (item table cascades).
- `PcsEndpoints` gains `SavedPcsPort` + `ActorResolver`; `savePc(body)` with
  `{nombre, picks[], presupuesto, conGpu, totalEstimado}` → `{ok,id,nombre,
  totalEstimado}`; `getSavedPcs()`; `deleteSavedPc(id)` 200/404;
  `renameSavedPc(id, body)` 400/200/404. Mappings in `ApiController`:
  `POST /api/pcs/save`, `GET /api/pcs/saved`, `DELETE /api/pcs/saved/{id}`,
  `PATCH /api/pcs/saved/{id}/nombre`. `ApiRoutePolicy` row AUTHENTICATED,
  `docs/openapi.yaml` entries (own rows only), ArchUnit
  `webUsaPcsGuardadasPorElPuerto` mirroring the outfits rule.
- Tests: `DatabaseServiceSavedPcsTest` (PostgresTestBase, `UsuarioDePrueba`),
  `ApiControllerSavedPcsTest` (Mockito, `SujetoDePrueba`),
  `ApiControllerPcsBuilderTest` setUp gains `SujetoDePrueba.entrar`.

Frontend:

- `api.js`: `savePc(body)`, `fetchSavedPcs()`, `deleteSavedPc(id)`,
  `renamePc(id, nombre)` next to `fetchPcsBuilder` + tests.
- `AppLayout.jsx`: `savedPcs` state + `SET/ADD/REMOVE/RENAME_SAVED_PC`, load on
  mount like `loadSavedOutfits`; `PcsRoute` passes `savedPcs`/`onSavePc` via
  outlet context; new lazy `ArmadoresRoute` passing saved outfits + PCs and
  their delete/rename handlers.
- `PcsPanel.jsx`: Guardar button next to Regenerar, guarded by picks > 0 and
  `onSavePc`, `saving` state; body = `{nombre, picks, presupuesto, conGpu,
  totalEstimado}` where `nombre` defaults to `PC $<total>` style like outfits.
- `ArmadoresPanel.jsx` (+ test): two sections, "Outfits guardados" (move
  `SavedOutfitCard` out of `FavoritosPanel.jsx` into its own file) and "PCs
  guardadas" (`SavedPcCard`: name editable, picks list with slot pill, price,
  `precioActual` when it differs, total, delete). `FavoritosPanel.jsx` loses
  the outfits section and its props; its tests follow.
- Route `armadores` in `App.jsx`, nav entry (Explorar group, `Boxes`/`Layers`
  icon), `CLAUDE.md` route list + Armador de PCs pending list.

Out of scope: agent tool `propose_pc`, e2e spec, splash tile.

TDD: **strict**. Runners: backend
`cd scraper && JAVA_HOME=<jdk24> mvn -q clean test` per CONTRIBUTING; focused
`-Dtest=DatabaseServiceSavedPcsTest,ApiControllerSavedPcsTest,ApiControllerPcsBuilderTest,OpenApiRouteCoverageTest,BackendLayeringArchTest`.
Frontend `cd frontend && npx vitest run <files>`, full `npm test`,
`npm run build`.

## Tasks

- [x] T1 Backend persistence: V34 + `DATABASE.md` (rollback, tables, carve-out) + `SavedPcsPort` + `SavedPcsRepository` + `DatabaseService` accessor + `truncateAll`; `DatabaseServiceSavedPcsTest` RED→GREEN; rollback round-trip green.
- [x] T2 Backend transport: `PcsEndpoints` save/list/delete/rename + `ApiController` mappings + `ApiRoutePolicy` + `openapi.yaml` + ArchUnit rule; `ApiControllerSavedPcsTest` RED→GREEN; `OpenApiRouteCoverageTest`, `BackendLayeringArchTest`, full backend suite green.
- [x] T3 Frontend api + save: `api.js` functions + tests; `AppLayout` state/handlers; `PcsPanel` Guardar (test RED→GREEN).
- [x] T4 Frontend `/armadores`: `ArmadoresPanel` + `SavedOutfitCard` moved + `SavedPcCard`; `FavoritosPanel` without outfits; route + nav; tests; full `npm test` + `npm run build`; `CLAUDE.md`.

## Acceptance

- Backend full suite green (report counts); frontend full suite + build green.
- `/favoritos` no longer renders saved outfits; `/armadores` renders both lists.
- Save from `/pcs` persists and appears in `/armadores` without reload.

## Progress / evidence

### T1 — Backend persistence

Files: `scraper/src/main/resources/db/migration/V34__saved_pcs.sql`,
`scraper/src/main/java/ar/scraper/pcs/SavedPcsPort.java`,
`scraper/src/main/java/ar/scraper/db/SavedPcsRepository.java`,
`scraper/src/main/java/ar/scraper/db/DatabaseService.java` (field, both ctors,
`pcsGuardadas()` accessor, `guardarPc`/`obtenerPcsGuardadas`/`eliminarPcGuardada`/
`renombrarPc` delegates mirroring the outfits ones),
`scraper/src/test/java/ar/scraper/db/support/PostgresTestBase.java` (`truncateAll`
+= `saved_pcs`), `docs/DATABASE.md` (migrations table, schema list, carve-out
list at "¿Lleva FK?", `## V34` section + rollback block).

**Deviation from the task text**: the spec's Scope line says "id BIGSERIAL
PK". Every existing migration in this repo (`V1`, `V26`, `V33`, …) uses
`BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` instead — there is no
`BIGSERIAL` anywhere in `db/migration/`. Followed the established mold over
the literal spec wording; behaviorally equivalent.

**Second deviation, found only by running the suite**: `V34` gives `usuario`
a second inbound FK (from `saved_pcs.usuario_id`), and `V26`'s documented
rollback drops `usuario` outright — same class of break `V29`/`scrape_run`
already caused once. Added `ALTER TABLE saved_pcs DROP CONSTRAINT IF EXISTS
saved_pcs_usuario_id_fkey;` to the `V26` rollback block in `DATABASE.md`,
same pattern as the existing `fk_scrape_run_usuario` line. Confirmed by
`V26RollbackRoundTripTest` going red then green.

RED (compile failure, right reason — `DatabaseService` had no PC-persistence
methods yet):
```
[ERROR] .../DatabaseServiceSavedPcsTest.java:[54,11] cannot find symbol
  symbol:   method guardarPc(java.util.UUID,java.lang.String,java.util.List<ar.scraper.pcs.PcPick>,double,boolean,double)
  location: variable db of type ar.scraper.db.DatabaseService
[ERROR] .../DatabaseServiceSavedPcsTest.java:[56,44] cannot find symbol
  symbol:   method obtenerPcsGuardadas(java.util.UUID)
```
(plus the same for `eliminarPcGuardada`/`renombrarPc`, 9 errors total.)

GREEN: `DatabaseServiceSavedPcsTest` 9/9, `V34RollbackRoundTripTest` 2/2 —
real Postgres via Testcontainers.

### T2 — Backend transport

Files: `scraper/src/main/java/ar/scraper/web/PcsEndpoints.java` (save/list/
delete/rename + picks→`PcPick` conversion, spec abstention on missing fields,
skips picks with blank url), `ApiController.java` (4 mappings + `PcsEndpoints`
wiring with `db.pcsGuardadas()`/`actorResolver`), `ApiRoutePolicy.java` (new
row for `/api/pcs/save`, `/api/pcs/saved`, `/api/pcs/saved/**`, AUTHENTICATED,
"own rows only"), `docs/openapi.yaml` (4 entries copied from the outfits
mold), `BackendLayeringArchTest.java` (`METODOS_PCS_GUARDADAS` /
`webUsaPcsGuardadasPorElPuerto`), `ApiControllerPcsBuilderTest.java`
(`SujetoDePrueba.entrar`/`salir` in setUp/tearDown).

RED (compile failure, right reason — none of the four endpoint methods existed):
```
[ERROR] .../ApiControllerSavedPcsTest.java:[93,44] cannot find symbol
  symbol:   method savePc(java.util.Map<java.lang.String,java.lang.Object>)
  location: variable controller of type ar.scraper.web.ApiController
```
(plus `getSavedPcs`/`deleteSavedPc`/`renameSavedPc`, 9 errors total.)

GREEN: `ApiControllerSavedPcsTest` 11/11, `ApiControllerPcsBuilderTest` 5/5.

### T3 — Frontend api + save

Files: `frontend/src/api.js` (`savePc`/`fetchSavedPcs`/`deleteSavedPc`/`renamePc`
next to `fetchPcsBuilder`, same shape as the outfits mold), `frontend/src/api.test.js`
(new `describe('Saved PCs', ...)` block, 6 tests), `frontend/src/components/AppLayout.jsx`
(`savedPcs` state + `SET/ADD/REMOVE/RENAME_SAVED_PC` reducer cases, `loadSavedPcs`
called on mount next to `loadSavedOutfits`, `PcsRoute` gains `onSavePc` that calls
`savePc` then dispatches `ADD_SAVED_PC`), `frontend/src/components/PcsPanel.jsx`
(`onSavePc` prop, `saving` state, Guardar button next to Regenerar guarded by
`picks.length > 0 && onSavePc`), `frontend/src/components/PcsPanel.test.jsx`
(new `describe('PcsPanel — Guardar', ...)` block, 4 tests).

RED — `api.test.js`, 6 failures, right reason (functions didn't exist yet):
```
TypeError: fetchSavedPcs is not a function
TypeError: deleteSavedPc is not a function
TypeError: renamePc is not a function
```
(plus `savePc` failing inside its own test body.)
GREEN: `api.test.js` 38/38.

RED — `PcsPanel.test.jsx`, 2 of 4 new tests failed for the right reason (button
not found — `getByRole('button', { name: /Guardar/ })` threw `TestingLibraryElementError`);
the other 2 ("no button without onSavePc/without picks") passed trivially before
the feature existed, which is expected for an absence assertion.
GREEN: `PcsPanel.test.jsx` 14/14.

**Deviation from the task text**: the task doc says `PcsRoute` passes
`savedPcs`/`onSavePc`; the orchestrator's mold instructions say only `onSavePc`.
`PcsPanel`'s Guardar button names the saved PC from `totalEstimado`
(`PC $<fmt(totalEstimado)>`), not from `savedPcs.length` the way the outfit
panel names outfits — so `PcsPanel` never reads `savedPcs`. Passing it anyway
would be an unused prop. Followed the narrower mold; `savedPcs` still lives in
`AppLayout` state and reaches `/armadores` via `ArmadoresRoute`.

### T4 — Frontend `/armadores`

Files: `frontend/src/components/SavedOutfitCard.jsx` (new — `SavedOutfitCard`
moved verbatim out of `FavoritosPanel.jsx`, plus its own small `ImgFallbackIcon`
copy, same duplication pattern the file's own comment already documents three
times over), `frontend/src/components/SavedPcCard.jsx` (new — modeled on
`SavedOutfitCard`: editable name, picks list with a slot pill + name + price,
`precioActual` shown only when present and different from the saved price,
total, delete), `frontend/src/components/ArmadoresPanel.jsx` (new — two
sections, empty-state text per section), `frontend/src/components/ArmadoresPanel.test.jsx`
(new, 7 tests), `frontend/src/components/FavoritosPanel.jsx` (`SavedOutfitCard`
definition removed, `formatFecha` import dropped — it was only used there,
`savedOutfits`/`onDeleteSavedOutfit`/`onRenameSavedOutfit` props and every
outfit-slide/expand code path removed from the carousel and the list view),
`frontend/src/components/AppLayout.jsx` (new `ArmadoresRoute`/`ArmadoresPanelRoute`
export, `FavoritosRoute` stops passing outfit props), `frontend/src/App.jsx`
(`armadores` route), `frontend/src/components/nav/nav-config.js` (`Armadores`
entry in the Explorar group, `Boxes` icon), `frontend/src/App.test.jsx`
(`authedRouter` mock gains a `/api/pcs/saved` stub — `AppLayout` now fetches it
on mount; without the stub every test using that router threw an unhandled
rejection after passing), `CLAUDE.md` (route list + PC builder pending line).

RED — `ArmadoresPanel.test.jsx`: import failure, right reason (`ArmadoresPanel.jsx`
didn't exist yet):
```
Error: Failed to resolve import "@/components/ArmadoresPanel"
```
GREEN: `ArmadoresPanel.test.jsx` 7/7.

**No `FavoritosPanel.test.jsx` exists in the repo** — checked before editing;
there is nothing to fix there. The task text assumed one; this is a deviation
from what it describes, not from what was done.

**Deviation, found running the suite**: `App.test.jsx`'s `authedRouter` test
helper threw `unexpected fetch` for `/api/pcs/saved` after `AppLayout` started
fetching it on mount — 8 unhandled rejections, all tests still green (the
throw happened after assertions ran). Added a stub next to the existing
`/api/outfits/saved` one; this is why `App.test.jsx` shows as modified.

Verification (run in this order):
1. `npx vitest run src/api.test.js src/components/PcsPanel.test.jsx src/components/ArmadoresPanel.test.jsx src/components/FavoritosPanel.test.jsx src/api.contract.test.js` →
   4 files ran (no `FavoritosPanel.test.jsx` to run), 63/63 passed.
2. `npm test` → 42 files, 315/315 passed, 0 failures.
3. `npm run build` → succeeded (`VITE_API_BASE_URL` set for the build shell only,
   same as any local build per the repo's own vite.config.js guard — no `.env`
   file touched).

Focused command (T1+T2 together):
```
JAVA_HOME=<jdk24> mvn -f scraper/pom.xml clean test -Djvm=<jre21> \
  -Dtest='DatabaseServiceSavedPcsTest,ApiControllerSavedPcsTest,ApiControllerPcsBuilderTest,OpenApiRouteCoverageTest,BackendLayeringArchTest,*Rollback*' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
→ all green, 0 failures/errors/skipped across every class in the pattern
(includes all 10 existing `V*RollbackRoundTripTest` classes, `V26`'s among
them — confirms the `V26` rollback fix above).

Full backend suite (`mvn clean test`, no `-Dtest` filter, run by the
orchestrator after the writer was cut off by a provider rate limit):
`Tests run: 2187, Failures: 0, Errors: 0, Skipped: 7` — BUILD SUCCESS
(7 skipped = pre-existing baseline). `SiteRegistrySingletonWiringTest` gained
`SavedPcsRepository.class` in its hand-built context (the widened-constructor
trap). Two one-line javadocs that only restated the method name were removed
from `SavedPcsRepository`.
