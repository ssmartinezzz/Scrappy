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

## Forma objetivo (POO / hexagonal, todo dentro del área `ar.scraper.pcs`)

```
pcs/
├── Gama.java                  ← enum ordenado BAJA < MEDIA < ALTA + DESCONOCIDA
├── Certificacion.java         ← enum ordenado NINGUNA < WHITE < BRONZE < SILVER < GOLD < PLATINUM < TITANIUM
├── TechSpecs.java             ← + gama, certificacion, coolerIncluido
├── specs/
│   ├── Tokens.java            ← value object: tokeniza una vez, reemplaza los helpers estáticos
│   ├── LectorDeSpecs.java     ← interface { String categoria(); TechSpecs leer(Tokens); }
│   ├── CpuSpecsReader, MotherboardSpecsReader, RamSpecsReader,
│   │   FuenteSpecsReader, GabineteSpecsReader, GpuSpecsReader, CoolerSpecsReader
│   └── TechSpecsParser        ← pasa de switch de 7 ramas a registry categoria → lector
├── reglas/
│   ├── ReglaCompatibilidad.java  ← interface { boolean permite(TechSpecs candidato, ContextoDeArmado); String motivo(); }
│   └── ReglaSocket, ReglaDdr, ReglaFormFactor, ReglaWatts, ReglaCertificacion, ReglaGama
├── SlotDeArmado.java          ← nombre + categoría + sus reglas (reemplaza el switch por nombre de slot)
├── ContextoDeArmado.java      ← lo ya elegido: specs de la mother, ddr derivada, gama pedida, watts mínimos
├── CriterioDeSeleccion.java   ← interface; impl PorGamaLuegoPrecio (reemplaza mejorPick)
├── EstimadorDeConsumo.java    ← wattsMinimos(contexto) — reemplaza WATTS_MIN_*
├── PcBuilder.java             ← queda como orquestador: recorre slots, aplica reglas, delega el pick
└── PcBuild.java               ← + mensajes por slot vacío
```

`TechSpecsParser.parse(nombre, categoria)` **conserva su firma pública**: los
49 tests de la fase 1 tienen que pasar sin tocarse (contrato de refactor).

## Escala de gama

| Gama | CPU | GPU |
|---|---|---|
| ALTA | i9 · Ryzen 9 · cualquier `X3D` · Ultra 9 · i7 · Ryzen 7 · Ultra 7 | RTX x090/x080/x070 · RX x900/x800 |
| MEDIA | i5 · Ryzen 5 · Ultra 5 | RTX x060 · RX x700/x600 |
| BAJA | i3 · Ryzen 3 · Ultra 3 · Athlon · Celeron · Pentium | RTX x050 · GTX · ARC · RX x500 y abajo |
| DESCONOCIDA | no se pudo leer | no se pudo leer |

Requisitos derivados de la gama pedida:

| Gama pedida | Piso de watts | Certificación mínima | Slot cooler |
|---|---|---|---|
| alta | 750 sin GPU · 1000 con GPU | GOLD | sí, si el CPU no lo incluye |
| media | 550 · 750 | BRONZE | no |
| económica | 450 · 650 | NINGUNA | no |

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
DROP TABLE tipo_memoria; DROP TABLE certificacion; DROP TABLE gama;
DELETE FROM flyway_schema_history WHERE version = '35';
```

⚠️ `PostgresTestBase.truncateAll` es una lista a mano: agregar
`preferencia_armador` y `producto_tech_specs`. **Las seis tablas de lookup
NO se truncan** (`gama`, `socket`, `ddr`, `form_factor`, `tipo_memoria`,
`certificacion`) — son dato semilla de la migración, igual que `rol`.
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

CREATE TABLE producto_tech_specs (
  url              text PRIMARY KEY REFERENCES productos(url) ON DELETE CASCADE,
  socket_id        smallint REFERENCES socket(id),
  ddr_id           smallint REFERENCES ddr(id),
  form_factor_id   smallint REFERENCES form_factor(id),
  tipo_memoria_id  smallint REFERENCES tipo_memoria(id),
  certificacion_id smallint REFERENCES certificacion(id),
  gama_id          smallint REFERENCES gama(id),
  watts            integer,
  capacidad_gb     integer,
  actualizado_at   timestamptz NOT NULL DEFAULT now());
```

⚠️ **Toda columna `*_id` es NULLABLE y NULL significa abstención** (D10).
Ninguna tabla de lookup siembra una fila "DESCONOCIDA" / "NINGUNA": el
centinela de abstención vive en el dominio Java, no en la base. `watts` y
`capacidad_gb` son NULL cuando el parser se abstuvo, nunca `0`.

Write path: `TechSpecsPort.upsertSpecs(...)`, llamado después de agregar
sobre los productos de `rubro='tecnologia'`, igual que `MlEnricher` llena
`ml_output`. `sp_upsert_run` no se toca (D11).

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
- [ ] **T2 — `PcBuilder` en objetos (refactor puro).** `SlotDeArmado`,
      `ContextoDeArmado`, `ReglaCompatibilidad` + las 4 reglas existentes,
      `CriterioDeSeleccion`. Sin cambio de comportamiento:
      `PcBuilderTest` pasa sin tocarse.
- [ ] **T3 — La feature.** `gama` en `armar`; `ReglaGama` (D2) y
      `ReglaCertificacion`; `EstimadorDeConsumo` reemplaza las constantes;
      slot `cooler` dinámico (D4); `PorGamaLuegoPrecio` reemplaza el ranking
      por `baseMlScore`; `mensajes` por slot vacío (D6).
- [ ] **T4 — Persistencia.** `V35`, `Gama` como lookup con FK,
      `PreferenciaArmadorPort` + su `@Repository` package-private en `db/`,
      `preferencia_armador` en `truncateAll`, rollback en `docs/DATABASE.md`.
      Tests: dominio del CHECK por SQLState `23514`, UNIQUE por `23505`,
      cascade del borrado de usuario, scoping por dueño.
- [ ] **T5 — Specs de producto persistidas.** Las seis tablas de lookup +
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
- 60 tests nuevos.

Corrección aplicada sobre lo que entregó el writer: `coolerIncluido` se sacó
del record (ver la medición arriba). El writer lo había implementado como un
`return false` constante con el javadoc admitiéndolo; la medición dijo que el
campo no tiene sustento y que el plan estaba mal, no el código.
