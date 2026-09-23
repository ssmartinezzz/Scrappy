# Set de evaluación de categorías (300 productos)

Primer set de etiquetas de categoría **independiente del clasificador por
keywords**. Generado 2026-09-23 contra la dev DB (16.052 productos activos).

Plan, decisiones y porqué: [`odd/tasks/eval-set-categorias.md`](../../odd/tasks/eval-set-categorias.md).

## Qué es, y qué NO es

**No es ground truth.** Son dos opiniones sobre los mismos 300 productos:
`categoria_actual` (la del clasificador por keywords, que es lo que hoy vive en
`productos.categoria`) y `categoria_modelo` (un LLM local etiquetando a ciegas).
Ninguna de las dos está validada por un humano.

Por eso el **77% de acuerdo no es 77% de accuracy de nadie**. Lo que sirve es el
23% en desacuerdo: cada caso es un bug del clasificador **o** un error del
modelo, y separarlos requiere que alguien adjudique. Es una cola de triage con
precisión medida, no una nota.

## Los archivos

| | |
|---|---|
| `muestra-300.tsv` | La muestra. Estratificada por rubro, determinística: `row_number() OVER (PARTITION BY rubro ORDER BY md5(url))`, sin seed. Se regenera idéntica mientras no cambien las filas activas |
| `categorias-eval-300.jsonl` | El set etiquetado, una línea por producto |
| `label_eval_set.py` | El etiquetador. `S=<dir> python3 label_eval_set.py`, con `canon-db.txt` y `muestra.tsv` en `$S` |

Campos del JSONL: `url`, `nombre`, `rubro`, `categoria_actual`,
`categoria_modelo`, `acuerdo`, `reintentado`, `crudo` (la salida sin parsear,
sólo cuando hubo reintento o el modelo se fue del canon).

⚠️ **El formato es propio, no el de KEV.** El plan original decía escribirlo en
la forma que KEV pide, pero el esquema exacto de su JSONL no se verificó — sólo
la descripción en prosa de su README. Convertirlo es un paso aparte, cuando y si
se decide usarlo; inventar los nombres de campo ahora sería un archivo que dice
ser algo que no es.

## Cómo se etiquetó

- Modelo: `qwen3:14b` vía Ollama, `temperature 0`, `think: false`.
  300 productos en **117 s** (~0,3 s cada uno; la primera llamada es la carga).
- **A ciegas** (D3): el modelo nunca ve `categoria_actual`. Si la viera anclaría
  en ella y el acuerdo no mediría nada.
- Vocabulario: las 105 filas de la tabla `categoria` — verificado idéntico a
  `CategoryGroups.canonicalCategories()`, sin drift.
- Mismo criterio que el system prompt del agente: *la categoría es la
  CLASIFICADA, y el nombre puede no contener esa palabra*.
- Toda etiqueta cae dentro del canon: **0 salidas inválidas** tras un reintento
  más estricto.

**No se usó `POST /api/agent/apply`.** Setea `bloqueado_por`
(`ProductRepository.java:660`), el lock de `V3`, que vuelve la fila inmune a
reclasificación en todos los write paths — congelaría exactamente las filas que
el set existe para volver a testear. Y escribiría juicio de un LLM en la única
columna cuyo contrato dice "human-confirmed".

## Acuerdo medido

| Rubro | Acuerdo |
|---|---|
| indumentaria | 76/90 = 84,4% |
| tecnologia | 96/120 = 80,0% |
| oficina | 30/40 = 75,0% |
| suplementos | 29/50 = 58,0% |
| **total** | **231/300 = 77,0%** |

La muestra está estratificada, así que el total **no** es una estimación del
catálogo: sobre-representa oficina (40 de 147 filas) y suplementos (50 de 889).
Para un número global habría que ponderar por rubro.

## Qué encontró (adjudicado por inspección, no a ciegas)

De los 69 desacuerdos se revisaron a mano cuatro grupos. Van en las dos
direcciones, que es justamente por qué ninguna etiqueta es ground truth:

- **El clasificador se equivoca — `Conjunto` se come hardware.** 7 de los 120
  productos de tecnologia de la muestra. Sobre el catálogo entero son **348
  filas de tecnologia en una categoría de ropa**, más que las 212 de
  indumentaria legítimas. Detalle abajo.
- **El clasificador se equivoca — `Silla`** atrapa `Ergonomic Wrist Rest` y
  `Reposapiés Inteligente`, que no son sillas.
- **El modelo se equivoca — `Almacenamiento`**: mandó cuatro SSD M.2 NVMe
  (`HD SSD 1TB PATRIOT P410 M.2 NVME GEN4`) a `Otros`. Son discos, obviamente.
- **El modelo se equivoca — `Red`**: mandó un `Access Point Ubiquiti
  Nanostation` y un `Router Wireless Mercusys` a `Otros`.

El resto es granularidad: `Ojotas`→`Sandalia`, `Zapatilla Skate`→`Zapatilla`,
`Proteína Isolada`→`Proteína`. El modelo tiende al padre; no son bugs.
