# Armador de PCs — fase 9: preferencias finas (GB, torre, cooler, watts)

> **Objetivo.** Cuatro ejes que hoy el armador decide solo pasan a ser
> pedibles desde `/pcs`: capacidad de almacenamiento, tamaño del gabinete,
> tecnología del cooler y piso de watts de la fuente. Más dos ejes de
> ranking nuevos que profundizan lo que ya existía (radiador del cooler,
> watts de la fuente).
>
> Pedido del usuario (2026-09-22): *"Vamos con más profundidad con el
> almacenamiento: que en /pcs se pueda elegir la cantidad de GB. También
> profundidad del gabinete... Más profundidad en los cooler, water, aire.
> Más profundidad en los watts de las fuentes."*
>
> Modo TDD: **on** (`CODE-1`). Runner: `mvn -f scraper/pom.xml clean test`
> con el JDK partido de `TEST-2`; frontend `npm test`.

## Problema

El armador ya sabe leer estas cuatro cosas del nombre, pero el usuario no
puede pedir ninguna:

| Eje | Qué había | Qué faltaba |
|---|---|---|
| Almacenamiento | `capacidadGb` parseado, usado como **último** desempate del ranking | No se puede pedir un piso ("al menos 1 TB") |
| Gabinete | `formFactor` (ITX/MATX/ATX/EATX), sólo como veto "no más chico que la mother" | El **tamaño de torre** no se lee, y es lo que el comprador mira |
| Cooler | `TipoCooler` LIQUIDO/AIRE como eje de ranking (fase 7) | No se puede **pedir** líquida; y el slot sólo existe en gama ALTA |
| Fuente | `watts` sólo como piso derivado de la gama; ranking por certificación sola | No se puede pedir un piso propio, y entre dos GOLD ganaba la más barata (= la de menos watts) |

## Medición previa (dev DB, 2026-09-22)

Corrida antes de decidir nada, sobre las filas reales de cada categoría:

| Categoría | Filas | Cobertura del eje |
|---|---|---|
| Fuente | 354 | watts legibles **347 (98%)** |
| Almacenamiento | 297 | capacidad legible **297 (100%)** |
| Cooler | 342 | palabra de cooler 314; líquidos 171; de esos, **84 (49%) declaran radiador** (240mm×41, 360mm×38, 420mm×2, 280mm×1, 120mm×2) |
| Gabinete | 622 | tamaño de torre **46 (7%)**: MID 43 · FULL 2 · MINI 1. Form factor **54 (9%)** |

⚠️ **Esos conteos son sobre TODAS las filas, y el armador no ve todas.** La
corrida de T8 lo hizo visible: el snapshot del armador tiene sólo las filas
**activas**, y sobre ésas el gabinete es **40 de 575** — MID 39 · MINI 1 ·
**FULL 0**. Los dos únicos full tower del catálogo están soft-deleted, así
que hoy pedir `full` no deja dos candidatos: deja **ninguno**, y el slot sale
en `sinCompatible`. Toda medición que pretenda describir lo que el armador
hace tiene que filtrar `activo IS NOT FALSE`; la del armado lo hacía y las
de cobertura no.

⚠️ **El gabinete es el eje pobre, y es un dato, no una estimación.** Se
construye igual porque el usuario eligió ese eje explícitamente sabiendo el
número, pero el mensaje del slot tiene que poder explicarlo.

Las dos unidades vienen **pegadas** al número en el 100% de las filas
(`750W`, `1TB`) — medido: cero filas con `750 W` / `1 TB` separados. Los
lectores actuales, que exigen un token entero `\d+w` / `\d+(gb|tb)`, no
pierden nada.

## Decisiones

| | |
|---|---|
| **D1 — El tamaño de torre es un campo NUEVO, no un reemplazo de `formFactor`** | Son dos ejes distintos y el catálogo los nombra por separado: `mid-tower` es cuánto ocupa en el escritorio, `mATX` es qué placa entra. "mid-ATX" no existe. `formFactor` y su veto Gabinete ⊇ Mother quedan intactos; `tamanioGabinete` (`MINI`/`MID`/`FULL`/`DESCONOCIDO`) se suma al lado, con su propia regla |
| **D2 — La abstención VETA cuando el eje se pide** | Misma inversión que `gama` (fase 6) y las seis preferencias (fase 7): un nombre del que no se pudo leer el tamaño no permite afirmar que sea mid-tower. Sin pedido, la regla es no-op y no veta a nadie |
| **D3 — La capacidad se pide como PISO, no como valor exacto** | `capacidadMinimaGb`: "al menos 1 TB" es la pregunta que se hace quien compra; "exactamente 1024 GB" descartaría los 2 TB, que son mejores. El ranking (capacidad desc) ya resuelve el resto |
| **D4 — Pedir un tipo de cooler ABRE el slot, aunque la gama no sea ALTA** | Hasta la fase 8 el slot cooler sólo existía en gama ALTA (D4 de fase 8: es una decisión de tier). Pedir refrigeración líquida y recibir un armado sin cooler no responde la pregunta que se hizo. La condición pasa a ser `gama == ALTA` **o** `tipoCooler pedido`; sin pedido, byte por byte igual que antes |
| **D5 — El piso de watts pedido SUBE, nunca baja** | `wattsMin = max(EstimadorDeConsumo.wattsMinimos(gama, conGpu), pedido)`. Pedir 550 W en un armado de gama alta con GPU (piso 1000) no puede dejar el armado sin fuente suficiente: el piso de seguridad es del armado, el pedido es del usuario, y el que manda es el más alto. Por eso NO es una `ReglaCompatibilidad` nueva — `ReglaWatts` ya veta contra `contexto.wattsMin()` y no cambia una línea |
| **D6 — Dos ejes de ranking nuevos, y los dos cambian el default** | `COOLER`: tipo → **radiador desc** (entre dos líquidas gana la de 360mm). `FUENTE`: certificación → **watts desc** (entre dos GOLD que entren en la cuota gana la de más watts, no la más barata). Son cambios de comportamiento sin preferencia pedida, y son la "profundidad" que el pedido nombra. Con el reparto por cuotas de la fase 8 la fuente no puede vaciarle la caja a nadie |
| **D7 — Abstención última en los dos ejes nuevos** | D13 de la fase 8: `radiadorMm == 0` y `watts == 0` van al final vía `masEsMejor`, nunca primero |
| **D8 — `radiadorMm` y `tamanioGabinete` SÍ se persisten; el piso pedido no** | `producto_tech_specs` es la base del futuro filtro por specs de `/catalogo` (D3c de la fase 2) y los dos son atributos del PRODUCTO. `capacidadMinimaGb`/`wattsMinimos` son del PEDIDO, así que van a `preferencia_armador` y a ningún otro lado |
| **D9 — `tamanio_gabinete` es lookup con FK, los pisos son enteros nullable** | Mismo molde que los nueve lookups de `V35`/`V36` (D8 de la fase 2). `DESCONOCIDO` nunca se siembra: un centinela de abstención no es un valor de FK (`V21`, `marca=''`) |

## Alcance autorizado

`scraper/src/main/java/ar/scraper/pcs/**`, `scraper/src/main/resources/db/migration/V37__*.sql`,
`scraper/src/main/java/ar/scraper/web/{ApiController,PcsEndpoints}.java`,
`scraper/src/main/java/ar/scraper/db/` (write path de tech specs y preferencia),
`scraper/src/main/java/ar/scraper/agent/` (tool `propose_pc`),
`frontend/src/{components/PcsPanel.jsx,api.js}`, sus tests, `docs/openapi.yaml`,
`docs/DATABASE.md`, `CLAUDE.md`.

Fuera de alcance: el filtro por specs de `/catalogo`, `socketsSoportados` en la
base (sigue diferido desde la fase 7), y probar varias plataformas de mother
(la limitación greedy de la fase 8).

## Tareas

- [x] **T1 — Lectura: tamaño de torre y radiador.** `TamanioGabinete` enum;
      `TechSpecs` += `tamanioGabinete` + `radiadorMm` (ctor de compatibilidad,
      `CODE-2`); `GabineteSpecsReader` y `CoolerSpecsReader` los leen.
      Checks: tests de lector con las formas reales medidas.
- [x] **T2 — Ranking: radiador y watts (D6/D7).** `EjesTecnicos.COOLER` y
      `EjesTecnicos.FUENTE`. Checks: tests de comparador, abstención última.
- [x] **T3 — Pedido: las cuatro preferencias nuevas.**
      `PreferenciasDeArmado` += `capacidadMinimaGb`, `tamanioGabinete`,
      `tipoCooler`, `wattsMinimos`; `PreferenciasWire` parse/wire; reglas
      `ReglaCapacidadMinima`, `ReglaTamanioGabinete`, `ReglaTipoCoolerPedido`.
      Checks: tests de regla + `NINGUNA` idéntica al overload anterior.
- [x] **T4 — Armado: cableado en `PcBuilder` (D4/D5).** Slot cooler abierto
      por pedido; piso de watts `max`. Checks: test de armado real.
- [x] **T5 — Borde: endpoints + agente.** `ApiController`/`PcsEndpoints`
      query params y JSON de preferencia; `propose_pc`; `docs/openapi.yaml`.
- [x] **T6 — Persistencia: `V37`.** Lookup `tamanio_gabinete`, columnas en
      `preferencia_armador` y `producto_tech_specs`, write path e indexer.
- [x] **T7 — UI: `/pcs`.** Chips de capacidad mínima, tamaño de gabinete,
      cooler y watts mínimos; `resumenSpecs` muestra los campos nuevos.
- [x] **T9 — Total estimado en pesos y en dólares.** Pedido del usuario
      (2026-09-22, a mitad de la fase): el total de `/pcs` muestra además su
      equivalente en USD, tomando el **mismo servicio que el badge del
      header** (`GET /api/indices` → `usd.ultimoValor`, dólar oficial de
      `indices-service`). D10: si `usd` viene `sin_datos` o sin valor, la
      línea en dólares **no se muestra** — un factor nunca viaja sin marcar,
      y no hay tasa hardcodeada de reemplazo (ver CLAUDE.md, "Índices y
      señales"). Checks: test de `PcsPanel` con y sin dato de dólar.
- [x] **T8 — Docs y medición final.** `CLAUDE.md`, `docs/DATABASE.md`, y una
      corrida real de `PcBuilder.armar` sobre la dev DB con el antes/después.

## Criterios de aceptación

1. Sin ninguna preferencia nueva pedida, el armado es idéntico al de la fase 8
   **salvo** los dos ejes de ranking de D6, que son el cambio pedido.
2. Cada preferencia nueva, pedida sola, filtra su slot y deja un mensaje
   legible cuando lo vacía (`sinCompatible` + `motivo()`).
3. Pedir un piso de watts por debajo del de la gama no baja el piso real (D5).
4. Suite entera verde en el commit (`TEST-1`): backend, frontend.

## T8 — La medición (dev DB, 6875 filas de `tecnologia` activas, 2026-09-22)

Corriendo `PcBuilder.armar` de verdad sobre el catálogo real. El **antes** es
`aec48af` (master antes de mergear la fase 9) en un worktree aparte, con el
mismo catálogo y el mismo armado; no es una estimación ni una re-lectura del
diff.

| Armado | Slot | Antes (`aec48af`) | Después (fase 9) |
|---|---|---|---|
| sin presupuesto, GPU, gama alta | cooler | `Water Cooler 240mm` $66.700 | `Be Quiet! SILENT LOOP 3` **420mm** $244.035 |
| $2.000.000, GPU, gama alta | cooler | `Water Cooler 240mm` $66.700 | `Lovingcool` **360mm** $77.800 |
| $1.000.000, gama MEDIA, sin GPU | fuente | `Corsair RMe750` **750W** PLATINUM $157.536 | `Gamemax GX-1050` **1050W** PLATINUM $196.700 |
| $1.000.000, gama MEDIA, sin GPU | cooler | *(no existía el slot)* | `CPU Cooler Raptor Cryo` AIRE $18.400 |

El resto de los slots no se movió en ninguno de los tres armados: mother, cpu,
ram, gabinete, gpu y almacenamiento eligen exactamente lo mismo antes y después.
Eso es lo que se quería — los dos ejes nuevos tocan sólo los dos slots que
nombran.

⚠️ **El eje de watts se ejerce menos de lo que parece.** En los armados de gama
alta la fuente ya elegía el tope de certificación que entraba en la cuota
(TITANIUM 1600W y PLATINUM 1050W), así que el eje nuevo no cambió nada ahí. El
único armado donde se ve es el de gama media, porque es el único con dos
certificaciones iguales compitiendo. Es una mejora real y acotada, no una que
cambie todos los armados.

### Las preferencias nuevas, ejercidas

| Pedido | Qué hizo |
|---|---|
| disco ≥ 2 TB | `HD HDD 4TB WD BLUE SATA III` $356.510 (el disco de 1 TB que elegía sin pedido ya no califica) |
| fuente ≥ 1000 W | `Gamemax GX-1050` — el mismo que ya elegía; el piso no bajó nada |
| cooler LIQUIDO | `Lovingcool 360mm` |
| gabinete MID | `TEROS TE-1036S MID TOWER` $44.229 — contra los $22.500 del NOVA sin pedir tamaño |
| gabinete FULL | **slot vacío**, `sinCompatible`: *"no es un gabinete FULL, el tamaño pedido"*. Es el caso de cobertura cero descrito arriba |
| cooler AIRE en gama media | abre el slot, que sin pedido no existiría |

### Dos defectos que sólo aparecieron al armar de verdad

Ningún test los veía, porque los dos son sobre qué hay en el catálogo, no sobre
qué hace el código con lo que le das.

1. **Un fan de gabinete ganaba el slot de refrigeración por aire.** El guard de
   la fase 7 mira **sólo el primer token**, así que ataja `"Fan Cooler 120mm..."`
   y dejaba pasar `"Cooler Fan 120mm..."`, que es el mismo producto con las
   palabras al revés. Las 6 filas activas que lideran así son fans de 120/140mm
   y ninguna es un cooler de CPU; la más barata ($7.250) ganaba el slot. Ahora
   el líder cubre el par adyacente, y el pick pasó a `CPU Cooler Raptor Cryo`
   ($18.400), que es un cooler de CPU.

2. **Un disco externo USB ganaba el slot del disco.** Con un piso de capacidad
   pedido, `"HD HDD EXTERNO 4TB SEAGATE PORTABLE USB 3.0"` le ganaba a los
   internos — y un disco externo no es el disco de la PC que se está armando.
   18 de las 266 filas activas de Almacenamiento son externas. Ahora
   `AlmacenamientoSpecsReader` **abstiene la tecnología** de un externo en vez
   de vetarlo con una regla nueva: es la política de los pendrives, la
   abstención es el último escalón del eje y el producto se hunde solo, pero
   sigue siendo elegible como último recurso. `externo`/`externa` sola cubre
   las 18 y `portable`/`portatil` no suma ninguna por su cuenta, así que no
   entra al vocabulario y no puede traer falsos positivos.

   Esto rompió dos tests existentes que usaban un disco **externo** como fixture
   de "esto es un HDD". No es el contrato de refactor (`CODE-2`): es un cambio
   de comportamiento deliberado, y lo que esos tests querían probar era la
   lectura del keyword, no la externalidad. Se les cambió el fixture por un
   disco interno real del catálogo.
