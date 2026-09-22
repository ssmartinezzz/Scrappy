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

⚠️ **El gabinete es el eje pobre, y es un dato, no una estimación.** Con la
política de abstención invertida (ver D2) pedir `mid` deja 43 candidatos,
`full` deja **2** y `mini` deja **1**. Se construye igual porque el usuario
eligió ese eje explícitamente sabiendo el número, pero el mensaje del slot
tiene que poder explicarlo.

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
- [ ] **T5 — Borde: endpoints + agente.** `ApiController`/`PcsEndpoints`
      query params y JSON de preferencia; `propose_pc`; `docs/openapi.yaml`.
- [ ] **T6 — Persistencia: `V37`.** Lookup `tamanio_gabinete`, columnas en
      `preferencia_armador` y `producto_tech_specs`, write path e indexer.
- [ ] **T7 — UI: `/pcs`.** Chips de capacidad mínima, tamaño de gabinete,
      cooler y watts mínimos; `resumenSpecs` muestra los campos nuevos.
- [ ] **T9 — Total estimado en pesos y en dólares.** Pedido del usuario
      (2026-09-22, a mitad de la fase): el total de `/pcs` muestra además su
      equivalente en USD, tomando el **mismo servicio que el badge del
      header** (`GET /api/indices` → `usd.ultimoValor`, dólar oficial de
      `indices-service`). D10: si `usd` viene `sin_datos` o sin valor, la
      línea en dólares **no se muestra** — un factor nunca viaja sin marcar,
      y no hay tasa hardcodeada de reemplazo (ver CLAUDE.md, "Índices y
      señales"). Checks: test de `PcsPanel` con y sin dato de dólar.
- [ ] **T8 — Docs y medición final.** `CLAUDE.md`, `docs/DATABASE.md`, y una
      corrida real de `PcBuilder.armar` sobre la dev DB con el antes/después.

## Criterios de aceptación

1. Sin ninguna preferencia nueva pedida, el armado es idéntico al de la fase 8
   **salvo** los dos ejes de ranking de D6, que son el cambio pedido.
2. Cada preferencia nueva, pedida sola, filtra su slot y deja un mensaje
   legible cuando lo vacía (`sinCompatible` + `motivo()`).
3. Pedir un piso de watts por debajo del de la gama no baja el piso real (D5).
4. Suite entera verde en el commit (`TEST-1`): backend, frontend.
