# Estructura del repo

> Árbol comentado de archivos y paquetes clave. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Estructura de archivos clave

```
Scrappy/
├── CLAUDE.md                    ← Este archivo (estado)
├── CONTRIBUTING.md              ← Proceso: commits, PRs, TDD, docs — reglas con ID citable
├── .github/PULL_REQUEST_TEMPLATE.md
├── .github/workflows/           ← backend-tests, cli-tests, frontend-tests, ml-tests,
│                                docker-smoke, e2e-login-smoke
├── SKILL.md                     ← Índice de documentación
├── INSTALAR_Y_CORRER.bat        ← Windows: aprovisiona _tools/ e invoca `-m cli`
├── Ejecutar_instalar.sh         ← Mirror POSIX
├── docker-compose.yml + Dockerfile + docker.env.example
├── cli/                         ← CLI nativo (Python)
│   ├── core/                    ←   headless: config, env_file, builder, rest,
│   │                                processes, commands, logs, errors
│   ├── tui/                     ←   consola Textual
│   ├── plain/                   ←   fallback texto plano
│   └── __main__.py              ←   detección de capacidad + routing
├── docs/                        ← DATABASE, ARCHITECTURE, API_REFERENCE, ADD_SCRAPER,
│                                  ML_PIPELINE, LLM_EMBED, LLM_AGENT_SETUP
├── openspec/                    ← Artefactos SDD (changes/ activos, changes/archive/ cerrados, specs/)
├── odd/tasks/                   ← Documentos de feature ODD (objetivo, tareas, evidencia medida)
├── scripts/
│   ├── dev-db.sh                ← Postgres de dev on-demand (up/down/status)
│   └── hooks/commit-msg         ← bloquea COMMIT-1 y COMMIT-3 (activar: git config core.hooksPath scripts/hooks)
├── ml-tests/                    ← pytest del pipeline Python
├── tests/cli/                   ← pytest del CLI nativo
├── tests/e2e/                   ← e2e capa API (pytest) + `run-e2e.sh`, el runner de las dos capas
│                                  Levanta backend + preview y los apaga. NUNCA contra `vite dev` (ver Gotchas)
├── frontend/e2e/                ← e2e capa browser (Playwright): sesión, pestañas, roles, reseteo
├── tests/perf/                  ← performance: DOS suites independientes sobre los mismos endpoints
│   ├── jmeter/                  ←   jmeter-java-dsl (Java, pom propio; `*IT` ⇒ `mvn verify`, nunca `mvn test`)
│   ├── locust/                  ←   locust como motor + pytest como runner, sobre uv: `uv run pytest`
│   │                                Lo lento (carga/stress/spike) está marcado `lento` y sale del default
│   └── perf-user.sh             ←   crea la cuenta VIEWER por la API real → `.perf-credentials.env`
│                                  Las DOS suites lo corren solas si faltan credenciales: con el backend
│                                  arriba, un solo comando alcanza y no hay que exportar nada
│                                  Ninguna levanta el backend: exigen uno vivo (`run-e2e.sh --api --keep-up`).
│                                  Presupuestos p95 MEDIDOS (15.987 productos, 2026-09-22). Ojo: los caros
│                                  son los SQL (`data` 160ms, `facets` 150ms), no los armadores (`pcs` 23ms)
└── scraper/
    ├── pom.xml
    ├── src/test/
    │   └── java/ar/scraper/architecture/BackendLayeringArchTest.java
    │                                   ← reglas ArchUnit. `grafoSinCiclos` ya NO está congelada:
    │                                     los 7 ciclos se cerraron en F3a y el golden se borró
    └── src/main/
        ├── java/ar/scraper/
        │   ├── App.java                    ← Entry point Spring Boot
        │   ├── config/                     ← ScraperConfig, RequiredEnvVarsGuard, un *Config por área que
        │   │                                  arma sus servicios de dominio con @Bean, y los adaptadores
        │   │                                  de Spring (CronTicker, IndiceRefreshRunner, SpringCronSchedule),
        │   │                                  TransactionConfig y CacheConfig/CacheNames (Caffeine acotado)
        │   ├── model/Product.java          ← Record de 19 campos (kernel compartido)
        │   ├── catalog/                    ← área: CatalogFilter/Page/Resumen, Facets, TalleOrder,
        │   │                                  HistorialEntry, HistorialPort, UpsertStats,
        │   │                                  CatalogQueryPort, ProductPort,
        │   │                                  PreciosExternosPort (los puertos los implementan
        │   │                                  @Repository package-private en db/) + ProductKey
        │   ├── classification/             ← área: SiteRegistry, SiteClassification, BrandExtractor,
        │   │                                  RubroResolver, CategoryGroups, SitiosPort (lo
        │   │                                  implementa un @Repository package-private en db/)
        │   ├── scrape/                     ← área: CorridaInterrumpida, ScraperStatus, ScrapeRunPort
        │   │                                  (lo implementa un @Repository package-private en db/) +
        │   │                                  ScrapeControlPort (lo implementa ScrapeControlAdapter,
        │   │                                  package-private en web/)
        │   ├── scheduling/                 ← área: CronJob, CronExecution, CronPort (lo implementa
        │   │                                  un @Repository package-private en db/) + CronJobRunner
        │   │                                  y CronJobService, absorbidos de cron/ en F3a
        │   ├── favoritos/FavoritosPort     ← área: puerto del agregado favoritos (lo implementa
        │   │                                  un @Repository package-private en db/)
        │   ├── financiacion/               ← área: Preset, PresetPort (lo implementa un
        │   │                                  @Repository package-private en db/)
        │   ├── indices/                    ← área: Indice, PuntoIndice, Confianza, Deflactor, Serie,
        │   │                                  DeflactorPorRubro, Extrapolador, IndiceService (único
        │   │                                  entry point para ml/ y web/) + IndiceRefreshJob (lo dispara
        │   │                                  config.IndiceRefreshRunner, un ApplicationRunner: NUNCA
        │   │                                  @PostConstruct, corre después de Flyway),
        │   │                                  ResumenIndice, IndicePort/FuenteIndicePort — reemplaza a
        │   │                                  InflacionService (ver Gotchas → Índices y señales)
        │   ├── feedback/                    ← área: OutfitItemRow, FeedbackPort — outfit_feedback_item
        │   │                                  + categoria_dismiss, una sola señal de gusto (lo
        │   │                                  implementa un @Repository package-private en db/)
        │   ├── outfits/                    ← área: OutfitService, OutfitBudgetBuilder, OutfitRules,
        │   │                                  VisualCoherence, RecommendationService, FeedbackModels,
        │   │                                  SupplementCombo, SupplementSizeParser (movidos de web/
        │   │                                  en F3b) + SavedOutfitsPort (lo implementa un
        │   │                                  @Repository package-private en db/)
        │   ├── pcs/                        ← área: TechSpecs + specs/ (un lector por categoría, fase 1/6),
        │   │                                  PcBuilder + SlotDeArmado + reglas/ + EjesTecnicos (fases 2/6),
        │   │                                  Gama, GamaWire, PreferenciaArmadorPort, TechSpecsPort,
        │   │                                  SavedPcsPort (lo sirve PcsEndpoints en web/)
        │   ├── pages/                      ← Page Object Model
        │   ├── scrapers/                   ← BaseScraper, ScraperFactory, *Scraper
        │   ├── aggregator/                 ← ResultAggregator + collaborators SOLID +
        │   │                                  CatalogSnapshotPort (lo implementa ScraperService)
        │   │   ├── normalize/              ←   PackQuantityDetector, CategoryClassifier,
        │   │   │                               GenderResolver, SizeNormalizer,
        │   │   │                               SubcategoryResolver, GymratTagger
        │   │   ├── grouping/               ←   GroupingService, ProductIdentity, JaccardSimilarity
        │   │   └── text/AccentStripper     ←   hot path: 10 clases lo usan
        │   ├── json/                       ← ProductJson, HistorialJson, PcBuildJson (Jackson de borde;
        │   │                                  el dominio no importa Jackson)
        │   ├── ml/                         ← PythonRunner, MlEnricher, SenalCalculator, MlOutputPort,
        │   │                                  CategoriaStatsPort (puertos con JsonNode)
        │   ├── agent/                      ← LLM Catalog Agent (ChatProvider + tools)
        │   ├── health/SiteYieldGuard       ← detecta colapso por sitio vs. la corrida previa
        │   ├── security/                   ← PasswordHasher (Argon2id), TokenService (HS256),
        │   │                                  RefreshTokenService (rotación + reuso), RefreshCookie,
        │   │                                  AdminSeeder (siembra + adopción)
        │   │                                  ActorResolver + Sujeto (quién actúa; ex identity/),
        │   │                                  ApiRoutePolicy (la matriz, como dato),
        │   │                                  SecurityConfig + JwtAuthFilter (el gate)
        │   │   └── reset/                 ←   PasswordResetService, ResetRateLimiter,
        │   │                                  ConsoleChannel (default) / SmtpChannel (opt-in)
        │   ├── fuentes/                    ← adapters HTTP de indices/: ArgentinaDatosIpcFuente,
        │   │                                  DatosGobIpcFuente (fallback), ArgentinaDatosDolarFuente
        │   │                                  (@Component package-private implementando
        │   │                                  FuenteIndicePort) + HttpJson + FuenteIndiceConfig
        │   ├── db/                         ← DatabaseService (fachada, HikariCP) + *Repository por tabla
        │   └── web/                        ← ApiController + *Endpoints (20 clases, transporte)
        │       └── cache/                  ← CatalogoDerivadoCache (@Cacheable: grupos, marcas, mejores),
        │                                      CatalogCacheEvictor (vacía al publicarse CatalogoActualizado)
        └── resources/
            ├── application.properties, logback-spring.xml, config.properties
            ├── db/migration/               ← Flyway
            └── ml/                         ← ml_pipeline.py, ml_train.py, ml_embeddings.py
```

`scraper/ml_*.py` junto al jar son **artefactos de extracción runtime**
(gitignoreados). La única fuente de verdad es `scraper/src/main/resources/ml/`.

📄 Las capas del backend las hace cumplir ArchUnit (`BackendLayeringArchTest`);
la forma objetivo (áreas `ar.scraper.<área>`, sin `db/` central) y su porqué
están en [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md).

---
