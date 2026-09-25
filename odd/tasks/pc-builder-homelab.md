# Armador de PCs — fase 10: perfil homelab + categoría `Mini PC`

> **Objetivo.** Un perfil de uso `homelab` en `/pcs`, además del armado
> gamer de hoy (que sigue siendo el default, sin cambiar ni un byte). Va
> desde un mini PC chico pero potente hasta una torre grande con mucha RAM
> y mucho disco, armada con **hardware de consumo**. De paso, los mini PCs
> salen del slot `cpu`.
>
> Pedido del usuario (2026-09-24): *"Podemos meter una nueva categoria para
> el armador de PC? Homelabbing (todos los componentes para armar un homelab,
> desde basico pequeñito pero potente a un megaservidor.)"* → después de la
> medición: *"con hardware de consumo, y arreglá lo de los mini PCs."*
>
> Modo TDD: **on** (`CODE-1`, Strict TDD de la sesión). Runner:
> `mvn -f scraper/pom.xml clean test` con el JDK partido de `TEST-2`;
> frontend `npm test` en `frontend/`.

## Problema

1. El armador sólo sabe armar una PC de escritorio/gamer: los ejes priorizan
   potencia de CPU/GPU, y el disco se elige por tecnología (NVMe > SSD > HDD).
   Un homelab prioriza otras cosas: **cantidad de RAM** (VMs/containers) y
   **capacidad de disco** para datos, además de un disco de sistema.
2. **Los mini PCs se clasifican como `CPU`** (18 de 24) y compiten por el slot
   del procesador del armador normal. `"Mini PC Intel N100 ..."` no empieza con
   `" pc "` (`KW_PC_LIDER`), `KW_PC` no tiene `"mini pc"`, y cae en `KW_CPU`
   por `" intel "`/`" amd "`. Es la misma clase de bug que las PCs armadas de
   la fase 7.

## Medición previa (dev DB, filas activas de `tecnologia`, 6792, 2026-09-24)

| Lo que pediría un homelab "de servidor" | Hay |
|---|---|
| Xeon / EPYC / Threadripper | 1 (`Xeon E5-2699 V3` OEM, LGA2011-3, sin mother) / 0 / 0 |
| RAM ECC / RDIMM | **0** |
| Gabinete rack / NAS | 0 |
| Discos NAS (IronWolf / WD Red) | 2 |
| SSD enterprise (Kingston DC600M) | 4 |
| NIC 2.5G/10G | 1 |
| Switches (categoría `Red`) | ~22 |

→ El tope "megaservidor" **no se puede armar** con las tiendas de hoy. El usuario
eligió homelab con hardware de consumo (no sumar tiendas de servidor).

| Lo que sí hay | |
|---|---|
| Mini PCs (`mini pc`/`nuc`/`brix`/`cubi`/`barebone`) | 24: **CPU 18** · Otros 3 · Monitor 2 · Gabinete 1 |
| RAM por capacidad | 8GB 118 · 16GB 144 · 32GB 92 · 48GB 1 · 64GB 9 |
| Almacenamiento | 274 activas; HDD ~109; ≥4TB 16 |

## Decisiones

| | |
|---|---|
| **D1 — `Mini PC` es una categoría nueva, no `PC`** | Es un producto completo como `PC`, pero el perfil homelab lo necesita **distinguible** para elegirlo como pieza única. Una categoría nueva son **dos cambios** (keyword + migración, ver `V31`/`V32`): `V38` la inserta en `categoria` y `CategoryGroups` la suma al canon tech |
| **D2 — Se detecta por sustantivo líder**, antes que Monitor y CPU | `mini pc`/`minipc` líder (con `outlet` pelado, como `startsWithAny`), más `nuc`/`brix`/`cubi` líderes. El combo `"Mini PC ... + Monitor 22"` es un mini PC (el contenedor gana, como en todo el bloque tech). `"ARMADO DE BRIX..."` sigue en `Otros`: el guard de servicio corre antes. `"Kit Gabinete ... Barebone"` sigue en `Gabinete` |
| **D3 — `Uso` es un eje aparte de `Gama`** | `GAMING` (default) / `HOMELAB`. Cable `uso=gaming\|homelab`, dueño único `UsoWire`. Sin `uso` o con `gaming`, el armado es **byte a byte** el de hoy |
| **D4 — Homelab cambia slots y ejes, no las reglas de compatibilidad** | Slots: mother → cpu → ram → gabinete → fuente → **sistema** (el eje de almacenamiento de hoy) → **datos** (capacidad desc, HDD antes que SSD) → gpu sólo con `conGpu`. RAM rankea **capacidad primero** (desc), después DDR/módulos/MHz. Todos los vetos (socket, DDR, form factor, watts, certificación, preferencias) quedan iguales |
| **D5 — Cuotas homelab** (supuestas, no medidas, igual que las de fase 8) | mother 12 · cpu 18 · ram 18 · gabinete 6 · fuente 8 · sistema 8 · datos 20 · gpu 20 |
| **D6 — Modo mini PC: homelab + `tamanioGabinete=mini`** | Pedir "chico" en homelab arma **un** slot `minipc` (categoría `Mini PC`, ranking por gama → nivel de CPU leídos del nombre → RAM GB → precio) + slot `datos`. Hoy hay 1 gabinete mini activo, así que por la vía de torre "mini" no daba nada |
| **D7 — Se persiste en `preferencia_armador`, normalizado** | `V39`: lookup `uso` + `uso_id` **FK**, mismo molde que `gama` (1FN/3FN, pedido explícito del usuario 2026-09-24) — nunca TEXT/CHECK repetido ni un booleano `es_homelab`. `uso` siembra **las dos filas** (`GAMING`/`HOMELAB`): a diferencia de los diez lookups anteriores, `GAMING` no es un centinela de abstención, es un valor pedible con fila propia. `uso_id` es nullable sólo porque una fila guardada ANTES de esta migración no eligió ningún uso — esa sí es la abstención real (D10: NULL, nunca una fila sentinela) — y desde `V39` en adelante todo `guardar()` escribe una fila concreta (`UsoMapeo`, mismo molde que `GamaMapeo`). NULL se sigue leyendo como GAMING, el mismo default de siempre |
| **D8 — Fuera de alcance** | Slot de red (switch/NIC: 22 y 1 filas), ECC, rack, sumar tiendas de servidor |

## Alcance autorizado

Backend `ar.scraper.pcs` + clasificador + `V38`/`V39` + endpoint/tool/openapi,
frontend `/pcs`, docs (`CLAUDE.md`, `docs/DATABASE.md`). Sin commit/push hasta
que el usuario lo pida.

## Tareas

- [x] **T1** — Categoría `Mini PC`: líder en `CategoryClassifier`/`GarmentTaxonomy`, `V38`, `CategoryGroups`, tests de clasificación (RED primero) + test de paridad del vocabulario
- [x] **T2** — `MiniPcSpecsReader` en el registry (gama/nivel de CPU + RAM GB del nombre)
- [x] **T3** — `Uso` + slots/ejes/cuotas homelab en `PcBuilder` (gaming intacto)
- [x] **T4** — Modo mini PC (D6)
- [x] **T5** — Cable: `uso` en `GET /api/pcs/builder`, `propose_pc`, `docs/openapi.yaml`
- [x] **T6** — `V39` + `GET`/`PUT /api/pcs/preferencia` con `uso` + rollback en `docs/DATABASE.md`
- [x] **T7** — `/pcs`: chips de uso, `api.js`, tests
- [x] **T8** — Medición: reclasificar los nombres de la dev DB con el clasificador de hoy y armar de verdad (homelab × gamas × presupuestos, filas activas)
- [x] **T10** — `1.92TB` se lee 92 TB: el parser de capacidad tiene que aceptar TB decimales (defecto preexistente, afecta también el piso `capacidadMinimaGb` del armado gamer)
- [x] **T11** — Eje `datos`: un disco externo (tecnología abstenida) va último, antes que la capacidad (D13)
- [x] **T13** — Gama pedida → la mother se elige sólo entre plataformas (socket) con al menos un CPU compatible de esa gama en el pool (gama económica salía `cpu` en `sinCompatible` en todo presupuesto)
- [x] **T14** — Categoría `CPU` limpia: thermal pad / pasta térmica y memorias "AMD EXPO / Intel XMP" (35 filas) salen por sustantivo líder
- [x] **T15** — Categoría `Cooler` limpia: RAM y SSD "c/disipador", joystick, auricular, controladora afuera; `LC 240/360` de ASUS = LIQUIDO con radiador
- [x] **T16** — Eje de cooler de AIRE con profundidad: clase de disipador (doble torre > torre > bajo perfil/desconocido), después heatpipes si se leen — pedido del usuario: "no importa qué gama, en los cooler siempre estaba ganando uno medio pedorro"
- [x] **T17** — Xeon E5 v3/v4 sin socket en el nombre → `LGA2011-3` (mismo molde que el Athlon de T12)
- [x] **T18** — Sin presupuesto ("top top"), el desempate por precio es **desc** en todos los slots: adentro de un mismo escalón técnico gana el más caro. Con presupuesto sigue asc. Decisión del usuario (2026-09-25): "sin presupuesto", sin importar la gama
- [x] **T19** — Combos fuera del pool (soft): `PcBuilder.esCombo` — `combo` o `+ procesador/cpu/mother/memoria/ram/monitor/fuente/kit/...`. RED 3/4 → GREEN; suite 2931/0
- [x] **T9** — Docs: `CLAUDE.md` (fase 10, categoría), `docs/DATABASE.md`
- [x] **T12** — Derivar el socket de un Athlon de escritorio "G"/"GE" (`3000g`/`200ge`/`220ge`/`240ge`/`300ge`/`320ge`) a AM4 cuando el nombre no lo declara, para que `ReglaSocket` pueda vetarlo (pedido del coordinador tras T8: un `Athlon 3000G` sin socket ganaba una mother AM5 incompatible)

## Criterios de aceptación

- Los 18 mini PCs de `CPU` y los 2 de `Monitor` clasifican `Mini PC`; los servicios y kits de gabinete no.
- Todos los tests de armado gamer existentes pasan **sin tocarlos**.
- `uso=homelab` arma sistema + datos, con la RAM de mayor capacidad dentro de la cuota.
- `uso=homelab&tamanioGabinete=mini` devuelve un mini PC + disco de datos.
- Suites backend y frontend en verde; medición real registrada acá.

## Progreso

- **T1** (RED→GREEN, `TechCategoryClassifierTest`): 7 casos rojos ("Mini PC" esperado, "CPU"/"Monitor"/"Otros" observado) → verdes tras `KW_MINIPC_LIDER` + el check en `clasificarTech` antes de `KW_PC_LIDER`. `CategoryGroups` suma "Mini PC" (106 categorías); `RubroResolverEqualityParityTest` (105→106) y `V38__categoria_mini_pc.sql` actualizados en el mismo cambio. `CategoriaLookupTableTest.laTablaYElCanonDeJavaNoPuedenDiverger` verde contra Postgres real (Testcontainers); `altaDeCategoriaEsUnInsert` falla igual en `master` sin mis cambios (verificado con `git stash`) — infra preexistente, no tocada acá.
- **T2** (compile-fail RED→GREEN, `MiniPcSpecsReaderTest`): `MiniPcSpecsReader` reusa `CpuSpecsReader.gama/marcaChip/nivel` (bajados a package-visible) y lee `capacidadGb` como el primer token `NNgb` — 5/5 verdes, registrado en `TechSpecsParser`.
- **T3** (compile-fail RED→GREEN, `EjesTecnicosHomelabTest` + `CuotasDePresupuestoTest` + `PcBuilderHomelabTest`): `Uso`/`UsoWire`, tres ejes nuevos (`RAM_HOMELAB`, `ALMACENAMIENTO_DATOS`, `MINI_PC`), `CuotasDePresupuesto.para(slots, Uso)`, slots `sistema`+`datos` con hard-exclude de urls ya elegidas (D4). 16/16 verdes; **los 90 tests preexistentes de `ar.scraper.pcs.*` pasan sin editar ninguno** (CODE-2) — confirmado corriendo el paquete completo.
- **T4** (mismo ciclo que T3, `PcBuilderMiniPcModeTest`): homelab + `tamanioGabinete=MINI` arma sólo `minipc`+`datos`; `ReglaGama` respeta la misma abstención-veta que `cpu`; `conGpu` no agrega slot en este modo. 5/5 verdes.
- **T5** (compile-fail RED→GREEN, `ProposePcToolTest` + `ApiControllerPcsBuilderTest`): `uso` cableado en `PcsEndpoints.builder` (nuevo overload de 15 args, el de 14 queda back-compat — CODE-2), `ApiController.pcsBuilder` (nuevo `@GetMapping` + overload 14-arg back-compat), `ProposePcTool` (schema + parseo) y `docs/openapi.yaml`. 8/8 verdes nuevos; los tests preexistentes de ambos archivos (30) siguen pasando sin editarlos.
- **T6** (RED→GREEN, `PreferenciaArmadorRepositoryTest` + `ApiControllerPcsPreferenciaTest` + `V39RollbackRoundTripTest`): `PreferenciaArmador` suma `Uso uso` (ctor 4-arg back-compat default GAMING), `PreferenciaArmadorRepository` + `UsoMapeo` nuevo, `V39__uso_de_armado.sql`. **Corrección aplicada a pedido del coordinador (2026-09-24, normalización 1FN/3FN)**: `uso` siembra las DOS filas (`GAMING`/`HOMELAB`) — GAMING NO es un centinela de abstención, tiene fila propia igual que en `gama`; `uso_id` nullable sólo representa "fila guardada antes de V39, sin uso elegido" (abstención real, D10), y desde esta migración todo `guardar()` escribe una fila concreta. 8/8 nuevos verdes (incluye CHECK de dominio y el caso NULL→GAMING de filas viejas); `PreferenciaArmadorRepositoryTest` completo (14/14), `ApiControllerPcsPreferenciaTest` completo (17/17) y `ApiControllerPcsBuilderTest` completo (22/22) sin editar ningún test preexistente. `docs/DATABASE.md` V39 actualizado con la justificación 1FN/3FN (mismo molde que la entrada de `V35`).
- **T7** (RED→GREEN, `src/api.test.js` + `PcsPanelHomelab.test.jsx` nuevo, frontend): 14 casos rojos (querystring de `uso`, chip group "Uso" inexistente, labels `sistema`/`datos`/`minipc`, hint de modo mini, exclusión de Regenerar en los slots nuevos) → verdes. `fetchPcsBuilder` suma `uso=''` con el mismo patrón que `gama` (`if (uso) p.set(...)`). `PcsPanel`: chip group "Uso" (`Gaming`/`Homelab`, mismo molde `ChipGroup` que DDR/CPU/Disco) con `''`=Gaming default; `SLOT_LABELS` suma `sistema`/`datos`/`minipc`; hint de una línea bajo el picker de Gabinete, sólo con `uso==='homelab'`. **Decisión de diseño para no tocar el test preexistente `toHaveBeenCalledWith` de `savePcPreferencia`** (línea ~293 de `PcsPanel.test.jsx`, match exacto del payload): `uso` se omite de la preferencia guardada en Gaming y sólo se agrega la clave cuando es `'homelab'` — mismo criterio que el querystring, y consistente con `UsoWire.parse(null) → GAMING` en el backend (omitir la clave y mandar `null` son equivalentes para el servidor). Regenerar no vuelve a guardar preferencia (ya estaba gateado por `!acumulando`); la exclusión por slot es genérica (keyed por `p.slot`) así que `sistema`/`datos`/`minipc` funcionan sin código nuevo — cubierto con un test explícito. Suite completa: **383/383 tests, 46 archivos, verde**. `npm run build` sólo funciona con `VITE_API_BASE_URL` seteada (falla igual en `master`, sin `frontend/.env` en este checkout) — no es una regresión de este cambio; build verificado pasando la variable inline.

## T8 — La medición (dev DB, 6792 filas activas de `tecnologia`, 2026-09-24)

`PcBuilder.armar` de verdad, con cada nombre **reclasificado con el clasificador de hoy**
(la categoría de la base es la del último scrape). Script scratch fuera del repo.

- Reclasificación: **22 filas pasan a `Mini PC`** — CPU 18 · Monitor 2 · Otros 2.
- Gaming sin `uso`: mismos picks que antes del cambio.
- Homelab sin presupuesto: RAM **64GB (2x32GB)** donde gaming elige 32GB; slots `sistema` + `datos`.
- Homelab $2M/$5M: `datos` = `HD HDD 4TB WD BLUE` / `WD 6TB Red Pro NAS`.
- Modo mini: `Ryzen 3 3250U` (BAJA) … `Ryzen 7 6800H` (ALTA / sin gama) + disco de datos.

Tres defectos que sólo aparecieron armando de verdad:

| | Qué pasó | Tarea |
|---|---|---|
| `HD SSD 1.92TB KINGSTON DC600M` → `capacidadGb=94208` | El tokenizer corta en el punto y lee `92tb`. En `datos` (capacidad primero) le ganaba al WD Red Pro 6TB por $2.588.680 | T10 |
| `Disco Duro Externo 1Tb Seagate Portable` como `datos` con $800k | La capacidad corre antes que la tecnología, así que la abstención del externo no lo hundía | T11 |
| `Athlon 3000G` (AM4) sobre mother AM5 en gama BAJA, **también en gaming** | El nombre no trae socket → `ReglaSocket` abstiene. Preexistente | T12 |

## Progreso — T10, T11, T12

- **T10** (RED→GREEN, `AlmacenamientoSpecsReaderTest` + `TokensTest`): `Tokens` suma `original()` (acento-stripeado/lowercased, ANTES del colapso a espacios — aditivo puro, no cambia `array()`/`padded()`/`has()`). `AlmacenamientoSpecsReader.capacidadGb` corre `CAPACIDAD_DECIMAL_TB` (`(\d+)\.(\d+)\s*tb\b`) sobre `original()` antes del loop de un solo token: "1.92TB" → 1966, "3.84TB" → 3932, "7.68TB" → 7864 (×1024, redondeado). El "tb" tiene que seguir inmediatamente (sólo espacio de por medio), así que un "2.5\"" de form factor antes o después nunca se confunde con la fracción — verificado con `HD HDD 4TB WD BLUE SATA III 3.5\"` (sigue dando 4096) y con el nombre real completo del Kingston DC600M. 3 tests nuevos verdes; **medí el resto de los lectores de capacidad** (RAM, GPU, Mini PC): ninguno usa TB, sólo `NNgb`/`NNxNNgb` — el defecto es exclusivo de `AlmacenamientoSpecsReader`, fix acotado ahí, ningún otro reader tocado.
- **T11** (RED→GREEN, `EjesTecnicosHomelabTest` + `AlmacenamientoSpecsReaderTest` + `PcBuilderHomelabTest`): `EjesTecnicos.ALMACENAMIENTO_DATOS` suma `tecnologiaConocidaRank` (0 conocida / 1 abstenida) como PRIMER key, antes de la capacidad — D13 aplicado al eje `datos`, que hasta ahora rankeaba capacidad primero y dejaba que un externo (tecnología abstenida) ganara por GB. 2 tests nuevos rojos → verdes (uno con specs sintéticos 512 vs 4000GB, uno con los nombres reales de T8: `HD SSD 512GB LEXAR` vs `Disco Duro Externo 1Tb Seagate Portable`, donde el externo tiene MÁS capacidad cruda y aun así pierde). El test preexistente `laAbstencionDeTecnologiaSigueUltima` (a igual capacidad) sigue verde sin tocarlo.
- **T12** (RED→GREEN, `CpuSpecsReaderTest` + `PcBuilderTest`): `CpuSpecsReader` suma `athlonDesktopSocket` — un set cerrado y medido (`3000g`/`200ge`/`220ge`/`240ge`/`300ge`/`320ge`) → `AM4`, sólo cuando `tokens.has("athlon")` y no hay socket explícito ni patrón Ryzen/Core (fallback al final de `cpuSocket`). 5 tests nuevos en `CpuSpecsReaderTest` (2 rojos → verdes: el 3000G suelto y los 5 GE; 3 ya verdes de entrada, confirmando que socket explícito gana, que Athlon móvil (`3050U`) no deriva, y que Xeon no deriva). 2 tests nuevos en `PcBuilderTest`: mother AM5 + sólo el Athlon → `sinCompatible` contiene `cpu`; con un Ryzen 5 8500G AM5 también en el pool, el slot cae en él. **Los 35 filas restantes sin socket** (`"AMD EXPO"/"Intel XMP"` en nombres de RAM mal clasificados como `CPU`) no contienen `athlon` ni ninguno de los 6 modelos del set, así que este cambio no los toca — siguen abstiniendo el socket exactamente como antes (`ReglaSocket` no los veta, igual que el `"Procesador Generico Sin Socket"` del test preexistente). Confirmado: **sí pueden seguir ganando el slot `cpu`**, pero sólo en el caso ya preexistente y fuera de este alcance — cuando son el único candidato del pool o cuando ningún candidato con gama conocida les gana en `EjesTecnicos.CPU` (su `gama()`/`nivel()` también abstienen al no tener keywords de CPU, así que rankean al final salvo que sean los únicos). Nada de esto cambió con T12.

## T8b — Re-medición tras T10–T12 (dev DB, 2026-09-25)

- `datos` ya no elige el DC600M ni el externo: sin presupuesto / $5M → `WD 6TB Red Pro NAS` $650.650; $2M → `WD BLUE 4TB`; $800k → `LEXAR 512GB` SATA interno.
- El Athlon 3000G ya no aparece sobre AM5.

Tres cosas nuevas, pendientes de decisión del usuario:

| | Qué pasó |
|---|---|
| **Gama económica: `cpu` sale en `sinCompatible`** (gaming y homelab, todos los presupuestos) | Antes era un armado **inválido** (Athlon AM4 sobre AM5) y ahora es honesto, pero vacío. La mother se elige primero (`MSI A620M-E PRO`, AM5) y los 45 CPUs de gama baja son AM4/LGA1700. Es la limitación greedy de "la mother es el ancla" |
| **Homelab $800k sin gama: un `Thermal Pad Carbice ... para CPU AM4/AM5` ($13.500) gana el slot `cpu`** | La cuota de cpu de homelab (18%) no alcanza para ningún CPU real sobre esa mother AM5, cae al fallback "el más barato compatible", y el pad está clasificado `CPU`. Misma clase que las 35 RAM "AMD EXPO/Intel XMP" en `CPU` |
| Gaming gama BAJA: `Memoria Ram Corsair 8gb Dddr4` sobre mother DDR5 | El typo `Dddr4` hace abstener la DDR → `ReglaDdr` no veta. Preexistente, 1 fila |

## T8c — Cooler (dev DB, 2026-09-25): siempre gana el más barato de aire

Pedido del usuario: *"no importa que gama, en los cooler siempre estaba ganando uno medio pedorro"*.
Medido con `PcBuilder.armar` (gaming, con GPU, 4 presupuestos × 4 gamas × tipoCooler null/AIRE/LIQUIDO):

- Con `tipoCooler=aire` gana **siempre** `CPU Cooler Raptor Cryo RGB - 3P` ($18.400), en las 16 combinaciones. Gama ALTA con $1M sin pedido también cae en él.
- 321 filas `Cooler`: AIRE 85 (**todas con radiador 0**, o sea que no hay eje: empatan y desempata el precio asc) · DESCONOCIDO 82 · LIQUIDO 116.
- Entre las "AIRE" hay RAM `C/DISIPADOR` (9), SSD `con disipador` (6), un joystick, un auricular, una controladora, y AIOs `ASUS PRIME LC 240/360`, `TUF LC III 240`, `ROG STRIX LC III 360` tipadas como aire.

## Progreso — T13, T14, T15, T16

- **T13** (RED→GREEN, `ReglaPlataformaConCpuTest` + `ContextoDeArmadoTest` + `PcBuilderGamaTest` + `PcBuilderHomelabTest`): `ContextoDeArmado` suma `Set<String> socketsConCpuElegible` (5º overload de `inicial`, vacío = sin restricción — el mismo fallback que pide la tarea). `PcBuilder.socketsConCpuElegible` la calcula UNA vez antes del loop de slots (sólo cuando hay gama pedida y el modo tiene slot `cpu`, o sea nunca en mini PC): recorre el pool de CPU, exige socket legible, `gama()==gamaPedida` y `marcaChip` si se pidió — ignora a propósito `ReglaSocket` (es el emparejamiento bajo prueba). `ReglaPlataformaConCpu` (nueva, en `reglas/`) se suma como CUARTA regla del slot `mother` en `slotsFijos` y `slotsFijosHomelab`: vetea una mother cuyo socket no esté en el set, salvo que el set esté vacío (fallback) o el socket de la mother no haya parseado (abstención, igual que `ReglaSocket`). Repro medido: mother `MSI A620M-E PRO DDR5 AM5` ($100k, chipset A/H tier 3, distancia 0 al target BAJA) ganaba el ranking sobre una `MSI PRO B760M-A WIFI DDR4` ($90k, tier B, distancia 1) con sólo un `Intel Core i3 12100` (BAJA, LGA1700) en el pool — antes el i3 quedaba `sinCompatible`, ahora la mother AM5 se vetea y gana la LGA1700. 22/22 verdes en `PcBuilderGamaTest` (3 nuevos), 13/13 en `PcBuilderHomelabTest` (1 nuevo), 22/22 en `ContextoDeArmadoTest` (5 nuevos), 5/5 en `ReglaPlataformaConCpuTest` (nuevo); **los 90+ tests preexistentes de `ar.scraper.pcs.*` pasan sin editar ninguno** — sin gama pedida el set siempre da vacío y la regla es no-op (`sinGamaPedidaLaMotherNoSeRestringe`, nuevo, lo fija).
- **T14** (RED→GREEN, `TechCategoryClassifierTest`): dos sustantivos líder nuevos en `GarmentTaxonomy`/`CategoryClassifier`. (1) `KW_COOLER` suma `"thermal pad"`/`"pad termico"`/`"thermal paste"` — ya corría antes que `KW_CPU`, así que alcanza con el vocabulario (no hace falta un líder nuevo): "Thermal Pad Carbice Ice Pad para CPU AM4/AM5..." tenía " cpu " y ninguna forma de pasta/grasa térmica in inglés. (2) `KW_RAM_LIDER = { " memoria " }`, chequeado junto con `anyMatch(KW_RAM)` (un token DDR real) ANTES de `KW_RED` — las 3 filas medidas ("...DDR5... AMD EXPO", "...DDR5... Intel XMP 3.0 / AMD EXPO", "...DDR4... Solo Intel") arrancan con "memoria" y declaran DDR, así que ganan antes de que " amd "/" intel " (el perfil de overclock, no la marca de un procesador) las mande a CPU vía `KW_CPU`. 2 tests nuevos (7 aserciones), verifican además que un CPU real (no arranca con "memoria") y una notebook con RAM en el nombre siguen intactos. 56/56 verdes.
- **T15** (RED→GREEN, `TechCategoryClassifierTest` + `CoolerSpecsReaderTest`): mismo mecanismo de sustantivo líder para lo que `KW_COOLER` (bare `"cooler"`/`"disipador"`) se comía por marca o accesorio, corriendo ANTES de esa línea: `KW_ALMACENAMIENTO_LIDER = {" hd ", " ssd ", " disco "}` (+ `anyMatch(KW_ALMACENAMIENTO)`), `KW_AURICULAR_LIDER = {" auricular ", " auriculares "}`, `KW_JOYSTICK_LIDER = {" joystick "}`. Las dos RAM "con disipador" del pedido ya las cubría el líder de T14 sin cambios (confirmado con test dedicado). "Controladora Cooler Master A1 Gen 2 ARGB P/Fan Coolers" se suma a `KW_ACCESORIO_LIDER` (junto a `" bracket "`) — es un hub para controlar fans ya instalados, mismo trato que un bracket: abstiene TODO el bloque tech y cae a `Otros`. 4 tests nuevos en `TechCategoryClassifierTest`, 56/56 verdes.
  En `CoolerSpecsReader`: `esLiquido` suma dos vías nuevas — `TOKENS_LIQUIDO_SERIE={"ryuo","ryujin"}` (series ASUS ROG reales, nunca otra cosa en este catálogo) y `tokens.has("lc") && radiador bare (240/280/360/420, sin "mm")`; `radiadorMm` prueba esa misma forma sin sufijo tras la que ya exigía "mm". Cubre los 5 nombres del pedido: `ASUS PRIME LC 240 ARGB`, `ASUS TUF LC III 240`, `ASUS ROG STRIX LC III 360` (vía "lc"+240/360), `ROG RYUO 3 240`, `ROG RYUJIN III 360` (vía la serie). 8 tests nuevos, 31/31 verdes en `CoolerSpecsReaderTest`.
- **T16** (RED→GREEN, `ClaseDisipadorTest` + `CoolerSpecsReaderTest` + `EjesTecnicosT16Test`): `ClaseDisipador` (`DOBLE_TORRE`/`TORRE`/`DESCONOCIDA`, molde `TipoAlmacenamiento`/`TamanioGabinete`) nuevo. `TechSpecs` suma `claseDisipador`+`heatpipes` (22 campos; constructor de 20 args conservado para compat — CODE-2). `CoolerSpecsReader.claseDisipador`/`heatpipes` sólo corren si `tipoCooler()==AIRE` (un líquido no tiene "clase de aire", y case-fan/pasta ya abstuvieron antes). Vocabulario MEDIDO contra la dev DB real (323 filas activas de `Cooler`, 2026-09-25): frases espaciadas sobre `Tokens.padded()` (mismo mecanismo que `KW_RAM`), sub-frases con guión propio (`"se-214"`, `"nh-d15"`, `"lc-x1210"`, `"lc-ap600"`) sobre `Tokens.original()` (el guión ya es separador real, no hace falta boundary extra), tokens sueltos (`"assassin"`, `"dt621"`, `"ak620"`, `"ak400"`, `"ux500"`) vía `tokens.has()`. De los 27 términos pedidos, 22 tienen match real medido hoy (`peerless assassin`, `phantom spirit`, `nh-d15`, `ak620`, `ak400` dan 0 — se dejan igual, son series reales de fabricante y el pedido las lista explícitamente). Heatpipes: `^(\d{1,2})hdp$`/`^(\d{1,2})h$` (token pegado, sin separador) + `" N heatpipes? "` (frase) — confirmado con los dos nombres reales medidos (`Hyper 212 3HDP`, `Cryo Pro 4h`).
  `EjesTecnicos.COOLER` suma clase (desc) y heatpipes (desc) como 3º/4º key, DESPUÉS de radiador — dentro de LIQUIDO ambos abstienen siempre (empate uniforme, no-op, eje líquido intacto) y dentro de AIRE (donde radiador siempre abstiene) son los que de verdad desempatan. D13 aplicado: `claseDisipadorRank`/`masEsMejor(heatpipes)` mandan la abstención al final. `elRadiadorNoReordenaCoolersDeAire` (preexistente, fase 9) sigue verde sin tocarlo: dos AIRE sin clase/heatpipes explícitos siguen empatando en 0. 7 tests nuevos en `EjesTecnicosT16Test`, 2 en `ClaseDisipadorTest`, 8 en `CoolerSpecsReaderTest` (dentro de los 31 ya contados en T15).
  **NO se persiste `claseDisipador`/`heatpipes` en `producto_tech_specs` en esta fase** — mismo precedente que `nivel` (D7, fase 8): el armador los calcula al armar, desde el snapshot en memoria. Documentado en el javadoc de `ClaseDisipador` y en el comentario del campo en `TechSpecs`.

## T8d — Re-medición tras T13–T16 (dev DB, 2026-09-25)

- Gama BAJA ya no sale sin CPU: homelab/gaming → `MSI Pro H610M-G LGA1700 DDR5` + `Intel Core i3 14100F`.
- Ningún thermal pad en ningún armado.
- `tipoCooler=aire`: gana `ID-Cooling FROZN A620 PRO SE` (doble torre, $81.350) en 14 de 16 combinaciones; con $1M y gama MEDIA/ALTA la cuota sólo alcanza para `Gamemax Gamma 500` ($33.800).
- Categoría `Cooler`: 321 → 303 filas; AIRE 85 → 55; LIQUIDO 116 → 165.

Pendiente de decisión:

| | Qué pasó |
|---|---|
| **Homelab $800k sin gama: `Intel Xeon E5-2699 V3 Oem` ($45.746) sobre mother AM5** | Mismo caso que el Athlon: el nombre no trae socket (LGA2011-3), `ReglaSocket` abstiene, y gana el fallback "el más barato" |
| **Adentro de un mismo escalón técnico gana el más barato**, también sin presupuesto ("top top") | Líquido 420mm: `Gamdias Chione E4 420` $99.990 le gana al `Be Quiet! SILENT LOOP 3 420mm` $244.809; aire doble torre: `FROZN A620 PRO SE` $81.350 le gana a `DARK ROCK PRO 5` / `ASSASSIN VC ELITE`. El desempate precio asc es el de todos los slots desde la fase 2 |

## Progreso — T17, T18

- **T17** (RED→GREEN, `CpuSpecsReaderTest` + `PcBuilderTest`): `CpuSpecsReader` suma `xeonE5ServerSocket` — un patrón cerrado (`tokens.has("xeon")` + `" e5 \d{3,4} v[34] "` sobre `padded()`) → `LGA2011-3`, sólo como fallback tras Athlon y sin socket explícito — mismo molde que T12. 5 tests nuevos en `CpuSpecsReaderTest` (2 rojos → verdes: `E5-2699 V3` y `E5-2680 V4`; 3 ya verdes de entrada, confirmando que sin "v3"/"v4" sigue abstiniendo — `xeonNoSeDerivaSocket` preexistente intacto — y que una familia Xeon sin evidencia medida, `Xeon Gold 6248 V3`, tampoco deriva). 1 test nuevo en `PcBuilderTest` (`xeonE5V3SeDerivaALga20113YSeVetaContraMotherAm5`, mismo molde que los dos tests de Athlon de T12): con el Xeon E5-2699 V3 real medido en T8d como único CPU del pool y una mother AM5, el slot `cpu` cae en `sinCompatible` en vez de ganar por fallback. `LGA2011-3` nunca matchea ninguna mother del catálogo (ninguna declara ese socket) — el veto dispara siempre, que es el punto pedido por la tarea. `gama(tokens)` no se tocó: sigue `DESCONOCIDA` para Xeon (ya lo estaba antes de T17).
- **T18** (RED→GREEN, `ContextoDeArmadoTest` + `CriterioPorEjesTecnicosTest` + `PcBuilderTest`): `ContextoDeArmado` suma el campo `sinPresupuesto` (booleano, no un sentinel de abstención — es un hecho del llamador) vía un 6º overload de `inicial`; todo overload previo lo deja en `false` (con presupuesto, byte a byte igual que siempre) y `conMother` lo preserva. `PcBuilder.armar` lo calcula UNA vez (`presupuesto <= 0`) al construir el `contexto` inicial, antes del loop de slots. `CriterioPorEjesTecnicos.elegir` es el ÚNICO lugar que aplica la cola `precio → url` (los ocho slots —mother/cpu/ram/gabinete/fuente/almacenamiento/sistema/datos/gpu/cooler/minipc— pasan todos por esta clase, ninguno tiene su propio criterio): ahora arma el comparador de precio como `asc` o `desc` según `contexto.sinPresupuesto()`, dejando `url asc` sin invertir (es un identificador, no una magnitud). El fallback "el más barato" de D5 sólo corre con `presupuesto > 0`, así que queda sin tocar (confirmado: no hubo que cambiar esa rama).
  9 tests nuevos: 3 en `ContextoDeArmadoTest` (overload 6-arg expone `sinPresupuesto`, los 4 overloads previos dan `false`, `conMother` lo preserva), 5 en `CriterioPorEjesTecnicosTest` (empate en eje técnico → gana el más caro sin presupuesto / sigue ganando el más barato con presupuesto; gabinete sin ejes elige el más caro; empate total sigue desempatando por url; el eje técnico sigue mandando ANTES que el precio — no es "gana el más caro siempre"), 4 en `PcBuilderTest` (con presupuesto real, RAM sigue asc — confirma el threading de `presupuesto<=0` a través de `PcBuilder.armar`; y los DOS targets medidos del enunciado: `SILENT LOOP 3 420mm` $244.809 le gana a `Gamdias Chione E4 420` $99.990 sin presupuesto con gama ALTA — ambos LIQUIDO+420mm, empate total en `EjesTecnicos.COOLER`; `DARK ROCK PRO 5` $225.500 le gana a `FROZN A620 PRO SE` $81.350 sin presupuesto con `tipoCooler=AIRE` pedido — ambos DOBLE_TORRE, heatpipes abstenidos en los dos).

  **Un test preexistente cambió de comportamiento y se actualizó — marcado acá como pide la tarea**: `PcBuilderTest.rankingEmpataEnTecnologiaYDesempataPorPrecio` (línea ~521) armaba con `presupuesto=0` dos RAM DDR4 16GB idénticas en eje técnico, $20.000 vs $10.000, y afirmaba que ganaba la barata (`https://t/ram-barata`) — antes de T18 `presupuesto=0` no tenía NINGÚN efecto sobre el desempate de precio, así que era indistinguible de cualquier otro valor. Con T18, `presupuesto=0` es el modo top-top (D3/D4, fase 8) y el desempate se invierte a propósito: la aserción pasó a `https://t/ram-cara`, el nombre y el `@DisplayName` se actualizaron para decir "desempata por precio DESC", y queda un comentario explícito arriba: **"test que fijaba el desempate asc sin presupuesto — cambio de comportamiento pedido por el usuario"**. Se agregó un test hermano (`conPresupuestoElEmpateEnRamSigueDesempatandoPorPrecioAsc`, mismo catálogo, `presupuesto=999_999`) para no perder cobertura del caso "con presupuesto sigue asc" a nivel `PcBuilder`. Ningún otro test existente cambió — confirmado corriendo el paquete `ar.scraper.pcs.**` completo antes y después (639/639 verdes) y la suite backend entera (`clean test`, 2927/2927, 0 failures/errors, BUILD SUCCESS, 7 skips preexistentes de `PythonRunner*Test`, no relacionados).

Verificación (foreground):
```
JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
```
→ `Tests run: 2927, Failures: 0, Errors: 0, Skipped: 7` · `BUILD SUCCESS` · sin `ERROR]` en el log.

## T8e — Tras T17–T19 (dev DB, 2026-09-25)

- Sin presupuesto, gaming: `ROG CROSSHAIR X870E DARK HERO` + `Ryzen 9 9950X3D2` (antes `Z790I` + `i9 14900K`); cooler líquido `DeepCool SPARTACUS 420`, aire `Be Quiet! DARK ROCK ELITE`.
- T18 destapó combos ganando por caros (`Kit Mother Z890 TAICHI + Ultra 9 285K`, `Mini PC + Monitor 22"`, `Combo Actualización i3 14100 8GB`) → T19. Tras T19: cero combos en los 40 armados.
- Gama BAJA sin presupuesto: `Asus Prime H610M-K` + `Intel I3-14100`.
- Suite backend `clean test`: 2931, 0 fallos, 7 skips preexistentes. RDD: off (global) → sin revisión nativa.
- Sigue pendiente (anotado, no pedido): RAM `Dddr4` (typo) se monta sobre mother DDR5.
