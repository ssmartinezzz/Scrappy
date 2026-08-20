# Contrato de autenticación para el frontend

> **Qué es este archivo.** El backend de autenticación, autorización, sesiones y
> reseteo de contraseña está **terminado y sin cliente de browser**. Se construyó
> a propósito antes que el frontend, para que el SDD de frontend sea únicamente
> *consumir y diseñar vistas*. Esto es lo que hay para consumir, y —más
> importante— las trampas que ya conocemos y que no queremos que redescubras a
> los golpes.
>
> **Base de trabajo:** rama `feature/user-accounts-and-roles`, no `master`.
> Todo lo descrito acá vive ahí.
>
> **Referencia completa de cada endpoint:** [`API_REFERENCE.md`](./API_REFERENCE.md).
> Este documento no la repite: cuenta lo que un cliente necesita *decidir*.

---

## Lo primero: el estado en que te lo entregamos

**El API está cerrado.** Desde el slice de enforcement, toda ruta `/api/*` exige
un access token salvo seis excepciones, y una ruta sin regla se rechaza en vez de
permitirse. El dashboard React actual **no funciona** contra este backend: no
manda ningún header. Repararlo es el primer trabajo del SDD de frontend, no un
efecto colateral inesperado.

Las seis rutas abiertas, que son toda la superficie sin credencial:

| | |
|---|---|
| `OPTIONS /**` | preflight de CORS — no lleva `Authorization` por definición |
| `POST /api/auth/login` | |
| `POST` y `DELETE /api/auth/refresh` | rotar y cerrar sesión |
| `POST /api/auth/password-reset/request` | |
| `POST /api/auth/password-reset/confirm` | |
| `GET /` | liveness |

---

## Las tres piezas y dónde vive cada una

| Pieza | Dónde va | Vida | Quién la maneja |
|---|---|---|---|
| **Access token** | header `Authorization: Bearer`, **en memoria** | 15 min | tu código |
| **Refresh token** | cookie `refresh`, `HttpOnly` | 14 días | **el browser, no vos** |
| **Nonce CSRF** | body JSON al recibirlo, header `X-Refresh-CSRF` al mandarlo | mientras dure el refresh token | tu código, en memoria |

**El access token va en memoria, no en `localStorage`.** En `localStorage`
sobrevive al reload pero lo lee cualquier script inyectado. En memoria muere con
el reload — y eso está bien, porque la sesión se recupera con la cookie.

**El refresh token no lo vas a ver nunca.** Es `HttpOnly`: no aparece en
`document.cookie` y no está en ningún body de respuesta.

**`credentials: 'include'` va en DOS llamadas, no en una: `/api/auth/login` y
`/api/auth/refresh`.** Y el login es el que se olvida, porque uno piensa en la
cookie como algo que se *manda* y ahí todavía no existe. Pero **la respuesta del
login es la que la planta**, y cross-origin el browser **descarta el
`Set-Cookie`** de una respuesta cuyo pedido no se hizo en modo credenciales. Sin
eso la cookie no se guarda nunca, y la recuperación de sesión al recargar no
puede funcionar — no falla a veces: no funciona.

> ⚠️ Este documento afirmaba lo contrario —"`credentials` sólo en `/refresh`"— y
> estaba mal. El bug sobrevivió a 1570 tests de backend y 148 de frontend, y
> apareció recién en la suite de browser de la fase 8 (`tests/e2e/run-e2e.sh`).
> **Bajo `vite dev` no se ve**: el proxy hace que el login sea same-origin y la
> cookie se guarda igual. Sólo se manifiesta en las topologías que se instalan
> de verdad. Si vas a tocar esta superficie, corré la suite cross-origin.

CORS con credenciales está habilitado exactamente en esas dos rutas y en ninguna
más. Agregar una tercera es una decisión de seguridad, no un detalle.

---

## Las cuatro trampas que ya conocemos

### 1. Single-flight en el refresh, o te vas a deslogear solo

Si N pedidos reciben 401 a la vez y cada uno dispara su propio refresh, el
segundo y siguientes presentan un token **ya rotado** — y eso **dispara nuestra
propia detección de reuso contra vos mismo**. El backend revoca la familia
entera y el usuario queda afuera, sin haber hecho nada malo.

Hay una ventana de gracia de 10 segundos que devuelve el mismo par sucesor en vez
de gritar robo, pero **no la trates como la solución**: es red de seguridad, no
diseño. Compartí una sola promesa de refresh entre todos los pedidos en vuelo.

Y ojo con algo que hace esto más necesario que en otros diseños, no menos: **no
podés inspeccionar el token para saber si está viejo**, porque no lo tenés. La
única señal es el 401.

### 2. `401` y `403` significan cosas distintas — no los trates igual

- **401** → no sé quién sos, o tu credencial no sirve. **Refrescá y reintentá.**
- **403** → sé quién sos y la respuesta es no. **Reautenticar no ayuda.** Mostrá
  un error de permisos.

Confundirlos hace que un token vencido se vea como "no tenés permiso", o que un
VIEWER golpeando una ruta ADMIN entre en un loop de refresh que nunca sirve.

### 3. `SameSite` ignora el puerto — por eso existe el nonce

`SameSite=Strict` no alcanza acá: **cualquier página en cualquier otro puerto de
`localhost` es same-site con este backend**. Por eso el refresh además exige el
nonce en `X-Refresh-CSRF`, que una página ajena no puede setear.

Guardá el nonce en memoria junto al access token y **reemplazalo en cada
respuesta**: rota con el token.

### 4. La topología puede romper la sesión en silencio

`SameSite` usa el dominio registrable; el esquema cuenta, el puerto no.

| Topología | ¿Anda? |
|---|---|
| `localhost:5173` → `localhost:3000` | **Sí** |
| `app.example.com` → `api.example.com` | **Sí** |
| `app.example.com` → `api.example.net` | **No** — cross-site, se descarta la cookie |
| `http://` front → `https://` back | **No** — cross-scheme |
| Fuera de localhost por HTTP plano | **No** — `Secure` la descarta |

Cuando falla, **no hay ningún error a la vista**: el refresh simplemente no lleva
cookie y al usuario lo desloguean cada 15 minutos. Parece un bug del frontend y
no lo es.

---

## Flujos, en el orden en que los vas a escribir

### Login

```
POST /api/auth/login   { username, password }
→ 200 { accessToken, tokenType: "Bearer", expiresIn: 900, csrfNonce }
       Set-Cookie: refresh=…; HttpOnly; Secure; SameSite=Strict; Path=/api/auth/refresh
→ 401 { error: "credenciales_invalidas", … }
```

Guardá `accessToken` y `csrfNonce` en memoria. La cookie ya está puesta.

**Identifica por `username`, nunca por email.** El email es opcional y las
cuentas bootstrap y de servicio no tienen.

**Todas las fallas son idénticas** — password incorrecta, usuario inexistente,
cuenta desactivada, body malformado: mismo status y mismo body. No intentes
distinguirlas para dar un mensaje más específico; la uniformidad es deliberada y
el backend además iguala los tiempos.

### Recuperar la sesión al recargar la página

No hay `localStorage` que leer. Al arrancar la app, llamá a
`POST /api/auth/refresh` con `credentials: 'include'` — **sin** `X-Refresh-CSRF`,
porque después de un reload no tenés el nonce:

- si la cookie sigue viva y el pedido cumple la admisión de arranque en frío
  (ver abajo) → 200 con un access token y un nonce nuevos, sesión recuperada
- si no → 401 (`refresh_invalido`/`sesion_invalidada`) o 403 (`csrf_invalido`), mostrá el login

**Admisión de arranque en frío ("bootstrap"), el mecanismo que se shippeó.**
Una versión anterior de este documento describía acá un escape hatch —
"el backend acepta el refresh sin nonce sólo si la fila no tenía uno"— que
resultó ser **código muerto**: `RefreshTokenService.emitir()` genera un nonce
en **toda** emisión, así que una fila sin nonce nunca existe. Seguir esa regla
al pie de la letra significaba que un refresh sin nonce recibía siempre
`403 csrf_invalido`, nunca la recuperación silenciosa que el arranque necesita.

Lo que se implementó en su lugar: un refresh sin `X-Refresh-CSRF` (bootstrap)
se admite sólo si **las dos** condiciones valen —

| # | Condición |
|---|---|
| 1 | `Origin` presente y coincide **exactamente** (string, con puerto) con una entrada de `APP_CORS_ALLOWED_ORIGINS` |
| 2 | `Sec-Fetch-Site` presente y ∈ {`same-origin`, `same-site`} |

Si falta cualquiera de los dos headers, o el valor no matchea, el backend
**falla cerrado**: `403 csrf_invalido`, igual que hoy. El camino de bootstrap
no otorga ninguna otra leniencia — un token revocado, vencido o reusado se
comporta exactamente igual que en el camino con nonce; lo único que cambia es
que la ausencia del nonce deja de ser, por sí sola, un rechazo.

**Por qué NO alcanza con `Sec-Fetch-Site: same-origin` solo** — la opción que
este documento recomendaba antes de medir las topologías reales: **ninguna
instalación que se shippea de esta app es same-origin**. El SPA vive en
`localhost:5173` (portable/POSIX, `npm run preview`) o `localhost:8080`
(Docker); el backend siempre en `localhost:3000`. Un refresh legítimo manda
`Sec-Fetch-Site: same-site`, nunca `same-origin` — eso sólo pasa bajo
`vite dev`, con el proxy y una `VITE_API_BASE_URL` relativa vacía. Exigir
`same-origin` habría rechazado el arranque en frío en **las dos**
instalaciones que se shippean, y sólo hubiera andado en dev. Peor: como
`same-site` es también exactamente lo que manda una página servida desde otro
puerto de `localhost` —el atacante contra el que el nonce existe—,
`Sec-Fetch-Site` solo no discrimina nada en este caso. La señal que sí
discrimina es `Origin`: lleva el puerto (a diferencia de `SameSite`, que lo
ignora), es un forbidden header name que un script no puede forjar, y el
backend ya tiene un allow-list exacto y validado al arranque para compararlo.
Por eso `Origin` es el gate primario y `Sec-Fetch-Site` queda como segundo
gate — fail-closed, no la única señal.

**Del lado del cliente** esto es un carve-out acotado a **un** reintento sin
nonce, en dos lugares — el bootstrap en sí, y un refresh con nonce que volvió
`403 csrf_invalido` (nonce stale porque otra pestaña ya rotó primero). Todo
otro `403` de la app sigue siendo terminal y nunca dispara un refresh.

### Refresh

```
POST /api/auth/refresh
  Cookie: refresh=…            (la manda el browser; usá credentials:'include')
  X-Refresh-CSRF: <nonce>
→ 200 { accessToken, expiresIn, csrfNonce }  + Set-Cookie con el sucesor
→ 403 { error: "csrf_invalido" }             el token queda INTACTO
→ 401 { error: "refresh_invalido" }          desconocido/vencido/revocado
→ 401 { error: "sesion_invalidada" }         reuso detectado — familia revocada
```

Ante `sesion_invalidada`, no reintentes: mandá al login.

### Logout

```
DELETE /api/auth/refresh   + X-Refresh-CSRF
```

Revoca la familia entera —todos los dispositivos— y limpia la cookie. Es `DELETE`
sobre `/refresh` y no `/logout` porque el `Path` de la cookie sólo la adjunta a
esa ruta.

### Reseteo de contraseña

```
POST /api/auth/password-reset/request   { email }   → SIEMPRE 202, mismo body
POST /api/auth/password-reset/confirm   { token, password }
```

El link llega como `…/reset-password#token=…` — **el token está en el fragmento**,
así que tu ruta tiene que leerlo de `location.hash`, no de la query. Y **borralo
con `history.replaceState` antes de cualquier llamada de red**, o se va a filtrar
por `Referer` a cualquier recurso de terceros que cargue la página.

Mínimo 8 caracteres. Un reseteo exitoso **cierra todas las sesiones del usuario**,
así que después hay que loguear de nuevo.

> Con el canal por defecto (`console`) el link se escribe en `scraper.log`. Para
> desarrollo alcanza y sobra.

---

## La matriz ADMIN / VIEWER

La fuente de verdad es `ar.scraper.security.ApiRoutePolicy.TABLE` — una sola
tabla, legible de un vistazo. Lo que el frontend necesita saber:

**Sólo ADMIN:** administración de usuarios (`/api/usuarios/**`, todavía no
existe), todo `/api/agent/**`, todo `/api/cron/**` (lecturas incluidas),
`POST /api/scrape`, `PUT /api/config`, escrituras de `/api/sitios`, todo
`/api/db/**` (incluido `GET /api/db/export`), las mutaciones de ML, `DELETE
/api/data`, y las escrituras de `/api/financiacion/presets`.

**VIEWER llega a todo lo demás**: catálogo, facetas, CSV, grupos, mejores,
tendencias, historial, outfits, suplementos, recomendados, inflación, marcas,
`GET /api/sitios`, `GET /api/ml/estado` y `/api/ml/resultado`, y sus propios
favoritos, feedback y outfits guardados.

**El rol no viene en el token** — se relee de la base en cada request. No
intentes decodificar el JWT para saber qué mostrar: no está ahí, y si estuviera
no sería confiable. Para armar el menú llamá a `GET /api/auth/me` — devuelve
`{ username, roles: [...] }` — después de login, después de la recuperación de
sesión al recargar, y después de **cada** refresh exitoso, no sólo el de
arranque, así un cambio de rol server-side aparece dentro de una vida de token
(15 min) en vez de recién en el próximo login.

---

## Cosas que el backend NO tiene y quizás esperes

- **`GET /api/auth/me` ya existe** — devuelve `{ username, roles: [...] }` del
  sujeto autenticado, leído de la base en cada request (nunca del token).
  Ver la sección de arriba y [`API_REFERENCE.md`](./API_REFERENCE.md).
- **La administración de usuarios YA existe** (`GET`/`POST /api/usuarios`,
  `PUT /api/usuarios/{username}/rol`, `DELETE /api/usuarios/{username}`,
  `PUT /api/usuarios/{username}/activar`) pero **sin UI**: es ADMIN-only y se
  maneja por `curl`. Una pantalla es tuya si la querés.
- **La cuenta admin inicial no tiene email**, así que **no es reseteable**. Darle
  una dirección sigue siendo SQL directo: el endpoint de creación acepta `email`,
  pero no hay uno para editarlo en una cuenta existente.
- **Cambiar `ADMIN_BOOTSTRAP_PASSWORD` y reiniciar no cambia la password** de una
  cuenta ya creada. El seeder nunca pisa un hash existente.

---

## Puntas sueltas que te dejamos, honestamente

| Punta | Estado |
|---|---|
| La ventana de gracia de 10 s | Propuesta, no medición. Medirla necesita justo el cliente que vos vas a escribir |
| Los topes de rate-limit del reseteo (3/h por dirección, 10/h por IP, 100/h global) | Propuestas, sin validar contra tráfico real |
| Parámetros de Argon2id | Medidos en Linux (76 ms), sin medir en el Windows portable |
