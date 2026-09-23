# Set de evaluación de categorías — etiquetado con el criterio del agente

## Objetivo

Producir el primer set de etiquetas de categoría **independiente del
clasificador por keywords**, para poder medir algo. Hoy no existe ninguna:
`bloqueado_por IS NOT NULL` da **0 filas** sobre 16.052 productos activos, y
`agent_reclassify_audit` también 0 (medido 2026-09-23).

## Problema

`ml_train.py:load_dataset()` entrena con `SELECT nombre, categoria FROM
productos`, y esa columna la escribe `CategoryClassifier` al scrapear. El modelo
de texto aprende a imitar las reglas de keywords, bugs incluidos — y esos bugs
están medidos (146/470 filas de `Cooler` eran CPUs; 83 PCs enteras compitiendo
como componentes; `"ram "` matcheando Monogram; 453 teclados en `Otros`).

Sin un set independiente no se puede responder "¿el clasificador acierta?",
sólo "¿el modelo reproduce al clasificador?". Y esa es la pregunta que bloquea
cualquier decisión sobre fine-tuning (KEV u otro).

## Alcance autorizado

Etiquetar un set de evaluación con el criterio del agente. **Sin** tocar la
clasificación de ningún producto en la base.

## Decisiones

**D1 — Las etiquetas van a un ARCHIVO, no por `POST /api/agent/apply`.** Dos
razones, ambas verificadas en el código:

1. `apply` setea `bloqueado_por`/`bloqueado_at` (`ProductRepository.java:660`),
   que es el lock de `V3`: una fila bloqueada queda inmune a reclasificación en
   **todos** los write paths (los `CASE` de `sp_upsert_run` y el
   `AND bloqueado_por IS NULL` del lado Java). Etiquetar el set por ahí
   **congelaría exactamente las filas que queremos volver a testear** — el set
   quedaría incapaz de medir cualquier cambio futuro del clasificador, que es
   para lo único que existe. Es autodestructivo.
2. Escribiría juicio de un LLM en la única columna del esquema cuyo contrato
   dice "human-confirmed" (header de `V3__manual_classification_lock.sql`).

**D2 — Mismo vocabulario y mismo encuadre que el agente.** El vocabulario sale
de la tabla `categoria` (105 filas, la que las FK aceptan de verdad), y el
prompt reusa el criterio del system prompt de `CatalogAgentService`: *la
categoría es la CLASIFICADA, y el nombre puede no contener esa palabra*. Eso es
lo que hace comparables estas etiquetas con lo que el agente propondría.

**D3 — El modelo etiqueta A CIEGAS.** No ve `productos.categoria`. Si la viera,
ancla en ella y el acuerdo medido no significaría nada.

**D4 — Muestra estratificada por rubro**, no aleatoria uniforme: indumentaria
(8.114) y tecnologia (6.902) ahogarían a suplementos (889) y oficina (147), y
los bugs documentados viven casi todos en tecnologia.

**D5 — ~~El archivo se escribe en la forma JSONL que KEV pide~~ → revisada al
ejecutar.** El esquema exacto del JSONL de KEV no se verificó: sólo tengo la
descripción en prosa de su README ("una request por línea, label en cada
pregunta"). Escribir nombres de campo adivinados daría un archivo que dice ser
KEV-ready sin serlo. El set se escribe en un formato propio y documentado;
convertirlo es un paso aparte, cuando y si se decide usar KEV.

## Tareas

- [x] T1 — Canon verificado: `diff` de los dos da vacío, **105 categorías
      idénticas**. Sin drift entre la tabla y el Java
- [x] T2 — 300 productos: indumentaria 90 · tecnologia 120 · suplementos 50 ·
      oficina 40. Determinístico por `md5(url)`, sin seed
- [x] T3 — `qwen3:14b` vía Ollama, `temperature 0`, `think:false`, a ciegas.
      300 en **117 s**; **0 etiquetas fuera del canon** tras un reintento estricto
- [x] T4 — `ml-tests/eval/` (set + muestra + etiquetador + README)
- [x] T5 — Acuerdo **231/300 = 77,0%**; por rubro: indumentaria 84,4 ·
      tecnologia 80,0 · oficina 75,0 · suplementos 58,0

## Verificación aplicable

TDD no aplica a un artefacto de datos: no hay comportamiento nuevo en el
producto. Lo que se verifica es el set —que toda etiqueta esté en el canon, que
la muestra sea reproducible, que el modelo no haya visto la etiqueta actual— y
se reporta el acuerdo medido, no un verde.

## Progreso

Cerrado. Entregado en `ml-tests/eval/` (set de 300, muestra, etiquetador, README
con la medición y los desacuerdos adjudicados).

**El 77% no es accuracy de nadie** — ninguna de las dos etiquetas está validada
por un humano. Lo que el set entrega es el 23% en desacuerdo como cola de
triage, y ahí ya pagó:

**Bug encontrado y confirmado vivo** — `KW_CONJUNTO` (`CategoryClassifier.java:140`)
corre antes de los bloques OFICINA y TECH, y contiene `"combo"`, `" kit "`,
`" pack "`, `" set "`. **348 filas de tecnologia** viven en `Conjunto`, una
categoría de ropa, contra 212 de indumentaria legítimas: 77 bundles mother+CPU,
41 PCs enteras que `KW_PC_LIDER` nunca ve, y 17 RAM (8 con el kit `NxMGB`
explícito que la preferencia `ramDual` busca). Verificado que **no es drift**:
el clasificador de hoy devuelve los 348 a `Conjunto`. Anotado en los bugs
abiertos de `CLAUDE.md`; **no arreglado** — está fuera del alcance autorizado de
este documento, que era etiquetar.

Los desacuerdos también van en la otra dirección (el modelo mandó cuatro SSD
NVMe y dos productos de red a `Otros`), que es la evidencia de que el set no
puede usarse como ground truth sin adjudicación humana.

**Siguiente paso** para que el set sirva de ground truth: adjudicar a mano los 69
desacuerdos. Recién con eso se puede medir si gana el clasificador por keywords
o un fine-tune, que era la pregunta que abrió todo esto.
