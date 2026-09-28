# Armador de PCs

> Fases 1 a 10 del armador `ar.scraper.pcs`: parser, reglas, ranking, gama, preferencias, homelab. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Armador de PCs (`ar.scraper.pcs`) — fases 1 a 10

**Fase 1** es el parser: `TechSpecsParser.parse(nombre, categoria)` →
`TechSpecs(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama,
certificacion, velocidadMhz, tipoAlmacenamiento)`, puro, fill-only y con
abstención (`""`/`0`/`DESCONOCIDA`/`NINGUNA`, `EMPTY`) igual que `VisualAttrs`.
Desde la fase 6 es un registry categoría → `LectorDeSpecs` (`specs/`, uno por
categoría, sobre un `Tokens` que tokeniza una vez), con la firma pública intacta.
Plan y cobertura medida en [`odd/tasks/pc-builder-specs.md`](../odd/tasks/pc-builder-specs.md).

**Fase 2** es `PcBuilder.armar(productos, presupuesto, conGpu, excluir[, gama])`,
servido por `GET /api/pcs/builder` (`AUTHENTICATED`). Molde de
`SupplementCombo`: un pick por slot, best-effort, presupuesto opcional,
`excluir` con fallback por slot. Desde la fase 6 el builder es un orquestador
sobre objetos: cada `SlotDeArmado` lleva su categoría, sus
`ReglaCompatibilidad` y su `CriterioDeSeleccion`; `ContextoDeArmado` acumula lo
ya elegido. `TechSpecs` se calcula **al armar** desde el snapshot — la tabla
`producto_tech_specs` existe (ver abajo) pero el armador no la lee (D3d).
Diseño en [`odd/tasks/pc-builder.md`](../odd/tasks/pc-builder.md) y
[`odd/tasks/pc-builder-gama.md`](../odd/tasks/pc-builder-gama.md); lo que hay que saber:

| | |
|---|---|
| **El presupuesto se reparte por cuotas, no lo agarra el primer slot** (D3, fase 8) | `CuotasDePresupuesto` le da a cada slot `share × presupuesto` más lo que los anteriores dejaron sin gastar. Con GPU: mother 12 · cpu 20 · ram 10 · gabinete 6 · fuente 10 · gpu 30 · almacenamiento 12; se **normalizan sobre los slots presentes**, así que sin GPU ese 30 se reparte solo y el cooler de gama ALTA entra con su 6 sin tabla nueva. Las proporciones son **supuestas, no medidas**, igual que el piso de watts. Antes cada slot veía TODO el restante y, con el precio como mero desempate, se llevaba el mejor que entrara: medido con $2.000.000, la RAM se llevaba $1.102.200 —el 55% de la caja— y al llegar el slot `fuente` no quedaba nada asequible, así que caía al fallback *"gastá lo mínimo"* y elegía la más barata del catálogo, **sin certificar**. El ranking de fuente ya era correcto; nunca llegaba a ejercerse |
| **Presupuesto vacío es el modo "top top"** (D4, fase 8) | Sin presupuesto no hay cuotas ni filtro de precio: gana el mejor de cada slot por eje técnico, cueste lo que cueste. Es un modo, no un caso borde |
| **El fallback "el más barato" se conserva** (D5, fase 8) | Cuando ni con el arrastre entra nada en la cuota, el slot elige el más barato compatible en vez de salir vacío: un armado incompleto es peor que uno con un componente flojo, y `sinCompatible` sigue reservado para los vetos |
| **La mother es el ancla y se elige primero** | Los vetos de socket, DDR y form factor la referencian. Orden: mother → cpu → (cooler, sólo gama alta) → ram → gabinete → fuente → gpu (sólo con `conGpu=true`) → almacenamiento. Es greedy: si ninguna CPU es compatible con la mother elegida, el slot sale en `sinCompatible`, no se prueba otra mother |
| **Un veto sólo dispara cuando los DOS lados parsearon** | socket CPU↔mother · DDR RAM↔mother · gabinete ⊇ mother (`ITX < MATX < ATX < EATX`) · watts fuente ≥ piso · certificación fuente ≥ mínima. Abstención = sin veto, la política de `VisualCoherence`. Con 7% de cobertura en gabinete, lo contrario vaciaría el slot |
| ⚠️ **La gama es la ÚNICA regla donde la abstención VETA** (D2) | `ReglaGama` exige `candidato.gama() == pedida`, así que `DESCONOCIDA` cae. Es al revés a propósito: el usuario pidió un tier, y de un nombre que no se pudo leer no se puede afirmar que esté en ese tier. Sin gama pedida (`null`) la regla no filtra nada. El costo es el 17% de CPUs sin tier legible, y el mensaje del slot lo dice |
| **El ranking es una escalera de tecnología por slot; el precio es sólo desempate** (D12, extendida en fase 7 por D4/D9) | `baseMlScore` salió del armador entero: es un percentil de PRECIO y donde participe vuelve "lo más barato" por la ventana — el mismo defecto que ya se arregló en `OutfitBudgetBuilder`. Desde fase 8: cpu: gama → **nivel** de familia desc → **año** desc · gpu: gama → **año** desc → **nivel** de modelo desc → VRAM (`capacidadGb`) desc · mother: DDR → tier de chipset, rankeado por **distancia a la gama pedida** (D9: ALTA→X/Z, MEDIA→B, BAJA→A/H; sin gama pedida cae al orden absoluto X/Z<B<A/H de fase 6) · ram: DDR → módulos (kit `NxMGB`) desc → MHz → GB · fuente: certificación desc · almacenamiento: NVMe > SSD > HDD · cooler: `TipoCooler` LIQUIDO > AIRE (fase 7, antes sólo precio) · gabinete: sólo precio (más grande ≠ mejor; el ruido que hacía elegir un service se corrigió en el clasificador, no acá — ver Taxonomía y clasificación). Siempre precio asc → url asc al final, abstención última en todo sub-eje nuevo (D13) |
| **La abstención va ÚLTIMA en todo eje de ranking** (D13) | `DESCONOCIDA`/`DESCONOCIDO` se mapean al último escalón a mano, nunca por ordinal; `0` y `""` son el mismo centinela para su eje. Un pendrive (sin tecnología legible) ya no puede ganarle a un NVMe como "el disco de la PC" — se hunde solo, sin veto nuevo. `Certificacion.NINGUNA` sí compara por ordinal: es el escalón real de abajo, no abstención |
| **La DDR de la mother se deriva del socket cuando el nombre no la dice** | `AM5`/`LGA1851` → DDR5, `AM4` → DDR4, `LGA1700` queda abstenida (plataforma mixta). Vive en `ContextoDeArmado`, no en el parser, y el ranking de mother la comparte (D14): "la más barata" clavaba AM4/DDR4 y después `ReglaDdr` vetaba toda la RAM DDR5 |
| **El piso de watts y la certificación mínima salen de la gama pedida** (`EstimadorDeConsumo`) | alta: 750 / 1000 W con GPU, GOLD · media: 550 / 750, BRONZE · económica o sin gama: 450 / 650, NINGUNA. Siguen siendo constantes, ahora por tier; el consumo de la GPU sigue sin parsearse |
| **El slot `cooler` sólo existe en gama alta** (D4) | Se inserta después del cpu, sin reglas y sin eje (sólo precio). La condición "y si el CPU no trae cooler" se cayó en T1: 309/313 CPUs no dicen nada al respecto |
| `sinStock` ≠ `sinCompatible`, y ambos traen **motivo** (D6) | Sin candidatos en la categoría vs. candidatos que todos cayeron por veto. `PcBuild.mensajes` (slot → motivo de la regla que vació el slot) es lo que la UI pinta debajo del placeholder. Ninguno aborta el armado |

**Escala de gama** (`Gama`, `BAJA < MEDIA < ALTA` + `DESCONOCIDA`; en el cable
es `economica|media|alta`, dueño único `GamaWire`, que nunca emite `DESCONOCIDA`):

| Gama | CPU | GPU |
|---|---|---|
| ALTA | i9 · Ryzen 9 · cualquier `X3D` · Ultra 9 · i7 · Ryzen 7 · Ultra 7 | RTX x090/x080/x070 · RX 9070 · RX x900/x800 |
| MEDIA | i5 · Ryzen 5 · Ultra 5 | RTX x060 · RX 9060 · RX x700/x600 |
| BAJA | i3 · Ryzen 3 · Ultra 3 · Athlon · Celeron · Pentium | RTX x050 · GTX · ARC · RX 9050 · RX x500 y abajo |

⚠️ **Radeon numera de DOS maneras y las dos están vivas en el catálogo** (64
filas RX 9000 contra 28 de RX 5000–7000, medido 2026-09-19): en la serie 9000
manda la **decena** como en Nvidia, en las anteriores la **centena**. Una sola
regla numérica se come una de las dos, y con la gama como filtro duro más la
abstención que veta, eso saca a las RX 9070 de **todo** armado sin un solo
error. `GpuSpecsReader` ramifica por serie antes de mirar el tier.

**Persistencia** (`V35` + `V36`, detalle en [`docs/DATABASE.md`](./DATABASE.md)):
`gama` es lookup con FK, no un TEXT con CHECK (D8); `preferencia_armador` es
**una fila por usuario** (D9), servida por `GET`/`PUT /api/pcs/preferencia` —
GET da 204 hasta que el usuario guarda una, y **el armador nunca la aplica
solo**: `/pcs` la precarga en los chips y manda `gama=` explícito.
`producto_tech_specs` guarda el `TechSpecs` entero normalizado con su propio
write path (`TechSpecsIndexer` desde `ScraperService`, no `sp_upsert_run` —
D11); es la base del filtro por specs de `/catalogo`, que **no** es de esta
fase (D3c). **La abstención ahí es NULL, nunca una fila de lookup** (D10):
`DESCONOCIDA`/`NINGUNA` son centinelas del dominio Java y un centinela no es
un valor de FK — exactamente lo que rompió `marca=''` en `V21`.

Desde fase 7, `V36` suma tres lookups más (`marca_chip`, `chipset_tier`,
`tipo_cooler`, mismo molde que los seis de `V35`) y columnas nullable en
`preferencia_armador` (las seis preferencias — `ram_dual`/`wifi` son la
excepción `NOT NULL DEFAULT false`, D2 de `pc-builder-deep-taxonomy`) y en
`producto_tech_specs` (`marca_chip_id`, `chipset_tier_id`, `tipo_cooler_id`,
`generacion`, `modulos`, `wifi`). **`socketsSoportados` (el veto cooler↔mother
de D6, fase 7) NO se persiste**: `producto_tech_specs` sigue guardando un
`socket_id` singular vía FK, y un cooler real puede listar varios sockets —
forzar esa lista en una columna FK escalar la truncaría. Queda diferido, no
descartado; la compatibilidad se sigue calculando al armar, desde el
snapshot en memoria, igual que el resto de `TechSpecs` (D3d).

**Fase 3** es la página `/pcs` (`PcsPanel`, molde de `SuplementosPanel`): sin
picker de tipos porque los slots son fijos del lado del servidor; chips de
gama excluyentes (`Cualquiera` = sin filtro) + presupuesto + checkbox `conGpu`
+ Generar/Regenerar con `excluir` por slot; `sinStock` y `sinCompatible` se
pintan como placeholders distintos con su `mensaje`. La preferencia se
persiste en Generar, no en Regenerar. Plan y evidencia en
[`odd/tasks/pc-builder-ui.md`](../odd/tasks/pc-builder-ui.md).

**Fase 4** persiste el build (`saved_pcs` + `saved_pc_item`, molde
`saved_outfits`, `gama_id` nullable desde `V35`) con Guardar en `/pcs` y
listado en `/armadores` — ver el párrafo de esa ruta más abajo.

**Fase 5** es la tool `propose_pc` del agente (ver LLM Catalog Agent), que
acepta `gama` como enum. Plan y evidencia en
[`odd/tasks/pc-builder-agent-tool.md`](../odd/tasks/pc-builder-agent-tool.md).

**Fase 7** agrega seis preferencias técnicas pedidas como filtro duro y
profundiza los ejes de ranking dentro de cada tier — pedido explícito del
usuario ("muchas veces no me arma bien"). Diseño y medición completos en
[`odd/tasks/pc-builder-deep-taxonomy.md`](../odd/tasks/pc-builder-deep-taxonomy.md).

| | |
|---|---|
| **Las seis preferencias** viven en `PreferenciasDeArmado` (`ddr`, `marcaCpu`, `marcaGpu`, `tipoAlmacenamiento`, `ramDual`, `wifi`), todas nullable = "no pedida" (D1). Cable: `ddr=DDR4\|DDR5` (mother+ram) · `marcaCpu=intel\|amd` (mother+cpu) · `marcaGpu=nvidia\|amd` (gpu) · `tipoAlmacenamiento=nvme\|sata\|hdd` (almacenamiento) · `ramDual=true` (ram) · `wifi=true` (mother). Una regla por preferencia, molde `ReglaGama`: `ReglaDdrPedida`, `ReglaMarcaChip` (una sola clase sirve a mother/cpu/gpu — D3, la marca de la mother sale del socket: `AM*`→AMD, `LGA*`→Intel), `ReglaTipoAlmacenamiento`, `ReglaRamDual`, `ReglaWifi` |
| ⚠️ **La abstención vuelve a vetar cuando HAY preferencia pedida** (D2, misma inversión que `gama`) | Pedir DDR5 y no poder leer la DDR de una mother (ni derivarla del socket) la descarta. **Excepción escrita a propósito**: `ramDual`/`wifi` nunca abstienen — el nombre es la afirmación (`2x` presente / `wifi` presente), y su ausencia es `false`, no "no sé"; sólo `TRUE` pide algo, `FALSE` se comporta como "no pedida" |
| **Compatibilidad nueva, sólo donde los dos lados parsean** (D6) | RAM `SODIMM` (notebook) veta incondicional en el slot ram — `tipoMemoria` nunca abstiene, no hace falta el guard de "los dos lados parsearon" —; cooler↔mother por socket, cuando el cooler lista sockets soportados y la mother parseó el suyo (`socketsSoportados`, sin persistir — ver Persistencia); sockets viejos (`LGA1151`, `LGA1200`, `AM3` + chipsets `H310/B360/Z390/H410/B460/Z490/H510/B560`) suman al vocabulario de `MotherboardSpecsReader`/`CpuSpecsReader` para que `ReglaSocket` los vea en vez de abstenerlos |
| **`TipoCooler`** (`LIQUIDO`/`AIRE`/`DESCONOCIDO`, molde `TipoAlmacenamiento`) | Le da al slot cooler un eje real por primera vez — hasta fase 6 sólo tenía precio, y el más barato de una categoría con ruido de clasificación ganaba siempre |

**Fase 8** arregla el tope del catálogo y el reparto del presupuesto — pedido
del usuario (2026-09-22): "las fuentes que tiene el armador de PC no están
certificadas" y, preguntando por el modo sin presupuesto, "si quiero ir a lo
top top?". Diseño y medición completos en
[`odd/tasks/pc-builder-top-tier.md`](../odd/tasks/pc-builder-top-tier.md).

| | |
|---|---|
| ⚠️ **`generacion` no es una magnitud, son dos** (D2) | En Intel es la generación Core real (`i7 14700F` → 14); en AMD y Nvidia es el **dígito de los miles del modelo** (`Ryzen 9 9950X3D` → 9, `RTX 5080` → 5). Comparadas crudas, `14 > 9 > 5` hacía que **Intel le ganara a AMD en CPU y AMD a Nvidia en GPU, siempre**, por aritmética y no por potencia: sin presupuesto el armado era `i7 14700F` + `RX 9070` teniendo un `Ryzen 9 9950X3D` y una `RTX 5080` en el catálogo. `EjesTecnicos.anioCpu`/`anioGpu` la normalizan a año de lanzamiento, ramificando por marca. La tabla es **por slot, no global**: `AMD`+`9` es Ryzen 9000 (2024) en CPU y RX 9000 (2025) en GPU. Sin marca legible, o fuera de tabla, abstiene y va última — un número sin escala no puede rankear contra uno que sí la tiene. Misma clase de bug que las RX 9000 de la fase 7 |
| **`nivel`: el escalón DENTRO de la gama** (D1) | `Gama.ALTA` mete en la misma bolsa a `i7`, `i9`, `Ryzen 9` y `Ultra 9`, y a `RTX 5090`, `RTX 5080` y `RX 9070`. `nivel` (CPU `9\|7\|5\|3` de la familia · GPU `90\|80\|70\|60\|50` del modelo, ramificando por serie igual que `gama()`) es la única magnitud de potencia **comparable entre marcas**: un `i9` y un `Ryzen 9` son pares. Cobertura medida (dev DB, 7054 filas, 2026-09-22): CPU **374/427 (88%)**, GPU **346/388 (89%)**. Athlon/Celeron/Pentium abstienen: tienen gama BAJA pero no juegan en la escala |
| ⚠️ **El orden de los dos ejes DIFIERE entre CPU y GPU, y es medido** | CPU va `nivel → año`: el dígito de familia es un escalón estable y de vida larga, así que un `i9` de 2023 vale más que un `Ryzen 7` de 2024. GPU va `año → nivel`: el escalón de modelo no sobrevive a cinco años de proceso — una `RX 6900 XT` (x90 de 2020) no es comparable con una `RTX 5080` (x80 de 2025), y con el nivel primero le ganaba. `gama` corre antes que los dos, así que una x50 nueva nunca le gana a una x90 vieja: están en gamas distintas |
| **`nivel` no se persiste** (D7) | `producto_tech_specs` no crece y no hay migración en esta fase. Mismo precedente que `socketsSoportados`: el armador calcula `TechSpecs` al armar desde el snapshot (D3d) |
| **`ReglaCertificacion` NO cambió** (D6) | Sigue dejando pasar `NINGUNA` (política normal de abstención). Con el reparto por cuotas la fuente sin certificar deja de ganar por presupuesto agotado, que era la causa real; invertir la regla vaciaría el slot en vez de arreglarlo |
| **DDR2 entró al vocabulario de `RamSpecsReader`** | Una sola fila activa, pero sin leerla el reader abstiene, `ReglaDdr` no puede vetarla, y una `Kimota DDR2 2GB` de 2007 ganaba el slot `ram` de un armado con mother DDR5 por ser lo más barato del pool |

Medido en el catálogo real (dev DB, 7054 filas de `tecnologia`, 2026-09-22),
corriendo `PcBuilder.armar` de verdad — la fuente sale **certificada en los 8
armados** probados (4 presupuestos × 2 gamas):

| | antes de fase 8 | después |
|---|---|---|
| sin presupuesto, gpu | `RX 9070` $1.324.990 | `RTX 5080` $2.988.500 |
| sin presupuesto, cpu | `i7 14700F` $588.270 | `i9 14900K` $685.072 |
| $2.000.000, fuente | `Jalatec Jt-520` **`NINGUNA`** $25.881 | `Corsair RM750` **`PLATINUM`** |
| $2.000.000, gpu | `"ARMADO ITEM 6302"` $800 | `RX 6900 XT` $739.878 |
| $2.000.000, almacenamiento | `Bracket Disco SSD` $3.300 | `SSD M.2 1TB` $217.339 |

⚠️ **El `Ryzen 9 9950X3D` sigue sin salir en el modo top-top, y no es el
ranking**: la mother se elige primero (`Asrock Z790I`, `LGA1700`) y
`ReglaSocket` veta todo AM5 después — el `i9 14900K` es el tope real de esa
plataforma. Es la limitación greedy que ya describe la fila "la mother es el
ancla": no se prueba otra mother. Probar varias plataformas es otro tamaño de
cambio, y queda pendiente.

**Fase 9** hace pedibles cuatro ejes que el armador decidía solo, y profundiza
dos rankings — pedido del usuario (2026-09-22): *"que en /pcs se pueda elegir
la cantidad de GB... profundidad del gabinete... más profundidad en los cooler,
water, aire... más profundidad en los watts de las fuentes"*. Diseño y medición
completos en [`odd/tasks/pc-builder-fine-grained-prefs.md`](../odd/tasks/pc-builder-fine-grained-prefs.md).

| | |
|---|---|
| **Cuatro preferencias nuevas**, mismo molde que las seis de la fase 7 | `capacidadMinimaGb` (piso de GB del disco) · `tamanioGabinete` (`mini\|mid\|full`) · `tipoCooler` (`liquido\|aire`) · `wattsMinimos` (piso de la fuente). Reglas `ReglaCapacidadMinima`, `ReglaTamanioGabinete`, `ReglaTipoCoolerPedido`; los watts NO son regla nueva (ver abajo) |
| ⚠️ **El tamaño de torre es un eje DISTINTO del form factor, y "mid-ATX" no existe** | `TamanioGabinete` (`MINI`/`MID`/`FULL`/`DESCONOCIDO`) es cuánto ocupa el gabinete; `formFactor` (ITX/MATX/ATX/EATX) es qué placa entra. El catálogo los nombra por separado —`"MID-TOWER EATX"` trae los dos— y el veto Gabinete ⊇ Mother sigue corriendo sobre `formFactor`, sin tocarse |
| ⚠️ **El gabinete es el eje pobre, y el número hay que medirlo sobre las filas ACTIVAS** | Sobre lo que el armador realmente ve —el snapshot, `activo IS NOT FALSE`— son **40 de 575**: MID 39 · MINI 1 · **FULL 0** (dev DB, 2026-09-22). Sobre el total de 622 filas dan 46 (MID 43 · FULL 2 · MINI 1), pero los dos full tower están soft-deleted, así que hoy pedir `full` no deja dos candidatos sino **ninguno**, y el slot sale en `sinCompatible`. Con la abstención vetando (D2, la misma inversión que `gama`), `/pcs` lo avisa en pantalla debajo de los chips: el resultado es contraintuitivo y el número tiene que estar a la vista. **Toda medición que pretenda describir lo que el armador hace tiene que filtrar por `activo`** — contar el catálogo entero describe otra cosa |
| **Los dos pisos son "al menos", no valores exactos** | Pedir 1 TB admite un disco de 2 TB, que es justo el mejor candidato. Un piso de `0` se **rechaza** en el borde: un filtro que no filtra no es un pedido |
| **Pedir un tipo de cooler ABRE el slot**, aunque la gama no sea ALTA | Hasta la fase 8 el cooler era una decisión de tier y sólo existía en gama alta. Pedir refrigeración líquida y recibir un armado sin cooler no responde la pregunta que se hizo. Sin pedido, byte por byte igual que antes |
| **El piso de watts pedido SUBE, nunca baja** | `wattsMin = max(EstimadorDeConsumo.wattsMinimos(gama, conGpu), pedido)`. Pedir 550 W en un armado de gama alta con GPU (piso 1000) no puede dejarlo sin fuente suficiente. Por eso no hay `ReglaCompatibilidad` nueva: `ReglaWatts` ya veta contra `contexto.wattsMin()` y no cambió una línea |
| **Dos ejes de ranking nuevos, y los dos cambian el default** | `COOLER`: tipo → **radiador desc** (entre dos AIO gana la de 360mm; 84 de los 171 líquidos lo declaran). `FUENTE`: certificación → **watts desc** — hasta acá el eje era la certificación sola, así que entre dos GOLD desempataba el precio y ganaba la más chica, apenas por encima del piso. La certificación sigue mandando: una GOLD de 650 W le gana a una sin certificar de 1200 W |
| **`tamanioGabinete` y `radiadorMm` SÍ se persisten; los pisos pedidos no** | `V37` (ver [`docs/DATABASE.md`](./DATABASE.md)): lookup `tamanio_gabinete` + columnas en `producto_tech_specs`. Los pisos son del PEDIDO, no del producto, así que van sólo a `preferencia_armador` |
| ⚠️ **Los rollbacks componen en orden inverso, y `V37` lo hizo visible** | `V37` cuelga un `tipo_cooler_id` de `preferencia_armador` que referencia la tabla que creó `V36`, así que el `DROP TABLE tipo_cooler` del bloque de `V36` falla mientras `V37` siga aplicada. `V36RollbackRoundTripTest` ejecuta primero el bloque de `V37`; cada bloque sigue siendo dueño exactamente de sus propios objetos |

⚠️ **Dos defectos que ningún test podía ver, porque son sobre qué hay en el
catálogo y no sobre qué hace el código con lo que le das.** Los encontró armar
de verdad contra la dev DB (T8 de la fase 9), y los dos son de la misma familia
que el bracket y el service de la fase 8:

- **Un fan de gabinete ganaba el slot de refrigeración por aire.** El guard de
  fan de la fase 7 mira **sólo el primer token**, así que ataja `"Fan Cooler
  120mm..."` y dejaba pasar `"Cooler Fan 120mm..."` — el mismo producto con las
  palabras al revés. Las 6 filas activas que lideran así son fans de 120/140mm,
  ninguna es un cooler de CPU, y la más barata ($7.250) se llevaba el slot.
  `CoolerSpecsReader.esLiderCaseFan` cubre ahora el par adyacente (y pela un
  `outlet` líder antes, igual que `startsWithAny`).
- **Un disco externo USB ganaba el slot del disco.** Con un piso de capacidad
  pedido, `"HD HDD EXTERNO 4TB SEAGATE PORTABLE USB 3.0"` le ganaba a los
  internos. 18 de las 266 filas activas de Almacenamiento son externas.
  `AlmacenamientoSpecsReader` **abstiene la tecnología** de un externo en vez de
  vetarlo: misma política que los pendrives — la abstención es el último escalón
  del eje, el producto se hunde solo y sigue siendo elegible como último
  recurso. `externo`/`externa` sola cubre las 18; `portable`/`portatil` no suma
  ninguna por su cuenta y por eso no entra al vocabulario.

**Fase 10** agrega el perfil **homelab** con hardware de consumo, y una
categoría `Mini PC` — pedido del usuario (2026-09-24). Diseño y medición en
[`odd/tasks/pc-builder-homelab.md`](../odd/tasks/pc-builder-homelab.md).

| | |
|---|---|
| **`Uso` es un eje aparte de `Gama`** | `uso=gaming\|homelab` (dueño `UsoWire`), default gaming. Homelab cambia slots y ejes, no las reglas: `almacenamiento` se parte en `sistema` (el eje de siempre) + `datos` (capacidad desc, tecnología abstenida última), la RAM rankea por capacidad primero, cuotas propias. Persistido normalizado: lookup `uso` + FK en `preferencia_armador` (`V39`) |
| **Modo mini PC** | `uso=homelab` + `tamanioGabinete=mini` arma sólo `minipc` + `datos` |
| ⚠️ **No hay hardware de servidor en el catálogo** | Medido (2026-09-24, 6792 filas): 0 ECC, 0 EPYC, 1 Xeon sin mother, 0 rack. El tope "megaservidor" no se puede armar con estas tiendas |
| **Sin presupuesto, el desempate por precio es DESC** (T18) | Adentro de un mismo escalón técnico gana el más caro — decisión del usuario, sólo sin presupuesto, en todos los slots. Con presupuesto sigue asc |
| ⚠️ **Por eso los combos se filtran** | Con el desempate desc, `"Kit Mother ... + Procesador ..."` y `"Mini PC ... + Monitor"` ganaban por caros (y el CPU se compraba dos veces). `PcBuilder.esCombo` (`combo` o `+ <otro componente>`) los saca del pool, soft: sólo entran si son lo único del slot. `80 + Gold` y `+ Wraith Cooler` no son combos |
| **La mother elige plataforma con CPU** (T13) | Con gama pedida, sólo mothers cuyo socket tiene algún CPU elegible de esa gama. Gama BAJA salía `cpu` en `sinCompatible` en todo presupuesto (A620M AM5 contra i3/Athlon AM4/LGA1700) |
| **Socket derivado de la familia** | Athlon G de escritorio → `AM4`, Xeon E5 v3/v4 → `LGA2011-3`, sólo sin socket explícito. Ganaban por el fallback "el más barato" sobre una AM5 |
| **Cooler de aire con eje** (T16) | `ClaseDisipador` (doble torre > torre > desconocida) → heatpipes. Antes los 85 AIRE empataban y ganaba siempre el `Raptor Cryo` de $18.400. No se persiste (precedente `nivel`) |
| ⚠️ **`1.92TB` se leía 92 TB** | El tokenizer corta en el punto. `AlmacenamientoSpecsReader` lee TB decimales aparte |

Limpieza de taxonomía que salió de armar de verdad: mini PCs fuera de `CPU`
(sustantivo líder `mini pc`/`nuc`/`brix`/`cubi`), `memoria` líder con token DDR
gana antes que la marca de CPU (35 RAM "AMD EXPO / Intel XMP" vivían en `CPU`),
thermal pad → `Cooler`, y RAM/SSD "c/disipador", joystick, auricular y
controladora fuera de `Cooler`.

**El total estimado de `/pcs` va en pesos y en dólares**, con la cotización del
**mismo servicio que el badge del header** (`GET /api/indices` → `usd.ultimoValor`,
el dólar oficial de `indices-service`). Si ese servicio viene `sin_datos`, sin
valor o falla entero, la línea en dólares **no se muestra** — no hay tasa
hardcodeada de reemplazo, que es la misma regla que `InflacionService` rompía
(ver [Índices y señales](./GOTCHAS.md#índices-y-señales)).

Cobertura medida (TSV de hardware, 3360 filas, reclasificadas con los cambios
de T1/T2a/T4d-1, 2026-09-21): CPU marcaChip **388/389**, generación
**329/389** · GPU marcaChip **438/445**, generación **355/445**, VRAM
(`capacidadGb`) **382/445** · Motherboard marcaChip **511/515**, tierChipset
**509/515**, wifi=true **264/515** (el resto es `false` afirmado, D2) · RAM
módulos (kit `NxMGB` explícito) **46/375** · Cooler, tras la reclasificación
de T4d-1 (323 filas): LIQUIDO 164 · AIRE 99 · DESCONOCIDO 60.

Lo que la medición de fase 1 dijo (dev DB, 2157 filas, 2026-09-18) y condicionó la fase 2:

| Campo | Cobertura | Consecuencia |
|---|---|---|
| Motherboard socket / RAM ddr+GB / Fuente watts | 98–100% | Los vetos CPU↔Mother, RAM↔Mother y watts pueden ser duros |
| CPU socket | 81% | Los misses son casi todos **memorias clasificadas como `CPU`** ("AMD EXPO / Intel XMP"), no CPUs sin socket |
| Motherboard ddr | 78% | El nombre dice socket y no DDR; derivarlo del chipset es seguro en AM4/AM5/LGA1851 y **no** en LGA1700, que es mixto |
| Gabinete formFactor | **7%** | El nombre no lo dice. El veto Gabinete ⊇ Mother no puede correr sobre nombres: abstención = sin veto, igual que `VisualCoherence` |

Y lo que dijo la de fase 6 (dev DB, 3435 filas de hardware, 2026-09-19):
CPU con tier legible **260/313 = 83%** · GPU con familia+modelo **429/462 =
93%** · fuente con certificación 80+ **295/347 = 85%** · RAM con velocidad
**377/387 = 97%** (116 traen el número pelado detrás del `DDRn`; se acepta
sólo contra una whitelist de velocidades DDR reales) · almacenamiento con
tecnología **260/290 = 90%** (los 30 restantes son pendrives y micro SD, no
discos). `Cooler` tiene 483 filas y hasta esta fase no era slot.

El parser **tokeniza** (split en todo no-alfanumérico) en vez de padear
substrings: `1851` no puede matchear adentro de `B860M`. Los sufijos de chipset
son letras, no sólo `M`: `X670E`, `B650EM`, `A620AM` existen y un match de 4-o-5
caracteres perdía todas las Extreme.

---
