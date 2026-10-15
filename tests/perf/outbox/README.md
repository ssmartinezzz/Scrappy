# Performance del outbox de avisos de baja de precio

Dos mediciones sobre `notificacion_precio` (V49), contra una base descartable y un
Mailpit. Ninguna toca el catálogo real: `db_crear` se niega a usar la base `scraper`.

```
outbox/
├── lib.sh            entorno (todo parametrizable), arranque/parada del jar, helpers de psql
├── abba.sh           costo de sp_upsert_run: ABBA entre dos o más cuerpos de la función
├── rafaga.sh         una ráfaga de bajas contra el relay y Mailpit
├── rafaga-abba.sh    A, B, B, A de rafaga.sh entre dos jars
├── payload.sql       5000 productos, 500 que bajan, 1000 filas a encolar, 200 usuarios
├── seed.sql / drop.sql / stats.sql / una_llamada.sql / resumen.py
└── purga-seed.sql    siembra N avisos terminales para medir la purga por retención
```

```bash
docker run -d --name perf-mailpit -e MP_MAX_MESSAGES=10000 \
  -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit    # sin MP_MAX_MESSAGES tira mails a los 500

git show master:scraper/src/main/resources/db/migration/R__sp_upsert_run.sql > /tmp/R_master.sql
tests/perf/outbox/abba.sh /tmp/R_master.sql scraper/src/main/resources/db/migration/R__sp_upsert_run.sql
OUTBOX_SIN_VIGILANTES=1 tests/perf/outbox/abba.sh <A.sql> <B.sql>      # sólo el costo del chequeo

tests/perf/outbox/rafaga-abba.sh baseline.jar current.jar NOTIFICACIONES_INTERVALO_MS=1000
```

Purga por retención sobre una tabla grande (V51, trozos de 5000):

```bash
psql -h 127.0.0.1 -U postgres perf_outbox -v filas=1000000 -v viejas=0.5 -f tests/perf/outbox/purga-seed.sql
```

La base tiene que estar migrada (`abba.sh` la crea con el esquema). Lo que se mide es
`purgarTerminadas`: tiempo total, tiempo del trozo más largo y filas por segundo.

Copiá los jars fuera de `scraper/target` antes de medir: `mvn clean` lo borra.

## Variables

| Variable | Default |
|---|---|
| `OUTBOX_DB` | `perf_outbox` (se crea y se borra) |
| `OUTBOX_PGHOST` / `PGPORT` / `PGUSER` / `PGPASSWORD` | `127.0.0.1` / `5432` / `postgres` / vacío |
| `OUTBOX_JAR` | `scraper/target/fashion-scraper-1.0.1.jar` (en `abba.sh`, sólo crea el esquema) |
| `OUTBOX_JAVA`, `OUTBOX_HEAP` | JRE 21, `512m` (heap fijo: `-Xms` = `-Xmx`) |
| `OUTBOX_SECRETO` | aleatorio por corrida; fijarlo mantiene las credenciales del backend entre reinicios del jar |
| `OUTBOX_PORT` | `3100` (aborta si está ocupado: otro backend contestaría por el medido) |
| `OUTBOX_SMTP_HOST` / `SMTP_PORT`, `OUTBOX_MAILPIT_API` | `127.0.0.1` / `1025`, `http://127.0.0.1:8025` |
| `OUTBOX_RONDAS` | `12` (24 llamadas por variante) |
| `OUTBOX_BAJAS`, `OUTBOX_USUARIOS` | `1000`, `200` |
| `OUTBOX_PRODUCTOS`, `OUTBOX_VIGILADOS` | `5000`, `500` |

Las credenciales del backend se generan al azar en cada corrida. Sin crons: la base
es nueva y no tiene filas en `cron_jobs`, así que el arranque no dispara ningún scrape.
Cualquier `VAR=valor` extra de `rafaga*.sh` llega al backend.

## Cómo leer los resultados

**`abba.sh`**: mediana y p95 (nearest-rank) por llamada, y el delta de la mediana contra
la primera variante. Cada llamada corre en una transacción que se revierte: la base queda
idéntica y todas hacen el mismo trabajo. `encoladas` es la cuenta de `notificacion_precio`
dentro de esa transacción; una variante sin outbox (master) debe dar 0, y si una con
outbox da 0 el upsert se tragó un error SQL. Mirá el delta contra el ruido: el rango
min-max de una variante es ~3%.

**`rafaga*.sh`**: `p50/p95/p99/max` son `enviada_at - creada_at`, y `creada_at` es el
`now()` de la transacción del upsert (el instante en que la baja quedó encolada). `pared`
va del primer `creada_at` al último `enviada_at`. `mails/s` cuenta lo que llegó a Mailpit
y `filas/s` lo que quedó `ENVIADA`; con un mail por usuario valen `filas = 5 x mails`.
Con sondeo, la latencia es la espera al próximo tick más el envío; con el despertar por
LISTEN debería ser sólo el envío. Con `NOTIFICACIONES_MAX_POR_MINUTO=N` mirá cuántos
llegaron en el primer minuto (`OUTBOX_MIRAR_PRIMER_MINUTO=1` corta la espera a los 60 s).
Lo medido tiene que repetirse en orden A,B,B,A: el primero de la serie paga el calentamiento.
