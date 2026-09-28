# Problemas conocidos y pendientes

> Lo que está mal y sin arreglar, lo que necesita datos, y lo medido y descartado. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Problemas conocidos / pendientes

> Esta tabla lista **lo que está mal y sin arreglar**. Nada más.
>
> Una decisión tomada no es un problema pendiente, y mientras vivió acá mezclada
> con los bugs hizo que la lista pareciera deuda cuando no lo era. El *por qué*
> de cada decisión está en [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md) y
> [`docs/DATABASE.md`](./DATABASE.md), que es donde lo manda `DOC-1`.

### Bugs abiertos

| Problema | Estado |
|---------|--------|
| La e2e **completa** sigue sin correr en CI — sólo el smoke de login | `e2e-login-smoke.yml` cubre 8 de los 26 specs de browser (sesión, cookie de refresh, topología) y **cero** de los 51 de la capa API. Es a propósito: un check que cuesta lo mismo que la suite entera se termina esquivando. Pero significa que roles, tabs, reseteo y backend-down siguen dependiendo de que alguien corra `tests/e2e/run-e2e.sh` a mano. Así se coló el PR #179, que mergeó con todo verde dejando la browser en 21 fallos de 26 |
| `ResetRateLimiter` y `LoginRateLimiter` deciden lo OPUESTO sobre la clave por IP | `LoginRateLimiter` no tiene clave por IP **a propósito**, y su javadoc explica por qué: `getRemoteAddr()` devuelve la IP del proxy en cuanto haya uno adelante, y ahí todos los clientes caen en el mismo balde sin que nada falle. `ResetRateLimiter` sí la tiene, y la alimenta con ese mismo `getRemoteAddr()`. Detrás de un proxy su tope de 10/h pasa a ser global de hecho, y frena los resets de todos. Hoy es latente —ninguna de las tres vías de instalación proxea `/api`— pero las dos clases no pueden seguir contestando distinto a la misma pregunta. El arreglo es el que su hermana ya describe: allowlist de proxies de confianza antes de mirar `X-Forwarded-For`, nunca confiar en el header a ciegas |
| `Ejecutar_instalar.sh` asume java/mvn/node del sistema en vez de vendorizar como el `.bat` | Gap preexistente. La parte de `uv`/`cli-venv` sí vendoriza igual en ambos SO y se validó end-to-end en Linux; `INSTALAR_Y_CORRER.bat` nunca se corrió end-to-end (sandbox de dev = Linux) |

### Necesitan datos, no código

Ninguno de estos se puede cerrar sentado frente al editor: hace falta muestrear
el catálogo real primero.

| Pendiente | Qué falta |
|---------|--------|
| Pack/unit pricing: posible drift de distribución ML en categorías con alta densidad de packs | Monitorear badges en vivo. **No** recalibrar thresholds todavía |
| Un suplemento en cápsulas que declara su dosis en gramos ("Colágeno 10 g en cápsulas") parsea como envase de 10 g | Un umbral de tamaño calibrado con datos reales |
| El veto de formato y `FORMATO_ALIMENTO` de `SupplementCombo` se escribieron sin un catálogo para muestrear | Contrastarlos contra el catálogo real |
| La ventana de gracia de 10 s del refresh y los umbrales de rate-limit son propuestas, no mediciones | Ya no falta infraestructura: el cliente existe (`frontend/src/lib/authSession.js`) y `tests/e2e/run-e2e.sh` lo ejercita contra un backend real. Falta la medición en sí, que es un trabajo aparte — nadie corrió todavía refrescos concurrentes para ver dónde cae el número. Hasta entonces queda como está, documentado como propuesta |
| Parámetros de Argon2id sin medir en el Windows portable | Medidos acá (Linux dev, re-medidos 2026-09-22): **~22 ms hash / ~22 ms verify** con `m=16384, t=2, p=1`. Decía 76/76 hasta esa fecha, con el mismo método y la misma máquina; lo desmintió la suite de perf, que clavó el `POST /api/auth/login` **entero** en 43 ms p95 — un número que no puede ser la mitad del verify que contiene. Cuál de las dos corridas fue la anómala no se sabe. Falta igual la máquina que importa: el costo es memory-bound y un laptop de gama baja puede ser varias veces más lento. Hasta tener ese número, los defaults quedan como están |

### Sin dueño

| Pendiente | Estado |
|---------|--------|
| Vans 0 productos (plataforma Grimoldi custom) | Comentado en `config.properties`, pendiente investigación de su API |
| Logg (`logg.com.ar`, ABP/ASP.NET) sigue sin scraper | **Fuera de scope por decisión explícita, no por fallar.** Diagnóstico completo en [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md) y en el header de `V24` |
| El fallback de IPC (`datos.gob.ar`) apunta a un series id muerto | `148.3_INIVELGENERAL_DICI_M_26` devuelve `{"errors":[...]}`; ya estaba muerto en `InflacionService`. Falta encontrar/confirmar un id vivo — la cadena de IPC hoy corre con una sola fuente real |
| `GET /api/recomendacion` sigue duplicando `SenalCalculator` inline | Preexistente a `indices-service`: `FinanciacionEndpoints.recomendacion` recalcula la señal a mano en vez de llamar a `SenalCalculator.compute`, en paralelo al camino que usa `SenalEnricher` para el catálogo |
| `indice_valor` para USD (`DIARIO`) crece sin límite | ~5.7k filas desde 2011 a hoy tras la primera corrida real de `IndiceRefreshJob`. Inocuo al ritmo actual — sin poda ni partición todavía, y no hace falta con ese volumen |

### Medido y descartado

Lo que alguna vez estuvo en esta lista y las mediciones sacaron de ella. Se deja
escrito para que no vuelva a proponerse.

| Sospecha | Qué dijo la medición |
|---------|--------|
| `/api/outfits` y `/api/outfits/builder` rearman el `FeedbackModel` y pegan 2 queries a la DB en **cada** request — "candidato a cachear por corrida" | **No es un problema de performance** (medido 2026-08-18, catálogo de 6700): `FeedbackModels.build` 0,208 ms · 2 queries con pool HikariCP 0,429 ms · `OutfitService.armar` —el trabajo real del endpoint— 0,258 ms. Total ≈ 0,64 ms por request. La caché exigiría invalidar en cinco métodos de escritura, y si se escapa uno el like de un usuario deja de afectar los outfits en silencio: correctitud a cambio de 0,64 ms imperceptibles |
| Idem, medido sin pool | ⚠️ **Trampa de medición, no un dato.** `PostgresTestBase` usa `SimpleDriverDataSource`, que abre una conexión nueva por llamada: las mismas 2 queries dan 13,5 ms así y 0,429 ms con HikariCP, 31x inflado. Cualquier medición de DB en este repo tiene que envolver el datasource de test en un `HikariDataSource` o el número es ficción |

### La banda de precios: `precio.maximo=5000000`

**Era `300000` hasta `add-inpro-office-store` (2026-08-20).** Esa banda no era un
bug —filtraba lo que decía filtrar— pero borraba en silencio justo los productos
caros de dos rubros enteros:

| Sitio | Qué se perdía con 300.000 |
|---|---|
| Maximus (medido 2026-08-13) | notebooks `CAT=56` conservaba 0 de 16, computadoras armadas `CAT=68` 0 de 59, GPUs `CAT=48` 5 de 59 — **377 de 1122, 34%** |
| INPRO (medido 2026-08-20) | **32 de 101, 32%**: TODAS las sillas ergonómicas de gama y TODOS los standing desks salvo los tres más baratos |

Con `5000000`, INPRO entra entero: **101 de 101, 0% filtrado** (verificado contra
el sitio en vivo). El producto más caro del catálogo es `LiberNovo Omni` a
$2.999.000.

| Lo que hay que saber | |
|---|---|
| **La banda es GLOBAL** | No hay override por sitio. `precio.maximo` sale de `config.properties`, lo lee `ScraperConfig`, y subirla alcanza a **todos** los sitios configurados — 29 activos desde `add-zentra-and-mmartinez`. Una banda por sitio sería una feature aparte |
| **`PUT /api/config` NO persiste** | `ScraperConfig.setPrecioMaximo` sólo toca el `Properties` en memoria: lo que se cambia desde el dashboard se pierde al reiniciar. El valor durable es el del archivo |
| **El número vive en cuatro lugares y tienen que decir lo mismo** | `config.properties` · el default de `ScraperConfig.getPrecioMaximo()` · `frontend/src/lib/scrapeDefaults.js` · y un test del frontend lee el `.properties` para que no puedan separarse |
| ⚠️ **Los conteos por sitio de la tabla de sitios son con la banda VIEJA** | Están fechados y medidos a 300.000, así que **subestiman** la cobertura real de ahora. Re-medirlos es trabajo pendiente, no un dato que ya tengamos |
| **Mueve las distribuciones del ML, y no hay nada que recalibrar** | Entran productos caros que antes no estaban, así que mediana, IQR y percentiles por categoría se corren. Los thresholds **no se tocan**: ninguna condición de `assign_badges` está denominada en pesos — todas son posiciones sobre distribuciones que se recalculan por corrida (`comp` 0-100, z-score modificado, cercos de Tukey, porcentajes). Medido ejercitando el código real: 88 combinaciones, escalando las distribuciones x10/x100/x1000/x0.01, **cero cambios de badge**. Lo fija `ml-tests/test_ml_pipeline_scale_invariance.py`. La única constante en pesos del archivo es el piso de `bin_size` en `_calc_mode`, y `mode` se reporta sin alimentar ningún score |

Decisiones que antes vivían acá y ahora están donde corresponde:
`/api/db/export`/`import` en 410 Gone → [`docs/API_REFERENCE.md`](./API_REFERENCE.md) ·
`precio_orig` con strings genuinamente no parseables → [`docs/DATABASE.md`](./DATABASE.md).

---
