# Fashion Scraper — Índice de Documentación Técnica

Este índice referencia todos los documentos técnicos del proyecto. Cada doc es independiente y cubre un área específica.

---

## Documentos disponibles

| Doc | Qué cubre | Cuándo leerlo |
|-----|-----------|---------------|
| [`CLAUDE.md`](./CLAUDE.md) | Estado completo del proyecto, stack, sitios, API, problemas conocidos | Siempre — inicio de sesión |
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | Decisiones de arquitectura y por qué se tomaron | Antes de proponer cambios estructurales |
| [`docs/ADD_SCRAPER.md`](./docs/ADD_SCRAPER.md) | Paso a paso para agregar un sitio nuevo | Al agregar soporte para una tienda nueva |
| [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md) | Cómo funciona el pipeline ML y cómo extenderlo | Al modificar scoring, badges o clustering |
| [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md) | Todos los endpoints REST con params y responses | Al modificar la API o integrar con externos |
| [`docs/migration/aggregator-solid-modularization.md`](./docs/migration/aggregator-solid-modularization.md) | Historial slice por slice de la modularización SOLID de `ar.scraper.aggregator` (NormalizerService/GroupingService/ResultAggregator → orquestadores + collaborators) | Al tocar código en `aggregator/` y necesitar entender por qué una clase quedó donde quedó |
| [`docker-compose.yml`](./docker-compose.yml) + [`docker.env.example`](./docker.env.example) | Vía de instalación **aditiva** por Docker (`docker compose up`): 3 servicios (postgres + backend Java/Python/Playwright + frontend nginx). No reemplaza el `.bat`/`.sh`. | Al correr el proyecto con Docker o tocar la config de contenedores (ver también la topología en `ARCHITECTURE.md`) |
| [`menu.ps1`](./menu.ps1) / [`menu.sh`](./menu.sh) | Launcher interactivo (REST client puro de la API): arranca backend + frontend, ofrece scrape/retrain/status/CRUD de sitios, teardown limpio | Al modificar el menú interactivo o el arranque de servicios del flujo portable |

---

## Convenciones del proyecto

### Entrega y control de versiones
El proyecto se entrega por **git**: cada cambio va en una feature branch → PR a
`master` (squash merge), con **conventional commits** (`feat:`, `fix:`, `chore:`,
`docs:`, `ci:`…). Sin "Co-Authored-By" ni atribución de IA en los commits.

### Spec Driven Development (SDD)
Los cambios sustanciales pasan por el ciclo SDD, con los artefactos **persistidos
en engram** (no en chat): `explore → proposal → spec → design → tasks → apply →
verify → archive`. Cada fase la corre un sub-agente que lee/escribe su artefacto
en engram (topic keys `sdd/{change}/{fase}`). El orden de implementación dentro de
`apply` sigue siendo model → service → api → frontend.

### Escaping en Java strings con JS embebido
**Regla crítica**: dentro de strings Java que contienen código JavaScript:
- Regex con `\d`, `\s`, etc. → usar `\\d`, `\\s` (un nivel más de escape)
- Comillas dobles en JS → usar `'` (single quotes) siempre que sea posible
- NO usar `\?`, `\$`, `\,`, `\.` como escapes en Java — son ilegales
- Verificar siempre con el script Python de validación antes de empaquetar

### Patrón de adición de sitio nuevo
Ver `docs/ADD_SCRAPER.md`. Para una plataforma ya soportada (Shopify/TN/VTEX/Vaypol/Woo) alcanza con **2 archivos**: `config.properties` + el name-set en `ScraperFactory`. Una plataforma totalmente custom suma hasta **2 más**: un nuevo `*Page.java` + `*Scraper.java`.
