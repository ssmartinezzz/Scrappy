# Arquitectura del Fashion Scraper

---

## Base de datos → [`DATABASE.md`](./DATABASE.md)

**Nada sobre la base se documenta acá.** Esquema, migraciones, semántica del
upsert, estado de normalización, el porqué de cada decisión y el SQL de
rollback ejecutable viven todos en [`DATABASE.md`](./DATABASE.md). Esta
sección es sólo el índice, para que buscar "por qué" en este archivo no
termine en una copia desactualizada.

**La regla que gobierna toda tabla nueva, y que no se negocia caso por caso:
1FN y 3FN son precondición, no aspiración.** Una tabla que no las cumple se
rediseña antes de escribir la migración. El desarrollo —incluido el matiz de
1FN que este proyecto aprendió a los golpes: pide valores atómicos **además**
de ausencia de grupos repetitivos— está en
[`DATABASE.md § Regla de admisión`](./DATABASE.md#regla-de-admisión-toda-tabla-nueva-cumple-1fn-y-3fn).

| Qué buscás | Dónde está en `DATABASE.md` |
|---|---|
| Qué tablas hay y qué guarda cada una | § Esquema |
| Qué hizo cada `V1`..`V24` y las dos `R__` | § Esquema → Migraciones |
| Cuándo tabla de lookup y cuándo CHECK · qué lleva FK · cómo se dice "no hay valor" | § Regla de admisión |
| Semántica del upsert, soft-delete y reactivación | § Esquema → Estado normal |
| Por qué Postgres y no SQLite/H2 | § Decisiones y su justificación |
| Cómo revertir una migración aplicada (SQL que los tests ejecutan) | § Decisiones → los bloques `-- >>> rollback:VN` |
| Qué quedó deliberadamente sin normalizar, y por qué | § Non-goals |
| Por qué `/api/data` filtra en SQL y el resto lee el snapshot | § Decisiones y su justificación |

⚠️ Las dos funciones plpgsql (`sp_upsert_run`, `sp_soft_delete_ausentes`) se
editan **en su archivo `R__`** y en ningún otro lado. No agregues una migración
versionada para tocarlas.

---

## Decisiones principales y su justificación

---

### ¿Por qué un fat JAR con todo incluido?

**Decisión**: Spring Boot fat JAR con Tomcat embebido, backend **API-only** (sin servir la SPA).

**Razón**: es una herramienta local mono-usuario en Windows, no un servicio desplegado. Para ese escenario, un `.bat` que descarga Java + Postgres portable + Node + Python y ejecuta `java -jar scraper.jar` es la UX más simple posible: cero-setup, sin infraestructura previa. No hay Dockerfile, no hay instalaciones previas, no hay conflictos de versiones.

**Actualización (docker-install-alternative, 2026-07-21)**: la afirmación "no hay Dockerfile" de arriba queda como contexto histórico de por qué el installer portable fue la primera opción, no como estado actual — ahora existe una alternativa Docker **aditiva** (`Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`) para quien prefiera `docker compose up` en vez del `.bat`/`Ejecutar_instalar.sh` portable. Es un camino de instalación adicional, no un reemplazo: el installer portable y `_tools/` siguen intactos y sin cambios (el launcher interactivo que el installer portable invoca en su tail pasó de `menu.ps1`/`menu.sh` al CLI nativo en `cli/` — ver `native-cli-installer` más abajo — sin afectar este camino Docker). El backend usa la imagen `mcr.microsoft.com/playwright/java:v1.44.0-jammy` (Chromium + libs ya matcheadas a `playwright.version=1.44.0`) con Temurin 21 instalado explícitamente encima (la imagen base trae JDK 17) y Python 3.11 + deps ML (`psycopg2-binary`, `torch`/`torchvision` CPU, `open_clip_torch`, `huggingface_hub`, `transformers`) para que `PythonRunner.detectarPython()` resuelva `python3` por PATH sin necesitar `_tools/`. El frontend usa un build multi-stage (`node:20-alpine` → `nginx:alpine`) con `VITE_API_BASE_URL` como build ARG. Volúmenes nombrados (`pgdata`, `models`, `logs`) preservan datos y pesos ML descargados entre `docker compose down`/`up`. Ver `docker.env.example` para la plantilla de variables de este modo (distinta de `.env.example`).

Topología del modo Docker (mapea 1:1 a los 3 servicios de abajo; el ML sigue
siendo un subprocess **dentro** del contenedor backend, no un servicio propio):

```
docker compose up
┌─────────────────────┐   CORS    ┌──────────────────────────────┐
│ frontend (nginx)    │  :8080    │ backend (Java+Python+Chromium)│
│  host :8080 → :80   │──────────►│  host :3000 → :3000           │
│  build ARG          │  fetch    │  ├─ ML subprocess (psycopg2)  │
│  VITE_API_BASE_URL  │           │  └─ Playwright/Chromium       │
└─────────────────────┘           └──────────────┬───────────────┘
        depends_on: backend                       │ DNS interna: "postgres"
                                   ┌──────────────▼───────────────┐
                                   │ postgres:16-alpine (healthcheck)│
                                   └──────────────┬───────────────┘
volúmenes nombrados:  pgdata (DB)  ·  models (pesos Marqo/HF, lazy)  ·  logs
```

`APP_CORS_ALLOWED_ORIGINS` (`:8080`) y `VITE_API_BASE_URL` (`:3000`) tienen que
cerrar entre sí. `DATABASE_URL` apunta a `postgres:5432` (nombre del servicio, no
`localhost`) o a un Postgres externo (ver `docker-compose.override.yml.example`).

**Actualización (decouple-services-postgres, Batch 3/D6)**: el backend dejó de servir `static/` (se retiró `SpaController`); el proyecto pasó de "monolito con SPA embebida" a **3 servicios independientes** (backend API, frontend Vite, ML Python subprocess lanzado por el backend), cada uno configurado 100% por variables de entorno (`spec` "Environment-Only Configuration"). Ver el diagrama de topología más abajo.

---

### ¿Por qué Python como subprocess en lugar de Java ML?

**Decisión**: `ml_pipeline.py` ejecutado como proceso separado desde `PythonRunner.java`.

**Razones**:
1. El ecosistema ML de Python (TF-IDF, clustering, historial) es mucho más expresivo
2. El script no tiene dependencias externas — usa solo stdlib Python (json, math, re, collections)
3. Si Python no está disponible, el scraper sigue funcionando sin ML (degradación elegante)
4. El script vive en `src/main/resources/ml/` y se extrae del JAR al directorio de trabajo en el primer run

**Trade-off**: adds ~5-15 segundos al pipeline. Aceptable dado que el scraping tarda minutos.

---

### ¿Por qué Playwright headless y no requests/BeautifulSoup?

**Decisión**: Playwright (Chromium headless) para todos los scrapers.

**Razón**: los sitios argentinos usan JavaScript intensivamente. Tiendanube en particular actualiza precios dinámicamente. Con `requests` simple obtendrías `$0,00` en todos los precios. Playwright ejecuta el JS completo y expone el DOM final.

**Optimización**: `BaseScraper` bloquea imágenes, CSS, fonts y videos durante el scraping para reducir bandwidth y tiempo.

---

### ¿Por qué JS heurístico para TiendaNube en lugar de la API?

**Decisión**: intentar API REST (`/api/v1/{storeId}/products`) y si falla, usar extractor JS.

**Razón**: la API de TN requiere `Authorization: bearer TOKEN` de OAuth. Sin el token, devuelve array vacío. Implementar OAuth completo requeriría que el usuario registre una app en TN y obtenga credenciales, lo cual es demasiada fricción.

**Fallback JS**: busca `data-price` (atributo TN nativo, siempre presente, entero sin formateo), luego scan manual del texto para precios, luego clase CSS. Funciona en la mayoría de temas pero solo captura la primera página a menos que `nextPageUrl()` encuentre el link de siguiente página.

---

### ¿Por qué INPRO es su propia plataforma y no `tiendanube`?

**Decisión** (`add-inpro-office-store`, 2026-08-20): `plataforma='inpro'`, con
`InproPage`/`InproScraper` propios, aunque los datos que sirve son objetos
crudos de la API de Tiendanube.

**Razón**: la plataforma que importa es la de la **vidriera**, no la del
backend. INPRO corre un Next.js propio en Vercel y el storefront clásico de
Tiendanube no es alcanzable — `inpro.mitiendanube.com` redirige a otra tienda
(`inproindumentaria.com.ar`) y los slugs candidatos dan 410. Rutearlo a
`TiendanubeScraper` lo mandaría a buscar selectores de un tema que ahí no
existe: **0 productos y ningún error**, que es el mismo modo de falla que `V24`
cerró para Rockethard y Venex.

Leer el payload RSC en vez del DOM además **gana** datos: precio de lista,
precio promocional, precio comparado, stock por variante y SKU, todo lo que la
vidriera no muestra.

Esto generaliza: **"qué API sirve los datos" y "qué scraper hay que usar" son
preguntas distintas**, y confundirlas produce sitios registrados que scrapean
cero en silencio. El procedimiento de detección está en
[`ADD_SCRAPER.md`, Caso 6](./ADD_SCRAPER.md).

---

### ¿Por qué `oficina` es un rubro nuevo y no se reusó uno existente?

**Decisión**: abrir `productos.rubro` a un cuarto valor (`V27`) en vez de
meter sillas y escritorios en `indumentaria`, `tecnologia` o `suplementos`.

**Razón**: `V6` puso `CHECK` en esas columnas justamente para que un valor de
dominio no pueda mentir sobre el producto. Una silla ergonómica clasificada
como `tecnologia` no es un compromiso: es el dato roto que el `CHECK` existe
para impedir, y además contamina las estadísticas por categoría de las que come
el pipeline ML.

El rubro se resuelve por `sitio.rubro_forzado` y **nunca** por la categoría —
una silla la vende una tienda de oficina, pero una silla suelta en una tienda
de ropa no convierte a esa tienda en otra cosa. `suplementos` es la excepción
deliberada y ya existente: ahí la categoría gana sobre el sitio, porque un
suplemento es un suplemento lo venda quien lo venda.

El esquema, las tres ampliaciones de `CHECK` y su rollback están en
[`DATABASE.md`, `V27`](./DATABASE.md).

---

### Bloqueo conocido: Logg queda fuera de `fix-zero-yield-tech-sites`

De los cinco sitios tech que scrapeaban 0 productos en el run del 2026-08-11
(Compragamer, Rockethard, Venex, Maximus, Logg), cuatro se resolvieron en este
cambio. **Logg no.** El diagnóstico heredado lo daba como "typo de URL,
minutos" — medido en vivo contra `logg.com.ar` (2026-08-13) resultó ser el más
caro de los cinco, no el más barato:

- Plataforma custom ASP.NET **ABP** (`abp.min.js`, `abp.jquery.js`,
  `signalr.min.js`), no una de las plataformas ya soportadas.
- El grid de productos es **JS-hydrated**: `/Products?categoryName=…` sirve
  ~119 KB con cero product cards en el HTML crudo — todo el contenido llega
  después, por un mecanismo no identificado (¿JSON endpoint propio? ¿SignalR?
  ¿ambos?).
- La fuente de hidratación real nunca se aisló durante la exploración de este
  cambio (proposal, riesgo R2). Sin eso, ni un `LoggPage`/`LoggScraper` ni una
  fila de seed para `logg` en `sitio.plataforma` pueden escribirse con
  confianza — arrancarían adivinando un contrato que nadie confirmó contra el
  sitio real.

**Decisión** (post-design, explícita del usuario): no ship Logg en este
cambio. Consecuencia directa en el esquema: el dominio de `sitio.plataforma`
crece de 9 a **11** valores (`qloud`, `oscommerce`), no a 12 — no existe
`logg` en el CHECK, no existe `V25`, no existe `LoggPage`/`LoggScraper`, y
`config.properties` no tiene ninguna entrada `sitio.logg.*`. No queda ningún
valor muerto ni código muerto por retirar después: la migración que hubiera
agregado `logg` nunca se escribió, en vez de escribirse y revertirse.

Retomar Logg es trabajo de exploración, no de implementación: hay que capturar
tráfico de red real contra `/Products?...` (DevTools, no `curl`) para encontrar
qué endpoint (o mecanismo SignalR) entrega los datos antes de poder diseñar
`LoggPage`.

---

### ¿Por qué el aggregator está modularizado en collaborators de responsabilidad única?

**Decisión**: `ar.scraper.aggregator` se organiza como orquestadores delgados (`NormalizerService`, `GroupingService`, `ResultAggregator`) que secuencian collaborators de responsabilidad única, agrupados en subpaquetes por tema (`normalize/`, `grouping/`, `text/`), más `FacetCalculator` como utility estática en la raíz del paquete.

**Razón**: antes de esta modularización, `NormalizerService` (categoría, talles, género, marca, pack/combo, subcategoría, rubro, gymrat) y `ResultAggregator` (validación, dedup, pipeline ML, persistencia, facets) eran clases monolíticas: cada regla de negocio nueva crecía el mismo archivo y era imposible testear una regla sin arrastrar todas las demás. La modularización es **behavior-preserving** — cero cambios observables end-to-end, con la suite existente pasando sin editar un solo test. El historial slice por slice se retiró de `docs/` una vez completada; queda en el historial de git.

**Estructura resultante**:

| Paquete | Responsabilidad | Clases |
|---------|------------------|--------|
| `aggregator` (raíz) | Orquestación de la agregación completa + utility de facets | `ResultAggregator` (orquestador: validar → dedup → pipeline ML → persistir → facets), `FacetCalculator` (cálculo puro y estático de facets) |
| `aggregator.normalize` | Normalización de un `Product`, orquestada por `NormalizerService` | `PackQuantityDetector`, `CategoryClassifier`, `BrandExtractor`, `GenderResolver`, `SizeNormalizer`, `SubcategoryResolver`, `RubroResolver`, `GymratTagger` + holders estáticos de datos/predicados: `GarmentTaxonomy`, `CategoryGroups`, `SiteClassification`, `NonTextileGuard` |
| `aggregator.grouping` | Agrupación de productos equivalentes entre sitios, orquestada por `GroupingService` | `ProductIdentity`, `JaccardSimilarity`, `ProductGroup` |
| `aggregator.text` | Utilidades de texto compartidas entre `normalize` y `grouping` | `AccentStripper` |

**Patrones aplicados**:
- **Orquestadores puros**: `NormalizerService.normalizarProducto` y `ResultAggregator.agregar` son el único lugar donde se reconstruye el record `Product` o se arma el `AggregatedResult` — secuencian sus collaborators (inyectados por constructor) y no contienen lógica de negocio propia. Ningún collaborator conoce a los demás.
- **Holders estáticos de datos/predicados**: `GarmentTaxonomy`, `CategoryGroups`, `SiteClassification` y `NonTextileGuard` no tienen estado ni dependencias — se consumen vía static import dentro de los collaborators que los necesitan, en vez de inyectarse como beans adicionales en `NormalizerService`.
- **`FacetCalculator` como utility estática, no bean**: a diferencia de los collaborators de `normalize`/`grouping` (todos `@Component`), `FacetCalculator` es `final` con constructor privado y un único método estático — refleja que el cálculo de facets no tiene estado ni dependencias. `ResultAggregator.calcularFacets` se mantiene como delegate público porque ~10 tests fuera del paquete (`ar.scraper.web`) construyen fixtures de `AggregatedResult` contra esa firma exacta.
- **Test factory para tests de orquestación**: `NormalizerService` requiere 8 collaborators por constructor, así que los tests que ejercitan la normalización end-to-end usan `NormalizerServiceTestFactory.create()` (solo en `src/test/java`) en lugar de instanciar los 8 collaborators a mano en cada test.

---

### ¿Por qué React + Vite en el frontend?

**Decisión**: SPA en React 18 + Vite 5, servida como su **propio servicio** (no más static resource embebido en el JAR).

**Nota histórica**: este documento describía el frontend como "HTML/JS vanilla servido como static resource desde Spring Boot" — eso dejó de ser preciso mucho antes de `decouple-services-postgres` (el frontend ya era React/Vite, buildeando a `scraper/src/main/resources/static/`). El swap de `decouple-services-postgres` (Batch 3, design D6) es un cambio distinto y posterior: dejar de embeber el build de Vite en el JAR — el backend ahora es API-only (`SpaController` removido) y el frontend corre como servicio independiente, hablándole al backend por CORS (`APP_CORS_ALLOWED_ORIGINS`) usando `VITE_API_BASE_URL` como base de sus fetches (`frontend/src/api.js`).

---

### ¿Por qué el LLM vive fuera del backend y no puede escribir solo?

**Decisión**: el modelo corre en un proceso aparte (Ollama por defecto) al que Java le habla por HTTP detrás de la costura `ChatProvider`; el agente tiene tres herramientas, **todas de solo lectura**, y la única escritura real ocurre fuera de su loop, tras confirmación humana explícita y con re-validación server-side.

**Por qué**: embeber un runtime de inferencia en el JAR ataría el proyecto a un modelo y a un backend de hardware. Como proceso externo, el LLM es una dependencia **opcional**: si no responde, solo falla el chat del agente. Y como el loop autónomo no puede escribir, el tool-calling poco confiable de un modelo local de 14B degrada, en el peor caso, a *una propuesta rechazable* — nunca a una escritura corrupta.

📄 **Detalle completo en [`LLM_EMBED.md`](./LLM_EMBED.md)**: topología de la integración, la costura `ChatProvider` y su único adapter, el loop acotado (`MAX_ITERATIONS`), las tres herramientas, y las **ocho reglas** que gobiernan al agente — empezando por la Regla 0, que el system prompt es guía y no un control de seguridad. Para instalar y configurar, ver [`LLM_AGENT_SETUP.md`](./LLM_AGENT_SETUP.md).

---

### ¿Por qué los armadores de outfits puntúan con pesos y nunca con filtros?

**Decisión**: hay dos armadores con objetivos distintos —`OutfitService.armar` (aleatorio ponderado, superficie Gym) y `OutfitBudgetBuilder` (MCKP con branch-and-bound, superficie de presupuesto)— y **toda** señal que incorporan (oportunidad ML, likes del usuario, coherencia visual) entra como multiplicador acotado, nunca como descarte.

**Por qué dos armadores**: son dos preguntas distintas. "Mostrame un outfit" quiere variedad entre recargas, así que muestrea; "armame el mejor outfit con $X" quiere el óptimo global bajo una restricción dura, que es literalmente un Multi-Choice Knapsack. Un solo algoritmo haría mal las dos.

**Por qué pesos y no filtros**: el catálogo argentino es chico y desparejo por categoría. Un filtro convierte "este short combina mal" en un **slot vacío**, y un outfit incompleto es peor producto que uno un poco ruidoso. Un peso degrada el candidato malo y lo deja alcanzable.

**Por qué el neutro se ancla en 1.0**: cada factor se normaliza contra el valor que produce una señal ausente — `mlFactor` divide por `baseMlScore(MlScore.EMPTY)`, la coherencia devuelve 1.0 cuando no hay atributos. Así, un catálogo sin datos de ML o sin clasificación visual produce **exactamente** los pesos previos. Es lo que permite agregar una señal nueva sin invalidar la suite existente como red de regresión.

**Por qué la coherencia visual no rompe el óptimo del MCKP**: la penalización se aplica como **resta de un monto no-negativo** al score del candidato, así que `aporte ≤ score` siempre y la cota superior del branch-and-bound —construida con scores sin penalizar— sigue siendo válida. Ninguna rama óptima se poda. Además cada par de slots se evalúa exactamente una vez, cuando se asigna el segundo de los dos, así que el total no depende del orden en que el solver recorre los slots.

**Por qué la regla de color es una rueda de tonos y no una tabla de pares**: una lista escrita a mano de "estos colores combinan" es un conjunto de opiniones que nadie puede revisar y que crece cada vez que alguien discrepa con una entrada. Un orden circular de tonos con un umbral de distancia es una estructura chequeable y un solo parámetro que tunear. Los neutros no tienen posición en la rueda — por eso combinan con todo, sin necesidad de enumerarlo.

**Por qué un atributo visual vacío no penaliza**: vienen de un clasificador zero-shot que se abstiene cuando duda, así que buena parte del catálogo no los tiene. Una regla que castigara el dato faltante no estaría coordinando outfits: estaría degradando en silencio a todo producto que el clasificador salteó.

### ¿Por qué Morashop tiene page y plataforma propias si es un Tiendanube común?

Porque el valor de `plataforma` no describe la tienda, **rutea el scraper**. Desde `V20` `ScraperFactory` elige la clase leyendo `sitio.plataforma` vía `SiteRegistry`, y los name-sets en código se borraron (`CODE-6`). Morashop necesita una page propia, así que necesita un valor propio; rutearla por nombre de sitio reintroduciría exactamente lo que `V20` sacó. `monkyforce` ya había sentado el precedente. El costo aceptado es que `plataforma` sigue derivando hacia "discriminador de ruteo" más que hacia "qué software corre la tienda" — una deriva que ya existía con `vaypol` y `qloud`.

La page propia **no toca la extracción**. El extractor compartido lee las cards de Morashop sin un solo cambio; se verificó corriendo `buildExtractorJs()` verbatim contra `/suplementos/proteinas/` en un Chrome real, con 50 productos limpios. Lo que cambia es la navegación, y por dos motivos distintos que conviene no mezclar:

**Uno: la tienda no tiene URL de catálogo.** `/productos/` es una landing del tema con cero productos y `/suplementos/` es un índice de subcategorías, también cero. El catálogo vive un nivel más abajo. La convención de Tiendanube dice que `/productos/` lista todo; el tema puede pisarla, y cuando la pisa el fallo es de los caros: cero productos sin error y sin página vacía. Se evaluó y **descartó** el sitemap como fuente de enumeración: `/sitemap.xml` trae 1724 URLs, todas `/productos/{slug}/` planas y sin señal de categoría, así que acotar a suplementos exigiría visitar 1724 páginas de producto contra 12 listados.

**Por qué se descubren las hojas en runtime y no se hardcodean las 12**: una lista fija es correcta el día que se escribe y se pudre en silencio el día que la tienda agrega la categoría 13. La alternativa barata —hardcodear más un test que pegue al landing y compare— habría metido el primer test con dependencia de red del repo, que se pone rojo cuando el sitio se cae y no cuando nosotros nos equivocamos. El descubrimiento se parte en un helper estático puro sobre hrefs (testeado con fixtures, sin browser, igual que `resolveNextPageFromHrefs`) más un borde de browser de una línea que no decide nada. Una sola regla hace todo el trabajo de alcance —una hoja es un path del mismo host exactamente un segmento debajo de la sección— y eso solo excluye el índice, las sub-subcategorías y las secciones hermanas, sin lista negra que mantener.

**Dos: la API está apagada por correctitud, no por velocidad.** Hoy `/api/v1/{storeId}/products` da 404 en Morashop, igual que en Entreno, así que intentarla sólo desperdicia dos navegaciones. Pero Morashop además vende supermercado, electro-hogar y bodega, y esa API devuelve la tienda **entera** sin filtro por sección. Si el endpoint se habilitara del lado del servidor, una page que siguiera intentándolo importaría tres rubros que no tienen valor en el dominio de `rubro` — y meterlos a la fuerza haría justo lo que `V6` existe para impedir. Depender de que un endpoint ajeno siga roto no es un diseño; `usaApi()` lo apaga.

**Por qué el descubrimiento vacío tira excepción en vez de devolver una lista vacía**: `SiteYieldGuard` detecta colapso comparando contra la corrida anterior, así que sólo ve **caídas**. Un sitio que rinde cero en su primera corrida —o que ya venía en cero— nunca lo despierta. Sin el throw, "cambió el markup del landing" es indistinguible de "la tienda está vacía". Mismo criterio y misma forma que `MaximusPayloadException`.

### ¿Por qué el tope de páginas de Tiendanube pasó a ser configurable?

Porque era un número sin dueño. El loop paraba en 25 páginas, un valor compartido por los trece sitios TN que nadie eligió pensando en ninguno de ellos. El catálogo real de Entreno son 53 páginas de 12 productos —la 54 devuelve cero— así que ese techo descartaba cerca de la mitad, ~313 de ~636, sin error y sin nada que un operador pudiera ver.

**Por qué se subió el default global y no se le puso un caso especial a Entreno**: un override por sitio habría arreglado Entreno y dejado la bomba armada para el próximo catálogo que crezca. El tope nunca fue el mecanismo de corte real —quien corta es el chequeo de dos páginas vacías seguidas, que en Tiendanube funciona porque pasado el final sirve una página vacía en vez de repetir la última como hace osCommerce—, así que subirlo a 60 no cambia dónde termina ninguna corrida sana: sólo deja de truncar las que el techo cortaba. El override por sitio queda como escape, no como el arreglo.

**El default vive en `TiendanubePage`, no en `ScraperConfig`**: `ar.scraper.pages` no importa `ar.scraper.config` y esa frontera valía la pena conservarla, pero tener el número dos veces valía menos. La solución es que la page sea dueña de la constante, que config sólo parsee el override, y que el scraper —el único que ya depende de las dos capas— les pase el fallback. Una definición sola (`CODE-6`) sin invertir la dependencia.

### ¿Por qué un lector no ve la corrida que está en curso?

Porque durante un scrape el catálogo no es un estado, es una transición. El
soft-delete desactiva lo ausente, el upsert re-toca lo presente y
`fromDBParcial` rearma el snapshot en memoria una vez por sitio terminado: entre
el sitio 1 y el 26 el catálogo pasa por veintiséis formas intermedias, y ninguna
es una foto de nada. Servir eso hace que un producto desaparezca de la búsqueda
y reaparezca dos minutos después sin que nadie haya tocado nada.

El aislamiento tiene **dos mitades** porque hay dos familias de lectores:

| | Qué lee | Cómo se aísla |
|---|---|---|
| `/api/data`, `/api/facets` | SQL contra `productos` | Cota `touched_at < started_at` en los cinco predicados `activo` |
| `/api/mejores`, `/api/grupos`, outfits, agente | El snapshot en memoria | `servedResult`: la referencia a `lastResult` tal como estaba al arrancar |

**Por qué en memoria es una referencia y no una cota**: acotar el snapshot
significa re-filtrar ~20k productos en cada request, y `/api/grupos` ya reagrupa
el catálogo filtrado entero por request a través de `AccentStripper`, un hot path
documentado. `AggregatedResult` ya es copy-on-write, así que retener la
referencia vieja cuesta ~15 MB retenidos y **cero** en el pico — la corrida ya
sostiene dos o tres catálogos profundos por sitio terminado. O(n) por request
para ahorrar una referencia es el trade equivocado.

**El lector se aísla del SCRAPE, no de sí mismo.** Los cuatro caminos por los que
un usuario cambia el catálogo a mano —soft-delete manual, reclasificación del
agente, activar un preset de financiación, y `DELETE /api/db/productos`— parchean
**las dos** fotos bajo `catalogLock`. Olvidarse de uno no rompe nada visible: da
un defecto que sólo existe mientras hay una corrida abierta, que es exactamente
la clase de bug que ningún test de una sola foto puede ver.

**Por qué la cota SQL se suprime hasta que haya una corrida `COMPLETED`**, y no
"hasta que haya alguna corrida": en una instalación nueva la primera corrida *es*
una corrida. Con la regla floja la cota se aplicaría, ninguna fila cumpliría
`touched_at < started_at`, y el dashboard serviría una pantalla vacía durante todo
el primer scrape — justo lo que la supresión existe para evitar. Los otros estados
terminales (`CANCELLED`, `INTERRUPTED`, `ERROR`) tampoco cuentan: dejan el catálogo
a medio barrer, o sea sin un estado previo limpio en el que sostener al lector.
Ausencia de cota significa **servir todo**, nunca "cota = epoch, no servir nada".

La foto en memoria no necesita ese guard porque degrada sola: sin catálogo previo
`servedResult` queda null y el lector cae al vivo, que es precisamente ver el
progreso. Queda una ventana angosta y aceptada —una instalación que ya tenía
productos pero ninguna corrida registrada, o sea el primer scrape después de
`V29`— en la que las superficies SQL muestran el movimiento y las de memoria no.
Dura una corrida y se cierra sola.

**`/api/producto/{key}` queda exento**, por el mismo motivo por el que ya está
exento de `activo`: una ficha no puede tirar 404 a mitad de una corrida. No es una
excepción escrita a mano — entra por `obtenerProductoPorKey`, que nunca pasa por
`CatalogQueryRepository`, así que no hay nada de qué eximirlo. Hay un test que lo
fija para que agregarle la cota rompa el build.

**Adoptar la corrida y aislar al lector son una sola operación** (`adoptarCorrida`),
no dos que haya que acordarse de llamar juntas. Hay **tres** caminos que ponen una
corrida en curso —la normal, la retomada, y la retoma que sólo debe el barrido
final— y sólo el primero pasa por `abrirRun`. Con el aislamiento colgado de
`abrirRun`, los otros dos servían el catálogo a medio rearmar durante todo el
scrape, justo en el escenario donde más importa: una retoma corre sobre un
catálogo que ya quedó a medias. Ninguna de las dos ramas podía verlo con sus
propios tests —una no tenía aislamiento, la otra no tenía retoma— y las dos
mitades de `ScraperService` auto-mergean limpio, así que git tampoco avisa.

**La cota es `<` estricto, deliberadamente al revés que el `>=` de la unión del
soft-delete.** Es la misma columna en direcciones opuestas y las dos son
correctas: el barrido tiene que **proteger** filas de ser borradas, así que
incluye el segundo del arranque; el lector puede **ocultar** una fila fresca de
más, que no le cuesta nada a nadie. Una fila tocada en el primer segundo de la
corrida queda oculta, no visible temprano.

### ¿Por qué la oferta de retomar vive en el layout y no en `/splash`?

Porque `/splash` es la pantalla que un ADMIN **no** ve después de una caída.

Una corrida interrumpida commiteó los sitios que alcanzó a terminar, así que
`GET /api/status` reporta `tieneData: true`, y `RootGate` —que rutea por
exactamente ese campo— manda la primera visita a `/catalogo`. Un banner montado
dentro de `SplashPanel` sólo aparecería si el operador navegara a `/splash` a
mano, adivinando que hay algo ahí que mirar. Sería invisible justo en el único
escenario para el que existe.

Montarlo a nivel `AppLayout` lo pone en toda ruta de la app, sobre el read único
de `isAdmin` que ese archivo ya hace. **Es una oferta, no un secuestro**: la ruta
debajo no se toca y nada redirige, que es lo que pide la regla de que un ADMIN
pueda estar donde quiera durante una corrida. Retomar es lo que navega a
`/splash`, porque ahí es donde está el progreso.

**El gate de rol es sobre la request, no sobre el render.** `GET /api/scrape/interrupted`
y `POST /api/scrape/resume` son ADMIN en `ApiRoutePolicy.TABLE` —medido: VIEWER
403 en las dos, anónimo 401— así que preguntar y esconder la respuesta compraría
un 403 por una pregunta que no hay que hacer. Un VIEWER no emite la llamada.

**No hay endpoint para descartar, y la UI lo dice en vez de disimularlo.**
`ScraperService.interrumpida` se limpia únicamente dentro de `reanudar()`. El
botón secundario dice **"Ocultar por ahora"**, nunca "Descartar": esconde el
aviso en esta sesión y un reload lo trae de vuelta, porque la corrida sigue
interrumpida. La alternativa —un "Descartar" que en realidad sólo oculta— sería
un botón mintiendo sobre estado que el cliente no posee.

**El poller no se arma solo, y eso era la mitad faltante.** Sólo el botón de
lanzar armaba el intervalo, así que aterrizar en `/splash` con una corrida ya
`RUNNING` —que es exactamente lo que pasa al retomar, y también tras un reload
a mitad de corrida— escribía `RUNNING` en pantalla y se quedaba ahí: status
congelado, sin progreso y sin completar, mientras la pestaña siguiera abierta.
La bandera que lo dispara la levanta la lectura de montaje **una sola vez**; si
espejara el status vivo, el efecto que la observa re-armaría el intervalo en
cada render que viera una corrida en curso.

---

## Diagrama de capas y topología de servicios

**Topología (decouple-services-postgres, Batch 3, design D6)**: 3 servicios independientes, cada uno arrancable solo con env vars — ninguno requiere que los otros estén corriendo para bootear (spec "Independent Service Startup").

```
┌───────────────────────────┐        ┌───────────────────────────┐
│   Frontend (Vite/React)   │  CORS  │   Backend (Spring Boot)    │
│   VITE_API_BASE_URL ──────┼───────►│   APP_CORS_ALLOWED_ORIGINS │
│   propio proceso/puerto   │  fetch │   API-only (sin SPA)       │
└───────────────────────────┘        └──────────────┬──────────────┘
                                                      │ lanza subprocess
                                      ┌───────────────▼───────────────┐
                                      │  Python ML (subprocess)        │
                                      │  DATABASE_URL (psycopg2 DSN,   │
                                      │  traducido desde el jdbc: de   │
                                      │  Java por toPsycopgDsn)         │
                                      │  SCRAPER_MODELS_ROOT / HF_HOME │
                                      └───────────────┬───────────────┘
                                                      │
                                      ┌───────────────▼───────────────┐
                                      │      PostgreSQL (DATABASE_URL) │
                                      │  Flyway V1__baseline.sql +     │
                                      │  sp_upsert_run/                │
                                      │  sp_soft_delete_ausentes       │
                                      └────────────────────────────────┘
```

Capas internas del backend (sin cambios de forma, solo el datasource):

```
┌───────────────────▼─────────────────────┐
│         ApiController.java              │  Spring MVC (+ CorsConfig)
├─────────────────────────────────────────┤
│         ScraperService.java             │  Orquestación async
├──────────────┬──────────────────────────┤
│  Scrapers    │  ResultAggregator        │  Scraping + merge +
│  *Page.java  │  (aggregator.normalize/  │  normalizar + agrupar
│              │   .grouping/.text)       │
├──────────────┴──────────────────────────┤
│         DatabaseService.java            │  PostgreSQL (HikariCP pool),
│                                          │  write-path via plpgsql
├─────────────────────────────────────────┤
│   PythonRunner → ml_pipeline.py         │  ML subprocess (psycopg2)
└─────────────────────────────────────────┘
```

---

### Launcher: CLI nativo (`native-cli-installer` 2026-07-25 + `cli-command-console` 2026-08-05)

Supersede el launcher `menu.ps1`/`menu.sh` (`interactive-cli-launcher`, PR
#108) — ambos scripts, y sus tests (`tests/menu.Tests.ps1`/
`tests/menu_test.sh`), fueron **retirados** (borrados).

**El seam se movió:** antes, `INSTALAR_Y_CORRER.bat`/`Ejecutar_instalar.sh`
compilaban el proyecto (`npm install`/`npm run build`, `mvn clean package`),
generaban `.env` con un bloque `echo`/`cat` hardcodeado, y en su tail
invocaban `menu.ps1`/`menu.sh`. Ahora:

- Los installers **solo aprovisionan el toolchain**: JDK, Maven, Node, el
  Python 3.11 embeddable + deps ML (torch/scikit-learn/Marqo, sin cambios),
  PostgreSQL portable, y — nuevo — `uv` + un `_tools/cli-venv` dedicado.
- El **CLI nativo** (`cli/`, Python) posee todo lo que antes hacía el
  installer post-toolchain: build (`npm`+`mvn` vía las rutas vendorizadas
  en `_tools/`), generación/reconciliación de `.env` (template-driven desde
  `.env.example` — crea si falta, nunca pisa valores existentes salvo
  `--regenerate`/`--force`), y la orquestación de backend (`:3000`) +
  frontend (`npm run preview` en `:5173`), incluyendo el mismo teardown
  limpio en `Q`/`Ctrl+C` y la carga JVM `-DDATABASE_PASSWORD=<valor, incluso
  vacío>` que evita el bug de Windows con variables de entorno vacías.
- **Invariante (bloqueado por diseño):** el installer nunca compila el
  proyecto; el CLI nunca descarga ni instala un componente del toolchain
  bajo `_tools/`.

**Arquitectura del CLI** (headless core + presentadores, no una app Textual
monolítica):

```
cli/
├── __main__.py        # entry point: detección de capacidad + routing
├── core/               # HEADLESS — cero imports de textual/rich, testeable con pytest
│   ├── config.py        #   repo-root, paths de _tools/, puertos
│   ├── env_file.py       #   .env template-driven (crea/reconcilia/--force)
│   ├── builder.py        #   npm+mvn, ordenamiento de VITE_API_BASE_URL
│   ├── rest.py            #   cliente REST de la API existente (json.dumps, sin shell)
│   ├── processes.py       #   lifecycle backend+frontend + teardown funnel
│   ├── commands.py        #   registro ÚNICO de verbos (autocompletado + help + menú plano)
│   ├── logs.py            #   archivos de log de servicios + tail acotado + scrub de ANSI
│   └── errors.py          #   excepciones tipadas con mensaje accionable
├── tui/                 # PRESENTADOR — Textual App, solo presentación
└── plain/                # FALLBACK — driver de texto plano sobre el MISMO core
```

`__main__.py` decide UNA vez, antes de construir cualquier presentador, si
la terminal soporta la TUI interactiva (`isatty`, `NO_COLOR`, `TERM=dumb`,
`--plain`, `cmd.exe` legacy sin ANSI, o Textual no instalable) — si no,
degrada al runner de texto plano sobre el mismo `core/`. Nunca crashea.

**Consola por comandos, no menú (`cli-command-console`, 2026-08-05):** la TUI
es una franja de health de una línea + consola + prompt — tres filas de chrome,
corre en 60×18 sin maximizar. Las operaciones se tipean; el vocabulario vive
en `core/commands.py`, del que salen el autocompletado, el `help` y el menú del
runner plano, así que no pueden divergir. Se eliminaron los bindings de una
sola letra (se comían caracteres tipeables); quedan `ctrl+c` y `ctrl+l`.

**Por qué el stdio de los hijos está atado (el bug de fondo):** Spring Boot y
Vite escribían ANSI crudo sobre el mismo TTY que Textual estaba pintando, y el
frame quedaba destruido — Textual no se entera, así que ningún repaint lo
arregla. Parecía un problema de layout y era de stdio. Hoy los **tres** streams
de todo hijo están atados: stdout+stderr a `scraper/logs/{backend,frontend}.log`,
stdin a `DEVNULL` (si no, el hijo compite por las teclas del prompt). `PIPE` no
es opción: nadie lo drena y el hijo se cuelga cuando se llena el buffer. La
salida se lee con el comando `logs`, cuyo tail **escapa las secuencias de
control** antes de renderizarlas — una línea de log de un sitio scrapeado es
input no confiable y podría inyectar escapes en la terminal.

**Aislamiento del venv del CLI (`_tools/cli-venv`):** construido por `uv`
sobre un **CPython 3.11.9 administrado por uv** (`uv python install` +
`uv venv --managed-python`), **NO** sobre el Python embeddable de ML
(`_tools/python`). El CLI nunca importa librerías ML, así que este venv no
comparte nada con el pipeline de imágenes y no arriesga el guard de versión
de `torch` que el bloque ML del `.bat` ya protege. Esto también elimina por
construcción el riesgo de bootstrap que tendría reusar el embeddable (su
`python311._pth` congela `sys.path` de una forma que podría filtrarse a un
venv construido encima). `import textual` es el acceptance check del
installer — si falla tras el aprovisionamiento, el install aborta con un
mensaje accionable (Python es ahora load-bearing en Windows, igual que ya
lo era en POSIX).

**Invocación:** `_tools/cli-venv/{Scripts/python.exe,bin/python} -m cli`,
corrido con cwd = raíz del repo — **no** `cli/__main__.py` como ruta de
script directa, que falla con `ModuleNotFoundError: No module named 'cli'`
porque los módulos del CLI usan imports absolutos `cli.*` que solo resuelven
cuando la raíz del repo está en `sys.path` (el caso de `-m cli`, no el de
invocar el archivo directamente).

**Tests:** `tests/cli/` (pytest) — unit tests del `core/` headless, tests
`Pilot` de Textual para el `tui/`, tests de degradación del routing, un
test de **injection-safety** (`json.dumps` con input hostil `a"b;$(x)`
round-tripea como un único campo JSON bien formado, sin invocar shell) que
reemplaza estructuralmente a los viejos `tests/menu.Tests.ps1`/
`tests/menu_test.sh`, y un test con un **subproceso real** que escupe
stdout/stderr y verifica vía `capfd` que ni un byte llega a nuestra terminal.

---

### ¿Por qué Allure declarativo para el reporte de tests?

**Decisión**: reporte declarativo Allure sobre TODA la suite de tests backend (66 clases / 556 `@Test`), como **capa de reporte pura** — sin tocar assertions ni lógica de test. Anotaciones `@Epic`/`@Feature`/`@Story`/`@DisplayName` a nivel clase, `@Step` locales privados extraídos del setup/arrange ya existente, y `Allure.parameter(...)` en tests de boundary.

**Wiring clave (`scraper/pom.xml`)**:
- `allure-bom` 2.29.1 gestiona la versión de `allure-junit5`; `aspectjweaver` 1.9.24 es el javaagent que captura los `@Step` en runtime. Ambas son `scope=test` — NO entran al fat JAR (`spring-boot:repackage` corre en `package`, después de `test`).
- surefire usa `<argLine>@{argLine} -javaagent:"...aspectjweaver..."</argLine>` con **late-binding `@{...}`** (NO `${argLine}`). Esto es lo más frágil de todo el wiring: `jacoco:prepare-agent` (fase `initialize`) escribe su propio javaagent en la property Maven `argLine`; con `${argLine}` (interpolación *eager* en parse-time del POM) esa property todavía está vacía y JaCoCo **deja de recolectar cobertura silenciosamente** mientras los tests siguen pasando. `@{...}` es expansión *tardía*: surefire la resuelve en fase `test`, ya con la property poblada, concatenando el agente de JaCoCo + el de AspectJ.
- `allure-maven` 2.15.2 wirea `mvn allure:serve` / `allure:report`, pero el render HTML es **opcional**: el entregable CI-crítico es solo `target/allure-results/*.json`.

**`@Step` locales, no un god-class compartido**: cada `@Step` se extrae del ARRANGE de su propia clase → cada slice queda auto-contenido y revertible (fue clave para entregarlo como cadena de PRs encadenados sin conflictos cruzados). El único helper compartido, `testsupport/AllureSteps.java`, se reserva para pasos genuinamente cross-cutting (hoy solo `toJson`).

**CLI bundleado en el toolchain**: `INSTALAR_Y_CORRER.bat` baja el Allure CLI a `_tools/allure` y lo agrega al PATH de la sesión (mismo patrón que `jdk21`/`maven`/`node`; descarga no-fatal — si falla, la app igual corre). Flujo de uso:
```
mvn -f scraper/pom.xml test        REM genera target/allure-results/
allure serve scraper/target/allure-results
```

**Trade-off**: la versión del CLI (`allure-commandline` 2.29.0) se versiona **aparte** de las libs Java (`allure-bom` 2.29.1) — no existe un `allure-commandline` 2.29.1; el formato de `allure-results` es estable entre versiones de CLI, así que la diferencia es inocua. La coexistencia del `-javaagent` de AspectJ con el inline-mock-maker de Mockito 5 se verificó explícitamente en PR0.
