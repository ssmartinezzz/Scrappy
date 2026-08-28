# API Reference

Base URL: `http://localhost:3000/api`

> The native CLI (`cli/`, `native-cli-installer` 2026-07-25) is a pure REST
> client of this API — it owns the lifecycle of the backend and the frontend
> `npm run preview` process but adds no new endpoints. `cli/core/rest.py` is
> the single source of truth for which endpoints it calls (status, scrape,
> `ml/entrenar`, `sitios` CRUD); `tests/cli/test_rest.py` asserts no other
> endpoint is ever invoked. Supersedes the retired `menu.ps1`/`menu.sh`
> (`interactive-cli-launcher`, PR #108), which carried the same contract.

## CORS e integración externa

Desde `decouple-services-postgres`, el backend es **API-only** (no sirve la SPA).
El frontend es un servicio propio que le habla por **CORS**:

- El backend acepta orígenes de la env var **`APP_CORS_ALLOWED_ORIGINS`**
  (allow-list separada por comas, sin default en el profile por defecto).
- El frontend usa **`VITE_API_BASE_URL`** como base de sus fetches (build-time).
- En Docker, el compose cablea las dos (ver `docs/DOCKER` / `docker.env.example`).
  Cualquier integración externa debe agregar su origen a `APP_CORS_ALLOWED_ORIGINS`.

## Formato de timestamps

**Desde `V8` (`normalize-db-schema-fks-1nf`, slice A.4) todos los campos de
fecha/hora que salen de la base viajan en ISO-8601 UTC al segundo:
`2026-08-11T20:15:00Z`.** Antes salían como `2026-08-11 17:15:00` — hora local,
separada por espacio, sin offset — porque las columnas eran `TEXT` y la API
devolvía el string tal cual estaba guardado.

Campos afectados:

| Endpoint | Campos |
|----------|--------|
| `GET /favoritos` | `addedAt` · `lastCheckedAt` |
| `GET /outfits/saved` | `createdAt` |
| `GET /cron` · `GET /cron/{id}` | `createdAt` · `updatedAt` · `lastRunAt` · `nextRunAt` |
| `GET /cron/{id}/executions` | `startedAt` · `finishedAt` |

Un campo nulo sigue siendo `null` (un `lastRunAt` de un job que nunca corrió, un
`finishedAt` de una ejecución en curso), nunca un string vacío ni un `—`.

**Qué NO cambió**: `training.startedAt` de `GET /ml/estado` no sale de la base
—vive en memoria en `PythonRunner`— y ya emitía este mismo formato. El cambio
alinea el resto de la API con lo que ese campo hacía desde siempre.

`POST /cron` y `PUT /cron/{id}` siguen aceptando lo de antes: el `nextRunAt` lo
calcula el backend, no lo manda el cliente.

## Índice de endpoints

Las secciones detalladas de abajo cubren el núcleo. El resto sigue las mismas
convenciones (params server-side, respuestas JSON). Lista completa por grupo:

| Grupo | Endpoints |
|-------|-----------|
| Auth | `POST /auth/login` · `POST`/`DELETE /auth/refresh` · `GET /auth/me` · `POST /auth/password-reset/request` · `/confirm` |
| Usuarios | `GET`/`POST /usuarios` · `PUT /usuarios/{username}/rol` · `DELETE /usuarios/{username}` · `PUT /usuarios/{username}/activar` — **ADMIN, sin UI** |
| Scraping | `GET /status` · `POST /scrape?precioMin&precioMax&sitios&forceRetrain` |
| Catálogo | `GET /data` · `GET /facets` · `GET /csv` · `DELETE /data?url=` (soft-delete) |
| Catálogo | `GET /producto/{key}` (producto + historial, 404 si no existe) |
| ML | `GET /tendencias` · `GET /historial?url=` (204 sin puntos) · `POST /ml/aplicar` · `POST /ml/renormalizar` · `GET /ml/estado` · `POST /ml/entrenar` · `GET /ml/resultado` |
| Comparador | `GET /grupos` · `GET /buscar-externo` (MercadoLibre) |
| Financiación | CRUD `/financiacion/presets` · `GET /recomendacion?url=` · `GET /inflacion` (INDEC) |
| Outfits | `GET /outfits` · `GET /outfits/builder` · `GET /suplementos/builder` · `GET /suplementos/tipos` (subtipos ofrecibles + grupo de selector; taxonomía pura, responde sin catálogo) · `POST /outfits/feedback` · CRUD `/outfits/saved` |
| Para ti | `GET /recomendados` · `POST /recomendados/feedback` · `POST`/`DELETE /recomendados/dismiss-categoria` |
| Favoritos | `GET`/`POST`/`DELETE /favoritos` |
| Picks/Marcas | `GET /mejores?rubro=` · `GET /marcas-browser` |
| Sitios/Config | `GET`/`POST`/`DELETE /sitios` · `PUT /config` |
| Cron | `GET`/`POST /cron` · `GET`/`PUT`/`DELETE /cron/{id}` · `GET /cron/{id}/executions` · `POST /cron/{id}/run-now` |
| DB | `GET /db/export` · `POST /db/import` (ambos **410 Gone** — usar `pg_dump`/`pg_restore` contra `DATABASE_URL`) · `DELETE /db/productos` (**409** si hay favoritos protegidos, ver detalle abajo) · `DELETE /db/ml` |
| LLM Agent | `POST /agent/chat` · `POST /agent/apply` · `GET /agent/models` |

---

## POST /auth/login

Autentica por **`username`** y devuelve un access token JWT de 15 minutos.

> 🔒 **El token que emite acá sí se exige en todos lados.** Esta advertencia
> decía lo contrario —"este endpoint no protege nada, no existe ningún
> `SecurityFilterChain`"— y era cierta cuando se escribió, dos slices antes de
> que existiera el gate. `SecurityConfig` + `JwtAuthFilter` filtran hoy toda ruta
> `/api/*` contra `ApiRoutePolicy.TABLE`, que termina en `denyAll()`: una ruta
> sin fila se rechaza, no se permite.

**Request**

```json
{ "username": "admin", "password": "..." }
```

`email` **nunca** es identificador de login: es opcional, y ni la cuenta
bootstrap ni la de servicio del CLI tienen uno.

**200**

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 900 }
```

El token lleva `sub`, `iat`, `exp` y `jti`, y **nada más** — en particular, sin
claim de rol. Un rol dentro de un token firmado no se puede revocar antes de que
venza, así que el rol se relee de la base en cada request (fase 2).

**401** — mismo status y mismo body para credencial equivocada, usuario
inexistente, cuenta con `activo=FALSE` y body malformado:

```json
{ "error": "credenciales_invalidas", "mensaje": "Usuario o contraseña incorrectos" }
```

Distinguirlos convertiría al endpoint en un oráculo de qué usuarios existen. Por
la misma razón el tiempo de respuesta tampoco los distingue: cuando el usuario no
existe se verifica igual contra un hash señuelo, así que las dos ramas cuestan el
mismo Argon2id (~76 ms) en vez de diferir en algo perfectamente medible por red.

**429** — cinco fallos sobre la misma cuenta en 15 minutos, o cien en total:

```json
{ "error": "demasiados_intentos", "mensaje": "Demasiados intentos fallidos. Probá de nuevo en 15 minutos." }
```

Con `Retry-After` en segundos. Se cuentan **fallos, no intentos**: un login
exitoso limpia el contador de esa cuenta, así que abrir cinco pestañas no te
echa. El techo global, en cambio, no lo limpia nadie — si lo hiciera, alguien
con una credencial válida resetearía el presupuesto de todos entre tanda y tanda
de adivinanzas.

El username se cuenta **exista o no la cuenta**: si sólo se contaran las reales,
el 429 sería el oráculo de existencia que el 401 de arriba se toma tanto trabajo
en no ser.

No hay límite por IP a propósito. `getRemoteAddr()` devuelve la IP del proxy en
cuanto haya uno adelante, y ahí todos los clientes caen en el mismo balde sin que
nada falle — la clave por IP se agrega junto con la allowlist de proxies de
confianza, no antes. Los dos techos y la ventana son **propuestas, no
mediciones**.

**Cuentas iniciales.** Se siembran al arrancar desde el entorno
(`ADMIN_BOOTSTRAP_*`, `CLI_SERVICE_ACCOUNT_*`), de forma idempotente. El seeder
**nunca pisa un hash existente**: cambiar la variable y reiniciar *no* cambia la
password de una cuenta ya creada. Recuperarla es SQL directo.

### Exponerlo fuera de localhost exige TLS

El access token viaja como `Authorization: Bearer` en texto plano. Sobre HTTP sin
cifrar es legible en tránsito por cualquiera en el camino, y con él se puede
actuar como el usuario hasta que venza. Esta aplicación está pensada para correr
en `localhost`; publicarla más allá **requiere TLS por delante**, no es una
recomendación.


## POST /auth/refresh · DELETE /auth/refresh

Rotación de sesión y logout. El CLI se reautentica con sus credenciales del
`.env` y nunca sostiene un refresh token — esta superficie es exclusivamente
para un cliente de browser, y el dashboard React (`frontend/src/lib/authSession.js`)
es ese consumidor: es el único módulo del frontend donde aparece
`credentials: 'include'`.

**Cómo viaja cada cosa, y por qué**

| | Dónde viaja | Por qué |
|---|---|---|
| Access token | header `Authorization: Bearer`, en memoria | un header no se adjunta solo, así que ninguna página ajena puede hacer que el browser lo mande |
| Refresh token | cookie `refresh`, `HttpOnly; Secure; SameSite=Strict; Path=/api/auth/refresh` | es lo único que tiene que sobrevivir a un F5. En memoria muere con el reload; en `localStorage` sobrevive pero lo lee cualquier script inyectado. `HttpOnly` hace las dos |
| Nonce CSRF | body JSON al recibirlo, header `X-Refresh-CSRF` al mandarlo | el browser no adjunta headers custom por su cuenta — eso es lo que lo hace una prueba de intención |

**Por qué hace falta el nonce si ya está `SameSite=Strict`.** Porque *same-site
ignora el puerto*. Cualquier página servida desde cualquier otro puerto de
`localhost` es same-site con este backend y la cookie se le adjunta igual. En una
app pensada para localhost eso no es un caso de borde, es el escenario normal.

**El orden importa: el nonce se valida ANTES de consumir el token.** Al revés, un
pedido forjado rotaría primero y fallaría el chequeo después — dejando al cliente
real con un token ya gastado, cuyo próximo refresco dispara la detección de reuso
y lo desloguea. Sería convertir un ataque bloqueado en una denegación de servicio
exitosa.

**`POST /auth/refresh`** — cookie + `X-Refresh-CSRF`

- **200**: `{ "accessToken", "tokenType", "expiresIn", "csrfNonce" }` + `Set-Cookie` con el token sucesor. El refresh token **nunca** aparece en el body.
- **403** `csrf_invalido`: falta o no coincide el nonce. El token presentado **queda intacto**.
- **401** `refresh_invalido`: desconocido, vencido o revocado. Limpia la cookie.
- **401** `sesion_invalidada`: el token se presentó dos veces pasada la ventana de gracia. **Toda la familia queda revocada** — no se puede saber cuál de los dos presentadores es el ladrón, así que ninguno conserva la sesión.

**Ventana de gracia de 10 s.** Un cliente que dispara varios pedidos en paralelo
recibe varios 401 y puede refrescar más de una vez con el mismo token. Sin la
ventana eso es indistinguible de un robo, y el detector desloguearía al usuario
por ser rápido. Dentro de la ventana se devuelve el mismo par sucesor, sin rotar
de nuevo. **Los 10 s son una propuesta, no una medición** — medirlos requiere un
cliente de browser, que todavía no existe.

**`DELETE /auth/refresh`** (logout) — cookie + `X-Refresh-CSRF`

Revoca la familia entera y limpia la cookie. Es `DELETE` sobre `/auth/refresh` y
no `/auth/logout` por una razón mecánica: el `Path` de la cookie es
`/api/auth/refresh`, así que el browser no la adjuntaría a otra ruta y el
servidor no sabría qué familia revocar. Deslogearse en otro path limpiaría la
copia del browser dejando la sesión viva en el servidor.

La cookie se limpia **siempre**, incluso con un token desconocido: quien la tiene
igual la quiere afuera, y no limpiarla lo deja reenviando algo que ya nunca va a
funcionar.

**Las cuentas de servicio no reciben sesión.** `POST /auth/login` con
`es_servicio = TRUE` devuelve access token y ninguna cookie: el CLI se
reautentica de su `.env`, así que una credencial de catorce días sería una
credencial tirada al pedo.

### Topología: dónde funciona y dónde no

`SameSite` usa el dominio registrable e **ignora el puerto**; el esquema sí cuenta.

| Topología | ¿Anda? |
|---|---|
| `localhost:5173` → `localhost:3000` | **Sí** |
| `app.example.com` → `api.example.com` | **Sí** |
| `app.example.com` → `api.example.net` | **No** — cross-site, se descarta la cookie |
| `http://` front → `https://` back | **No** — cross-scheme |
| Fuera de localhost por HTTP plano | **No** — `Secure` la descarta |

Frontend y backend tienen que compartir dominio registrable (o ser los dos
localhost); fuera de localhost, HTTPS en ambos. Si no, el refresco falla **en
silencio** y al usuario lo desloguean cada quince minutos sin ningún error a la
vista — parece un bug, no una mala configuración.

**CORS con credenciales está acotado a DOS rutas: `/api/auth/login` y
`/api/auth/refresh`.** Todo el resto sigue en `allowCredentials=false`, y sumar
una tercera es una decisión de seguridad, no una comodidad.

El login está en la lista porque **su respuesta es la que planta la cookie**, y
cross-origin el browser descarta el `Set-Cookie` de una respuesta cuyo pedido no
se hizo en modo credenciales. Sin eso la cookie no se guarda nunca y la sesión no
se puede recuperar al recargar. Esto no se ve bajo `vite dev`, donde el proxy
hace same-origin al login — se ve sólo en las topologías que se instalan de
verdad, y lo encontró la suite de browser (`tests/e2e/run-e2e.sh`), no un test
unitario.

Los dos mapeos se registran **antes** del `/**` porque gana el primero que
matchea; al revés nunca se consultarían y el browser descartaría la cookie. Y `APP_CORS_ALLOWED_ORIGINS=*`
**aborta el arranque** nombrando la variable: el comodín está prohibido bajo CORS
con credenciales, y dejárselo a Spring lo convierte en un 500 al hacer login.

### Admisión de arranque en frío ("bootstrap") sin nonce

Un reload no deja nonce en memoria — el cliente no tiene forma de mandar
`X-Refresh-CSRF` en el primer refresh tras recargar la página. Un `POST
/auth/refresh` que **no lleva ese header en absoluto** (bootstrap, distinto de
llevarlo y que no matchee, que sigue siendo `403` sin excepción) se admite
sólo si **las dos** condiciones valen:

| # | Condición |
|---|---|
| 1 | `Origin` presente y coincide **exactamente** (string completo, con puerto) con una entrada de `APP_CORS_ALLOWED_ORIGINS` |
| 2 | `Sec-Fetch-Site` presente y ∈ {`same-origin`, `same-site`} |

Falta cualquiera de los dos headers, o no matchea el valor → **falla cerrado**,
`403 csrf_invalido`, igual que hoy. El camino de bootstrap no relaja nada más:
token vencido, revocado o reusado se comporta exactamente igual que con nonce.

**`Sec-Fetch-Site: same-origin` solo no alcanza** — es la opción que este
documento recomendaba antes de medir las topologías reales. Ninguna instalación
que se shippea de esta app es same-origin: el SPA vive en `localhost:5173`
(portable/POSIX) o `localhost:8080` (Docker), el backend en `localhost:3000`.
Un refresh legítimo manda `Sec-Fetch-Site: same-site`, nunca `same-origin` —
eso sólo ocurre bajo `vite dev`. Exigir `same-origin` habría rechazado el
arranque en frío en las dos instalaciones que se shippean. Y como `same-site`
es también lo que manda una página servida desde otro puerto de `localhost`
—el atacante que el nonce existe para frenar—, `Sec-Fetch-Site` por sí solo no
discrimina nada acá. `Origin` sí: lleva el puerto, es un forbidden header name
que un script no puede forjar, y el backend ya valida ese allow-list al
arrancar. Por eso `Origin` es el gate primario y `Sec-Fetch-Site` un segundo
gate fail-closed, no la única señal.


## GET /auth/me

Devuelve el sujeto autenticado — `AUTHENTICATED`, no en la lista de rutas
abiertas: contestar "quién sos" a un caller anónimo es un oráculo de qué
usuarios existen, así que exige un access token válido como cualquier otra
ruta cerrada.

**200**

```json
{ "username": "valeria", "roles": ["VIEWER"] }
```

`roles` es un **array**, no un string: `usuario_rol` es una tabla de join que
admite más de un rol por cuenta, y la API no colapsa esa cardinalidad. El rol
se lee de la base en cada request — nunca del claim del JWT, que no lo lleva.

**401** — sin access token, o vencido/inválido. Nunca 200 con datos vacíos.

Zero queries nuevas: el filtro de seguridad ya hace la lectura por request que
esto expone (`JwtAuthFilter`), así que el endpoint sólo formatea lo que el
contexto de seguridad ya tiene.

El cliente lo llama después de login, después de recuperar la sesión al
recargar (bootstrap), y después de **cada** refresh exitoso — no sólo el de
arranque — para que un cambio de rol server-side se vea dentro de una vida de
token (15 min) en vez de recién en el próximo login.


## POST /auth/password-reset/request · POST /auth/password-reset/confirm

"Me olvidé la contraseña". Sin autenticación por diseño: quien lo usa es
exactamente quien no puede entrar.

### `/request` — siempre 202, siempre igual, siempre a la misma velocidad

```json
{ "email": "ana@example.com" }
```

Responde **202** con el mismo body para una dirección real, una inexistente, una
malformada y una de cuenta de servicio:

```json
{ "mensaje": "Si la dirección corresponde a una cuenta, va a recibir un enlace." }
```

**La uniformidad no es cortesía.** Un formulario que contesta distinto para una
dirección conocida es la lista de usuarios de este sistema, regalada a cualquiera
con un diccionario.

Y el body igual no alcanza: si la rama de la cuenta existente hiciera una lectura
a la base, un hash y una vuelta a SMTP antes de contestar, **el reloj respondería
lo que el body se niega a decir**. Por eso el hilo del request no hace *nada* que
dependa de la cuenta: normaliza la dirección, la manda a un hilo virtual y
retorna. La búsqueda, el rate-limit y el envío pasan todos después. Efecto lateral
útil: una falla de envío es **estructuralmente incapaz** de llegar al que llamó,
porque para cuando el `send` explota el 202 ya se escribió.

**Las cuentas de servicio quedan afuera por el esquema, no por un `if`.** `V26`
trae `CHECK (NOT es_servicio OR email IS NULL)`: no tienen dirección, y un flujo
que busca *por* dirección no puede encontrarlas.

**Rate-limit silencioso**, tres ventanas de una hora: 3 por dirección, 10 por IP,
100 global. Un pedido limitado **igual devuelve 202** — un 429 sería un oráculo
por cuenta: preguntá dos veces y la segunda respuesta te dice si la dirección
existe. Los tres topes son **propuestas, no mediciones**.

### `/confirm`

```json
{ "token": "…", "password": "la-nueva" }
```

- **200**: contraseña cambiada. **Todas** las sesiones del usuario quedan revocadas.
- **400** `reseteo_invalido`: token desconocido, ya usado o vencido, o contraseña de menos de 8 caracteres. No se distinguen.

Corre en **una** transacción: consumir el token, escribir el hash nuevo y
`password_changed_at`, revocar todas las familias de refresh, anular los demás
links pendientes. Que alguna de esas fallara sola dejaría un desastre con buena
cara: un token quemado con la contraseña vieja todavía puesta, o una contraseña
nueva con la sesión del intruso viva.

El consumo es **una sola sentencia** (`UPDATE … WHERE consumed_at IS NULL AND
expires_at > now() RETURNING usuario_id`). Leer-chequear-actualizar pasa todos
los tests secuenciales y falla exactamente cuando llegan dos pedidos juntos, que
es la carrera que un atacante corre y la que causa un doble click.

### El link

`{PASSWORD_RESET_LINK_BASE}/reset-password#token=…` — el token va en el
**fragmento**, no en el query string. Un fragmento nunca llega al servidor, así
que no puede terminar en un access log, y nunca viaja en `Referer`, así que no se
filtra a los recursos de terceros que cargue la página.

### Canales

| | `console` (default) | `smtp` (opt-in) |
|---|---|---|
| Configuración | ninguna | las cinco `SMTP_*`, o el arranque aborta nombrándolas |
| Entrega | escribe el link en `scraper.log` | manda el mail |

El default es `console` a propósito: exigir un servidor de mail para recuperar
una contraseña dejaría la función fuera de alcance para las instalaciones de un
solo usuario que más la necesitan. **Pone un token vivo en el log** — trade-off
real, acotado por el uso único y los 30 minutos, y por que quien lee el log ya
puede leer `.env`.

**Los rebotes son invisibles.** El log de ERROR cubre el rechazo sincrónico
únicamente; un mail aceptado por el relay y rebotado después no deja línea en
ningún lado, porque nada en este diseño recibe correo. Una dirección tipeada mal
devuelve el mismo "fijate tu mail" y después silencio permanente — consecuencia
directa de no ser un oráculo de enumeración. El diagnóstico del operador es
consultar `password_reset_token`.


## /usuarios — administración de cuentas

**Sólo ADMIN, y sin interfaz.** Se maneja por `curl`; una pantalla es una
decisión aparte con su propio diseño.

**Nacieron gateados.** La regla ADMIN para `/api/usuarios/**` estaba en
`ApiRoutePolicy.TABLE` desde el slice de enforcement, **matcheando nada**, un
slice antes de que existieran las rutas. No hubo un instante en que un endpoint
de creación de usuarios existiera sin una regla arriba — y eso importa porque un
`POST /api/usuarios` abierto deja que cualquiera se cree una cuenta ADMIN, que es
estrictamente peor que no tener la función.

| | |
|---|---|
| `GET /usuarios` | Lista **todas** las cuentas, activas y desactivadas. Nunca devuelve el hash |
| `POST /usuarios` | `{username, password, role, email?}` → **201**. `role` ∈ `ADMIN`/`VIEWER`; password mínimo 8 |
| `PUT /usuarios/{username}/rol` | `{role}` — **reemplaza**, no acumula |
| `DELETE /usuarios/{username}` | **Desactiva**, no borra |
| `PUT /usuarios/{username}/activar` | Reactiva |

**Errores**: `400 rol_invalido` (fuera del vocabulario cerrado) · `400 password_corta`
· `400 faltan_campos` · `409 username_tomado` · `404 no_existe` · `409 ultimo_admin`.

**Desactivar no es borrar.** Un DELETE real se llevaría por CASCADE los roles, los
refresh tokens, los tokens de reseteo, el rastro de auditoría de lo que hizo esa
persona, y —como el ownership cascadea— sus datos personales. La desactivación
reusa el mecanismo de revocación que ya existe: el **próximo request** con su token
todavía válido se rechaza, sin reemitir nada y sin mecanismo nuevo.

**El crear es atómico**: cuenta + rol en una transacción. Las dos mitades no sirven
por separado — una cuenta sin rol no autoriza nada (la consulta por request vuelve
vacía y se lee como "desactivada"), así que media creación dejaría una cuenta que
figura en la lista y no puede entrar, sin ninguna pista de por qué.

**Y una cuenta duplicada no pisa la existente**: si el username está tomado
devuelve 409 sin tocar nada. Si no, crear un duplicado sería una forma de
resetearle la contraseña a otro.

### La última cuenta ADMIN no se puede sacar

Esto **no está en el spec** y lo agregué igual: desactivar o degradar al único
ADMIN activo deja una aplicación que nadie puede administrar, y que sólo se
recupera con SQL directo contra la base. Es un estado de un solo click,
silencioso e irrecuperable por la API. Ambas rutas devuelven `409 ultimo_admin`,
y el chequeo cuesta una consulta.


## GET /status

Estado actual del scraper.

**Response:**
```json
{
  "status": "IDLE | RUNNING | DONE | ERROR",
  "mensaje": "Completado: 3034 productos",
  "tieneData": true,
  "total": 3034,
  "run": {
    "uuid": "f4dec74b-8a28-49c9-b206-1301811fbfc8",
    "startedAt": "2026-08-25T22:54:59Z",
    "cancelando": false
  }
}
```

**`run` es aditivo y sólo aparece mientras hay una corrida abierta** (`V29`).
`status` **no** cambió y no va a cambiar: sigue siendo `IDLE | RUNNING | DONE |
ERROR`, y **una corrida cancelada reporta `DONE`**, con el motivo en `mensaje`.
Agregar `CANCELLED` al enum habría cambiado la superficie del contrato del CLI
(`cli/core/rest.py`) sin ganar nada funcional — un cliente que sólo mira
`status` ve una corrida que terminó, que es la verdad. El que quiera el detalle
tiene `run`.

---

## GET /scrape/interrupted

**ADMIN.** Qué dejó abierto el proceso anterior. **Sólo informa.**

```json
{
  "hayInterrumpida": true,
  "uuid": "f4dec74b-...",
  "startedAt": "2026-08-25T22:54:59Z",
  "soloFaltaLaPasadaFinal": false,
  "atendidos":  ["freres", "midway"],
  "pendientes": ["batuk", "tussy"],
  "salteados":  []
}
```

**Detectar no reanuda.** La detección corre en `cargarDesdeBD()`
(`@PostConstruct`) y lo único que hace es levantar esta bandera. Un reinicio que
retomara trabajo por su cuenta sería una falla peor que la caída que está
atendiendo: nadie pidió ese scrape, y el servidor puede haberse reiniciado
justo para dejar de hacerlo.

Cómo se reparten los sitios:

| Grupo | Qué es | Por qué |
|---|---|---|
| `atendidos` | `DONE` o `ERROR` | Ya tuvieron su turno. Un `ERROR` además reintentó tres veces adentro de la corrida, así que ofrecerlo de nuevo es ofrecer repetir un fallo |
| `pendientes` | `PENDING` o `RUNNING` | Nunca arrancaron, o los agarró la caída a mitad — su resultado parcial murió con el proceso, así que se les debe lo mismo |
| `salteados` | `SKIPPED` | Salieron del registro entre la caída y el reinicio. **Se nombran a propósito**: que desaparezcan en silencio de una corrida que los debía es peor que no retomarlos |

`soloFaltaLaPasadaFinal` es el caso que se olvida: todos los sitios terminaron y
la caída pegó durante la pasada de ML/agregación. Ahí re-scrapear es trabajo
puro perdido.

---

## POST /scrape/resume

**ADMIN.** Retoma la corrida interrumpida. Sólo los sitios que faltan.

**Response:** `{"retomando": true, "mensaje": "..."}` · `false` cuando no hay
nada que retomar o ya hay un scraping en curso.

**Reusa la fila de corrida original, no abre una nueva**, y eso es lo que
sostiene todo lo demás. `started_at` es a la vez la cota de aislamiento del
lector y el alcance del barrido final. Con una fila nueva las dos nombrarían
**sólo la mitad retomada**, y el barrido leería los productos de la primera
mitad como ausentes y los desactivaría — estrictamente peor que la interrupción
que venía a reparar. Preservarla también evita una columna `resumed_from` que no
haría falta.

Tres caídas, tres comportamientos:

1. **A mitad de un sitio** → ese sitio vuelve a `PENDING` y se re-scrapea.
2. **Después de varios** → sólo los que faltan.
3. **Después de que todos terminaron** → **no se re-scrapea nada**: corre sólo
   el barrido final, con el alcance derivado de `touched_at >= started_at`.

⚠️ El caso 3 **no vuelve a correr el pipeline de ML**. Esa mitad se recupera
sola en la próxima corrida normal. Lo que no se puede postergar es el barrido:
sin él los productos ausentes quedan activos para siempre.

---

## POST /scrape/cancel

**ADMIN.** Pide que la corrida en curso se detenga. Idempotente.

**Response:** `{"cancelando": true, "mensaje": "..."}` · `cancelando: false`
cuando no había nada corriendo.

Qué hace, exactamente:

- Deja de esperar sitios dentro de los **~5 s** del pedido. Antes de esto el
  loop se bloqueaba hasta 600 s en un solo `poll`, así que una cancelación
  podía tardar diez minutos en notarse.
- **No corre la agregación**, y eso es el punto. Adentro vive la pasada de
  soft-delete, que da por ausente todo lo que no vino en *esta* tanda de
  resultados — y una corrida cancelada tiene, por definición, sitios que nunca
  llegaron a hablar. Cancelar deja el catálogo **exactamente como estaba**.
- Cierra los browsers que sobrevivieron. Medido contra proceso real: 24
  chromium + 4 node durante una corrida de 4 sitios, **0 a los 10 s** del
  cancel. Sin ese cierre sobreviven al `shutdownNow()` y quedan colgados del
  JVM, que en un server no reinicia nunca.
- Marca la corrida `CANCELLED` en `scrape_run`, con su `finished_at`.

---

## POST /scrape

Lanza scraping async. Retorna inmediatamente.

**Query params:**
| Param | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `precioMin` | double | config | Precio mínimo |
| `precioMax` | double | config | Precio máximo |
| `sitios` | string[] | todos | Nombres de sitios a scrapear |

**Ejemplo:** `POST /api/scrape?precioMin=0&precioMax=200000&sitios=Freres&sitios=Sporting`

**Response:** `{"iniciado": true, "mensaje": "Scraping iniciado"}`

---

## GET /data

Productos con filtros y paginación server-side.

**Query params:**
| Param | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `page` | int | 1 | Página |
| `size` | int | 24 | Productos por página |
| `q` | string | - | Búsqueda full-text en nombre |
| `sitio` | string | - | Filtrar por tienda (exacto) |
| `marca` | string | - | Filtrar por marca normalizada |
| `badge` | string | - | Filtrar por badge ML — pertenencia al set (`badges`), no igualdad exacta con el principal. Keys: `all_time_low`, `below_market`, `verified_deal`, `trending`, `price_dropping`, `above_market`, `fake_discount` |
| `genero` | string | - | `hombre` / `mujer` / `unisex` |
| `categoria` | string[] | - | Multi-select categoría |
| `talle` | string[] | - | Multi-select talle |
| `fit` | string | - | Atributo visual: fit de la prenda (ej. `oversize`, `slim`) |
| `estampado` | string | - | Atributo visual: estampado (ej. `liso`, `rayado`) |
| `escote` | string | - | Atributo visual: escote (ej. `redondo`, `en v`) |
| `colorDominante` | string | - | Atributo visual: color dominante de la foto |
| `subCategoria` | string[] | - | Multi-select subcategoría |
| `rubro` | string | - | `moda` / `gym` / `suplementos` / `deportes` / `tecnologia` |
| `gymrat` | boolean | - | Solo productos tagueados gymrat |
| `pack` | boolean | - | Solo packs/combos (`cantidadUnidades > 1`) |
| `segment` | string | - | `budget` / `standard` / `premium` / `luxury` |
| `precioMin` / `precioMax` | double | - | Rango de precio |
| `orden` | string | `precio_asc` | `precio_asc` / `precio_desc` / `nombre` |

Los cuatro filtros de atributos visuales son single-select y provienen del índice
visual (embeddings de imagen); un producto sin backfill de embeddings no matchea.

**Response:**
```json
{
  "meta": {
    "moneda": "ARS",
    "precioMin": 0,
    "precioMax": 5000000,
    "rangMin": 2999,
    "rangMax": 299999,
    "total": 1156,
    "pagina": 1,
    "pageSize": 24,
    "totalPaginas": 49,
    "facets": {
      "talles": {"S": 45, "M": 67},
      "generos": {"hombre": 120},
      "categorias": {"Zapatillas": 500},
      "marcas": {"Nike": 342, "Adidas": 280},
      "badges": {"below_market": 89, "verified_deal": 45},
      "fits": {"oversize": 120, "slim": 85},
      "estampados": {"liso": 900, "rayado": 40},
      "escotes": {"redondo": 300, "en v": 55},
      "colorDominantes": {"negro": 800, "blanco": 420}
    },
    "marcas": {"Freres": 136, "Sporting": 2444}
  },
  "productos": [
    {
      "sitio": "Sporting",
      "nombre": "Zapatillas Nike Vomero 17",
      "precio": 247999,
      "precioOrig": 309999,
      "descuento": true,
      "url": "https://...",
      "img": "https://...",
      "categoria": "Zapatillas",
      "genero": "hombre",
      "marca": "Nike",
      "talles": ["39","40","41","42","43"],
      "ml": {
        "badge": "verified_deal",
        "badges": ["verified_deal", "trending"],
        "scoreP": 28,
        "ofertaReal": true,
        "tendencia": "bajando",
        "pctil": 28
      }
    }
  ]
}
```

`precioOrig` es `number | null` (`close-1nf-and-3nf-foundation`, D1) — antes
era `string`. Un valor no parseable es `null`, nunca un string vacío ni `"0"`.
`marca` nunca es un nombre de tienda (V19, D3): `?marca=<tienda>` devuelve un
result set vacío, sin el viejo fallback marca→sitio.

---

## GET /facets

Solo facets, sin productos (para cargar filtros rápido).

**Response:** igual al objeto `meta.facets` de `/data`. `badges` cuenta un producto
una vez POR CADA badge que tiene en su set (multi-badge) — la suma de todos los
conteos puede superar el total de productos.

---

## GET /tendencias

Output del pipeline ML para el panel de Tendencias.

**Response:**
```json
{
  "categoriaStats": [
    {"categoria": "Zapatillas", "count": 1173, "avgPrecio": 129837}
  ],
  "topProductos": [
    {"url": "...", "nombre": "...", "precio": 2999, "img": "...", "sitio": "...", "marca": "..."}
  ],
  "trendingClusters": [
    {"cluster": 5, "label": "Remera Nike", "size": 183}
  ],
  "totalProductos": 3034,
  "fecha": "2026-05-29"
}
```

Cuando hay `categoria_stats` persistidas, la response incluye además
`distribucionCategorias.<categoria>` con 12 campos (`n, mean, median, mode,
std, cv, q1, q3, iqr, mad, fence_low, fence_high`; `cv` a 1 decimal, el resto
enteros). La clave es la categoria **canónica** (`"Medias"`, Title Case),
`close-1nf-and-3nf-foundation` V16 — no la salida de `norm_cat`. Ausente
(no la clave `{}`) hasta el próximo run de ML tras la migración, que
regenera la tabla entera.

---

## GET /historial?url=URL

Historial de precios de un producto, para los widgets que lo resumen (el
sparkline de `BuySignal`, el del `DetailPanel`).

**Query params:** `url` (required) — URL canónica del producto

**`204 No Content`** cuando el producto no tiene puntos registrados: un
sparkline sin nada que dibujar no dibuja nada. Una página que igual tiene que
renderizar el producto **no puede usar este endpoint** — para eso está
`GET /producto`.

`min`/`max`/`avg`/`deltaPct` aparecen **sólo desde dos puntos**. Una sola
observación no tiene mínimo, máximo ni variación: tiene un precio. El cuerpo lo
arma `HistorialJson`, compartido con `GET /producto` para que las dos rutas no
puedan divergir.

**Response:**
```json
{
  "puntos": [
    {"fecha": "2026-05-20", "precio": 15990},
    {"fecha": "2026-05-28", "precio": 14990}
  ],
  "min": 14990,
  "max": 15990,
  "avg": 15490,
  "deltaPct": -6.3
}
```

---

## GET /producto/{key}

Un producto y su historial en una sola respuesta. Es la lectura detrás de la
vista dedicada de historial de precios (`/historial?url=` en el frontend).

**Path params:** `key` (required) — el handle corto del producto: 16 hex de
`productos.producto_key`, la columna generada de `V25`. Viene ya calculado en
el campo `key` de cada fila de `GET /data`, así que el frontend nunca tiene que
derivarlo ni ir a buscarlo.

No entra por la URL entera porque como query param era ilegible, había que
encodearla en cada borde y metía el dominio scrapeado adentro de nuestra ruta.
Tampoco por un id sustituto: `productos.url` **es** la clave primaria (clave
natural, igual que `categoria`, `marca` y `sitio_key`), y un id no habría
cambiado ninguna forma normal — ver [`DATABASE.md`](./DATABASE.md) § `V25`.

Se lee de la **base**, no del snapshot en memoria: la página es deep-linkeable y
un producto soft-deleted tiene que seguir siendo inspeccionable, que es justo
cuando su historial es interesante.

- **`404`** — el producto no existe.
- **`200` con `puntos: []`** — el producto existe pero todavía no tiene serie.
  Deliberadamente **no** es un `204`: la página tiene que renderizar el producto
  igual y decir que la serie no está.

**Response:**
```json
{
  "producto": {
    "key": "6f1c2b8a4d3e5079",
    "url": "https://site.com/remera-negra",
    "sitio": "Freres",
    "nombre": "Remera Negra",
    "precio": 15990,
    "precioOrig": 19990,
    "descuento": true,
    "img": "https://...",
    "categoria": "Remera",
    "genero": "hombre",
    "marca": "Nike",
    "rubro": "indumentaria",
    "cantidadUnidades": 1,
    "esPack": false,
    "precioUnitario": 15990,
    "talles": ["M", "L"],
    "ml": { "badge": "", "badges": [], "scoreP": 50 }
  },
  "historial": {
    "puntos": [{"fecha": "2026-05-20", "precio": 15990}],
    "min": 14990, "max": 15990, "avg": 15490, "deltaPct": -6.3
  }
}
```

---

## GET /sitios

Lista de sitios configurados + dinámicos.

**Response:**
```json
{
  "base": [{"nombre": "Freres", "url": "https://..."}],
  "extras": [{"nombre": "MiMarca", "url": "https://...", "plataforma": "shopify"}],
  "precioMinimo": 0,
  "precioMaximo": 5000000,
  "moneda": "ARS"
}
```

---

## POST /sitios

Agrega sitio dinámico (persiste en DB).

**Body:** `{"nombre": "MiMarca", "url": "https://...", "plataforma": "tiendanube"}`

---

## DELETE /sitios/{nombre}

Elimina sitio dinámico de DB y memoria.

---

## PUT /config

Actualiza configuración en runtime.

**Body:** `{"precioMinimo": 0, "precioMaximo": 200000}`

---

## GET /ml/estado

Estado de los modelos ML y del índice visual. Pensado para polling desde el panel.

**Response:**
```json
{
  "hasTextModel": true,
  "hasImageModel": false,
  "textMeta": {"...": "contenido de _models/text_meta.json si existe"},
  "training": {
    "running": true,
    "phase": "training | embedding | idle | timeout | error",
    "pct": 40,
    "msg": "...",
    "startedAt": "2026-07-12T16:00:00Z"
  },
  "embeddingsCount": 2100,
  "totalProductos": 3034,
  "coveragePct": 69.2
}
```

`embeddingsCount` / `totalProductos` / `coveragePct` reportan la cobertura del
índice visual (tabla `image_embeddings` vs catálogo en memoria). Son campos
aditivos: clientes anteriores pueden ignorarlos.

---

## POST /ml/entrenar

Lanza en background (un solo thread, secuencial): re-entrenamiento del
clasificador de texto y luego backfill del índice visual (embeddings).
Retorna inmediatamente.

**Query params:**
| Param | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `images` | boolean | `false` | Incluye entrenamiento del modelo de imagen |
| `epochs` | int | 8 | Epochs del modelo de imagen |

**Responses:**
- `200` `{"status": "started"}` — secuencia iniciada
- `400` `{"error": "Entrenamiento ya en curso"}` — pre-check: ya hay un entrenamiento corriendo
- `409` `{"error": "Entrenamiento ya en curso"}` — carrera entre dos POST simultáneos: este request perdió el CAS y NO inició nada

Progreso via polling de `GET /ml/estado` (`training.phase` pasa por
`training` → `embedding` → `idle`/`error`).

---

## GET /ml/resultado

Snapshot corto del estado de entrenamiento: `{running, phase, pct, msg, done}`.

---

## POST /ml/renormalizar

Re-aplica las reglas actuales de `NormalizerService` sobre el catálogo ya
persistido en la DB (sin re-scrapear). Síncrono — corre antes de cada
entrenamiento de imagen (ver "Pipeline ML" en `CLAUDE.md`).

**Response:**
```json
{
  "totalRevisados": 3034,
  "categoriaCambiada": 12,
  "marcaCambiada": 4,
  "escriturasIntentadas": 14,
  "escriturasAplicadas": 13,
  "escriturasFallidas": 1
}
```
`totalRevisados`/`categoriaCambiada`/`marcaCambiada` describen el diff
**intencional** detectado por `NormalizerService` (significado sin cambios).
`escrituras*` (agent-chat-finetune) son aditivos y describen el resultado
**real** del `UPDATE` en DB: `escriturasIntentadas` = productos con algún
campo cambiado, `escriturasAplicadas` = filas realmente actualizadas,
`escriturasFallidas` = 0 filas afectadas o excepción — un producto que falla
no aborta el resto del batch.

---

## DELETE /db/productos

Vacía `productos` (cascadea `precio_historico`/`precios_externos` por FK, ver
`docs/ARCHITECTURE.md`) y `categoria_stats`. `agent_reclassify_audit`
sobrevive siempre — es un audit trail sin FK a `productos`, por diseño.

`normalize-db-schema-fks-1nf`: `favoritos.url` tiene una FK `RESTRICT` contra
`productos(url)`. Si algún favorito referencia un producto vivo, el endpoint
devuelve **409** con la cantidad bloqueante y **no borra nada** (chequeo y
DELETE comparten transacción, sin condición de carrera entre el conteo y el
borrado). **No existe** un `?force=` — el usuario tiene que desmarcar los
favoritos primero.

**Response (409, favoritos bloqueantes):**
```
No se puede vaciar el catálogo: 3 producto(s) favorito(s) todavía existen.
```

**Response (200, sin favoritos bloqueantes):**
```
Catálogo eliminado.
```

Gateado por scraping igual que el resto de `/db/*`: **409** mientras
`GET /status` está `RUNNING`.

---

## GET /csv

Descarga CSV completo (sin filtrar) con BOM para Excel.

**Headers:** `Content-Disposition: attachment; filename=ofertas.csv`

**Columnas:** Sitio, Nombre, Precio, Precio Original, Categoria, Genero, Talles, URL, Imagen

---

## LLM Catalog Agent (llm-catalog-nlp)

Agente de chat con tool-use, provider-pluggable (env `LLM_PROVIDER`/`LLM_MODEL`/
`LLM_BASE_URL`/`LLM_API_KEY`, ver `.env.example` — todas opcionales, con
defaults locales para Ollama). El agente SOLO tiene herramientas de lectura
(`search_products`, `view_product`, `propose_reclassify`); `propose_reclassify`
NUNCA escribe — valida y devuelve un diff. El único endpoint que escribe es
`POST /agent/apply`, fuera del loop del agente y solo tras confirmación
explícita del usuario en la UI.

`POST /agent/chat` y `POST /agent/apply` están gateados por scraping (igual
que `DELETE /db/productos`): devuelven **409** mientras `GET /status` está
`RUNNING` (evita contención de VRAM entre el LLM local y el modelo visual
Marqo-FashionSigLIP). `GET /agent/models` NO está gateado (metadata de solo
lectura, no toca VRAM).

### POST /agent/chat

**Body:**
```json
{
  "messages": [
    { "role": "user", "text": "mostrame la zapatilla" },
    { "role": "assistant", "text": "Es una Zapatilla Running.",
      "trace": [{ "calls": [{ "name": "view_product", "arguments": {"url": "https://…"} }] }] },
    { "role": "user", "text": "¿y de qué marca es?" }
  ],
  "model": "qwen3:14b"
}
```
`model` es opcional — presente y disponible → se usa para ese request; ausente
→ default de `LLM_MODEL`; presente pero desconocido → `400` (nunca fallback
silencioso).

**`trace` (agent-chat-continuity).** Es la actividad de herramientas de un
turno `assistant` anterior — la lista de *steps* del loop, cada uno con las
llamadas que el modelo emitió en ese step. Devuelto por este mismo endpoint
(campo `trace` de la respuesta) y reenviado tal cual por el cliente en el
siguiente turno.

Contrato explícito: **el cliente manda solo lo que el modelo PIDIÓ (`name` +
`arguments`), nunca lo que el catálogo RESPONDIÓ.** El servidor re-ejecuta cada
llamada contra el snapshot vivo antes de contactar al proveedor, así que:

- ningún resultado de herramienta llega desde el browser (un `trace`
  manipulado no puede inyectarle un "dato del catálogo" al modelo);
- la evidencia replayada está **al día** — después de un `/agent/apply`
  confirmado, un `view_product` replayado devuelve la categoría NUEVA.

Reglas del parseo (todo se descarta por campo, nunca se rechaza el request):
`role` solo puede ser `user` o `assistant` (cualquier otro valor, incluidos
`system` y `tool`, degrada a `user`); `trace` se ignora en turnos `user`; un
nombre de herramienta desconocido se descarta antes de ejecutarse; máximo 8
steps × 6 llamadas por step de transporte, y el servicio aplica encima su
presupuesto `MAX_REPLAY_CALLS = 12`, quedándose con la **cola** más reciente de
la conversación.

Sin `trace`, el historial que vuelve al modelo son respuestas en prosa pelada
sin rastro de que alguna vez se usó una herramienta — el modelo imita ese
transcript, deja de llamar herramientas y el guard de grounding lo rechaza. Ese
era el bug de "funciona una vez y después dice que no puede".

**Responses:**
- `200` `{"assistantText": "...", "outcome": "complete|capability|ungrounded|exhausted", "proposals": [{"url","nombreProducto","categoriaActual","categoriaPropuesta","subCategoriaPropuesta","marcaPropuesta","generoPropuesto"}], "trace": [{"calls":[{"name","arguments"}]}]}`
  — `trace` solo viene poblado en `complete` (las demás outcomes no dejan
  mensaje durable en la conversación, así que no exportan traza)
- `400` — `messages` vacío/ausente, o `model` desconocido
- `409` — scraping en curso
- `502` — proveedor LLM caído (`codigo: proveedor_no_disponible`)

**Grounding (sigue siendo por turno).** El replay reconstruye el contexto pero
**no** otorga grounding: para que la prosa del modelo se entregue, el modelo
tiene que ejecutar una herramienta con resultado válido *en ese turno*. Si
responde sin herramientas, recibe **un** empujón correctivo pidiéndole que la
use y, si insiste, el turno se rechaza (`outcome: ungrounded`) y su texto se
descarta.

### POST /agent/apply

Confirma (fuera del loop del agente) una propuesta de reclasificación devuelta
por `/agent/chat`. Body tipado — acepta el mismo `ReclassifyProposal` que
`/agent/chat` devuelve y `frontend/src/api.js`'s `applyProposal` postea tal
cual (agent-chat-finetune; antes leía un shape de Map distinto y todo click
de confirmación devolvía `400`). Re-valida server-side en 3 pasos
independientes — el cliente nunca se asume validado, ni siquiera con un body
tipado:
1. `url`/`categoriaPropuesta` presentes.
2. `categoriaPropuesta` ∈ taxonomía canónica (`CategoryGroups.canonicalCategories()`).
3. **Staleness guard**: `categoriaActual` del body vs. la categoría real leída
   de la DB (`DatabaseService.obtenerProducto`, no el snapshot en memoria) —
   detecta que el producto cambió entre que se generó la propuesta y que se
   confirmó. Falla cerrado: una lectura vacía (no existe, o error de DB)
   cuenta como conflicto, nunca como "seguro escribir".

Persiste vía `DatabaseService.aplicarReclasificacionAuditada`: UPDATE +
INSERT de auditoría (tabla `agent_reclassify_audit`) en una sola transacción
— si el INSERT de auditoría falla, el UPDATE también se revierte (nunca una
reclasificación sin fila de auditoría, ni una fila de auditoría de algo que
no pasó).

**Body:**
```json
{
  "url": "...",
  "nombreProducto": "...",
  "categoriaActual": "Zapatilla Running",
  "categoriaPropuesta": "Buzo",
  "subCategoriaPropuesta": "...",
  "marcaPropuesta": "...",
  "generoPropuesto": "..."
}
```
(`subCategoriaPropuesta`/`marcaPropuesta`/`generoPropuesto` opcionales — si se
omiten o vienen en blanco se preservan los valores actuales del producto.
Claves desconocidas se ignoran — `@JsonIgnoreProperties(ignoreUnknown = true)`
— así una propuesta reintentada puede seguir cargando las claves de UI
`_applied`/`_mensaje` sin romper el binding.)

**Responses:**
- `200` `{"ok": true, "applied": 1, "mensaje": "..."}`
- `400` `{"ok": false, "mensaje": "..."}` — falta `url`/`categoriaPropuesta`
  (nombra solo lo que falta), categoría inválida, o la url no existe en el
  catálogo
- `422` `{"ok": false, "codigo": "conflicto_stale", "mensaje": "...", "actual": {"categoria", "marca", "genero", "subCategoria"}}`
  — staleness guard: el producto cambió desde que se generó la propuesta;
  `actual` trae los valores reales para que el cliente los muestre sin un
  segundo round-trip
- `500` `{"ok": false, "mensaje": "..."}` — el write falló (0 filas afectadas
  o excepción); nunca se reporta como aplicado
- `409` — scraping en curso

### GET /agent/models

Descubre dinámicamente los modelos disponibles del proveedor activo (ej. los
modelos pulleados en la instancia local de Ollama) — no es una lista
hardcodeada. NO gateado por scraping.

**Response:** `{"available": ["qwen3:14b", "llama3.1:8b"], "default": "qwen3:14b"}`
