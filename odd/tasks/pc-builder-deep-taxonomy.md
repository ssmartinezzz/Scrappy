# pc-builder-deep-taxonomy — ejes profundos, filtros pedidos y compatibilidad real

> Fase 7 del armador de PCs. Sigue a `pc-builder-gama` (fase 6). Pedido del
> usuario (2026-09-21): "muchas veces no me arma bien" — quiere más ejes
> técnicos, elegir marca del chip, DDR4/DDR5, M.2 / SSD SATA / HDD, RAM dual,
> mother con wifi, gabinetes de verdad y compatibilidad entre componentes.

## Objetivo

Que `/pcs` y `propose_pc` acepten un conjunto de preferencias técnicas
(DDR, marca del chip, tipo de disco, RAM dual, wifi) como filtros duros, y que
dentro de cada slot el ranking distinga generación, chipset, modelo y kit en
vez de caer a "lo más barato del tier".

## Problema (medido, dev DB, 3360 filas activas de hardware, 2026-09-21)

`producto_tech_specs` está VACÍA (no hubo scrape desde `V35`), así que todo se
midió corriendo `TechSpecsParser` sobre `productos` (script scratch `Medir.java`).

1. **El slot Gabinete elige un servicio.** `EjesTecnicos.GABINETE` no tiene eje
   → sólo precio → el pick es `service instalación de armado de pc` ($2.050),
   después `bracket disco ssd para gabinete` ($5.700), después `filtro
   antipolvo ... para gabinete` ($13.700). El keyword `"gabinete"` de
   `KW_GABINETE` matchea `para gabinete`. **436 de 618** filas de `Gabinete`
   nombran fan/filtro/bracket/vidrio — la mayoría son gabinetes que listan sus
   fans (contenedor gana, y está bien), pero los que empiezan con
   `bracket`/`filtro`/`service`/`kit` no son gabinetes.
2. **Cada escalera tiene un escalón.** Sin gama el armado es siempre el mismo:
   mother = DDR5 más barata (`OUTLET Biostar B650M`, $72.200), CPU = ALTA más
   barato de ese socket (`Ryzen 7 8700F`, $388.990). `Ryzen 7 5700` = `Ryzen 7
   9700X` = `i7 12700` (todos ALTA); `A620M` = `X870E`; `RTX 3070` = `RTX 5070`.
   El presupuesto arriba del ítem más barato del tier nunca se gasta.
3. **Sockets viejos no existen para el parser.** `LGA1151`/`LGA1200`/`S1200`/
   `AM3` abstienen (4 CPUs, 7 mothers) y abstención = sin veto: un `i5 9400
   LGA1151` pasa `ReglaSocket` contra una mother AM5.
4. **SODIMM entra en una desktop.** 36 RAM `SODIMM` (notebook) compiten por el
   slot `ram` sin veto. `tipoMemoria` ya se parsea.
5. **Cooler sin compatibilidad.** 164/470 coolers nombran socket; ninguna regla
   los mira.
6. **DDR no se puede pedir**; se deriva de la mother que salió elegida.

Cobertura de lo que el usuario quiere filtrar:

| Señal | Filas | Consecuencia |
|---|---|---|
| Mother con `wifi` en el nombre | 264/515 | Filtro viable; ausencia en el nombre = sin wifi (es lo que el vendedor afirma) |
| Mother con chipset legible (A/H · B · X/Z) | ~500/515 | Tier de chipset como eje |
| CPU Intel / AMD | 162 / 167, 5 ni uno | Marca del chip por nombre |
| CPU con generación (`ryzen N Mxxx`, `iN 1Mxxx`, `ultra N 2xx`) | 126 + 71 + 28 | Eje de generación |
| GPU Nvidia / AMD / Intel | 287 / 145 / 6 | Marca del chip por nombre |
| GPU con VRAM `NGB` | 382/445 | Eje de VRAM = `capacidadGb` (campo existente, hoy vacío en GPU) |
| GPU con modelo `RTX/RX Nxxx` | 269 + 87 | Generación (miles) como eje dentro del tier |
| RAM kit `(2xNGB)` | 47/377 | Filtro "dual" viable, pero angosto: un kit sin `2x` en el nombre queda afuera cuando se pide dual |
| Almacenamiento NVMe / SSD SATA / HDD / pendrive | 143 / 80 / 29 / ~46 | `TipoAlmacenamiento` ya es exactamente M.2 / SATA / HDD; sólo falta poder pedirlo |

## Decisiones

- **D1** Las preferencias pedidas viajan en un record `PreferenciasDeArmado`
  (`ddr`, `marcaCpu`, `marcaGpu`, `tipoAlmacenamiento`, `ramDual`, `wifi`),
  todas nullable = "no pedida". `ContextoDeArmado` lo carga; `gama` se queda
  donde está. Una regla por preferencia, molde `ReglaGama`.
- **D2** Abstención veta cuando hay preferencia pedida (misma inversión que
  `ReglaGama`): pedir DDR5 y no poder leer la DDR de una mother (ni derivarla
  del socket) la descarta. Para `wifi` y `ramDual` no hay abstención: el
  nombre es la afirmación (`wifi` presente / `2x` presente), y su ausencia es
  `false`, no "no sé". Queda escrito porque es la excepción a la política.
- **D3** La marca del chip filtra tres slots: CPU y GPU por nombre, mother por
  socket (`AM*` → AMD, `LGA*` → Intel). Pedir Intel con una mother AM5 en el
  pool no tiene sentido y la mother se elige primero.
- **D4** Ejes nuevos, siempre después del eje que ya existe y antes del precio:
  CPU `gama → generación`; GPU `gama → generación → VRAM`; mother `DDR → tier
  de chipset` (X/Z=0 · B=1 · A/H=2); RAM `DDR → módulos (kit) → MHz → GB`.
  Abstención última, como en `EjesTecnicos` (D13 de fase 6).
- **D5** Gabinete se arregla en el CLASIFICADOR, no en el armador: un producto
  cuyo sustantivo líder es `bracket`/`filtro`/`service`/`kit`/`fan`/`soporte`
  y nombra `gabinete` sólo como destino (`para gabinete`) no es `Gabinete`.
  Regla del sustantivo líder, la misma de `Cable`. El catálogo ya scrapeado
  se corrige en el próximo run; no hay parche de snapshot.
- **D6** Compatibilidad nueva sólo donde los dos lados parsean: cooler↔mother
  por socket (el cooler lista sockets soportados), RAM `SODIMM` veto
  incondicional (una desktop nunca lleva SODIMM; `tipoMemoria` se afirma
  siempre), sockets viejos al vocabulario (`LGA1151`, `LGA1200`, `AM3`) para
  que `ReglaSocket` los vea.
- **D7** Persistencia en `V36`: columnas nuevas de preferencia en
  `preferencia_armador` (nullable) y en `producto_tech_specs` (`marca_chip_id`
  lookup, `generacion`, `modulos`, `wifi`, `chipset_tier_id` lookup). VRAM va
  en `capacidad_gb`, que ya existe. Abstención = NULL (D10 fase 6).
- **D8** El cable: `GET /api/pcs/builder?ddr=DDR4|DDR5&marcaCpu=intel|amd&marcaGpu=nvidia|amd&tipoAlmacenamiento=nvme|sata|hdd&ramDual=true&wifi=true`;
  `propose_pc` recibe los mismos como enums; `PUT /api/pcs/preferencia` los
  persiste. Valor inválido → 400, como `gama`.

- **D9** (surgió de la medición de T3) El tier de chipset se rankea **relativo
  a la gama pedida**, no absoluto: ALTA → X/Z primero, MEDIA → B primero,
  BAJA → A/H primero (distancia al tier objetivo, abstención última). Sin
  gama pedida queda tier desc como dejó T3. Sin esto una build económica
  compraba una `Z790I` de $284k de mother antes que nada.

## Fuera de scope

- Consumo real de GPU para el piso de watts (sigue por gama).
- Largo de GPU vs gabinete, altura de cooler: nada lo parsea.
- Filtro por specs en `/catalogo` (D3c fase 6) — `V36` le deja las columnas.
- Recalibrar la banda de precios.

## Modo de trabajo

TDD estricto (`CODE-1`): rojo observado antes de cada implementación.
Runner backend: `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test`
(JRE 21 para correr, ver CONTRIBUTING). Frontend: `cd frontend && npm test`.
Sin comentarios boilerplate. Artefactos técnicos en inglés salvo lo que ya
está en castellano en `pcs/`.

## Tareas

- [x] **T1 — Gabinete es gabinete (D5).** `CategoryClassifier`/`GarmentTaxonomy`:
  sustantivo líder `bracket|filtro|service|kit|fan|soporte|cooler` + `para
  gabinete` ⇒ no es `Gabinete`. Test con los tres nombres reales del pick de
  hoy. Medir después: cuántas de las 618 filas cambian y a qué categoría van.
- [x] **T2 — Vocabulario y compatibilidad (D6).** Primero el clasificador:
  **146 de 470 filas de `Cooler` son CPUs** (`"Procesador AMD Ryzen 9 9950X3D
  ... (no incluye cooler)"`, 85 de gama alta) porque `Cooler` corre antes que
  `CPU` y `cooler` aparece como accesorio; líder `procesador`/`microprocesador`
  /`micro amd|intel` ⇒ CPU antes de la línea de Cooler. Después: sockets viejos en
  `MotherboardSpecsReader`/`CpuSpecsReader` (+ chipsets `H310/B360/Z390/
  H410/B460/Z490/H510/B560` → socket). `ReglaSodimm` en el slot ram.
  `CoolerSpecsReader` lee la lista de sockets; `ReglaSocketCooler` veta cuando
  cooler y mother parsearon y no se cruzan. Mensajes D6 para cada regla nueva.
- [x] **T3 — Ejes profundos (D4).** `TechSpecs` gana `marcaChip`, `generacion`,
  `tierChipset`, `modulos`, `wifi` (T2 ya le agregó `socketsSoportados`) (con constructores de compatibilidad, CODE-2).
  Readers: CPU (marca, generación), GPU (marca, generación, VRAM en
  `capacidadGb`), mother (tier chipset, wifi, marca por socket), RAM (módulos).
  `EjesTecnicos` extendido según D4. Medir cobertura de cada eje nuevo.
- [x] **T4 — Preferencias pedidas (D1–D3).** `PreferenciasDeArmado` +
  `ContextoDeArmado` + seis reglas (`ReglaDdrPedida` en mother y ram,
  `ReglaMarcaChip` en mother/cpu/gpu, `ReglaTipoAlmacenamiento`,
  `ReglaRamDual`, `ReglaWifi`). `PcBuilder.armar` con overload nuevo; los
  overloads existentes intactos. Tier de chipset relativo a la gama (D9).
- [x] **T4d — Dos hallazgos de ruido de catálogo de las builds de T4.**
  T4d-1: `KW_PC_LIDER`/`KW_CPU_LIDER` corren primero de todo el bloque tech
  (antes ganaba `KW_GPU`, y una PC armada entraba al slot gpu). T4d-2:
  `EjesTecnicos.COOLER` gana un eje LIQUIDO/AIRE vía `TipoCooler` +
  `CoolerSpecsReader` (antes el slot cooler no tenía eje y elegía lo más
  barato de una categoría con ruido).
- [x] **T5 — Borde y persistencia (D7, D8).** `V36`, `PreferenciaArmador` +
  repository, `GET /api/pcs/builder` params, `PcBuildJson` con los campos
  nuevos, `openapi.yaml`, `propose_pc`, `OpenApiRouteCoverageTest` verde.
  `docs/DATABASE.md` con `V36` y su rollback.
- [x] **T6 — UI `/pcs`.** Chips por preferencia (DDR · Marca CPU · Marca GPU ·
  Disco · RAM dual · Wifi), precarga desde la preferencia, `excluir` intacto.
  Tests vitest.
- [x] **T7 — Docs.** `CLAUDE.md` (fase 7 en la sección del armador, tabla de
  sitios intacta), `docs/ARCHITECTURE.md` (el porqué de D2/D5), DOC-1.

## Verificación por tarea

`mvn clean test` sin `ERROR]`/`BUILD FAILURE`; `BackendLayeringArchTest`
verde; para T1–T3 además re-correr `Medir.java` sobre el TSV y anotar los
números acá. T5: boot real del jar (Flyway `V36` aplicada, `GET /api/pcs/builder`
con cada parámetro). T6: `npm test` + captura en `/pcs`.

## Progreso

**T1 — hecho** (`5e82117` + `c46e484` + `765eecb`). Suite 2443/0/0, ArchTest verde.
Medido sobre el TSV: de 618 `Gabinete`, 609 quedan y 9 cambian (1 service →
Otros, 2 accesorios Xigmatek → Otros, 1 → Fuente, 5 PCs armadas → PC). El
líder ` pc ` además mueve **67 filas de `CPU` a `PC`** — PCs armadas enteras
(`"PC AMD Ryzen 3 3200G 16GB 1TB SSD WIFI"`) que competían por el slot cpu;
eso explica el `Mini PC MSI Cubi` que salía como el i7 más barato. Hallazgo
colateral: `"mate"` sin padear en `KW_COMIDA` vivía adentro de *Xigmatek* y
*Ultimate*, y como palabra es un acabado (matte) en 5 de 7 nombres — se sacó,
`"yerba"` sigue cubriendo la yerba. El catálogo vivo se corrige en el próximo
scrape (D5).

**T2 — hecho** (`be35ee7` clasificador + `4ab8246` sockets viejos + `e4f4768`
SODIMM + `8b98a3b` cooler↔mother), cuatro commits, cada uno RED→GREEN propio.
Suite completa 2481/0/0 (7 skips preexistentes de infra), `ERROR]`=0, BUILD
SUCCESS, `BackendLayeringArchTest` 20/20.

- **T2a** (clasificador): `KW_CPU_LIDER` (procesador/microprocesador/micro
  amd/micro intel) corre antes que `KW_COOLER`. `startsWithAny` ahora pela un
  "outlet" líder antes de comparar contra cualquier `*_LIDER` (generalizado a
  los cuatro que ya existían: Gabinete-accesorio, Service, Fuente, PC) — hacía
  falta para `"Outlet Procesador Intel Core i5 13600KF..."`. Medido aislado:
  **146 → CPU exactas, cero cambios más** en las 3360 filas.
- **T2b** (sockets viejos): `LGA1151`/`LGA1200`/`AM3` en `MotherboardSpecsReader`
  y `CpuSpecsReader` (tokens + chipsets + derivación por modelo Core 8-9/10-11
  gen). `ContextoDeArmado.derivarMotherDdr` suma `LGA1200 → DDR4` (no
  ambiguo); `LGA1151`/`AM3` quedan abstenidos a propósito — las dos mezclan
  placas de más de una generación de DDR. Medido: cobertura de socket
  motherboard 504→511 (+7), CPU 253→261 (+8).
- **T2c** (SODIMM): `ReglaSodimm` veta incondicional (sin guard de "los dos
  lados parsearon" — `tipoMemoria` nunca abstiene), en el slot ram después de
  `ReglaDdr`.
- **T2d** (cooler↔mother): `TechSpecs.socketsSoportados` (campo 11, con
  constructor de compatibilidad — CODE-2). `CoolerSpecsReader` lo llena desde
  el nombre; `"115x"` mapea sólo a `LGA1151`, nunca a `LGA1200`. Un
  "intel"/"amd" pelado no cuenta como señal (D6). `ReglaSocketCooler` veta
  cuando el cooler nombra sockets, la mother parseó el suyo, y no cruzan.
  `TechSpecsIndexer`/`producto_tech_specs` quedan sin tocar a propósito (esa
  tabla persiste `socket_id` singular, no una lista — D7/T5). `PcBuildJson`
  también queda sin tocar: ningún test afirma la forma exacta del JSON de
  specs. Medido: **114/470** coolers con `socketsSoportados` no vacío —
  coincide exacto con la cifra ya anotada en el problema medido de T2.

Desviación menor de lo escrito en T2: el enunciado no pide sufijo `s1700`
para `CoolerSpecsReader` (sí lo pide para mother/CPU en T2b), pero el TSV
tiene nombres reales con `"s1700"` (`"Water Cooler Thermaltake TH120 V2 ARGB
Sync AIO S1700 y AM5"`) que quedan sin ese socket individual — el cooler
igual entra al pool porque lista AM5 además, así que no se perdió ningún
caso de los que armé tests para, pero la cobertura de socketsSoportados
sería mayor si se agregara. No lo agregué por no estar en el pedido
explícito; queda como nota para T3+ si hace falta.

**T3 — hecho** (`261a1f5` T3a + `043a6f0` T3b + `3ff8824` T3c), tres
commits, cada uno RED→GREEN propio. Suite completa 2531/0/0 (7 skips
preexistentes de infra), `ERROR]`=0, BUILD SUCCESS, `BackendLayeringArchTest`
20/20.

- **T3a** (`TechSpecs`): cinco campos nuevos al final del record —
  `marcaChip`/`generacion`/`tierChipset`/`modulos`/`wifi`. El canonical
  pre-T3a (11 args) pasa a ser constructor de compatibilidad (CODE-2); `EMPTY`
  actualizado. `wifi=false` es la única excepción D2 (afirmación, no
  abstención) y quedó escrita en el javadoc del campo. RED de compilación
  (los accessors no existían) → GREEN.
- **T3b** (readers): `CpuSpecsReader`/`GpuSpecsReader` leen `marcaChip` +
  `generacion` del nombre; GPU además llena VRAM en el `capacidadGb` ya
  existente. `MotherboardSpecsReader` deriva `marcaChip` del socket,
  `tierChipset` del `chipsetToken` que ya encontraba, y `wifi` como
  afirmación. `RamSpecsReader` lee `modulos` SÓLO del multiplicador explícito
  `NxMGB` — un `NGB` suelto sin multiplicador se abstiene, nunca asume 1.
  `MotherboardSpecsReaderTest` es archivo nuevo (Motherboard no tenía test
  directo, sólo cobertura vía `TechSpecsParserTest`). Dos tests de fase 1
  cuyo nombre/aserción decía "sólo abstiene" quedaron desactualizados por
  diseño y se renombraron con las aserciones nuevas
  (`gpuSoloLlenaGama`→`gpuLlenaGamaMarcaChipGeneracionYVram`,
  `cpuSoloLlenaSocketYGama`→`...YAbstieneElResto`). Bug propio encontrado
  durante el desarrollo: el primer intento de `MotherboardSpecsReader.leer`
  pasaba `tierChipset(chipsetToken)` en la posición del campo `generacion`
  y un `0` literal en la posición de `tierChipset` — típeaba pero el orden
  de argumentos no coincidía con el orden del record. Lo agarró el propio
  test (`tierChipsetUnoParaXZ` daba 0 en vez de 1) antes de commitear.
- **T3c** (`EjesTecnicos` + `PcBuildJson`): CPU `gama → generación desc`; GPU
  `gama → generación desc → VRAM desc`; MOTHER `ddr → tierChipset rank (1=X/Z
  < 2=B < 3=A/H, 0 última, mapeo explícito)`; RAM `ddr → módulos desc → MHz
  desc → GB desc` — el kit va ANTES que la velocidad, tal cual D4. Abstención
  (0) sigue última en cada sub-eje nuevo. `PcBuildJson`/`PcBuildJsonTest`
  ganan los cinco campos en el bloque `specs`; verificado que `propose_pc`
  (`ProposePcTool`) sigue reusando `PcBuildJson` sin duplicar serialización
  (DOC-1 para código). RED confirmado con `git stash` de `EjesTecnicos.java`
  (10 fallos en `EjesTecnicosTest` sobre las aserciones D4 nuevas) → stash
  pop → GREEN. `docs/openapi.yaml` no lista campos de `specs` (sólo
  path+método+`x-access`, como documenta `OpenApiRouteCoverageTest`), así que
  no hizo falta tocarlo para T3.

**Cobertura medida** (TSV de hardware, 3360 filas, reclasificadas con
`CategoryClassifier` antes de parsear — igual que T1/T2):

| Slot | Eje | Cobertura |
|---|---|---|
| CPU (389 filas) | marcaChip | 388/389 |
| CPU | generacion | 329/389 |
| GPU (445 filas) | marcaChip | 438/445 |
| GPU | generacion | 355/445 |
| GPU | VRAM (capacidadGb) | 382/445 |
| Motherboard (515 filas) | marcaChip | 511/515 |
| Motherboard | tierChipset | 509/515 |
| Motherboard | wifi=true | 264/515 (el resto es `false` afirmado, D2) |
| RAM (375 filas) | modulos | 46/375 (sólo el kit `NxMGB` explícito) |

**Top-3 por slot sin gama y sin presupuesto** (mother → cpu del socket
elegido → gpu → ram de la ddr derivada), mismo TSV:

- **Mother**: `Asrock Z790I Lightning WIFI ITX DDR5 S1700` ($284.037,
  tierChipset=1/X-Z) > `Gigabyte Z890 UD S/HDMI LGA1851 DDR5` ($320.999,
  tier=1) > `ASRock X870 PRO-A WIFI AM5 DDR5` ($324.999, tier=1). El eje de
  tier ahora le gana al precio: antes de T3 el pick era la DDR5 más barata
  del catálogo (`OUTLET Biostar B650M`, $72.200, sin ningún eje de chipset).
- **CPU** (socket LGA1700, 93 candidatos): `Intel Core i7 14700F` ($588.270,
  gen=14) empatado en gama/generación con dos `i7 14700KF` — antes de T3
  cualquier i7/Ryzen 7/9 de ese socket rankeaba igual y ganaba el más barato.
- **GPU**: tres `Radeon RX 9070 16GB` (gen=9, ALTA) — antes de T3 una RTX 3070
  y una RTX 5070 rankeaban idénticas (ambas ALTA, sin generación ni VRAM).
- **RAM** (ddr=DDR5): tres kits `2x16GB`/`2x32GB` de 6400-7600MHz — antes de
  T3 un kit de 32GB perdía contra un stick único más rápido del mismo DDR.

**T4 — hecho** (`4c5ed4f` T4a + `2c48967` T4b + `c248437` T4c), tres
commits, cada uno RED→GREEN propio. Suite completa 2592/0/0 (7 skips
preexistentes de infra), `ERROR]`=0, BUILD SUCCESS, `BackendLayeringArchTest`
20/20.

- **T4a** (`PreferenciasDeArmado` + `ContextoDeArmado`): record de seis
  campos, todos nullable = "no pedida" (D1). Validación en el constructor
  compacto: `ddr` sólo `DDR4`/`DDR5`, `marcaCpu` sólo `INTEL`/`AMD`,
  `marcaGpu` sólo `NVIDIA`/`AMD`, `tipoAlmacenamiento` nunca `DESCONOCIDO`
  (es el centinela de abstención, no un valor pedible). `ramDual`/`wifi` son
  `Boolean`, no `boolean`: sólo `TRUE` pide algo, `FALSE` se comporta como
  `null` — la excepción documentada de D2. `ContextoDeArmado.inicial` gana
  un cuarto overload que las carga; `derivarMotherDdr` pasó de
  package-private a `public` porque T4b la necesita desde
  `ar.scraper.pcs.reglas`.
- **T4b** (seis reglas): molde `ReglaGama` — `null` permite, un valor
  pedido veta en desacuerdo Y en abstención (D2), salvo `ramDual`/`wifi`
  donde sólo `TRUE` filtra. `ReglaMarcaChip` es UNA clase que sirve a los
  tres slots (mother/cpu/gpu), construida con la preferencia que cada slot
  lee — D3: mother y cpu leen `marcaCpu`, gpu lee `marcaGpu`. Las reglas
  ahora hornean el valor pedido en su constructor para que `motivo()` lo
  pueda nombrar (`"no es DDR5, la DDR pedida"`), lo que obligó a que
  `PcBuilder.SLOTS_FIJOS`/`SLOT_GPU` dejaran de ser `static final` y pasaran
  a `slotsFijos(prefs)`/`slotGpu(prefs)`, construidos de nuevo en cada
  llamada a `armar`. Overload de 6 args; los de 4 y 5 delegan con `NINGUNA`
  y quedan byte-for-byte (`PcBuilderPreferenciasTest.ningunaEsIdenticaAlOverloadDe5Args`).

  Desviación de proceso (no de diseño): al escribir T4c en paralelo mientras
  corría la verificación de suite completa de T4b en background, dos edits
  de `PcBuilder.java` (las llamadas a `elegir(..., contexto)` de 2 args y el
  wiring por `Function` del slot mother) se colaron en el `git add` del
  commit T4b antes de que `CriterioDeSeleccion`/`CriterioPorEjesTecnicos`
  tuvieran esa forma — el commit T4b (`2c48967`) por sí solo no compila
  aislado. Se dejó así (no se hizo `amend`, por la regla de nunca amendear
  salvo pedido explícito) y el commit T4c siguiente restaura la
  compilación completa del árbol; el RED de T4c se confirmó igual,
  stasheando sólo `EjesTecnicos`/`CriterioDeSeleccion`/`CriterioPorEjesTecnicos`
  (dejando el `PcBuilder.java` ya-commiteado) y viendo el fallo de compilación
  real (`cannot find symbol: method mother(Gama)`, mismatch de interfaz en
  `PcBuilder.java:213`).
- **T4c** (D9): `EjesTecnicos.mother(Gama gamaPedida)` rankea el tier de
  chipset por distancia a un target (ALTA→1/X-Z, MEDIA→2/B, BAJA→3/A-H);
  sin gama pedida o con `DESCONOCIDA`, sin target, cae al orden absoluto de
  T3. `EjesTecnicos.MOTHER` se preserva como `mother(null)` — ningún test
  ni caller previo cambia. `CriterioDeSeleccion.elegir` gana un parámetro
  `ContextoDeArmado`; sólo el criterio de mother lo usa de verdad (ctor por
  `Function<ContextoDeArmado, Comparator<TechSpecs>>` en
  `CriterioPorEjesTecnicos`), el resto de los slots siguen con su
  `Comparator<TechSpecs>` fijo e ignoran el contexto. Confirmado que el
  ripple de la firma no sale de `ar.scraper.pcs` (grep de `CriterioDeSeleccion`
  y `elegir(` fuera de `pcs/`: cero resultados) — no hizo falta el STOP.

**Las cuatro builds de aceptación** (TSV de hardware, 3360 filas, mismo
catálogo reclasificado que T1-T3, sin presupuesto — `MedirT4.java`, scratch):

```
========== (1) NINGUNA ==========
  mother           $   284.037 | Mother Asrock Z790I Lightning WIFI ITX DDR5 S1700
  cpu              $   588.270 | Procesador Intel Core i7 14700F 5.4GHz Turbo Socket 1700 Raptor Lake
  ram              $ 1.102.200 | Memoria Team DDR5 32GB (2x16GB) 7600MHz T-Force Delta RGB Black CL36
  gabinete         $    22.500 | GABINETE NOVA CM-04Q1 MICRO ATX P/MOTHER A520M B550M H510M A620M
  fuente           $   949.990 | Fuente MSI 1600W 80 Plus Titanium Modular MEG AI1600T ATX 3.1 PCIe 5.1
  almacenamiento   $   918.990 | Disco sólido SSD Kingston NV3 4TB M.2 NVMe PCIe 4.0 6000MB/s

========== (2) MEDIA, DDR5, AMD, ramDual, wifi, NVME, conGpu NVIDIA ==========
  mother           $    83.300 | OUTLET - Motherboard Asrock B850M Pro A Wifi DDR5 AM5
  cpu              $   382.999 | Procesador AMD Ryzen 5 9600 6/12 5.2GHz AM5
  ram              $ 1.102.200 | Memoria Team DDR5 32GB (2x16GB) 7600MHz T-Force Delta RGB Black CL36
  gabinete         $    22.500 | GABINETE NOVA CM-04Q1 MICRO ATX P/MOTHER A520M B550M H510M A620M
  fuente           $   949.990 | Fuente MSI 1600W 80 Plus Titanium Modular MEG AI1600T ATX 3.1 PCIe 5.1
  gpu              $ 2.680.028 | PC Powered by MSI Ultimate AMD Ryzen 7 5700X B550 32GB RAM 1TB RTX 5060 750W Gold Cpu Cooler WIFI
  almacenamiento   $   918.990 | Disco sólido SSD Kingston NV3 4TB M.2 NVMe PCIe 4.0 6000MB/s

========== (3) BAJA, DDR4, INTEL ==========
  mother           $    79.999 | Outlet Motherboard ASRock H510 PRO BTC+ Mining S1200 DDR4
  cpu              $   157.089 | Micro Intel I3-10100F 4.3Ghz 6Mb S.1200
  ram              $   199.030 | Memoria RAM Patriot Viper Steel DDR4 32GB (2x16GB) 3600MHz CL18
  gabinete         $    27.800 | OUTLET - Gabinete Gamer Zer01 Gaming Gemini 1 Fan Fixed Rgb
  fuente           $   949.990 | Fuente MSI 1600W 80 Plus Titanium Modular MEG AI1600T ATX 3.1 PCIe 5.1
  almacenamiento   $   918.990 | Disco sólido SSD Kingston NV3 4TB M.2 NVMe PCIe 4.0 6000MB/s

========== (4) ALTA, DDR5, INTEL, AMD gpu, conGpu ==========
  mother           $   284.037 | Mother Asrock Z790I Lightning WIFI ITX DDR5 S1700
  cpu              $   588.270 | Procesador Intel Core i7 14700F 5.4GHz Turbo Socket 1700 Raptor Lake
  cooler           $     1.800 | Paño de limpieza Arctic para Pasta térmica - Cleaner activo - por unidad
  ram              $ 1.102.200 | Memoria Team DDR5 32GB (2x16GB) 7600MHz T-Force Delta RGB Black CL36
  gabinete         $    22.500 | GABINETE NOVA CM-04Q1 MICRO ATX P/MOTHER A520M B550M H510M A620M
  fuente           $   949.990 | Fuente MSI 1600W 80 Plus Titanium Modular MEG AI1600T ATX 3.1 PCIe 5.1
  gpu              $ 1.324.990 | Placa de Video ASRock AMD Radeon RX 9070 16GB Challenger
  almacenamiento   $   918.990 | Disco sólido SSD Kingston NV3 4TB M.2 NVMe PCIe 4.0 6000MB/s
```

Ninguna build tuvo `sinStock`/`sinCompatible`/`mensajes` — el catálogo
medido siempre tuvo al menos un candidato compatible por slot pedido. Lo
que las cuatro confirman:

- **(1) vs (4)**: la mother `Z790I` (tier=1, X/Z) gana en las dos —
  coincide con D9: sin gama pedida (T3 absoluto) Y con gama ALTA pedida
  (target=1) el mismo tier de X/Z queda arriba; son el mismo pick por
  razones distintas, no una casualidad.
- **(2)**: con gama MEDIA pedida, la mother pasa de la `Z790I` ($284k,
  tier=1) a una `B850M` OUTLET ($83k, tier=2/B) — D9 funcionando en el
  catálogo real, no sólo en el test: el tier B le gana al X/Z porque está
  más cerca del target MEDIA=2.
- **(3)**: DDR4 + INTEL pedidos filtran correctamente a una mother/CPU de
  socket viejo (`S1200`/`H510`) — ninguna DDR5 ni AMD se cuela.
- **Hallazgo del catálogo, no de T4**: el pick de GPU en (2) es
  `"PC Powered by MSI Ultimate ... RTX 5060 ..."` — una PC ARMADA ENTERA
  categorizada como `GPU`, no una placa de video suelta (el guard de T1
  contra "PC armada" no cubrió este nombre). Y el pick de cooler en (4) es
  un paño de limpieza para pasta térmica, no un cooler — `EjesTecnicos.COOLER`
  no tiene eje (sólo precio, T3b-2 "trabajo pendiente"), así que el ítem
  más barato de una categoría con ruido de clasificación gana. Ninguno de
  los dos es un bug de T4 — las reglas de T4 (marca/ddr/tipo) hicieron
  exactamente lo que tenían que hacer sobre esos candidatos — pero quedan
  anotados acá porque son ruido de clasificación real que un usuario vería
  en `/pcs`. No se tocó nada para T4: está fuera del scope de esta tarea
  (T5+/otro ODD) y el catálogo scrapeado se corrige en el próximo run como
  documenta D5 de T1.

**T4d — hecho** (`c833b6f` T4d-1 + `9d9f6d9` T4d-2), dos commits, cada uno
RED→GREEN propio. Suite completa 2604/0/0 (7 skips preexistentes de infra),
`ERROR]`=0, BUILD SUCCESS, `BackendLayeringArchTest` 20/20.

- **T4d-1** (clasificador): `KW_PC_LIDER`/`KW_CPU_LIDER` se movieron al tope
  de `clasificarTech`, justo después del guard de abstención de gabinete/
  service y antes de `KW_RED` — antes corrían después de `KW_GPU` y seis
  checks más, así que "PC Powered by MSI Ultimate AMD Ryzen 7 5700X B550
  32GB RAM 1TB RTX 5060 750W Gold Cpu Cooler WIFI" clasificaba `GPU` y
  entraba al slot gpu de `PcBuilder` como placa de video suelta. Medido
  sobre las 3360 filas de hardware (antes/después): GPU 445→389 (-56), CPU
  389→429 (+40), PC 72→88 (+16) — las 56 filas que cambian son o PCs
  armadas enteras (`"PC ..."` → PC) o procesadores con cooler RGB/iGPU en
  el nombre (`"Procesador/Micro ..."` con Radeon/RTX/RX → CPU). Ninguna
  otra categoría se tocó.
- **T4d-2** (`EjesTecnicos.COOLER`): ganó un eje real. `TipoCooler`
  (LIQUIDO/AIRE/DESCONOCIDO, molde `TipoAlmacenamiento`) + campo
  `tipoCooler` en `TechSpecs` (el canonical de 16 args pasa a ser el
  constructor de compatibilidad, CODE-2). `CoolerSpecsReader` lo deriva del
  nombre: pasta/limpieza/pad (`pasta`/`grasa`/`pano`/`pad`/`thermal`) veta
  primero; un líder `fan`/`ventilador`/`kit` es un fan de gabinete, no un
  cooler de CPU; agua/AIO explícita o un radiador (`240/280/360/420mm`)
  junto a `cooler` es LIQUIDO; el resto de `cooler`/`disipador` es AIRE.
  `EjesTecnicos.COOLER` rankea LIQUIDO > AIRE, DESCONOCIDO siempre última,
  mismo mapeo explícito que el resto de los ejes. `PcBuildJson` gana
  `tipoCooler` en `specs`. `TechSpecsIndexer`/`producto_tech_specs` quedan
  sin tocar a propósito — la persistencia de este eje se difiere junto con
  `socketsSoportados` (T5+). Un test de fase 1
  (`TechSpecsParserTest.coolerAbstainsEntirelyInPhase1`) afirmaba
  `TechSpecs.EMPTY` para un cooler que ahora resuelve `AIRE`
  correctamente; se renombró a `coolerSoloLlenaTipoCoolerElRestoAbstiene`
  y se reescribió para afirmar que el resto de los campos sigue abstenido,
  mismo patrón que `almacenamientoSoloLlenaTipoYCapacidad` de T3b.

**Cobertura medida** (los 323 `Cooler` que quedan tras la reclasificación de
T4d-1, mismo TSV de 3360 filas): LIQUIDO 164, AIRE 99, DESCONOCIDO 60.

**Las dos builds de T4 re-medidas sobre el mismo catálogo, mismas
preferencias** (`MedirT4.java`, scratch):

- **(2)** (MEDIA, DDR5, AMD, ramDual, wifi, NVME, conGpu NVIDIA): el pick de
  gpu pasó de `"PC Powered by MSI Ultimate ... RTX 5060 ..."` (una PC
  armada entera) a `"Placa de Video MSI Nvidia Geforce RTX 5060 Ti 16gb
  Ventus 2x OC Plus GDDR7"` ($1.409.990) — una placa de video suelta de
  verdad.
- **(4)** (ALTA, DDR5, INTEL, AMD gpu, conGpu): el pick de cooler pasó de
  `"Paño de limpieza Arctic para Pasta térmica"` ($1.800) a `"OUTLET CPU
  Water Cooler Lovingcool 240mm HK-B240-02 - Argb - Black"` ($66.700) — un
  AIO real, coherente con la gama ALTA pedida.

**T5 — hecho** (`9af36e5` T5a + `3e2b907` T5b + `3c1d98d` T5c + `42e5fdf`
T5d), cuatro commits, cada uno RED→GREEN propio. Suite completa 2657/0/0
(7 skips preexistentes de `ml.PythonRunnerSequencingTest`/
`PythonRunnerEsperarConDrainTest`, sin relación con esto), `ERROR]`=0, BUILD
SUCCESS, `BackendLayeringArchTest` 20/20, `OpenApiRouteCoverageTest` verde.

- **T5a** (`V36__preferencias_de_armado.sql`): tres lookups sembrados más
  (`marca_chip`, `chipset_tier`, `tipo_cooler`, molde de los seis de `V35`)
  + columnas nullable en `preferencia_armador` (`ddr_id`, `marca_cpu_id`,
  `marca_gpu_id`, `tipo_almacenamiento_id`, más `ram_dual`/`wifi` NOT NULL
  DEFAULT false — D2) y en `producto_tech_specs` (`marca_chip_id`,
  `chipset_tier_id`, `tipo_cooler_id`, `generacion`, `modulos`, `wifi`
  nullable ahí — NULL es abstención O "no es Motherboard", las dos cosas se
  escriben igual). RED confirmado sacando el `.sql` del directorio de
  migraciones y viendo `42P01`/`42703` (relación/columna inexistente) antes
  de restaurarlo. `docs/DATABASE.md` documenta `V36` con su rollback
  (`PreferenciasDeArmadoSchemaTest` + `V36RollbackRoundTripTest`, nuevos).
  `PostgresTestBase.truncateAll` NO gana los tres lookups nuevos — mismo
  criterio que los seis de `V35`, son vocabulario sembrado, no residuo.

  Hallazgo de proceso (no de diseño): un test que hace `INSERT INTO
  productos` crudo (sin pasar por `sp_upsert_run`) necesita una fila
  preexistente en `sitio` — `TechSpecsRepositoryTest`/`V35RollbackRoundTripTest`
  (ya committeados, de T4/T5 de `pc-builder-gama`) dependían en silencio de
  que OTRA clase de test (`UnownedRowTest`/`DeleteProductosGlobalGuardTest`)
  sembrara esa fila antes en la misma corrida de Testcontainers compartida
  — `sitio` nunca se trunca entre tests. Corriendo esas clases aisladas
  (`-Dtest=UnaClase`) eso rompe con `fk_productos_sitio` violada, algo que
  la corrida completa nunca muestra. No es un bug de este cambio: los tests
  nuevos de T5a/T5b se escribieron auto-sembrando esa fila (molde
  `UnownedRowTest`) para no depender del orden de la suite, y se aplicó el
  mismo arreglo a `TechSpecsRepositoryTest.insertarProducto` (pre-existente,
  tocado igual para agregar los casos nuevos de T5b).

- **T5b** (`PreferenciaArmador` + repos + indexer): `PreferenciaArmador`
  gana el campo `preferencias` (constructor de compatibilidad de 3 args,
  CODE-2). `PreferenciaArmadorRepository.guardar/cargar` hacen round-trip de
  los seis campos nuevos, resolviendo cada lookup por nombre en el mismo
  statement (molde `gama`); `ramDual`/`wifi` en `FALSE` cargan como `null`
  al leer — la columna NOT NULL DEFAULT false no puede distinguir "pedido
  en falso" de "no pedido" y D2 dice que son el mismo estado, así que la
  normalización pasa en la LECTURA, no en la escritura (`PUT` sigue
  persistiendo el `false` literal que mandó el cliente).

  `TechSpecsPort.SpecsDeProducto` gana `categoria` (constructor de
  compatibilidad de 2 args) — hacía falta porque `producto_tech_specs.wifi`
  sólo es una afirmación real en una fila Motherboard; en cualquier otra
  categoría el `wifi=false` que trae el reader es un default, no una
  aserción, y `TechSpecsRepository` necesita la categoría para no
  escribirlo como si lo fuera. `marcaChip`/`generacion`/`modulos` se
  escriben directo desde los centinelas de abstención de `TechSpecs`
  (`""`/`0`); `tierChipset` (`int` 1/2/3/0) mapea a `chipset_tier` con un
  `switch` privado en el repository — no hizo falta una clase compartida
  tipo `GamaMapeo` porque `tierChipset` es un concepto sólo de
  `producto_tech_specs`, `PreferenciaArmador` no lo pide nunca.

- **T5c** (el cable): `pcs/PreferenciasWire` (molde `GamaWire`) — parse+wire
  para los seis, case-insensitive, blank/null = no pedido,
  `IllegalArgumentException` nombrando el campo en un valor inválido. El
  dato no obvio: la palabra de borde de `TipoAlmacenamiento.SSD` es
  `"sata"`, no `"ssd"` — el vocabulario de borde nombra la interfaz que el
  usuario reconoce, no el nombre Java del enum. `GET /api/pcs/builder` gana
  los seis query params y los reenvía al overload de 6 args de
  `PcBuilder.armar` que T4b ya dejó listo; inválido → 400 con `mensaje`
  nombrando el parámetro, mismo shape que `gama`. `GET`/`PUT
  /api/pcs/preferencia` ganan los mismos seis campos (`ramDual`/`wifi`
  siempre presentes como boolean; el resto null cuando no se pidió).
  `ApiController.pcsBuilder` mantiene los overloads de 3 y 4 args sin
  mapping propio (CODE-2) — los tests existentes que llamaban esas formas
  siguen compilando sin tocarlos. `docs/openapi.yaml` documenta los seis
  parámetros/campos; `OpenApiRouteCoverageTest` sigue verde porque sólo
  afirma path+método+`x-access`, no la forma de los params.

- **T5d** (`propose_pc`): mismo `PreferenciasWire`, mismo mensaje de error
  por campo que ya usaba `gama`. Hallazgo de proceso durante el RED: tres
  de los diez tests nuevos (`marcaGpu`/`ramDual`/`wifi`) pasaban en VERDE
  sin ninguna implementación — sus fixtures tenían un solo candidato
  compatible (nada que vetar) o el candidato "correcto" ya ganaba por el
  eje de ranking existente (generación) sin necesidad del filtro. Se
  reescribieron para forzar un caso que discrimine de verdad: un competidor
  que gana por default y sólo el filtro puede cambiar, o un único candidato
  que no matchea y tiene que terminar en `sinCompatible` en vez de elegido
  igual — recién ahí el RED fue real (confirmado antes de tocar
  `ProposePcTool`).

**Boot real del jar** (`fashion-scraper-1.0.0.jar`, JRE 21, contra
`fashion-scraper-pg` — Postgres de dev en 127.0.0.1:5432/scraper): arrancó
en 3.933s. `flyway_schema_history` confirma `version=36, description=
'preferencias de armado', success=t`; las tres tablas nuevas y las doce
columnas de `preferencia_armador` existen (verificado con `\d` +
`information_schema.columns`). `grep -E "V36|WARN|ERROR"` del log de boot:
sin `ERROR`, dos `WARN` preexistentes de una corrida interrumpida anterior
(nada de T5). Las cuatro llamadas HTTP autenticadas con la cuenta de
servicio del `.env`:

- `GET /api/pcs/builder?gama=media&ddr=DDR5&marcaCpu=amd&ramDual=true&wifi=true&tipoAlmacenamiento=nvme&conGpu=true&marcaGpu=nvidia`
  → 200, mother `OUTLET - Motherboard Asrock B850M Pro A Wifi DDR5 AM5`
  ($83.300, `wifi:true`, `tierChipset:2`), cpu `AMD Ryzen 5 9600` — coincide
  exacto con la build (2) ya documentada arriba, contra el catálogo vivo.
- `GET /api/pcs/builder?ddr=DDR3` → 400,
  `{"ok":false,"mensaje":"ddr inválida: DDR3"}`.
- `PUT /api/pcs/preferencia` (los seis campos + gama/presupuesto/conGpu) →
  200, eco exacto del body persistido.
- `GET /api/pcs/preferencia` → 200, idéntico byte a byte a lo que el `PUT`
  devolvió — round trip real contra Postgres, no un mock.

Jar detenido al terminar (`kill` del PID del proceso).

**T6 — hecho** (`59ef784`), un commit, RED→GREEN. Baseline `npm test`
330/330 (42 archivos). RED confirmado: 6 tests nuevos de
`fetchPcsBuilder` en `api.test.js` + 10 tests nuevos en
`PcsPanel.test.jsx` (grupos de chips, reset del grupo GPU, precarga de
preferencia, ejes nuevos de `resumenSpecs`) fallando por la razón
correcta; de paso, agregar los cinco chips de "Cualquiera" rompió dos
tests preexistentes de `gama` que hacían `getByRole('button', {name:
'Cualquiera'})` sin scope (ahora ambiguo entre 5 grupos) — se
corrigieron acotando con `within(getByRole('group', ...))`, y el test
de `savePcPreferencia` con gama se extendió con los seis campos nuevos
en el body esperado (edit de test declarado, no un refactor — CODE-2).
Final `npm test` 345/345 (42 archivos), sin skips.

`api.js`: `fetchPcsBuilder` manda `ddr`/`marcaCpu`/`marcaGpu`/
`tipoAlmacenamiento` sólo si no son `''`, y `ramDual`/`wifi` sólo si son
`true` — mismo criterio que `gama`/`conGpu` ya tenían. `PcsPanel.jsx`:
cuatro `ChipGroup` (Memoria/CPU/Placa de video/Disco, vocabulario de
cable en minúscula: `ddr4`/`ddr5`, `intel`/`amd`, `nvidia`/`amd`,
`nvme`/`sata`/`hdd`) + dos `ToggleChip` (RAM dual, Mother con WiFi) bajo
la fila de Gama. El grupo Placa de video sólo se monta con `conGpu`, y
desmarcar el checkbox resetea `marcaGpu` a `''` de una — evita mandar un
filtro de marca de GPU con `conGpu=false`, que el backend ignoraría en
silencio. La precarga de preferencia y el body de `savePcPreferencia` en
`Generar` (sólo cuando hay gama, igual que antes) ganan los seis campos.
`resumenSpecs` agrega `marcaChip`, `generacion` (`gen N` si
`marcaChip==='INTEL'`, si no `serie N000` — una sola grafía por marca,
no por slot, tal cual pide la letra), `tierChipset` (`1→X/Z`, `2→B`,
`3→A/H`), `modulos` (`Nx`), `wifi` (sólo si `true`) y `tipoCooler`
(`LIQUIDO→AIO`, `AIRE→aire`); abstención (`''`/`0`/`false`/
`'DESCONOCIDO'`) sigue sin agregar nada a la línea, mismo criterio que
los seis campos que ya tenía.

Chequeo visual (390×844, sin backend — molde de la memoria "visual
checks need no backend"): `frontend/preview.html` +
`src/preview-entry.jsx` temporales, `window.fetch` stubeado por
pathname para `/api/pcs/builder` y `/api/pcs/preferencia` antes del
import, sondeado con las Playwright MCP tools (`browser_navigate`,
`browser_resize`, `browser_evaluate`, `browser_take_screenshot`) y
borrados al terminar (vivían en `frontend/`, no están gitignoreados).
`document.documentElement.scrollWidth` dio **390** en el formulario y
después de `Generar` con ocho picks — sin scroll horizontal. Los seis
chips reflejaron la preferencia precargada (`alta`/`DDR5`/`Intel`/`AMD`
gpu/`M.2 NVMe`/RAM dual/WiFi, todos resaltados) y `resumenSpecs` mostró
los ejes nuevos en un pick real: mother `LGA1700 · DDR5 · ITX · INTEL ·
X/Z · WiFi`, cpu `LGA1700 · INTEL · gen 14`, ram `DDR5 · 32 GB · 2x`,
cooler `AIO`.

**T7 — hecho** (`29050fc`), docs-only, sin código ni tests. `CLAUDE.md`:
header "fases 1 a 7", extendida la fila de ranking de la tabla de fase 2
(D4/D9), fila `Fase 7` nueva (las seis preferencias, D2 con la excepción de
`ramDual`/`wifi`, D6, `TipoCooler`) + su tabla de cobertura, párrafo
`Persistencia` extendido con `V36` (tres lookups + columnas nullable;
`socketsSoportados` sin persistir), y dos gotchas nuevas en "Taxonomía y
clasificación" (el sustantivo líder generalizado a Cooler/CPU/PC/Gabinete
con los números medidos — 146/470, 67+16 PCs, el service de $2.050 — y
`"mate"` como acabado no como yerba). `docs/ARCHITECTURE.md`: subsección
nueva con el porqué de D2 (la excepción de `ramDual`/`wifi` no es una
incertidumbre inventada), D5 (arreglar en el clasificador, no en el
armador — mismo patrón que `Cable`), D9 (tier relativo a la gama, el caso
de la `Z790I` de $284k) y por qué `socketsSoportados` no se persiste (FK
escalar, D10 de fase 6). `docs/API_REFERENCE.md`/`docs/ADD_SCRAPER.md`:
sin tocar — ninguno documenta `/api/pcs/**` todavía (gap preexistente de
fase 2/5/6, no de esta tarea). `SKILL.md`: sin tocar — no indexa
`odd/tasks/*.md` uno por uno. `grep -rn "V35" CLAUDE.md docs/*.md | grep -v
"V35__\|rollback\|V35 |\`V35\`"` da sólo referencias históricas correctas
(ninguna reclama ser "la última migración"). Hallazgo aparte, no resuelto:
`docs/ARCHITECTURE.md` nunca tuvo una sección "Armador de PCs" — ninguna de
las fases 1-6 documentó su porqué ahí pese a `DOC-2`; T7 crea la sección
recién con el porqué de fase 7, sin retro-documentar D1-D14 de fases
anteriores (fuera del scope de esta tarea).
