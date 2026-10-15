# Avisos de baja de precio por mail — puesta en marcha

Cuando un scrape baja el precio de un favorito más de 5%, su dueño recibe **un mail por
corrida** con todas sus bajas. Este documento es para quien instala: cómo dejar el envío
andando, cómo comprobarlo y qué mirar cuando no llega. El diseño (outbox, relay, por qué no
Kafka) está en [`ARCHITECTURE.md`](./ARCHITECTURE.md); el esquema, en [`DATABASE.md`](./DATABASE.md).

## En cinco pasos

1. **Elegí un proveedor** y creá una *contraseña de aplicación* (tabla de abajo).
2. En la consola del CLI: `mail`. Responde proveedor, usuario, contraseña y remitente, y
   escribe las claves `SMTP_*` en `.env` sin tocar lo demás. La contraseña no se muestra.
3. `stop` y `start`: el backend lee `.env` sólo al arrancar.
4. Mirá el log del backend (`logs backend`): tiene que haber **una** línea `[SMTP] OK: …`.
5. Probá el envío: `mail test tu@mail.com`, o el botón **Probar mail** en
   `/admin/manage/users`. Tiene que llegar un mail de prueba.

Después, cada cuenta que quiera avisos necesita un **email cargado** (lo carga un ADMIN con
el lápiz del panel de cuentas) y el aviso activado en `/favoritos`.

No hay otro interruptor: con `SMTP_HOST` el relay manda; sin `SMTP_HOST` drena la cola y sólo
anuncia el mail en el log.

## Proveedores

| Proveedor | `SMTP_HOST` | `SMTP_PORT` | Contraseña |
|---|---|---|---|
| Gmail | `smtp.gmail.com` | 587 | **Contraseña de aplicación** (<https://myaccount.google.com/apppasswords>, exige verificación en dos pasos). La clave normal da `535` |
| Outlook / Office 365 | `smtp.office365.com` | 587 | Contraseña de aplicación si la cuenta tiene verificación en dos pasos; en cuentas de empresa el administrador puede tener SMTP autenticado deshabilitado |
| Yahoo | `smtp.mail.yahoo.com` | 587 | Contraseña de aplicación (Seguridad de la cuenta) |
| Otro | el de tu proveedor | 587 (STARTTLS) o 465 (SSL/TLS) | La que pida |

Puerto 465 es TLS implícito; cualquier otro habla SMTP plano y sube a TLS con STARTTLS si el
servidor lo ofrece. Google muestra la contraseña de aplicación con espacios (`abcd efgh …`):
el CLI los quita.

## Qué hace cada clave

Todas van en `.env` (Docker: `docker.env`). Sólo las `SMTP_*` y, en LAN, `NOTIFICACIONES_API_BASE_URL`
hace falta tocar; el resto tiene un default que sirve.

| Clave | Default | Para qué |
|---|---|---|
| `SMTP_HOST` / `SMTP_PORT` | vacío / 587 | Servidor. Vacío = no se envía nada |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | vacío | Credenciales. Ambas o ninguna (un relay sin autenticación) |
| `SMTP_FROM_ADDRESS` | el usuario | Remitente. Si queda vacío, el usuario tiene que ser un mail |
| `NOTIFICACIONES_API_BASE_URL` | `http://localhost:3000` | Origen **público del backend**: arma el enlace de baja de un clic de cada mail. Si el mail se lee en otro dispositivo, tiene que ser alcanzable desde ahí (en LAN, el origen `https://<ip>:…`). El CLI lo completa si conoce un origen no-loopback |
| `NOTIFICACIONES_ENABLED` | `true` | `false` apaga el relay; las bajas quedan encoladas |
| `NOTIFICACIONES_INTERVALO_MS` | 15000 | Cada cuánto mira la cola (además del aviso inmediato por LISTEN) |
| `NOTIFICACIONES_LOTE` | 200 | Destinatarios por tanda |
| `NOTIFICACIONES_MAX_INTENTOS` | 5 | Reintentos antes de `FALLIDA` |
| `NOTIFICACIONES_BACKOFF_BASE_SEGUNDOS` | 60 | Base del backoff exponencial entre reintentos |
| `NOTIFICACIONES_LEASE_SEGUNDOS` | 300 | Reserva de un lote; debe superar lo que tarda en enviarse |
| `NOTIFICACIONES_MAX_POR_MINUTO` | 0 | Tope de mails por minuto de todo el cluster (límite del proveedor); 0 = sin tope |
| `NOTIFICACIONES_RETENCION_DIAS` / `…_PURGA_CADA_HORAS` | 30 / 6 | Cuánto se guardan las filas ya enviadas o fallidas |

## Cómo verificar

- **Al arrancar**: con `SMTP_HOST` puesto, el backend revisa la configuración y prueba la
  conexión en un hilo aparte. Deja **una** línea `[SMTP]`; nunca demora ni tumba el arranque.
- **Bajo demanda**: `mail test [destino]` en el CLI o **Probar mail** en el panel de cuentas
  (`POST /api/notificaciones/correo-prueba`, sólo ADMIN). Sin destino manda al email del ADMIN
  que llama; el CLI manda al remitente configurado. La respuesta enmascara las direcciones.
- **En la base**: `SELECT estado, count(*) FROM notificacion_precio GROUP BY 1;` o los paneles
  del dashboard de Grafana (pendientes, fallidas, antigüedad de la más vieja).

Ninguna línea de log ni respuesta lleva direcciones completas ni secretos.

## Cuando no llega

Buscá la línea en `logs backend`:

| Línea | Qué pasó | Qué hacer |
|---|---|---|
| `[SMTP] OK: host:587 (STARTTLS) acepta conexiones…` | El servidor aceptó la conexión | Si igual no llega: ¿la cuenta tiene email y el aviso activo? ¿Cayó en spam? |
| `[SMTP] configuración incompleta: SMTP_PORT=…` / `SMTP_HOST debe ser sólo el nombre…` / `SMTP_USERNAME … SMTP_PASSWORD vacío` / `SMTP_FROM_ADDRESS está vacío…` | La configuración no puede funcionar; ni se intentó conectar | Corregí la clave que nombra (`mail` la reescribe) y reiniciá |
| `[SMTP] El servidor rechazó el usuario o la contraseña.` | Credenciales rechazadas (`535`) | Con Gmail/Outlook/Yahoo, usá una **contraseña de aplicación**, no la de la cuenta |
| `[SMTP] No se pudo conectar a host:puerto.` | Nada escucha, o un firewall corta | Revisá host y puerto (587 = STARTTLS, 465 = SSL/TLS) y la salida de red |
| `[SMTP] No se encontró el host …` | El nombre no resuelve | Revisá `SMTP_HOST` (typo, sin `smtp://`) |
| `[SMTP] Se agotó el tiempo esperando a …` | Conecta pero no responde | Puerto equivocado o filtrado |
| `[SMTP] Falló el handshake TLS con …` | Modo TLS equivocado | Probá el otro puerto (587 ↔ 465) |
| `[NOTIF] SMTP_HOST sin configurar, no se envía el mail …` | No hay SMTP: la cola se drena sin enviar | Configurá SMTP (pasos de arriba) |
| `[NOTIF] fallo al avisar a x**@…: …` | El servidor aceptó la conexión y rechazó ese destinatario o mensaje | Un `5xx` de destinatario es `FALLIDA` de inmediato; un `4xx` o un corte se reintenta con backoff |
| `[NOTIF] N bajas enviadas, M con error` | Resumen de cada tanda | `M` > 0 sostenido: mirá la línea anterior |

El mail de prueba responde con el mismo diagnóstico: `502 smtp_fallo` trae el motivo y la
pista; `409 smtp_no_configurado` es `SMTP_HOST` vacío; `409 sin_destino`, que el ADMIN no
tiene email y no mandó uno.

## Límites conocidos

- Un mail **aceptado** por el servidor y rebotado después no deja rastro: nada recibe los rebotes.
- Cambiar las claves `SMTP_*` exige reiniciar el backend.
- Un ADMIN puede mandar el mail de prueba a cualquier dirección; el contenido es fijo.
