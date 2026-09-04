# Apply progress — `swagger-ui-admin-gated`

Branch `feat/admin-api-console`, off `master` at `cb86a4f`. Two commits, both
green alone, matching the design's forced order.

## Commit A — `feat(api): serve the OpenAPI contract to authenticated admins`

`GET /api/openapi.yaml` streams `docs/openapi.yaml` from a classpath resource
(`OpenApiDocumentController`), bundled by a new `maven-resources-plugin`
`copy-resources` execution at `contract/openapi.yaml`. `ApiRoutePolicy` gains
one `ADMIN` row; `docs/openapi.yaml` documents the route itself; an additive
`OpenApiRouteCoverageTest` test asserts the classpath copy is byte-identical
to the checked-in file. `Dockerfile` gains a matching `COPY`.

Backend suite: **2017/0/0/7** (2013 baseline + 4: 3 in
`OpenApiDocumentControllerTest`, 1 additive byte-identity test), `clean test`,
zero `ERROR]` lines. 256 authored lines.

**Docker, verified for real** (not just "CI will catch it"): built the image
(`docker compose build backend`), brought up postgres+backend under a scratch
env file, logged in as a seeded ADMIN, and confirmed `GET /api/openapi.yaml`
returns 200 with a body byte-identical to the checked-in file, 401
anonymous, and 403 for a freshly created VIEWER account. Torn down and
cleaned up afterward (containers, volumes, images, temp env file).

## Commit B — `feat(ui): add the ADMIN-only interactive API console`

`/api-docs` renders `docs/openapi.yaml` via `swagger-ui-react`, fetched
through `authedFetch` (never swagger-ui's own `url` prop), with `servers`
rewritten at runtime to `api.js`'s exported `BASE`. A ten-entry try-it-out
deny-list is enforced by `statePlugins.spec.wrapActions.execute` and
explained by a `wrapComponents.OperationContainer` wrap. Nav entry and route
guard are cosmetic, matching the `Cronjobs`/`Cuentas` pattern.

Frontend suite: **268/268** (244 baseline + 24), 39 files (35 + 4), all
passing.

### Real bug found and fixed by manual verification (task 3.1)

The design's own mechanism for `wrapComponents.OperationContainer` — a plain
arrow function forwarding props to the original component — silently broke
expand/collapse for **every** operation in the console, not just denied
ones. Root cause: swagger-ui-react's own `withConnect` reads a custom
`mapStateToProps` off `Component.prototype` (its own convention, not
react-redux's usual separate-argument form). An arrow function has no
`.prototype` at all, so that lookup silently fell through to a no-op
default, and `isShown` never resolved for any operation.

The fix (`denyTryItOutPlugin.js`) uses a named `function` whose `.prototype`
is a plain object carrying only `{ mapStateToProps: Original.prototype.mapStateToProps }`
— not `Object.create(Original.prototype)`, which was tried first and broke
differently: inheriting `isReactComponent` made React try to instantiate the
wrapper as a class and call a `.render()` method it doesn't have
(`TypeError: r.render is not a function`).

No vitest run had, or could have had, any way to catch this — it only
reproduces against the real swagger-ui-react bundle in a real browser DOM,
which is exactly why `docs/ARCHITECTURE.md`'s design flagged this seam's
prop-shape assumption as unverifiable ahead of time. This is the value the
manual verification step was for.

### Manual verification, performed for real

Built `scraper/target/fashion-scraper-1.0.0.jar` (`mvn -DskipTests package`)
and the frontend (`VITE_API_BASE_URL=http://localhost:3000 npm run build`),
ran both against the existing dev Postgres container
(`scripts/dev-db.sh up`), reused `tests/e2e/.e2e-secrets.env`'s seeded
`e2e-admin` account, and drove a real Chromium instance via Playwright:

- Logged in as ADMIN, navigated to `/api-docs` through the real nav link
  (not a full page reload, to exercise the same session-bootstrap path a
  human would).
- Confirmed all 10 deny-listed operations render `.api-docs-deny-reason`
  with their exact stated text, and that zero `.try-out__btn` /
  `button.execute` nodes exist for any of them.
- Confirmed `GET /api/data` expands, shows "Try it out", executes for real,
  and returns a live 200 with actual catalog JSON (`total: 17022`, etc.).

Screenshots and the throwaway Playwright scripts were deleted after use;
none are part of the diff.

### Bundle measurement, performed for real

`VITE_API_BASE_URL=http://localhost:3000 npm run build`:

```
dist/assets/ApiDocsPanel-Db0FQ6v-.css   184.04 kB │ gzip:  27.34 kB
dist/assets/index-CCIV7I_G.js           606.95 kB │ gzip: 194.04 kB
dist/assets/ApiDocsPanel-Byc4g5-9.js  1,376.28 kB │ gzip: 397.11 kB
```

`ApiDocsPanel`'s JS+CSS is its own lazy chunk, separate from `index-*.js` —
confirms `lazy()` + the existing `<Suspense>` in `AppLayout.jsx` isolate it
with no `manualChunks` needed (Q2 resolved).

## Line budget

| | Authored lines (excl. `package-lock.json`) |
|---|---|
| Commit A | 256 |
| Commit B | 568 |
| **Total** | **824** |

Slightly over the 800-line budget raised for this change (design forecast
~420). The overage is almost entirely the extra Phase-3 evidence work itself
(the real bug fix + its test coverage, plus the JSDoc/comment trimming pass
requested mid-apply) rather than scope creep — no `size:exception` was
requested or recorded; flagging this here rather than silently absorbing it.

## Deviations from design/tasks

1. `nav-config.js` icon: design specified `Code2` from `lucide-react`; the
   installed version (`0.469.0`) has no `Code2` icon. Used `Code` instead.
2. `ApiDocsPanel.jsx` does not self-export `ApiDocsPanelRoute` as design's
   prose suggested — it only exports the default panel component. The lazy
   import, the `ApiDocsRoute` wrapper, and the `ApiDocsPanelRoute` export
   live in `AppLayout.jsx`, matching the actual established pattern for
   every other panel (`CronjobsRoute`/`UsuariosAdminRoute`), not a new one.
3. The `wrapComponents.OperationContainer` mechanism itself needed the fix
   described above — the deny-list's *shape* (key format, 10 entries,
   reasons) is exactly as designed; only the plugin's internal wrapping
   technique changed.

## Skill resolution

`work-unit-commits` — loaded via the path provided in the task (`paths-injected`).
