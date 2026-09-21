# pc-builder-gama — la gama como filtro duro, y el armador rediseñado en objetos

> Fase 6 del armador de PCs. Cierra los pendientes 1 y 2 de
> `odd/pc-builder-feedback/pendientes`, que son el mismo pendiente: la gama
> *es* el objetivo de calidad que hoy falta.

## Objetivo

Que `/pcs` pueda pedir una gama (económica · media · alta) y que el armador
elija por potencia real del componente, no por barato. Pedir gama alta tiene
que traer un i9/Ryzen 9, y arrastrar lo que ese chip exige: más watts, fuente
certificada y cooler.

## Problema

1. `PcBuilder.armar(productos, presupuesto, conGpu, excluir)` no tiene eje de
   potencia. No hay forma de pedir una gama.
2. `PcBuilder.mejorPick` (PcBuilder.java:164) rankea por
   `recommendationService.baseMlScore`, que es `(100 - percentil de PRECIO) +
   bonus`. O sea: **elige lo más barato de su categoría**. Es el mismo defecto
   que ya se arregló en `OutfitBudgetBuilder`, copiado a `pcs/` sin que nadie
   lo viera.
3. El piso de watts son dos constantes supuestas (450 / 650). CLAUDE.md ya las
   marca como pendientes de reemplazar por una estimación por build.
4. `Cooler` tiene 483 filas en catálogo y **no es un slot**.
5. Cuando un slot se vacía por vetos, `PcBuild` sólo devuelve el nombre del
   slot en `sinCompatible`. La UI no puede decir *por qué*.
6. `PcBuilder` resuelve todo con `switch (slot.nombre())` sobre strings y
   `TechSpecsParser` con un `switch (categoria)` de 7 ramas. Agregar gama,
   certificación y cooler a esa forma la duplica.

## Evidencia medida (dev DB, 3435 filas de hardware, 2026-09-19)

| Señal | Cobertura | Consecuencia |
|---|---|---|
| CPU con tier legible (`i3/i5/i7/i9`, `Ryzen 3/5/7/9`, `Ultra`) | 260/313 = 83% | La gama de CPU se puede parsear del nombre |
| GPU con familia+modelo (`RTX/GTX/RX`) | 429/462 = 93% | Idem GPU |
| Fuente con certificación 80+ | 295/347 = 85% | La certificación se puede exigir |
| CPU con `X3D` | 16 | Sufijo de gama alta, independiente del número |
| CPU que dice algo sobre su cooler | **4/313** | Se cae el campo `coolerIncluido` — ver abajo |

Los 53 misses de CPU **no** son CPUs sin tier: son las RAM mal clasificadas
como `CPU` que ya documentó la fase 1, más Athlon / Celeron / Pentium / Xeon
(CPUs reales de gama baja, que el parser tiene que aprender).

Conteos por categoría: Gabinete 625 · Motherboard 528 · Cooler 483 · GPU 462 ·
RAM 387 · Fuente 347 · CPU 313 · Almacenamiento 290.

**Hallazgo de T1 — las dos numeraciones de Radeon.** El writer se abstuvo en
las RX 9000 en vez de mapearlas mal, y reportó la tabla como incompleta en
lugar de rediseñarla por su cuenta. Tenía razón: el defecto estaba en el
plan. Corregido arriba; la implementación se ajusta en T3.

**`coolerIncluido` no existe: el catálogo no lo puede sostener.** El plan
original le daba un campo a `TechSpecs`. Medido en T1: `BOX` aparece en **0**
nombres, `OEM`/`tray` en 3, la forma negativa (`S/coller`, `sin cooler`) en 1,
y **ninguno** afirma que el CPU traiga cooler — **309 de 313 no dicen nada**.
Un campo que siempre vale `false` no es una abstención, es una columna muerta,
y estaba por normalizarse a una columna de base. Se sacó del record y de la
tabla. Consecuencia sobre D4: el slot `cooler` se abre **por gama alta y
nada más**.

## Decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | La gama es **filtro duro**, no preferencia | Pedido explícito del usuario (2026-09-19). Pedir alta y recibir un i5 es la queja original |
| D2 | **La gama es la única regla donde la abstención VETA** | Todas las demás siguen la política de la casa (abstención = sin veto). Acá es al revés a propósito: el usuario pidió un tier, y de un nombre que no se pudo leer **no se puede afirmar** que esté en ese tier. El precio es el 17% de CPUs que quedan fuera, y el mensaje del slot lo dice |
| D3 | **La gama elegida se persiste por usuario**, en tablas normalizadas y detrás de un puerto | Pedido explícito del usuario (2026-09-19): "quiero que se persista para cada usuario... relaciona un userID, tablas normalizadas, hexagonal POO" |
| D3b | Las `TechSpecs` de cada producto **también se persisten**, normalizadas | Pedido explícito (2026-09-19): "normalizá las specs de los productos también, así después al catálogo le podemos meter un filtro". Se arman las bases ahora |
| D3c | **El filtro de `/catalogo` NO entra en este ODD** | Dicho con todas las letras por el usuario: "NO EN ESTE ODD pero vamos armando las bases". Acá se entregan tabla + puerto + write path; el eje de filtrado es otra feature |
| D3d | El armador **sigue leyendo las specs del snapshot**, no de la tabla nueva | La tabla es la base del filtro futuro, no una caché del armador. Meter una lectura de base en el camino de armado agregaría una dependencia y un modo de fallo que hoy no existen, sin resolver nada que se haya pedido |
| D4 | El slot `cooler` se agrega **después** de elegir el CPU, sólo por su gama | Depende del pick, así que la lista de slots deja de ser estática. **Revisado en T1 contra el catálogo:** la condición "y no trae cooler" se cayó — ver abajo |
| D5 | El piso de watts pasa a estimarse por build | Reemplaza las constantes 450/650 que CLAUDE.md marcaba como supuestas |
| D6 | Un slot vacío devuelve **motivo**, no sólo su nombre | "si no hay mother compatible no mostrarlo y aclarar con un mensaje" |
| D7 | Rediseño POO/hexagonal antes de agregar la feature | Pedido explícito. La forma actual (dos `switch` sobre strings) no soporta tres ejes más |
| D8 | `gama` es una **tabla de lookup**, no un TEXT con CHECK | Es el patrón que ya usan `rol`, `categoria` y `marca`: `smallint` identity + `nombre` UNIQUE + CHECK de dominio. Una FK es lo que hace que la gama de un PC guardado no pueda ser un string inventado |
| D9 | La preferencia es **una fila por usuario** (UNIQUE `usuario_id`), no un historial | Es un ajuste de UI que se relee, no un evento. Un historial sería otra tabla y nadie lo pidió |
| D10 | En `producto_tech_specs` la abstención es **NULL**, nunca una fila de lookup | `Gama.DESCONOCIDA` y `Certificacion.NINGUNA` son centinelas de abstención del dominio Java, y un centinela de abstención **no es un valor de FK** — es exactamente lo que rompió el write path del agente con `marca=''` (ver `V21` en `docs/DATABASE.md`). No se siembran filas "DESCONOCIDA" |
| D11 | El write path de las specs es **propio**, no `sp_upsert_run` | Molde de `ml_output`: una tabla hija que se llena después de agregar, por su propio puerto. Tocar la plpgsql del upsert para esto arriesgaría el camino por el que entra todo el catálogo |
| D12 | **El ranking es una escalera de tecnología por slot; el precio es sólo desempate** | Pedido explícito del usuario (2026-09-19): "por tecnología, si DDR5, DDR4, después por velocidad, por almacenamiento". `baseMlScore` es un percentil de PRECIO y salió del armador entero: cualquier lugar donde participe vuelve a meter "lo más barato" por la ventana |
| D13 | **La abstención va última en todo eje de ranking**, nunca primera | Un candidato cuya tecnología no se pudo leer no puede ganarle a uno que la declara. `Gama.DESCONOCIDA` y `TipoAlmacenamiento.DESCONOCIDO` se mapean al último escalón a mano, nunca por ordinal; `0` en MHz/GB y `""` en DDR son el mismo centinela para su eje. `Certificacion.NINGUNA` sí compara por ordinal porque su javadoc la define como el escalón de abajo de la escala real, no como abstención |
| D14 | **La mother rankea por generación DDR** (derivada del socket si el nombre no la dice) | Es el slot que más pesa: se elige primera y sin reglas, así que "la más barata" clavaba DDR4/AM4 y después `ReglaDdr` vetaba toda la RAM DDR5 del catálogo. La derivación socket→DDR se comparte con `ContextoDeArmado`, no se duplica |
| D16 | **`producto_tech_specs` persiste el record entero**, incluidos `velocidad_mhz` y el lookup `tipo_almacenamiento` que T3b-1 agregó después de diseñar el SQL | Decidido con el usuario el 2026-09-20. El SQL de abajo cubría 8 de los 10 campos de `TechSpecs`; dejar afuera justo los dos ejes que un filtro de catálogo querría (NVMe/SSD/HDD, 97% y 90% de cobertura) era una trampa silenciosa. Mismo D10: NULL por abstención, ningún `DESCONOCIDO` sembrado |
| D15 | **Sin precomputar specs en el criterio** — medido, no hace falta | `CriterioPorEjesTecnicos` parsea dentro del comparador. Medido (JIT caliente): `elegir` sobre 625 gabinetes 1,25 ms, sobre 387 RAM 1,8 ms, `parse` 652 ns; los 7 slots ≈ 8 ms por request en el peor caso. Mismo orden que el 0,64 ms que se midió y descartó cachear en outfits (CLAUDE.md, "Medido y descartado") |

## Forma objetivo (POO / hexagonal, todo dentro del área `ar.scraper.pcs`)

```
pcs/
├── Gama.java                  ← enum ordenado BAJA < MEDIA < ALTA + DESCONOCIDA
├── Certificacion.java         ← enum ordenado NINGUNA < WHITE < BRONZE < SILVER < GOLD < PLATINUM < TITANIUM
├── TechSpecs.java             ← + gama, certificacion, velocidadMhz, tipoAlmacenamiento (coolerIncluido se cayó en T1)
├── specs/
│   ├── Tokens.java            ← value object: tokeniza una vez, reemplaza los helpers estáticos
│   ├── LectorDeSpecs.java     ← interface { String categoria(); TechSpecs leer(Tokens); }
│   ├── CpuSpecsReader, MotherboardSpecsReader, RamSpecsReader, FuenteSpecsReader,
│   │   GabineteSpecsReader, GpuSpecsReader, CoolerSpecsReader, AlmacenamientoSpecsReader (T3b)
│   └── TechSpecsParser        ← pasa de switch de 7 ramas a registry categoria → lector
├── reglas/
│   ├── ReglaCompatibilidad.java  ← interface { boolean permite(TechSpecs candidato, ContextoDeArmado); String motivo(); }
│   └── ReglaSocket, ReglaDdr, ReglaFormFactor, ReglaWatts, ReglaCertificacion, ReglaGama
├── SlotDeArmado.java          ← nombre + categoría + sus reglas + su criterio (reemplaza el switch por nombre de slot)
├── ContextoDeArmado.java      ← lo ya elegido: specs de la mother, ddr derivada, gama pedida, watts mínimos
├── CriterioDeSeleccion.java   ← interface; impl CriterioPorEjesTecnicos sobre EjesTecnicos (reemplaza mejorPick, D12)
├── EstimadorDeConsumo.java    ← wattsMinimos(contexto) — reemplaza WATTS_MIN_*
├── PcBuilder.java             ← queda como orquestador: recorre slots, aplica reglas, delega el pick
└── PcBuild.java               ← + mensajes por slot vacío
```

`TechSpecsParser.parse(nombre, categoria)` **conserva su firma pública**: los
49 tests de la fase 1 tienen que pasar sin tocarse (contrato de refactor).

## Escala de gama

| Gama | CPU | GPU |
|---|---|---|
| ALTA | i9 · Ryzen 9 · cualquier `X3D` · Ultra 9 · i7 · Ryzen 7 · Ultra 7 | RTX x090/x080/x070 |
| MEDIA | i5 · Ryzen 5 · Ultra 5 | RTX x060 |
| BAJA | i3 · Ryzen 3 · Ultra 3 · Athlon · Celeron · Pentium | RTX x050 · GTX · ARC |
| DESCONOCIDA | no se pudo leer | no se pudo leer |

⚠️ **Radeon numera de DOS maneras y las dos están vivas en el catálogo**
(medido 2026-09-19). Una sola regla numérica se come una de las dos:

| Serie | Modelos en catálogo | Qué dígito manda | Mapeo |
|---|---|---|---|
| RX 9000 (RDNA 4) | 9050 (4) · 9060 (24) · **9070 (36)** | la **decena**, como Nvidia | 9070 → ALTA · 9060 → MEDIA · 9050 → BAJA |
| RX 5000–7000 | 5500 · 5600 · 5700 · 6500 · 6600 · 6700 · 6900 · 7600 (28 filas) | la **centena** | x900/x800 → ALTA · x700/x600 → MEDIA · x500 y abajo → BAJA |

Son **64 filas contra 28**: el esquema nuevo es el mayoritario. Aplicarle la
regla de la centena a una RX 9070 la manda a MEDIA/BAJA o a DESCONOCIDA, y
con la gama como filtro duro (D1) más la abstención que veta (D2) eso las
saca de **todo** armado sin un solo error. La regla tiene que ramificar por
la serie antes de mirar el tier.

Requisitos derivados de la gama pedida:

| Gama pedida | Piso de watts | Certificación mínima | Slot cooler |
|---|---|---|---|
| alta | 750 sin GPU · 1000 con GPU | GOLD | sí (la condición "si el CPU no lo incluye" se cayó en T1) |
| media | 550 · 750 | BRONZE | no |
| económica | 450 · 650 | NINGUNA | no |

## Escalera de ranking por slot (D12–D14)

Cada `SlotDeArmado` lleva su `CriterioDeSeleccion`; los ejes viven con nombre
en `EjesTecnicos` y `CriterioPorEjesTecnicos` les agrega siempre precio asc →
url asc al final.

| Slot | Orden |
|---|---|
| mother | DDR desc (derivada del socket si el nombre no la dice) → precio asc |
| cpu | gama desc → precio asc |
| ram | DDR desc → MHz desc → GB desc → precio asc |
| gabinete | precio asc — no tiene eje: más grande ≠ mejor |
| fuente | certificación desc → precio asc |
| gpu | gama desc → precio asc |
| almacenamiento | NVMe > SSD > HDD > abstención → GB desc → precio asc |

Cobertura medida en la dev DB para los ejes nuevos (3435 filas, 2026-09-19):
RAM velocidad **377/387 = 97%** (260 dicen `MHz`, 116 traen el número pelado
detrás del `DDRn` — se acepta sólo con whitelist de velocidades DDR reales y
`DDRn` declarado) · Almacenamiento tecnología **260/290 = 90%** · capacidad
**289/290 = 99,7%**. Los 30 de almacenamiento sin tecnología legible **no son
discos**: pendrives y micro SD. Hasta T3b el slot podía elegir un pendrive
como el disco de la PC; con la abstención en el último escalón se hunden
solos, sin veto nuevo.

## Persistencia (`V35__preferencia_armador.sql`)

Normalizada, con FK a `usuario(id)` y a la nueva tabla de lookup. Molde:
`rol` para el lookup, `saved_pcs` para el scoping por dueño.

```sql
-- lookup: el vocabulario de gamas vive en la base, no en un TEXT libre
CREATE TABLE gama (
  id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre text NOT NULL UNIQUE,
  CONSTRAINT chk_gama_nombre_domain
    CHECK (nombre IN ('ECONOMICA','MEDIA','ALTA'))
);
INSERT INTO gama (nombre) VALUES ('ECONOMICA'),('MEDIA'),('ALTA');

-- una fila por usuario (D9)
CREATE TABLE preferencia_armador (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  usuario_id  uuid REFERENCES usuario(id) ON DELETE CASCADE,
  gama_id     smallint NOT NULL REFERENCES gama(id),
  presupuesto double precision,
  con_gpu     boolean NOT NULL DEFAULT false,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
-- Postgres no infiere un índice parcial solo: todo ON CONFLICT tiene que
-- repetir el WHERE (CLAUDE.md, la lección de favoritos en V26).
CREATE UNIQUE INDEX uq_preferencia_armador_usuario
  ON preferencia_armador (usuario_id) WHERE usuario_id IS NOT NULL;
CREATE UNIQUE INDEX uq_preferencia_armador_anonimo
  ON preferencia_armador ((true)) WHERE usuario_id IS NULL;

-- un PC guardado recuerda con qué gama se armó. Nullable: los guardados
-- antes de esta migración no tienen una, y no se puede inventar.
ALTER TABLE saved_pcs ADD COLUMN gama_id smallint REFERENCES gama(id);
```

Rollback (va a `docs/DATABASE.md` con la entrada de `V35`, nunca editando
el `.sql` aplicado):

```sql
ALTER TABLE saved_pcs DROP COLUMN gama_id;
DROP TABLE producto_tech_specs;
DROP TABLE preferencia_armador;
DROP TABLE socket; DROP TABLE ddr; DROP TABLE form_factor;
DROP TABLE tipo_memoria; DROP TABLE certificacion; DROP TABLE tipo_almacenamiento;
DROP TABLE gama;
-- sin DELETE FROM flyway_schema_history: ningún bloque de DATABASE.md lo lleva (ver T4)
```

⚠️ `PostgresTestBase.truncateAll` es una lista a mano: agregar
`preferencia_armador` y `producto_tech_specs`. **Las siete tablas de lookup
NO se truncan** (`gama`, `socket`, `ddr`, `form_factor`, `tipo_memoria`,
`certificacion`, `tipo_almacenamiento`) — son dato semilla de la migración, igual que `rol`.
Truncarlas deja el esquema sin vocabulario y las FK rechazan todo.

### Specs de producto normalizadas (mismas `V35`)

Vocabularios cerrados como lookup, molde `rol`; medidas numéricas como
columna. `productos.url` es la PK y los hijos cuelgan de ella con
`ON DELETE CASCADE`, igual que `producto_badge` y `producto_talle`.

```sql
CREATE TABLE socket (
  id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre text NOT NULL UNIQUE,
  CONSTRAINT chk_socket_nombre_domain
    CHECK (nombre IN ('AM4','AM5','LGA1700','LGA1851')));
CREATE TABLE ddr (... CHECK (nombre IN ('DDR3','DDR4','DDR5')));
CREATE TABLE form_factor (... CHECK (nombre IN ('ITX','MATX','ATX','EATX')));
CREATE TABLE tipo_memoria (... CHECK (nombre IN ('DIMM','SODIMM')));
CREATE TABLE certificacion (
  ... CHECK (nombre IN ('WHITE','BRONZE','SILVER','GOLD','PLATINUM','TITANIUM')));
CREATE TABLE tipo_almacenamiento (... CHECK (nombre IN ('NVME','SSD','HDD')));  -- D16

CREATE TABLE producto_tech_specs (
  url                    text PRIMARY KEY REFERENCES productos(url) ON DELETE CASCADE,
  socket_id              smallint REFERENCES socket(id),
  ddr_id                 smallint REFERENCES ddr(id),
  form_factor_id         smallint REFERENCES form_factor(id),
  tipo_memoria_id        smallint REFERENCES tipo_memoria(id),
  certificacion_id       smallint REFERENCES certificacion(id),
  gama_id                smallint REFERENCES gama(id),
  tipo_almacenamiento_id smallint REFERENCES tipo_almacenamiento(id),   -- D16
  watts                  integer,
  capacidad_gb           integer,
  velocidad_mhz          integer,                                       -- D16
  actualizado_at         timestamptz NOT NULL DEFAULT now(),
  CHECK ((watts IS NULL OR watts > 0) AND (capacidad_gb IS NULL OR capacidad_gb > 0)
     AND (velocidad_mhz IS NULL OR velocidad_mhz > 0)));
```

⚠️ **Toda columna `*_id` es NULLABLE y NULL significa abstención** (D10).
Ninguna tabla de lookup siembra una fila "DESCONOCIDA" / "NINGUNA": el
centinela de abstención vive en el dominio Java, no en la base. `watts` y
`capacidad_gb` son NULL cuando el parser se abstuvo, nunca `0`.

Write path: `TechSpecsPort.upsertSpecs(...)`, llamado después de agregar
sobre los productos de `rubro='tecnologia'`, igual que `MlEnricher` llena
`ml_output`. `sp_upsert_run` no se toca (D11). ⚠️ La llamada vive en
`web/ScraperService`, **no** en `aggregator/`: `pcs/` importa
`aggregator.text.AccentStripper` (carve-out F3b), así que `aggregator → pcs`
cierra un ciclo y `grafoSinCiclos` se pone rojo. Verificado antes de T5.

### Puerto (hexagonal)

`pcs/PreferenciaArmadorPort` — `cargar(Sujeto)` / `guardar(Sujeto, PreferenciaArmador)`.
Lo implementa un `@Repository` **package-private en `db/`**, que es el patrón
de los 13 puertos que ya existen. `pcs/` no nombra `db/` nunca.

## Alcance autorizado

`scraper/src/main/java/ar/scraper/pcs/**`, `db/` (sólo el adapter nuevo),
`db/migration/V35__preferencia_armador.sql`, `web/PcsEndpoints.java` +
`ApiController`, `agent/` (tool `propose_pc`), `docs/openapi.yaml`,
`docs/DATABASE.md`, `frontend/src/components/PcsPanel.jsx` + `api.js`,
`CLAUDE.md`. **Fuera de alcance:** `Product`, el filtro por specs en
`/catalogo` (D3c — se arman las bases, no el eje), `sp_upsert_run`, el
pendiente 3 (guardados al carrusel de favoritos).

## TDD

Modo: **estricto** (CLAUDE.md, "Strict TDD Mode: enabled"). RED observado
antes de implementar, GREEN, REFACTOR. Runner (`CONTRIBUTING.md` TEST-2):

```bash
JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 \
  mvn -f scraper/pom.xml clean test \
  -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
cd frontend && npm test
```

## Tareas

- [x] **T1 — Value objects + parser en lectores.** `Gama`, `Certificacion`,
      `Tokens`, `LectorDeSpecs` + los 7 lectores; `TechSpecsParser` pasa a
      registry conservando su firma. `TechSpecs` suma `gama`,
      `certificacion`, `coolerIncluido`. Los 49 tests de fase 1 pasan sin
      tocarse. Tests nuevos: escala de gama CPU/GPU, certificación, Athlon/
      Celeron/Pentium/Xeon, `X3D`, abstención por campo.
- [x] **T2 — `PcBuilder` en objetos (refactor puro).** `SlotDeArmado`,
      `ContextoDeArmado`, `ReglaCompatibilidad` + las 4 reglas existentes,
      `CriterioDeSeleccion`. Sin cambio de comportamiento:
      `PcBuilderTest` pasa sin tocarse.
- [x] **T3a — La gama.** `gama` en `armar`; `ReglaGama` (D2) y
      `ReglaCertificacion`; `EstimadorDeConsumo` reemplaza las constantes;
      fix de la doble numeración Radeon.
- [x] **T3b-1 — El ranking.** `EjesTecnicos` + `CriterioPorEjesTecnicos`
      reemplazan `CriterioScoreMlPrecioUrl` (D12–D14); un criterio por
      `SlotDeArmado`; `RamSpecsReader` lee MHz; `AlmacenamientoSpecsReader`
      nuevo; `RecommendationService` sale del constructor de `PcBuilder`.
- [x] **T3b-2 — Cooler y mensajes.** Slot `cooler` (D4, sólo gama ALTA,
      insertado después de `cpu`, sin reglas ni eje de ranking); `mensajes`
      por slot vacío (D6) usando `ReglaCompatibilidad.motivo()`, que existía
      desde T2 sin consumidor.
- [x] **T4 — Persistencia.** `V35`, `Gama` como lookup con FK,
      `PreferenciaArmadorPort` + su `@Repository` package-private en `db/`,
      `preferencia_armador` en `truncateAll`, rollback en `docs/DATABASE.md`.
      Tests: dominio del CHECK por SQLState `23514`, UNIQUE por `23505`,
      cascade del borrado de usuario, scoping por dueño.
- [x] **T5 — Specs de producto persistidas.** Las seis tablas de lookup +
      `producto_tech_specs` en la misma `V35`; `TechSpecsPort` en `pcs/` con
      su `@Repository` package-private en `db/`; llamada después de agregar
      sobre `rubro='tecnologia'`. Tests: NULL por abstención campo a campo
      (D10), cascade al borrar el producto, dominio de cada CHECK por
      SQLState `23514`, re-upsert idempotente. **Sin** endpoint ni filtro.
- [ ] **T6 — Borde.** `GET /api/pcs/builder?gama=`, `GET`/`PUT
      /api/pcs/preferencia` (`AUTHENTICATED`), `PcBuildJson` con `mensajes`,
      entradas de `openapi.yaml`, tool `propose_pc` del agente.
- [ ] **T7 — `/pcs`.** Chips de gama en `PcsPanel` (precargados con la
      preferencia guardada), render de `mensajes`, `fetchPcsBuilder` con el
      parámetro.
- [ ] **T8 — Docs.** Sección de CLAUDE.md (fase 6, la escala, D2 como
      excepción a la política de abstención), evidencia medida en este doc.

## Progreso

**T1 hecho** (2026-09-19, sin commitear al escribir esto).

Entregado: `Gama` (con `esConocida()`, porque `DESCONOCIDA` no es un escalón
de la escala y no puede depender del ordinal implícito), `Certificacion`,
`specs/Tokens`, `specs/LectorDeSpecs` + los 7 lectores, y `TechSpecsParser`
convertido en registry conservando firma y paquete. `TechSpecs` suma `gama` y
`certificacion`, y mantiene un constructor de 6 argumentos para que los
llamadores viejos compilen sin tocarse.

Verificación observada:
- `mvn clean test` → **BUILD SUCCESS, Tests run: 2253, Failures: 0, Errors: 0,
  Skipped: 7**, `BackendLayeringArchTest` 20/20.
- `TechSpecsParserTest` (49 tests de fase 1) y `PcBuilderTest`: **sin tocar**,
  confirmado por `git status` vacío para esos dos archivos. Contrato de
  refactor cumplido.

**T2 hecho** (2026-09-19, sin commitear al escribir esto).

Entregado: `pcs/ContextoDeArmado` (inmutable, `inicial(wattsMin)` +
`conMother(TechSpecs)`, con la derivación de DDR por socket movida desde
`PcBuilder`), `pcs/reglas/ReglaCompatibilidad` (interface con `permite` +
`motivo()`, este último sin consumidor todavío — lo usa T3), las cuatro
reglas (`ReglaSocket`, `ReglaDdr`, `ReglaFormFactor` con `ORDEN_FORM_FACTOR`,
`ReglaWatts`), `pcs/SlotDeArmado` (record `nombre, categoria, reglas`,
reemplaza el `record Slot` + el `switch` por nombre — mother/gpu/almacenamiento
llevan `List.of()`, no un `default -> true`), `pcs/CriterioDeSeleccion`
(interface) + `pcs/CriterioScoreMlPrecioUrl` (la implementación de hoy,
extraída tal cual: `-baseMlScore` desc, precio asc, url asc). `PcBuilder`
queda como orquestador: arma los slots una vez con sus reglas ya cableadas,
filtra con `slot.reglas().stream().allMatch(...)`, delega el pick al
criterio. Firma pública sin cambios.

Verificación observada:
- `mvn clean test` → **BUILD SUCCESS, Tests run: 2284, Failures: 0, Errors: 0,
  Skipped: 7** (2253 + 31 tests nuevos: 8 de `ContextoDeArmado`, 5+5+6+4 de
  las cuatro reglas, 3 de `CriterioScoreMlPrecioUrl`), `BackendLayeringArchTest`
  20/20 — `grafoSinCiclos` sigue verde: `pcs` y `pcs.reglas` caen en la misma
  slice (`ar.scraper.(*)..` matchea por el primer subpaquete).
- RED previo observado por fallo de compilación: los tests nuevos no
  compilaban contra clases inexistentes (`cannot find symbol: class
  ContextoDeArmado/ReglaSocket/ReglaDdr/ReglaWatts`).
- `PcBuilderTest` y `TechSpecsParserTest`: **sin tocar**, confirmado por
  `git status --short` vacío para los dos. Contrato de refactor cumplido.

Desvío observado, fuera de mi alcance de T2: `git status` mostraba
`odd/tasks/pc-builder-gama.md` modificado ya al empezar esta tarea (hallazgo
de T1 sobre la doble numeración de Radeon, sin commitear) pese a que se me
indicó árbol limpio salvo esta tarea. No lo toqué más allá de tildar T2 y
agregar esta entrada.
- 60 tests nuevos.

Corrección aplicada sobre lo que entregó el writer: `coolerIncluido` se sacó
del record (ver la medición arriba). El writer lo había implementado como un
`return false` constante con el javadoc admitiéndolo; la medición dijo que el
campo no tiene sustento y que el plan estaba mal, no el código.

**T3a — mitad entregada, BLOQUEADA por un conflicto con un test heredado
protegido** (2026-09-19, sin commitear).

Entregado y verde de forma aislada: `GpuSpecsReader` corregido (Radeon
ramifica por serie — RX 9000 por decena, RX 5000-7000 por centena — con
`rx9070/9060/9050` reales como test), `pcs/reglas/ReglaGama` (D2: única
regla donde la abstención veta, comentario explícito), `pcs/reglas/
ReglaCertificacion` (política NORMAL: `NINGUNA` no veta, comentario
explícito), `pcs/EstimadorDeConsumo` (tabla de watts/certificación por
gama, `null`/`BAJA` siguen dando 450/650 sin certificación — igual que
antes), `ContextoDeArmado` con `gamaPedida`/`certificacionMinima` (`null`
= "no se pidió gama", distinto de `Gama.DESCONOCIDA`), y `PcBuilder` con
la sobrecarga de 5 argumentos (`armar(..., Gama gamaPedida)`) — la de 4
argumentos delega con `null` y sigue siendo la que llaman `PcsEndpoints` y
`propose_pc`, sin tocarlas.

Evidencia medida (aislada, antes de la corrida completa):
- RED observado: `GpuSpecsReaderTest` (3 fallos, RX 9070/9060/9050 daban
  DESCONOCIDA) y fallos de compilación para `ReglaGama`, `ReglaCertificacion`,
  `EstimadorDeConsumo`, `ContextoDeArmado.inicial(int,Gama,Certificacion)`/
  `gamaPedida()`/`certificacionMinima()`, y la sobrecarga de 5 args de
  `PcBuilder.armar`.
- GREEN observado: `GpuSpecsReaderTest` 18/18, `ReglaGamaTest` 5/5,
  `ReglaCertificacionTest` 6/6, `EstimadorDeConsumoTest` 9/9,
  `ContextoDeArmadoTest` 11/11, `PcBuilderGamaTest` (nuevo, integración)
  11/11 — todos verdes en corridas filtradas.

**Bloqueo al correr la suite completa** (`mvn clean test`): **Tests run:
2320, Failures: 1, Errors: 0, Skipped: 7** — `BUILD FAILURE`.

```
ar.scraper.pcs.TechSpecsParserTest.gpuAbstainsEntirelyInPhase1
expected: TechSpecs[... gama=DESCONOCIDA, certificacion=NINGUNA]
 but was: TechSpecs[... gama=MEDIA, certificacion=NINGUNA]
```

`TechSpecsParserTest.java` es uno de los dos archivos que la tarea prohíbe
tocar. No lo edité. Razón por la que creo que cambió: ese test parsea
`"Placa de Video Gigabyte Radeon RX 9060 XT 8GB GDDR6 GAMING OC"` bajo la
sección `// phase-1 abstains entirely: GPU / Cooler / Monitor /
Almacenamiento` y afirma `TechSpecs.EMPTY` completo. Pero `GpuSpecsReader`
—entregado en T1, antes de esta tarea— ya llenaba `gama` para GPU (una RTX
5090 da `ALTA`, no `EMPTY`); ese test sólo pasaba por **coincidencia**,
porque el fixture elegido (RX 9060) caía exactamente en el defecto de
numeración Radeon que T1 reportó y que esta misma tarea (T3a, punto 1) me
pidió corregir (9060 → MEDIA, mandado por la tabla del ODD). Al corregir esa
numeración, el mismo nombre deja de dar `DESCONOCIDA` y pasa a dar `MEDIA`,
así que ya no es igual a `TechSpecs.EMPTY`.

Es exactamente el caso que la tarea pide frenar y reportar en vez de
resolver por mi cuenta (no edité el test, no rediseñé la regla de Radeon
para evitar el choque). Mecánica: si `GpuSpecsReader` vuelve a dar
`DESCONOCIDA` para RX 9060, se reabre el defecto que T3a pide cerrar; si el
test se edita, se toca un archivo explícitamente prohibido. Ninguna de las
dos la puedo decidir sola.

Estado del árbol: sin commitear, `PcBuilderTest.java` y
`TechSpecsParserTest.java` confirmados sin modificar (`git status --short`).
Entregables 2–5 completos y verdes de forma aislada; entregable 1 (el fix
de Radeon) es correcto según la tabla del ODD pero deja el árbol en rojo
contra un test heredado protegido. **T3a no se marca hecha** hasta resolver
este punto.


### T3a — el test heredado que pasaba por casualidad

`TechSpecsParserTest.gpuAbstainsEntirelyInPhase1` afirmaba
`TechSpecs.EMPTY` para una GPU. Desde T1 eso es falso: `GpuSpecsReader`
llena `gama`. Seguía en verde porque su fixture es una **RX 9060**, que caía
en `DESCONOCIDA` por el defecto de numeración Radeon — el mismo que T3a
corrigió. O sea que `ba876d0` y `7c01f16` se commitearon con ese test verde
mientras el comportamiento que afirmaba ya había cambiado: un test puede
pasar por la razón equivocada y no hay nada que grepear para encontrarlo.

Lo reescribí como `gpuSoloLlenaGama`, que afirma lo que hoy es cierto y
conserva su intención real (abstención campo por campo). Es la excepción
estrecha al contrato de refactor: la premisa del test caducó por diseño, no
porque se haya roto comportamiento.

El writer de T3a **paró y reportó el choque en vez de editar el test**, que
es la conducta que la tarea pedía. Si lo hubiera editado de paso, el hallazgo
—que el verde de dos commits anteriores era casualidad— se perdía.

### T3b-1 — el ranking (2026-09-20, sin commitear al escribir esto)

Entregado: `TipoAlmacenamiento` (molde `Gama`, con `esConocido()`),
`TechSpecs` + `velocidadMhz` + `tipoAlmacenamiento` (conserva constructores
de 6 y 8 argumentos para que los llamadores viejos compilen sin tocarse),
`RamSpecsReader` con velocidad (tres formas medidas; la pelada exige
whitelist + `DDRn`), `specs/AlmacenamientoSpecsReader` registrado en
`TechSpecsParser`, `EjesTecnicos` + `CriterioPorEjesTecnicos`, `SlotDeArmado`
con `criterio`, `ContextoDeArmado.derivarMotherDdr` package-private para
compartirla. Borrados `CriterioScoreMlPrecioUrl` y su test. `PcBuilder()`
sin argumentos; actualizados `ApiController` y `ProposePcTool`.

**Las aserciones tocadas en `PcBuilderTest`, exactamente las cinco
acordadas** — tres pasaban verdes por un mecanismo que dejó de existir (el
caso de `gpuSoloLlenaGama`, otra vez), dos conservan la aserción:

| Test | Antes → después | Por qué |
|---|---|---|
| `rankingPrefiereMejorScoreMl` → `rankingPrefiereMasCapacidadEnRam` | mismo valor esperado (`ram-cara`) | ganaba por `scoreP=10`; ahora por 32GB > 16GB. Sin `scoreP` en el fixture para que no pueda pasar por el camino viejo |
| `rankingEmpataPorScoreYDesempataPorPrecio` → `rankingEmpataEnTecnologiaYDesempataPorPrecio` | mismo valor (`barata`) | empate hasta precio |
| `rankingEmpataPorScoreYPrecioYDesempataPorUrl` → `rankingEmpataTodoYDesempataPorUrl` | mismo valor (`ram-a`) | `url` sigue último |
| `presupuestoDescartaElCandidatoMejorRankeadoSiNoAlcanza` | aserción intacta, comentario reescrito, `scoreP` fuera | el mejor rankeado sigue siendo el que no entra |
| `presupuestoCaeAlMasBaratoCuandoNadaAlcanza` | ídem | ídem |

El helper `producto(..., int scoreP)` y el campo `RecommendationService` del
test se borraron: sin consumidor.

**Segundo test heredado que caducó por diseño**:
`TechSpecsParserTest.almacenamientoAbstainsEntirelyInPhase1` afirmaba
`TechSpecs.EMPTY` para un `Disco SSD Kingston NV2 480GB M.2 NVMe`; al darle
lector a la categoría pasa a `NVME` + 480 GB. Reescrito como
`almacenamientoSoloLlenaTipoYCapacidad` (abstención campo por campo, molde
`gpuSoloLlenaGama`). A diferencia del anterior, éste era previsible desde el
spec y no estaba nombrado — el STOP del writer lo frenó igual.

Verificación observada (`mvn clean test`, 2026-09-20): **BUILD SUCCESS,
Tests run: 2363, Failures: 0, Errors: 0, Skipped: 7**, cero `ERROR]` en la
salida, `BackendLayeringArchTest` 20/20. RED previo observado por el writer:
fallos de compilación contra `TipoAlmacenamiento` / `AlmacenamientoSpecsReader`
/ `EjesTecnicos` inexistentes, y `almacenamientoAbstainsEntirelyInPhase1` en
rojo (`tipoAlmacenamiento=NVME, capacidadGb=480` contra `EMPTY`) antes de
reescribirlo.

### T3b-2 — cooler y mensajes (2026-09-20, sin commitear al escribir esto)

Entregado: `SLOT_COOLER` (categoría `Cooler`, `List.of()` de reglas,
`EjesTecnicos.COOLER` sin ejes) insertado justo después de `cpu` cuando
`gamaPedida == ALTA` y **sólo** entonces — con `null`/`BAJA`/`MEDIA`/
`DESCONOCIDA` no aparece en `picks`, `sinStock`, `sinCompatible` ni
`mensajes`. Depende de la gama pedida, no del pick de CPU (D4 revisado en
T1). El cooler **no tiene eje de ranking ni regla de socket**: no hay señal
medida para leer de un cooler (AIO vs aire, altura, TDP, socket) — es
pendiente que necesita datos, no código.

`PcBuild` suma `Map<String,String> mensajes`, una entrada **sólo** por slot
vacío. `sinStock` → `"no hay productos en la categoría <categoria>"`;
`sinCompatible` → los `motivo()` distintos de las reglas que vetaron al
menos un candidato, **en el orden de las reglas del slot**, unidos con
`" · "`. Para saber cuál vetó, `esCompatible` (un `allMatch` que
cortocircuitaba) pasó a `primeraQueVeta` → `Optional<ReglaCompatibilidad>`.
Constructor de conveniencia de 5 argumentos conservado; `PcBuildJsonTest`
compila sin tocarse. Serializar `mensajes` al JSON es T6.

Corrección mía sobre el writer: el índice de inserción del cooler estaba
hardcodeado (`slots.add(2, ...)`); pasó a `indiceDe(slots, "cpu") + 1`.

`PcBuilderTest`: **sin tocar** (29 aserciones intactas — sin gama no hay
cooler y `mensajes` es aditivo). `PcBuilderGamaTest` suma 7 tests: cooler
abierto con ALTA y cerrado con MEDIA/BAJA/null; cerrado con DESCONOCIDA;
posición después de `cpu`; cooler `sinStock` con su mensaje; `sinCompatible`
con un motivo; con dos motivos distintos en orden de regla (una fuente cae
por watts y otra por certificación); slot con pick sin mensaje.

Verificación observada (`mvn clean test`, 2026-09-20, con la corrección del
índice adentro): **BUILD SUCCESS, Tests run: 2370, Failures: 0, Errors: 0,
Skipped: 7** (2363 + 7), cero `ERROR]`, `BackendLayeringArchTest` 20/20.
RED previo observado por el writer: 6 × `cannot find symbol: method
mensajes()` en `PcBuilderGamaTest` contra el `PcBuild` anterior.
`PcBuilderTest` y `TechSpecsParserTest` sin tocar (`git status`).

### T4 — persistencia de la preferencia (2026-09-20)

Entregado: `V35__preferencia_armador.sql` (`gama` lookup sembrado con CHECK,
`preferencia_armador` con los dos índices parciales, `saved_pcs.gama_id`
nullable), `pcs/PreferenciaArmador` (record) + `pcs/PreferenciaArmadorPort`
(`cargar(UUID)` / `guardar(UUID, PreferenciaArmador)` — toma `UUID` como los
otros 13 puertos; `Sujeto` es el resolver estático que lo produce, no un tipo
que viaje), `db/PreferenciaArmadorRepository` package-private con el upsert
repitiendo el `WHERE usuario_id IS NOT NULL` del índice. El mapeo Java↔base
vive en el repository: `Gama.BAJA ↔ 'ECONOMICA'`; `Gama.DESCONOCIDA` tira
`IllegalArgumentException` antes de tocar la base (D10). `preferencia_armador`
en `truncateAll`; `gama` no, como `rol`.

Dos cosas que el ODD no tenía y hubo que hacer: (1) el rollback de `V26` en
`docs/DATABASE.md` suelta por nombre cada FK entrante a `usuario` y
`V26RollbackRoundTripTest` lo ejecuta contra el esquema completo — sin la
línea `fk_preferencia_armador_usuario` ese test se ponía rojo (el mismo caso
que `saved_pcs` en `V34`); (2) el `DELETE FROM flyway_schema_history` del
rollback diseñado arriba no existe en ningún bloque del doc, así que el de
`V35` tampoco lo lleva. Sin consumidor todavía: el cableado a `PcsEndpoints`
y escribir `saved_pcs.gama_id` desde `guardarPc` son T6.

Verificación observada (`mvn clean test`, 2026-09-20): **BUILD SUCCESS,
Tests run: 2384, Failures: 0, Errors: 0, Skipped: 7** (2370 + 14: 5 de
`PreferenciaArmadorSchemaTest` por SQLState `23514`/`23505`/`23503` + cascade,
7 de `PreferenciaArmadorRepositoryTest`, 2 de `V35RollbackRoundTripTest`),
Postgres real vía Testcontainers, cero `ERROR]`, `BackendLayeringArchTest`
20/20, `V26RollbackRoundTripTest` 3/3. RED previo observado por el writer:
`cannot find symbol: class PreferenciaArmadorRepository`.

Siguiente: T5 (specs de producto persistidas, misma `V35`).

### T5 — specs de producto persistidas (2026-09-20)

Entregado, misma `V35` (todavía sin aplicar en ningún entorno, sigue
editable): seis lookups más (`socket`, `ddr`, `form_factor`, `tipo_memoria`,
`certificacion`, `tipo_almacenamiento`) y `producto_tech_specs`
(`url` PK → `productos(url)` ON DELETE CASCADE, todo `*_id` NULLABLE = NULL
= abstención (D10), CHECK `chk_producto_tech_specs_enteros_positivos` para
que `watts`/`capacidad_gb`/`velocidad_mhz` nunca sean `0`); `pcs/TechSpecsPort`
(`upsertSpecs(List<SpecsDeProducto>)`, `SpecsDeProducto(url, TechSpecs)`) +
`db/TechSpecsRepository` package-private (resuelve cada lookup por nombre con
un subselect en el mismo INSERT, molde `PreferenciaArmadorRepository`,
`executeBatch`); `pcs/TechSpecsIndexer` (`@Component`, parsea sólo
`esTech()` con url no vacía, batch vacío no llama al puerto, una fila
`EMPTY` sí se escribe); hook en `web/ScraperService` (nuevo parámetro de
constructor, llamada envuelta en try/catch WARN inmediatamente después de
`aggregator.agregar(...)`, nunca en `aggregator/` para no crear el ciclo
`aggregator ↔ pcs` que `AGREGATOR_LEAVES_F3B` ya deja abierto en un sentido).

Extraído `db/GamaMapeo` (`nombreDeGama`/`gamaDeNombre`) desde
`PreferenciaArmadorRepository` para que `TechSpecsRepository` no duplicara el
mapeo `Gama.BAJA ↔ 'ECONOMICA'` (CODE-6) — `PreferenciaArmadorRepository`
sigue lanzando `IllegalArgumentException` ante `Gama.DESCONOCIDA` (su columna
es `NOT NULL`); `TechSpecsRepository` en cambio la escribe como NULL (su
columna es NULLABLE), sin pasar por `GamaMapeo.nombreDeGama` en ese caso.

`PostgresTestBase.truncateAll` suma `producto_tech_specs`; los seis lookups
nuevos quedan afuera, mismo motivo que `gama`/`rol`/`indice`.
`docs/DATABASE.md`: sección nueva para `producto_tech_specs` + sus lookups,
tabla de arriba, índice de migraciones, y el bloque de rollback de `V35`
creció a los ocho `DROP TABLE`/`ALTER TABLE` en el orden que exige la FK
(`producto_tech_specs` antes que cualquiera de sus siete referencias; `gama`
al final). `V35RollbackRoundTripTest` ahora siembra también una fila de
specs y afirma las siete tablas nuevas caídas, además de las dos de T4.

Verificación observada (`mvn clean test`, 2026-09-20): **BUILD SUCCESS,
Tests run: 2412, Failures: 0, Errors: 0, Skipped: 7** (2384 + 28: 17 de
`ProductoTechSpecsSchemaTest`, 5 de `TechSpecsRepositoryTest`, 6 de
`TechSpecsIndexerTest`; `V35RollbackRoundTripTest` sigue en 2 —mismo test,
aserciones ampliadas—, `PreferenciaArmadorSchemaTest`/
`PreferenciaArmadorRepositoryTest` sin cambio en conteo), cero `ERROR]`,
`BackendLayeringArchTest` 20/20, `SpringWiringTest` 6/6 (confirma que el
nuevo parámetro de `ScraperService` resuelve como bean real). Postgres real
vía Testcontainers. RED previo observado: compilación fallaba contra
`TechSpecsPort`/`TechSpecsRepository`/`TechSpecsIndexer` inexistentes y
contra la firma vieja de 7 argumentos de `new ScraperService(...)` en los 9
call sites de los 5 tests que lo construyen a mano.
`PcBuilderTest.java`/`TechSpecsParserTest.java` sin tocar (`git status`).

Re-verificado por el orquestador tras dos retoques (comentario de
`certificacion` en la migración acortado, `Collectors.toList()` →
`toList()` en el indexer): `mvn clean test` exit 0, **2412 / 0 / 0 / 7**,
cero `ERROR]`, `BackendLayeringArchTest` 20/20, `SpringWiringTest` 6/6.

Siguiente: T6 (borde — endpoints, `PcBuildJson.mensajes`, `openapi.yaml`,
tool `propose_pc`).
