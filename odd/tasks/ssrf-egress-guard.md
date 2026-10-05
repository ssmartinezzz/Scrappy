# SSRF egress guard — pentest OWASP 2026-10, Low "SSRF via POST /api/sitios"

## Objective

Stop the scraper from reaching internal addresses (loopback, private, link-local/metadata, CGNAT, ULA) when an ADMIN
registers a site URL or when a scraped page/redirect points there. Branch `fix/owasp-critical-example-secrets`
(11 commits ahead of master, unpushed). User asked for "la mejor, más prolija, que siga SOLID".

## Problem (verified 2026-10-03)

- `SitiosController.agregarSitio` (l.48) only checks `startsWith("http")`: accepts `http://127.0.0.1:5432`,
  `http://169.254.169.254/`, `httpx://…`. The site is then navigated by Playwright in `BaseScraper.ejecutar`.
- Probe (real Chromium 1117 / Playwright 1.44): a `ctx.route("**/*")` handler sees ONLY the first URL of a redirect
  chain. `store.test` → 302 → `http://127.0.0.1:<p>/secret`: route saw `[http://store.test:…/]`, the internal server
  got the GET, the page read `SECRET`. **A route-based guard is bypassable by redirect.**
- Probe 2: with `LaunchOptions.setProxy(...)` Chromium sends EVERYTHING through the proxy, loopback included
  (`127.0.0.1`, `localhost`, `10.0.0.1`, plain http absolute-form, https as CONNECT). Note: ports like 9 are
  Chrome-restricted (ERR_UNSAFE_PORT) — use ordinary ports in tests.
- `VaypolPage.enricherImagenesHttp` fetches scraped product URLs with a `java.net.http.HttpClient` that follows
  redirects → same SSRF class. `ComparadorController.HTTP` only hits fixed `api.mercadolibre.com` → out of scope.

## Why an egress proxy

Validating at connect time is the only place that sees redirects, DNS rebinding (proxy connects to the exact IP it
checked), subresources and popups. Input validation in the controller stays as fail-fast UX (400), not as the defense.

## Design

- `ar.scraper.security.OutboundAddressPolicy` (pure Java): `static boolean isInternal(InetAddress)`;
  `HostResolver` functional interface; `system()` factory (`InetAddress::getAllByName`);
  `Optional<InetAddress> resolvePermitted(String host)` — empty for `localhost`/`*.localhost`, unresolvable, or if ANY
  resolved address is internal; otherwise the first address.
- `ar.scraper.security.OutboundUrl` (pure Java, static): `isAcceptable(String url)` — scheme http/https, host present,
  not localhost, IP literal must be canonical and not internal; non-canonical numeric hosts (`2130706433`, `0x7f.1`,
  `127.1`) rejected. No DNS.
- `ar.scraper.scrapers.egress.EgressProxy`: loopback HTTP proxy (CONNECT tunnel + absolute-form http forwarding with
  `Connection: close`), depends on a destination-resolution abstraction (DIP) so tests can point an allowed fake host at
  a local server. Process-wide lazy `shared()` instance (avoids widening `ScraperService`, 15 manual call-sites).
- `BaseScraper` launches Chromium with the proxy (seam: package-private supplier of the proxy server, default
  `EgressProxy.shared()`).

## Tasks

- [x] T1 `OutboundAddressPolicy` + `OutboundUrl` + unit tests.
- [x] T2 `SitiosController.agregarSitio` → 400 `solicitud_invalida` for unacceptable URLs (existing tests untouched).
- [x] T3 `EgressProxy` + socket-level tests (tunnel works, http forwarded/rewritten, internal blocked with upstream
      never hit, redirect-to-internal blocked at the hop).
- [x] T4 `BaseScraper` runs Chromium through the proxy + real-browser regression of the redirect probe (skips when
      Chromium cannot launch; backend CI installs no browsers).
- [x] T5 `VaypolPage` image enrichment HttpClient goes through the proxy.
- [x] T6 Docs: GOTCHAS (route does not see redirect hops), ARCHITECTURE (egress decision), KNOWN_ISSUES (residuals).

## Checks

- Strict TDD: RED observed before each GREEN. Runner (always `clean`, grep `ERROR]`/`BUILD FAILURE` too):
  `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
  Baseline: 3420 / 0 / 0. ArchUnit `BackendLayeringArchTest` must stay green (scrapers/security must not depend on web).
- No boilerplate comments; English names; commons-lang3 over null ternaries; conventional commits, no attribution.

## Progress

- 2026-10-03: exploration + both probes done; document created.
- 2026-10-03: T1–T5 by one writer, RED observed per task (compile RED for new classes; T2 6 failures; T4 internal server
  hit=1 without proxy). Deviation: EgressProxy in `scrapers.egress` made an ArchUnit cycle pages→scrapers→pages via
  VaypolPage → moved to `ar.scraper.security.egress`. T6 docs: ARCHITECTURE (egress decision), GOTCHAS (Leer un sitio),
  openapi 400, KNOWN_ISSUES (browser test skips in CI).

## Verification evidence

- Full suite: 3549 / 0 F / 0 E / 7 skipped (PythonRunner*, untouched), BUILD SUCCESS, 0 `ERROR]`.
  `BaseScraperEgressTest` 3/0/0 skipped=0 (real Chromium): redirect to internal → 0 hits; direct internal → 0 hits;
  negative control without proxy → ≥1 hit.
- Real-store smoke, direct vs proxied (status / links / ms): venex 200/828/7312 vs 200/828/6369; vaypol 200/86/5726 vs
  200/86/7058; sporting 200/562/7013 vs 200/548/7259 (dynamic carousels; the proxy only blocks internal hosts).

## Next step

Uncommitted. Waiting on another agent's review comments (user relayed only the save receipt, obs-0625f9a7f6234a17,
not visible in this Engram) before committing.
