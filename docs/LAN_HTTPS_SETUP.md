# Servir el dashboard a otro dispositivo, por HTTPS

> Para probar la app desde un celular o una tablet en la misma red. El backend
> nunca sirve TLS: lo termina un proxy adelante, y esta guía arma ese proxy.
>
> Es la **misma forma** que un deploy real, en chico. Lo único descartable es el
> CA local: en producción se reemplaza por un certificado público y el resto
> queda igual (ver [§8](#8-qué-cambia-en-una-vps)).
>
> En los ejemplos, `192.0.2.10` es un placeholder (rango de documentación,
> RFC 5737). Sustituilo por la IP de tu máquina en la LAN.

---

## 1. Por qué HTTPS y no `http://<ip>` a secas

Porque por HTTP plano **la sesión no se recupera al recargar**, y el síntoma no
dice por qué.

La cookie de refresco se emite con el flag `Secure`
(`ar.scraper.security.RefreshCookie`), así que el navegador la manda sólo por
HTTPS. Los navegadores exceptúan `localhost` de esa regla — por eso en la
máquina de desarrollo todo anda — pero **no exceptúan una IP privada**. Desde el
celular por `http://192.0.2.10:5173` el login parece funcionar, y al recargar la
sesión se perdió: el navegador descartó la cookie en silencio.

Es la misma clase de bug que ya costó una vez en este repo (ver la sección de
gotchas de [`CLAUDE.md`](../CLAUDE.md) sobre dev same-origin), así que la guía
va directo al camino que funciona.

---

## 2. El camino corto: `start lan`

```
./Ejecutar_instalar.sh
# y en el CLI:
start lan
```

Eso es todo. El CLI detecta la IP de la red, genera el certificado, levanta el
terminador TLS, deriva los orígenes y arranca backend y frontend. `stop` baja
también el proxy, y `start` a secas vuelve a local sin reconstruir nada.

Al arrancar imprime la línea que confirma que agarró:

```
modo lan — API en https://192.0.2.10:8444
```

**Si no ves esa línea, el modo no se aplicó** y estás en local.

| Requisito | |
|---|---|
| Docker | Sólo para `lan`; el terminador corre en un contenedor. `local` no lo necesita |
| `mkcert` | Opcional pero recomendado: `sudo apt install mkcert`. Sin él el certificado es autofirmado y el dispositivo advierte en cada puerto |

---

## 3. Qué hace por debajo

No hace falta para usarlo; sirve para diagnosticar, y para armar el TLS de un
deploy real, donde el proxy lo vas a escribir vos.

**La IP** se detecta abriendo un socket UDP que no envía nada: sólo hace que el
kernel elija la interfaz por la que rutearía hacia afuera. Enumerar interfaces
obligaría a adivinar entre `docker0`, `lxcbr0` y `virbr0`, ninguna alcanzable
desde un celular. `SCRAPPY_LAN_IP` la pisa.

**El certificado** lo firma el CA local de `mkcert` cuando está instalado, así el
dispositivo puede confiar en él de verdad en vez de que le digas que ignore su
propia advertencia. El CA se copia en dos formatos: PEM para Android y **DER
bajo una URL `.cer`**, porque iOS no abre un PEM y sin eso Safari nunca ofrece
instalar el perfil.

**El proxy** es un nginx que termina TLS en `8443` (frontend) y `8444` (backend),
y sirve el CA por HTTP plano en `8081` — tiene que ser plano: el dispositivo
necesita bajar el CA *antes* de poder confiar en algo. Ese puerto responde 404 a
todo lo demás.

Tres detalles del proxy que no son cosméticos, y que valen para cualquier
terminador que escribas después:

- **`--network host`, no una red bridge.** El backend sólo le cree a los headers
  `X-Forwarded-*` que vengan de loopback
  (`server.tomcat.remoteip.internal-proxies`, ver [`ARCHITECTURE.md`](./ARCHITECTURE.md)).
  En bridge el peer es una dirección `172.x`, todos los headers se descartan,
  `isSecure()` queda en `false` y la cookie `Secure` no pega nunca. El síntoma es
  idéntico al de HTTP plano.
- **`X-Forwarded-Proto: https`** es lo que hace que el backend se vea a sí mismo
  como seguro. Sin eso, todo lo demás es decoración.
- **`error_page 497`** convierte el "HTTP plano contra un puerto HTTPS" de nginx
  en un redirect. Escribir la IP pelada hace que el navegador intente `http://`
  primero, y el 400 crudo no dice nada útil en un celular.

**Los orígenes** salen de la IP y esos puertos. `SCRAPPY_FRONTEND_ORIGIN` y
`SCRAPPY_BACKEND_ORIGIN` los pisan, para un túnel o un deploy cuyo nombre esta
máquina no puede deducir.

> **El modo no se guarda en ningún archivo, y es deliberado.** `apply_mode`
> (`cli/core/runtime_config.py`) muta el `.env` ya parseado, nunca el archivo.
> Antes esto se resolvía parcheando `.env` y `frontend/.env`, y esa escritura
> persistente dejaba la app apuntando a un proxy apagado cuando el proxy bajaba:
> el backend arrancaba bien, el frontend respondía 200, y la app no andaba.

> **`lan` nunca cae a localhost.** Un bundle que desde un celular llama a
> `localhost:3000` está llamando al celular: la app carga, no anda, y nada en
> pantalla lo explica.

Cómo llega el origen al bundle: `frontend/src/api.js` lee `window.__API_BASE__` y
cae a `VITE_API_BASE_URL` sólo si está vacío. Ese global lo setea
`dist/config.js`, que el CLI reescribe en cada `start` — por eso **un solo build
sirve los dos modos** y cambiar de modo no reconstruye nada.

---

## 4. Confiar en el CA desde el dispositivo

Abrí el archivo con el **navegador del sistema** — en iOS tiene que ser Safari,
Chrome no dispara el instalador de perfiles:

```
iOS      http://192.0.2.10:8081/dev-ca.cer
Android  http://192.0.2.10:8081/rootCA.pem
```

`http`, sin `s`: el dispositivo todavía no confía en nada de este proxy.

> **Verificá que bajaste el tuyo.** Ese endpoint es HTTP plano, o sea sustituible
> por cualquiera en la red. Comparalo antes de confirmar:
>
> ```bash
> openssl x509 -in rootCA.pem -noout -fingerprint -sha256
> ```

**Android:** Ajustes → Seguridad → Cifrado y credenciales → Instalar certificado
→ **Certificado de CA** → elegí el archivo descargado.

**iOS, y son dos pasos separados:**

1. Ajustes → **"Perfil descargado"** (arriba de todo) → Instalar. Si no aparece:
   Ajustes → General → **VPN y gestión de dispositivos**.
2. Ajustes → General → Información → **Ajustes de confianza de certificados** →
   activá el switch del CA.

> **El switch del paso 2 no existe hasta que el paso 1 esté hecho.** Si abrís ese
> menú y está vacío, el perfil no se instaló — no es que falte el toggle. La
> causa más común es haber servido un `.pem`: iOS no lo abre, necesita DER bajo
> una URL `.cer`.

Después: `https://192.0.2.10:8443`

Con el CA confiado, la barra muestra el candado limpio y **un solo puerto
alcanza**. Si tuviste que visitar `:8444` para que `:8443` funcionara, estás
usando excepciones de certificado en vez del CA — anda, pero se caen solas al
limpiar datos del navegador.

Cuando el dispositivo ya confía, el puerto `8081` no hace falta más.

---

## 5. Verificar que quedó bien

```bash
# 1. El redirect de http:// a https://
curl -s -o /dev/null -w '%{http_code} -> %{redirect_url}\n' http://192.0.2.10:8443/

# 2. El backend se ve a sí mismo como seguro.
#    Spring Security emite HSTS SÓLO si el request es seguro, así que este
#    header es la prueba de que los X-Forwarded-* se creyeron y de que la
#    cookie Secure va a viajar. Es la verificación que más importa.
curl -sk -i https://192.0.2.10:8444/ | grep -i strict-transport-security

# 3. CORS desde el origen del dispositivo
curl -sk -i -X OPTIONS https://192.0.2.10:8444/api/auth/login \
  -H 'Origin: https://192.0.2.10:8443' \
  -H 'Access-Control-Request-Method: POST' | grep -i access-control-allow

# 4. A qué backend apunta ESTA corrida. Ya no está en el bundle: lo reescribe
#    el CLI en cada `start`, así que este archivo es la fuente de verdad.
cat frontend/dist/config.js
```

El control negativo importa tanto como el positivo: pedile lo mismo que (2) a la
IP de la LAN **sin** pasar por el proxy y con un `X-Forwarded-Proto: https`
inventado. No tiene que aparecer HSTS — si aparece, la allowlist de proxies está
demasiado abierta y cualquiera en la red puede falsear su IP de origen.

La prueba final no es un `curl`: **logueate desde el dispositivo y recargá la
página.** Si seguís adentro, la cookie viajó.

---

## 6. Qué cambia en una VPS

Se conserva la forma: proxy que termina TLS, backend en HTTP detrás,
`forward-headers-strategy` y la allowlist de proxies. Lo que cambia:

| | LAN | VPS |
|---|---|---|
| Certificado | CA local | Let's Encrypt con dominio real |
| Postgres | `trust` para desarrollo | contraseña real; el `POSTGRES_PASSWORD` del compose tiene un default que hay que cambiar |
| Backend | publicado en la LAN | publicado **sólo en loopback**, detrás del proxy. `docker-compose.yml` mapea `3000:3000`, que en una VPS es la API en internet, sin TLS |
| `internal-proxies` | loopback alcanza | loopback si el proxy corre en la misma caja; la IP del contenedor o del balanceador si no |
| Secretos | generados por el CLI | `AUTH_JWT_SECRET` y `ADMIN_BOOTSTRAP_PASSWORD` propios |

> **Cuidado con cómo partís los dominios.** `app.dominio.com` +
> `api.dominio.com` funciona: `SameSite=Strict` se calcula por eTLD+1, así que
> siguen siendo same-site y la cookie viaja. Front y back en **dominios
> distintos** son cross-site, `Strict` no la manda, y la recuperación de sesión
> se rompe — mismo síntoma que por HTTP plano, otra causa.

---

## 7. Problemas frecuentes

| Síntoma | Causa |
|---|---|
| `400 Bad Request: plain HTTP request was sent to HTTPS port` | Escribiste la IP pelada y el navegador probó `http://`. Lo cubre el `error_page 497`, ver [§3](#3-qué-hace-por-debajo) |
| La app carga pero todo queda vacío o cargando | El navegador no confía en el certificado del **backend**. Con excepciones manuales hay que aceptarlas puerto por puerto; con el CA confiado no pasa |
| Login OK, pero al recargar te expulsa | La cookie `Secure` no viaja: o estás en HTTP plano, o el proxy no corre en loopback y los `X-Forwarded-*` se descartaron |
| El bundle sigue llamando a `localhost:3000` | Arrancaste con `start` en vez de `start lan`. La línea `modo lan — API en …` al arrancar es la confirmación de que agarró |
| Cambiaste de red y no anda | `stop` y `start lan` de nuevo: la IP se detecta en cada arranque. No hace falta reconstruir |
| `lan` falla diciendo que no encuentra Docker | El terminador TLS corre en un contenedor. Instalá Docker, o usá `start` (local), que no lo necesita |
| El bind de Postgres sigue abierto | El mapeo de puertos se fija al **crear** el contenedor: hay que recrearlo (`docker rm -f` + `scripts/dev-db.sh up`). El volumen es nombrado, los datos sobreviven |
