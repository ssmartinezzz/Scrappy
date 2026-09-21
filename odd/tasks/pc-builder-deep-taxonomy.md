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
- [ ] **T4 — Preferencias pedidas (D1–D3).** `PreferenciasDeArmado` +
  `ContextoDeArmado` + seis reglas (`ReglaDdrPedida` en mother y ram,
  `ReglaMarcaChip` en mother/cpu/gpu, `ReglaTipoAlmacenamiento`,
  `ReglaRamDual`, `ReglaWifi`). `PcBuilder.armar` con overload nuevo; los
  overloads existentes intactos.
- [ ] **T5 — Borde y persistencia (D7, D8).** `V36`, `PreferenciaArmador` +
  repository, `GET /api/pcs/builder` params, `PcBuildJson` con los campos
  nuevos, `openapi.yaml`, `propose_pc`, `OpenApiRouteCoverageTest` verde.
  `docs/DATABASE.md` con `V36` y su rollback.
- [ ] **T6 — UI `/pcs`.** Chips por preferencia (DDR · Marca CPU · Marca GPU ·
  Disco · RAM dual · Wifi), precarga desde la preferencia, `excluir` intacto.
  Tests vitest.
- [ ] **T7 — Docs.** `CLAUDE.md` (fase 7 en la sección del armador, tabla de
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
