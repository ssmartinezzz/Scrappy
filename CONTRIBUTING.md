# Cómo se trabaja en este repo

Dueño único de las convenciones de commits, PRs, código, tests y documentación.
`CLAUDE.md` describe el **estado** del proyecto; este archivo describe el
**proceso**. Si los dos se contradicen, gana este.

Cada regla tiene un **ID estable**. Están para citarse: en un review alcanza con
decir "esto rompe `CODE-3`" y no hace falta parafrasear. Buscá el ID con Ctrl+F.

Las reglas aplican **a quien sea que escriba el PR** — vos o un agente. No hay
sección aparte para agentes: las que más se saltean bajo presión ("medí, no
estimes") son justamente las que valen para todos por igual.

**Nivel**: `MUST` bloquea el merge. `SHOULD` es el default; apartarse se
justifica en el PR, no se hace en silencio.

---

## Índice

| ID | Regla | Nivel |
|----|-------|-------|
| **Commits** | | |
| `COMMIT-1` | Conventional commits, en inglés, imperativo | MUST |
| `COMMIT-2` | El subject nombra el comportamiento, no el archivo | MUST |
| `COMMIT-3` | Nunca atribución de IA | MUST |
| `COMMIT-4` | Un commit = una unidad revisable | SHOULD |
| `COMMIT-5` | El test y el doc viajan con su código | SHOULD |
| **Pull requests** | | |
| `PR-1` | Rama por cambio, merge commit, nunca directo a `master` | MUST |
| `PR-2` | Arriba de ~400 líneas, evaluar partir en cadena | SHOULD |
| `PR-3` | El body responde cuatro preguntas | MUST |
| `PR-4` | Sin secciones vacías ni firma de herramienta | MUST |
| **Código** | | |
| `CODE-1` | TDD: rojo primero, y por la razón correcta | MUST |
| `CODE-2` | Contrato de refactor: la suite pasa sin editar un test | MUST |
| `CODE-3` | Medir, no estimar | MUST |
| `CODE-4` | Pesos, no filtros — y el neutro anclado en 1.0 | SHOULD |
| `CODE-5` | Abstención: vacío es "sin opinión", nunca "malo" | MUST |
| `CODE-6` | Una taxonomía, un dueño | SHOULD |
| `CODE-7` | Escaping en strings Java con JS embebido | MUST |
| **Tests** | | |
| `TEST-1` | Suite entera verde en CADA commit | MUST |
| `TEST-2` | Los cuatro comandos | — |
| `TEST-3` | Infra ausente se skipea, no rompe la suite | MUST |
| `TEST-4` | Nombres inventados de persona en fixtures: PROHIBIDO | MUST |
| **Documentación** | | |
| `DOC-1` | Estado / proceso / por qué: cada dato en un solo lugar | MUST |
| `DOC-2` | Qué doc actualizar con cada tipo de cambio | MUST |
| `DOC-3` | Un comentario que describe mal el presente es peor que ninguno | MUST |
| **Ciclo SDD** | | |
| `SDD-1` | Los cambios sustanciales pasan por el ciclo | SHOULD |
| `SDD-2` | Al archivar, verificar que el destino quedó completo | MUST |

**Automatizado**: `COMMIT-1` y `COMMIT-3` los bloquea un hook local
(ver [Activar el hook](#activar-el-hook)). `PR-3` lo precarga
`.github/PULL_REQUEST_TEMPLATE.md`. El resto es criterio.

---

# Commits

### `COMMIT-1` · Conventional commits, en inglés, imperativo — MUST

`tipo(scope): descripción`. Tipos: `feat` `fix` `perf` `refactor` `chore` `ci`
`docs` `test` `style` `build`. Scopes en uso: `api` `cli` `ml` `db` `frontend`
`scraper` `aggregator` `web` `workflows` `deps`.

Minúscula después de los dos puntos, sin punto final, modo imperativo.

### `COMMIT-2` · El subject nombra el comportamiento, no el archivo — MUST

El subject dice **qué cambia para quien usa el sistema**, no qué archivo tocaste.
El diff ya dice qué archivos tocaste; lo que no puede decir es por qué importa.

```
✅  fix(api): having protein is not being protein
✅  fix(api): stop the supplement builder from guessing the pick
✅  perf(db): bind the history batch as one array instead of a parameter per URL
✅  fix(scraper): make silent scrape failures visible
✅  refactor(api): serve the supplement type list instead of duplicating it

❌  fix(api): update SupplementCombo.java
❌  refactor: changes to OutfitService and OutfitRules
❌  perf: optimize query
❌  fix: bug fixes
```

La prueba: si el subject se puede escribir sin haber entendido el problema, está mal.

### `COMMIT-3` · Nunca atribución de IA — MUST

Ni `Co-Authored-By`, ni `🤖 Generated with`, ni `Claude-Session`, ni variantes.
Sin excepciones. El hook lo bloquea.

### `COMMIT-4` · Un commit = una unidad revisable — SHOULD

Un commit tiene que poder leerse solo y compilar solo. La prueba: si al
describirlo tenés que decir "y además", son dos commits.

Un refactor mecánico que toca 20 archivos va **solo**, sin nada de lógica encima
— así el reviewer puede saltearlo con confianza en vez de leer 20 archivos
buscando el cambio real escondido.

### `COMMIT-5` · El test y el doc viajan con su código — SHOULD

El test va en el mismo commit que el código que prueba. El doc que describe un
cambio de comportamiento va en el mismo commit que ese cambio. Un commit
"agregar tests" separado deja al commit anterior mintiendo sobre su cobertura.

---

# Pull requests

### `PR-1` · Rama por cambio, merge commit, nunca directo a `master` — MUST

Nombre de rama: `<tipo>/<kebab-descripción>` — `fix/supplement-pick-quality`,
`perf/scrape-page-load-budget`, `feat/cli-command-console`.

**Merge commit, no squash.** Los commits individuales sobreviven en `master`, que
es lo que hace que `TEST-1` sirva para algo: squashear tira los puntos de bisect
que costó mantener verdes uno por uno. La rama se borra local y remota al mergear.

### `PR-2` · Arriba de ~400 líneas, evaluar partir en cadena — SHOULD

**No es un límite duro.** Es el punto donde la evidencia dice que la review deja
de encontrar bugs y empieza a aprobar por cansancio. Pasado ese tamaño, la
pregunta "¿esto se revisa mejor en dos?" se contesta explícitamente, y la
respuesta puede ser que no.

Al evaluarlo, mirá el código de producción: tests y goldens inflan el número sin
inflar la dificultad de la review.

### `PR-3` · El body responde cuatro preguntas — MUST

En este orden:

1. **Qué rompía** — el síntoma observable, no la clase que tocaste.
2. **Por qué pasaba** — la causa raíz. Si no la sabés, decilo: una hipótesis
   etiquetada como hipótesis es honesta; una hipótesis disfrazada de causa, no.
3. **Qué cambió** — y explícitamente qué **NO** cambió, si estuvo cerca.
4. **Cómo se verificó** — el comando y su salida. Ver `CODE-3`.

Para un `feat`, la 1 es "qué no se podía hacer" y la 2 es "por qué lo queremos".

`.github/PULL_REQUEST_TEMPLATE.md` lo precarga.

### `PR-4` · Sin secciones vacías ni firma de herramienta — MUST

Una sección de la plantilla que no aplica se borra, no se deja con el comentario
de ayuda adentro. Sin `🤖 Generated with`. Corolario de `COMMIT-3`.

---

# Código

### `CODE-1` · TDD: rojo primero, y por la razón correcta — MUST

Test que falla primero, después la implementación. Para un bug, el test tiene
que fallar **por la razón correcta** antes del fix: un test que pasa desde el
primer intento no probó nada, y uno que falla por un `NullPointerException` en
el setup tampoco.

Guardar el output del rojo — va en la sección 4 del PR.

### `CODE-2` · Contrato de refactor: la suite pasa sin editar un test — MUST

Un refactor puro significa que **la suite existente pasa sin tocar un solo
test**. Un test editado durante un refactor es la prueba de que rompiste algo,
no de que lo mejoraste.

Si un test tiene que cambiar, ya no es un refactor: decilo **al principio**,
antes de tocar código, y tratalo como cambio de comportamiento.

### `CODE-3` · Medir, no estimar — MUST

Toda afirmación de performance necesita un número antes y un número después,
**del mismo harness**. La forma barata:

```bash
# escribí el benchmark, corrélo
git stash push -- <los archivos que cambiaste>
# corrélo de nuevo  → ese es el "antes"
git stash pop
```

Sin eso, "es más rápido" es una opinión. Lo mismo para "esto arregla el bug":
mostrá el test que fallaba.

Y reportá el costo, no solo la ganancia: si una feature nueva se come parte de
una optimización, ese número también va.

### `CODE-4` · Pesos, no filtros — y el neutro anclado en 1.0 — SHOULD

Cuando un ranking incorpora una señal nueva (ML, feedback, coherencia visual),
que sea un multiplicador acotado, no un descarte. Un candidato malo baja de
probabilidad; excluirlo hace que un catálogo chico devuelva un slot vacío en vez
de un resultado imperfecto — peor producto.

**Anclá el neutro en 1.0**: si la señal falta, el peso no se mueve. Eso es lo que
deja la suite existente sirviendo como red de regresión cuando agregás un factor.
Si tenés que editar tests para acomodar una señal nueva, el ancla está mal
elegida — ver `CODE-2`.

### `CODE-5` · Abstención: vacío es "sin opinión", nunca "malo" — MUST

Varias señales del proyecto vienen de clasificadores que se abstienen cuando
dudan (zero-shot visual, tamaño de suplemento, género). Un campo vacío significa
"sin opinión" y **no puede** disparar una penalización.

Una regla que castiga el dato faltante no está clasificando: está degradando en
silencio a todo lo que el clasificador salteó, y eso no aparece en ningún test
que no lo busque a propósito.

### `CODE-6` · Una taxonomía, un dueño — SHOULD

Antes de agregar una keyword a un matcher, chequeá si ya existe un clasificador
canónico que resolvió eso mismo aguas arriba. El bug de fondo casi nunca es la
keyword que falta: es la segunda copia de la taxonomía.

Hoy la fuente es `GarmentTaxonomy` + `CategoryClassifier`. Todo lo demás lee de
ahí, y `p.categoria()` es señal primaria — una keyword del nombre solo la refina.

### `CODE-7` · Escaping en strings Java con JS embebido — MUST

- Regex con `\d`, `\s` → `\\d`, `\\s` (un nivel más de escape).
- Comillas en el JS → `'` simples siempre que se pueda.
- `\?`, `\$`, `\,`, `\.` son escapes **ilegales** en Java.

---

# Tests

### `TEST-1` · Suite entera verde en CADA commit — MUST

No antes del PR: **antes de cerrar cada commit**. Con `clean`.

Así cualquier commit del historial es un punto de bisect válido. El día que
necesites `git bisect` para un bug sutil, esto es la diferencia entre diez
minutos y una tarde.

Durante el desarrollo corré solo las clases afectadas (`-Dtest=...`); el
`clean test` completo va antes de cerrar el commit.

`clean` no es negociable en el backend: sin él, `mvn test` puede pasar contra
clases viejas y fingir verde.

### `TEST-2` · Los cuatro comandos

```bash
# Backend — el toolchain de esta máquina está partido: compila con JDK 24, corre con JRE 21
JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 \
  mvn -f scraper/pom.xml clean test \
  -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java

# CLI nativo (206 tests)
_tools/cli-venv/bin/python -m pytest tests/cli

# Pipeline ML — necesita el Python de ML (numpy); cli-venv NO sirve, está aislado a propósito
pytest ml-tests

# Frontend
cd frontend && npm test
```

### `TEST-3` · Infra ausente se skipea, no rompe la suite — MUST

`PostgresTestBase` elige solo entre Testcontainers y el Postgres portable de
`_tools/pgsql`, y se skipea **con un mensaje que explica qué falta** si no hay
ninguno. Un test nuevo que dependa de infra externa sigue ese patrón: nunca
hacer fallar la suite entera por una dependencia que no está.

### `TEST-4` · Nombres inventados de persona en fixtures: PROHIBIDO — MUST

Nada de `ana`, `bruno`, `valeria`, `juan`, `ephraim` ni ningún otro nombre propio
inventado en usuarios, cuentas, fixtures o prosa de tests. **Prohibido, no
desaconsejado.**

El nombre de una fixture es lo único que le dice al lector **qué papel cumple en
el escenario**, y un nombre propio no dice ninguno. `ana` y `bruno` obligan a
reconstruir de memoria cuál era el dueño de la sesión y cuál el tercero ajeno,
en cada lectura, para siempre. `usuario` y `otroUsuario` lo dicen solos.

Peor: la prosa se contamina con género y pronombres inventados —"her family",
"his window"— sobre entidades que no tienen ninguno, y esas frases sobreviven a
todos los renombres posteriores.

**Usá el rol:** `usuario` / `otroUsuario` · `admin` / `viewer` · `dueño` /
`ajeno` · `titular` / `tercero`. Si el escenario necesita tres, numerá:
`usuarioUno`, `usuarioDos`.

> ⚠️ **Deuda conocida, no ejemplo a seguir.** Al escribirse esta regla el repo
> tenía ~238 ocurrencias en 20 archivos, casi todas en los tests de
> `user-accounts-and-roles`. Están **grandfathereadas**: la regla rige para todo
> test nuevo y para cualquier archivo que toques, y migrarlas en bloque es un
> cambio aparte —mecánico y sin riesgo, pero que no tiene por qué viajar dentro
> de un PR de otra cosa. Un archivo que edites, lo dejás limpio.

---

# Documentación

### `DOC-1` · Estado / proceso / por qué: cada dato en un solo lugar — MUST

| Doc | Responde | |
|---|---|---|
| `CLAUDE.md` | ¿Qué hay hoy? | **estado** |
| `CONTRIBUTING.md` | ¿Cómo se trabaja? | **proceso** |
| `docs/ARCHITECTURE.md` | ¿Por qué así? | **por qué** |
| `docs/DATABASE.md` | Todo lo de la base | **la base** |
| `SKILL.md` | ¿Dónde está? | **índice** |

Si `CLAUDE.md` está creciendo una justificación, va a `ARCHITECTURE.md`. Un dato
escrito en dos lugares se desincroniza; es cuestión de cuándo.

`docs/DATABASE.md` es un corte por tema, no una cuarta categoría: se lleva el
estado, el porqué y el rollback de la base juntos, porque separarlos entre
`CLAUDE.md` y `ARCHITECTURE.md` era lo que hacía que el esquema se documentara
dos veces. `ARCHITECTURE.md` conserva sólo el índice hacia él.

Documentar un archivo obliga a trackearlo: un doc que referencia algo untracked
miente para cualquiera que clone.

### `DOC-2` · Qué doc actualizar con cada tipo de cambio — MUST

En el **mismo PR**, por `COMMIT-5`:

| Si tocás… | Actualizá |
|---|---|
| Endpoints REST | `docs/API_REFERENCE.md` + la tabla de API en `CLAUDE.md` |
| Scoring, badges, clustering, atributos visuales | `docs/ML_PIPELINE.md` |
| Un sitio o plataforma nueva | `docs/ADD_SCRAPER.md` + la tabla de sitios en `CLAUDE.md` |
| Una decisión estructural | `docs/ARCHITECTURE.md` |
| El esquema, una migración, el upsert o una tabla nueva | `docs/DATABASE.md` — **toda tabla nueva cumple 1FN y 3FN**, y el bloque `-- >>> rollback:VN` lo ejecuta un test |
| El agente LLM | `docs/LLM_EMBED.md` |
| Los armadores de outfits | La sección de armadores en `CLAUDE.md` + `ARCHITECTURE.md` si cambia el criterio |
| Un doc nuevo o retirado | `SKILL.md` |
| Una convención de proceso | Este archivo, con ID nuevo |

### `DOC-3` · Un comentario que describe mal el presente es peor que ninguno — MUST

Distinguí dos cosas al limpiar comentarios viejos:

- **Miente sobre el presente** → se arregla. `"the SQLite embedding cache"` cuando
  hace un año que es Postgres manda al lector a buscar un archivo que no existe.
- **Enmarca una decisión pasada como pasada** → se queda. `"era SQLite BLOB, es
  bytea desde D4"` explica por qué el schema es así y sigue siendo cierto.

---

# Ciclo SDD

### `SDD-1` · Los cambios sustanciales pasan por el ciclo — SHOULD

`explore → proposal → spec → design → tasks → apply → verify → archive`.
Artefactos en `openspec/changes/<nombre>/`; al cerrar se mueven a
`openspec/changes/archive/<fecha>-<nombre>/`.

Un fix acotado con su test no necesita el ciclo. Un cambio que toca varias capas
o inventa un criterio nuevo, sí.

### `SDD-2` · Al archivar, verificar que el destino quedó completo — MUST

Ya pasó que el paso de archivado dejó archivos **en 0 líneas** y la copia viva
siguió existiendo, pareciendo un duplicado descartable cuando era la única que
tenía el contenido.

`diff -rq` te dice que dos árboles difieren, **nunca cuál es el bueno**. Antes de
borrar algo como "duplicado", compará line counts archivo por archivo. Que git
registre el movimiento como *rename* es la confirmación de que sobrevivió
byte a byte.

---

# Activar el hook

Una vez por clon:

```bash
git config core.hooksPath scripts/hooks
```

Bloquea dos cosas antes de que el commit exista:

- **`COMMIT-1`, solo la forma** — que el subject sea `tipo(scope): descripción`
  con un tipo de la lista. Minúscula inicial, sin punto final e imperativo son
  parte de la regla pero **no** se validan acá.
- **`COMMIT-3` completo** — atribución de IA en cualquier parte del mensaje,
  case-insensitive, incluidos los trailers al final.

Lo cosmético queda afuera a propósito: un hook que rechaza por una mayúscula se
termina esquivando con `--no-verify`, y eso apaga también el check de `COMMIT-3`,
que es el que de verdad importa. Tampoco valida largo — varios de los mejores
subjects de este repo pasan los 72 caracteres con razón.

Exentos: merges, reverts y los `fixup!`/`squash!` autogenerados.

Para saltearlo en un caso puntual: `git commit --no-verify`. Si lo estás usando
seguido, la regla está mal escrita — arreglá la regla, no la esquives.
