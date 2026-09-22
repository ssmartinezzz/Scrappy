# pc-builder-top-tier — el tope del catálogo, y un presupuesto que se reparte

> Fase 8 del armador de PCs. Sigue a `pc-builder-deep-taxonomy` (fase 7).
> Pedido del usuario (2026-09-22): "las fuentes que tiene el armador de PC no
> están certificadas. Tampoco me muestra siempre gabinetes, hay mala taxonomía
> con filtro o bracket para gabinete". Y, preguntando por el modo sin
> presupuesto: "si quiero ir a lo top top?".

## Objetivo

Que el armado sin presupuesto sea de verdad el tope del catálogo, y que el
armado con presupuesto reparta la plata entre los slots en vez de dejar que el
primero que pasa se la coma.

## Problema (medido, dev DB, 7054 filas activas de `tecnologia`, 2026-09-22)

Todo medido corriendo `PcBuilder.armar` de verdad sobre el catálogo
(`producto_tech_specs` sigue vacía: el armador parsea del snapshot, D3d). Los
scripts scratch viven fuera del repo.

### 1. El eje de CPU y GPU compara números de marcas distintas

`EjesTecnicos.CPU` y `EjesTecnicos.GPU` son `gama → generación desc`, y
`generacion` no es la misma magnitud en las dos marcas:

| | qué lee `generacion()` | ejemplo |
|---|---|---|
| Intel Core | la generación real | `i7 14700F` → `14`; Ultra 200 → `15` (mapeado a mano) |
| AMD Ryzen | el dígito de los **miles del modelo** | `Ryzen 9 9950X3D` → `9` |
| Nvidia RTX | el dígito de los **miles del modelo** | `RTX 5080` → `5` |
| AMD Radeon | el dígito de los **miles del modelo** | `RX 9070` → `9` |

Dentro de una marca el orden es correcto. Cruzando marcas es aritmética pura:
`14 > 9` y `9 > 5`, así que **Intel le gana a AMD en CPU y AMD le gana a Nvidia
en GPU, siempre**. Sin presupuesto y `gama=alta`, con un `Ryzen 9 9950X3D` y una
`RTX 5080` en el catálogo, el armado sale:

| slot | elige | lo top real |
|---|---|---|
| cpu | `i7 14700F` $588.270 | `Ryzen 9 9950X3D` $1.386.449 |
| gpu | `RX 9070 16GB` $1.324.990 | `RTX 5080 16GB` $3.660.860 |

Es la misma clase de bug que ya se pagó en la fase 7 con las RX 9000 (*"Radeon
numera de DOS maneras"*): un número comparable dentro de una marca, comparado a
través de marcas.

### 2. Dentro de la gama no hay escalones

`Gama.ALTA` mete en la misma bolsa a `i7`, `i9`, `Ryzen 7`, `Ryzen 9` y `Ultra
9`; y a `RTX 5090`, `RTX 5080`, `RTX 5070` y `RX 9070`. Un `i9` y un `i7` son
indistinguibles para el eje, y una `x90` y una `x70` también. Por eso "lo top"
puede salir un `i7` de 14ª contra un `Ryzen 9` de la serie 9000.

Cobertura de la señal que hace falta para partir la gama:

| Señal | Filas | |
|---|---|---|
| CPU con nivel de familia legible (9/7/5/3) | **374/427 (88%)** | 85 · 119 · 139 · 31 |
| GPU con tier de modelo legible (x90..x50) | **346/388 (89%)** | rtx x80=28, x70=70, x60=113, x50=43 · rx x90=1, x80=3, x70=35, x60=40, x50=7 |

### 3. El presupuesto se lo come el primer slot que pasa

`PcBuilder` es greedy: cada slot ve **todo** el presupuesto restante y, como el
precio es sólo desempate, se lleva el mejor candidato que entre. Con
`pres=2.000.000` y GPU:

| slot | pick | $ |
|---|---|---|
| mother | Asrock Z790I DDR5 | 284.037 |
| cpu | i7 14700F | 588.270 |
| ram | **DDR5 32GB 7600MHz** | **1.102.200** |
| gabinete | NOVA micro-ATX | 22.500 |
| fuente | **Jalatec Jt-520** | **25.881** ← `NINGUNA`, `watts=0` |
| gpu | **"ARMADO ITEM 6302"** | **800** |
| almacenamiento | Bracket Disco SSD | 3.300 |

La RAM se lleva el 55% de la caja y cuando llega el slot `fuente` no queda nada
asequible, así que cae al fallback *"gastá lo mínimo"* (`PcBuilder:145`) y elige
la fuente más barata del catálogo. Esa es la respuesta a "las fuentes no están
certificadas": **el ranking de fuente ya es correcto** (`EjesTecnicos.FUENTE` =
certificación desc, y sin presupuesto elige la MSI 1600W Titanium); lo que falla
es que nunca llega a ejercerse. En el catálogo sólo 46 de 342 fuentes son
`NINGUNA`, y son exactamente las más baratas:

| cert | filas | mínimo | promedio |
|---|---|---|---|
| GOLD | 136 | 87.990 | 211.332 |
| BRONZE | 91 | 51.990 | 97.523 |
| **NINGUNA** | **46** | **19.500** | 83.012 |
| PLATINUM | 43 | 157.536 | 446.161 |
| SILVER | 12 | 61.900 | 91.756 |
| WHITE | 11 | 53.990 | 73.923 |
| TITANIUM | 3 | 949.990 | 1.313.523 |

`Fuente Jalatec Jt-520` además pasa `ReglaWatts` porque `watts=0`: el nombre dice
`Jt-520`, no `520w`, y la abstención no veta.

### 4. Taxonomía: dos primos del bug del gabinete que siguen vivos

El guard de gabinete de la fase 7 (`bracket|filtro|soporte|kit` líder +
`"para gabinete"`) **ya funciona**: con el clasificador de hoy `"Bracket Disco
SSD para Gabinete Xigmatek Medusa"` → `Otros`, y el pick del slot pasa a ser un
`GABINETE NOVA CM-04Q1` real. Lo que el usuario está viendo es **dato viejo**: la
categoría se fija al scrapear y la base no se re-scrapeó. El drift pendiente en
la dev DB es de 205 filas (73 `Cooler→CPU`, 59 `CPU→PC`, 16 `GPU→PC`, 8
`Monitor→PC`, 7 `Gabinete→PC|Otros|Fuente`).

Quedan dos que el guard no cubre, y los dos ganan su slot porque son lo más
barato de su categoría:

- `"Bracket Disco SSD para Xigmatek Gaming X"` ($3.300) → `Almacenamiento`. Es
  bracket, pero no dice `"para gabinete"`, así que el guard no lo ve.
- `"ARMADO ITEM 6302 GTX 1050 Ti ATHLON 950 8GB"` ($800) → `GPU`. Es una PC
  entera; `KW_PC_LIDER` cubre `"PC ..."` pero no `"ARMADO ITEM"`.

## Decisiones

- **D1 — `nivel` es un eje nuevo, entre `gama` y `generacion`.** Campo `int` en
  `TechSpecs`, `0` = abstención (última, D13). CPU: `9|7|5|3` del nombre de la
  familia (`i9`/`Ryzen 9`/`Ultra 9` → 9). GPU: la decena del modelo
  (`90|80|70|60|50`), con la misma ramificación por serie que ya hace `gama()`
  para las RX no-9000 (centena, no decena). Es **comparable entre marcas**: un
  `i9` y un `Ryzen 9` son pares, una `RTX 5080` y una `RX 9080` también. Eso solo
  ya arregla el caso del usuario sin tocar `generacion`.
- **D2 — la generación se normaliza a un índice de recencia por marca.** Una
  tabla chica `(marcaChip, generacion) → año`, ramificando por marca como manda
  el precedente de las RX. Sin marca legible, o fuera de tabla, el valor cae a
  abstención y va último — nunca se compara crudo contra otra marca.
  AMD Ryzen `1000→2017 2000→2018 3000→2019 5000→2020 7000→2022 9000→2024`;
  Intel Core `8→2017 9→2018 10→2020 11→2021 12→2021 13→2022 14→2023 15→2024`;
  Nvidia `GTX 10→2016 16→2019` / `RTX 20→2018 30→2020 40→2022 50→2025`;
  Radeon `RX 5000→2019 6000→2020 7000→2022 9000→2025`.
- **D3 — el presupuesto se reparte por cuotas, con arrastre.** Cada slot recibe
  `share * presupuesto + lo que sobró del anterior`. Con GPU: mother 12 · cpu 20 ·
  ram 10 · gabinete 6 · fuente 10 · gpu 30 · almacenamiento 12. Sin GPU esa
  cuota de 30 se redistribuye. El cooler (sólo gama ALTA) toma su cuota del
  arrastre. Las shares son **supuestas, no medidas**, igual que el piso de watts
  de `EstimadorDeConsumo` — se documentan como tales.
- **D4 — presupuesto vacío sigue siendo el modo "top top", sin techo.** Con
  `presupuesto <= 0` no hay cuotas, no hay filtro de precio y gana el mejor de
  cada slot por eje técnico. Es el modo que responde la pregunta del usuario, y
  D1/D2 son lo que lo hacen cierto.
- **D5 — el fallback "gastá lo mínimo" se conserva.** Cuando ni con el arrastre
  entra nada en la cuota, el slot elige el más barato compatible en vez de salir
  vacío. Un armado incompleto es peor que uno con un componente flojo, y el
  mensaje de `sinCompatible` sigue reservado para los vetos.
- **D6 — `ReglaCertificacion` NO cambia.** Sigue dejando pasar `NINGUNA` (política
  normal de abstención). Con D3 la fuente sin certificar deja de ganar por
  presupuesto agotado, que es la causa real; invertir la regla vaciaría el slot
  en vez de arreglarlo. Se revisa si vuelve a aparecer.
- **D7 — `nivel` no se persiste.** `producto_tech_specs` no crece y no hay
  migración en esta fase. Mismo precedente que `socketsSoportados` en la fase 7:
  el armador calcula `TechSpecs` al armar desde el snapshot (D3d), así que la
  columna no haría falta hasta el filtro por specs de `/catalogo`, que no es de
  esta fase.
- **D8 — el guard de taxonomía se extiende, no se reescribe.** `bracket` líder
  veta el bloque tech aunque no diga `"para gabinete"`; `ARMADO ITEM` entra a
  `KW_PC_LIDER`. Nada de esto se ve hasta el próximo scrape.

## Alcance autorizado

`scraper/src/main/java/ar/scraper/pcs/**` · `aggregator/normalize/**` ·
sus tests · `CLAUDE.md`. **Fuera de alcance**: migraciones, el borde REST, el
frontend de `/pcs`, y la reorganización de navegación (va en
`odd/tasks/nav-guardados-armadores.md`).

## TDD

Modo estricto (fuente: `CLAUDE.md`, "Strict TDD Mode: enabled"). Runner:
`mvn -q clean test` con el split de JDK de `CONTRIBUTING.md`. RED observado
antes de cada implementación.

## Tareas

- [ ] **T1 — `nivel` en `TechSpecs` + eje.** Campo nuevo, `CpuSpecsReader` y
  `GpuSpecsReader` lo leen, `EjesTecnicos.CPU`/`GPU` lo insertan entre `gama` y
  `generacion`, abstención última. Verificación: `mvn -q clean test`, y el
  armado sin presupuesto pasa a `Ryzen 9 9950X3D` + `RTX 5080`.
- [ ] **T2 — generación comparable entre marcas.** Tabla de recencia por marca;
  el eje compara el año, no el número crudo. Verificación: `mvn -q clean test`.
- [ ] **T3 — reparto del presupuesto por cuotas.** `PcBuilder` deja de pasar el
  restante entero a cada slot. Verificación: `mvn -q clean test`, y con
  `pres=2.000.000` la fuente sale certificada y el total queda cerca del
  presupuesto en vez de por debajo de la mitad.
- [ ] **T4 — taxonomía: bracket suelto y `ARMADO ITEM`.** Verificación:
  `mvn -q clean test`.
- [ ] **T5 — docs.** `CLAUDE.md` (bloque del armador de PCs) + este documento con
  la evidencia medida.

## Progreso

Sin empezar.
