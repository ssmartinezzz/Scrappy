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

- [ ] T1 Backend persistence: V34 + `DATABASE.md` (rollback, tables, carve-out) + `SavedPcsPort` + `SavedPcsRepository` + `DatabaseService` accessor + `truncateAll`; `DatabaseServiceSavedPcsTest` RED→GREEN; rollback round-trip green.
- [ ] T2 Backend transport: `PcsEndpoints` save/list/delete/rename + `ApiController` mappings + `ApiRoutePolicy` + `openapi.yaml` + ArchUnit rule; `ApiControllerSavedPcsTest` RED→GREEN; `OpenApiRouteCoverageTest`, `BackendLayeringArchTest`, full backend suite green.
- [ ] T3 Frontend api + save: `api.js` functions + tests; `AppLayout` state/handlers; `PcsPanel` Guardar (test RED→GREEN).
- [ ] T4 Frontend `/armadores`: `ArmadoresPanel` + `SavedOutfitCard` moved + `SavedPcCard`; `FavoritosPanel` without outfits; route + nav; tests; full `npm test` + `npm run build`; `CLAUDE.md`.

## Acceptance

- Backend full suite green (report counts); frontend full suite + build green.
- `/favoritos` no longer renders saved outfits; `/armadores` renders both lists.
- Save from `/pcs` persists and appears in `/armadores` without reload.

## Progress / evidence

(pending)
