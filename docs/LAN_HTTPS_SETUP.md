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

## 2. Prerequisitos

| | |
|---|---|
| Docker | Para el proxy y, si la usás, la base de desarrollo |
| `mkcert` | `sudo apt install mkcert`. Sin esto el certificado es autofirmado y el dispositivo va a advertir en cada puerto |
| `openssl` | Para convertir el CA a DER (iOS lo necesita) |
| La app instalada | `./Ejecutar_instalar.sh` corrido al menos una vez, para que exista el `.env` |

Averiguá la IP de tu máquina en la LAN:

```bash
ip route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p'
```

Las direcciones de Docker, LXC o libvirt (`172.17.x`, `10.0.3.x`, `192.168.122.x`)
**no** sirven: desde el celular no se llega a ninguna.

---

## 3. El certificado

```bash
mkcert -cert-file dev-lan.crt -key-file dev-lan.key \
  192.0.2.10 localhost 127.0.0.1

# el CA que el dispositivo va a tener que confiar
cp "$(mkcert -CAROOT)/rootCA.pem" .

# iOS no abre un PEM: necesita DER servido bajo una URL .cer
openssl x509 -in rootCA.pem -outform der -out dev-ca.cer
```

Guardalos en un directorio que git ignore. `_tools/` sirve: está ignorado entero.

> **La clave privada del CA (`rootCA-key.pem`) se queda donde `mkcert` la puso.**
> No la copies al directorio que vas a montar en el proxy. Es la única pieza
> secreta de todo esto: quien la tenga puede firmar certificados que tus
> dispositivos van a creer, para cualquier sitio.

---

## 4. El proxy TLS

```nginx
events {}
http {
  proxy_http_version 1.1;
  proxy_set_header Host              $host;
  proxy_set_header X-Real-IP         $remote_addr;
  proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto https;
  proxy_set_header X-Forwarded-Host  $host;
  proxy_set_header Upgrade           $http_upgrade;
  proxy_set_header Connection        "upgrade";
  client_max_body_size 25m;

  # HTTP plano, sólo para entregar el CA: el dispositivo tiene que poder
  # descargarlo ANTES de confiar en nada de este proxy. Todo lo demás, 404.
  server {
    listen 8081;
    location = /rootCA.pem { alias /certs/rootCA.pem; default_type application/x-x509-ca-cert; }
    location = /dev-ca.cer { alias /certs/dev-ca.cer; default_type application/x-x509-ca-cert; }
    location / { return 404; }
  }

  server {
    listen 8443 ssl;
    ssl_certificate     /certs/dev-lan.crt;
    ssl_certificate_key /certs/dev-lan.key;
    # 497 es el código interno de nginx para "HTTP plano contra un puerto HTTPS".
    # Escribir la IP pelada hace que el navegador intente http:// primero, y sin
    # esto el celular queda en un 400 que no explica nada.
    error_page 497 =301 https://$host:8443$request_uri;
    location / { proxy_pass http://127.0.0.1:5173; }
  }

  server {
    listen 8444 ssl;
    ssl_certificate     /certs/dev-lan.crt;
    ssl_certificate_key /certs/dev-lan.key;
    error_page 497 =301 https://$host:8444$request_uri;
    location / { proxy_pass http://127.0.0.1:3000; }
  }
}
```

```bash
docker run -d --name dev-lan-tls --network host \
  -v "$PWD/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v "$PWD:/certs:ro" \
  nginx:1.27-alpine
```

> **`--network host` es obligatorio, no una comodidad.** El backend sólo le cree
> a los headers `X-Forwarded-*` que vengan de **loopback**
> (`server.tomcat.remoteip.internal-proxies`, ver
> [`ARCHITECTURE.md`](./ARCHITECTURE.md)). En una red bridge el peer sería una
> dirección `172.x`, todos los headers se descartarían, `isSecure()` quedaría en
> `false` y la cookie `Secure` no pegaría nunca. El síntoma sería idéntico al de
> HTTP plano.

---

## 5. Los orígenes: **dos** archivos `.env`, no uno

Esta es la parte donde es fácil equivocarse en silencio.

| Archivo | Clave | Valor |
|---|---|---|
| `.env` | `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,https://192.0.2.10:8443` |
| `.env` | `APP_OPEN_URL` | `https://192.0.2.10:8443` |
| `.env` | `PASSWORD_RESET_LINK_BASE` | `https://192.0.2.10:8443` |
| **`frontend/.env`** | `VITE_API_BASE_URL` | `https://192.0.2.10:8444` |

> **`VITE_API_BASE_URL` en el `.env` raíz no hace nada.** El `.env.example` raíz
> la menciona sólo en prosa, así que la generación por plantilla nunca la emite
> como clave. La real vive en `frontend/.env.example`, y
> `cli/core/builder.py` mergea `{**os.environ, **raíz, **frontend}` — **gana
> frontend**. Un parcheo sólo en el raíz queda pisado con `localhost:3000` en el
> siguiente build, y el bundle sale llamando a una dirección que en el celular
> es el celular.

Alternativa a editar a mano, si el `.env` todavía no existe: el generador acepta
`SCRAPPY_FRONTEND_ORIGIN` y `SCRAPPY_BACKEND_ORIGIN` (`cli/core/env_file.py`).
La de frontend acepta lista separada por comas; `APP_OPEN_URL` toma la primera.

Después de tocar los orígenes, **hay que reconstruir el frontend**:
`VITE_API_BASE_URL` se hornea en el bundle. Alcanza con borrar `frontend/dist`:
`is_built()` exige `scraper/scraper.jar` **y** `frontend/dist`, así que el
próximo arranque del CLI reconstruye por el camino normal.

---

## 6. Confiar en el CA desde el dispositivo

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

## 7. Verificar que quedó bien

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

# 4. El bundle llama al backend, no a localhost
grep -o 'https://[0-9.]*:[0-9]*' frontend/dist/assets/*.js | sort -u
```

El control negativo importa tanto como el positivo: pedile lo mismo que (2) a la
IP de la LAN **sin** pasar por el proxy y con un `X-Forwarded-Proto: https`
inventado. No tiene que aparecer HSTS — si aparece, la allowlist de proxies está
demasiado abierta y cualquiera en la red puede falsear su IP de origen.

La prueba final no es un `curl`: **logueate desde el dispositivo y recargá la
página.** Si seguís adentro, la cookie viajó.

---

## 8. Qué cambia en una VPS

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

## 9. Problemas frecuentes

| Síntoma | Causa |
|---|---|
| `400 Bad Request: plain HTTP request was sent to HTTPS port` | Escribiste la IP pelada y el navegador probó `http://`. Lo cubre el `error_page 497` de [§4](#4-el-proxy-tls) |
| La app carga pero todo queda vacío o cargando | El navegador no confía en el certificado del **backend**. Con excepciones manuales hay que aceptarlas puerto por puerto; con el CA confiado no pasa |
| Login OK, pero al recargar te expulsa | La cookie `Secure` no viaja: o estás en HTTP plano, o el proxy no corre en loopback y los `X-Forwarded-*` se descartaron |
| El bundle sigue llamando a `localhost:3000` | Parcheaste sólo el `.env` raíz, o no se reconstruyó el frontend. Ver [§5](#5-los-orígenes-dos-archivos-env-no-uno) |
| Cambiaste la IP y no pasa nada | El CLI saltea el build entero si ya existen `scraper.jar` **y** `frontend/dist`. Borrá `dist` |
| El bind de Postgres sigue abierto | El mapeo de puertos se fija al **crear** el contenedor: hay que recrearlo (`docker rm -f` + `scripts/dev-db.sh up`). El volumen es nombrado, los datos sobreviven |
