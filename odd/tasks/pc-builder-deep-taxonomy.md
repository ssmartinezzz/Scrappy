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
- [ ] **T2 — Vocabulario y compatibilidad (D6).** Primero el clasificador:
  **146 de 470 filas de `Cooler` son CPUs** (`"Procesador AMD Ryzen 9 9950X3D
  ... (no incluye cooler)"`, 85 de gama alta) porque `Cooler` corre antes que
  `CPU` y `cooler` aparece como accesorio; líder `procesador`/`microprocesador`
  /`micro amd|intel` ⇒ CPU antes de la línea de Cooler. Después: sockets viejos en
  `MotherboardSpecsReader`/`CpuSpecsReader` (+ chipsets `H310/B360/Z390/
  H410/B460/Z490/H510/B560` → socket). `ReglaSodimm` en el slot ram.
  `CoolerSpecsReader` lee la lista de sockets; `ReglaSocketCooler` veta cuando
  cooler y mother parsearon y no se cruzan. Mensajes D6 para cada regla nueva.
- [ ] **T3 — Ejes profundos (D4).** `TechSpecs` gana `marcaChip`, `generacion`,
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
