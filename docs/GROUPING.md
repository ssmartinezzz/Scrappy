# Agrupado de `/api/grupos`

Paquete `ar.scraper.aggregator.grouping`. Decide qué publicaciones de distintos sitios son el mismo producto.

**Consumidores:** `GET /api/grupos` (el tool MCP `comparar_entre_sitios` y las suites de perf) y
`GET /api/producto/{key}/comparado`, que dibuja una línea por tienda en el gráfico de
`/historial/:key` ([`FRONTEND.md`](./FRONTEND.md)). La página `/grupos` se borró el 2026-11-14.

## Criterio

| Paso | Regla | Dónde |
|---|---|---|
| Bloque | Familia de categoría: primera palabra de `categoria` | `ProductIdentity` |
| Firma | Palabras significativas − palabras de la propia categoría − relleno | `JaccardSimilarity.firma`, `StopWords` |
| Umbral | Jaccard ≥ 0.60 | `JaccardSimilarity` |
| Guardas | Nunca agrupa: marca, código de modelo o versión distintos, niños vs adulto. Un lado vacío abstiene. `talle N` no es versión | `JaccardSimilarity.compatibles` |
| Specs del nombre | Nunca agrupa dos nombres que **declaran** una spec distinta (tabla de abajo). Si sólo un nombre la declara, abstiene | `NameSpecs` |
| Absorción | Por sitio, sólo los candidatos de mayor puntaje; los empates (variantes de color) entran todos | `subAgruparPorJaccard` |
| Candidatos | Prefix filtering sobre los tokens más raros. Exacto para Jaccard ≥ t | `subAgruparPorJaccard` |

## Specs del nombre (`NameSpecs`)

Se extraen del nombre en minúsculas y sin tildes. Una spec veta el par sólo si **los dos** nombres la declaran.

| Spec | Ejemplos | Compatibles si |
|---|---|---|
| Unidades normalizadas: capacidad (TB→GB, `2x8gb`=16), peso (kg/lb→g), volumen (l/oz→ml), W, Hz, MHz, mAh, DPI, pulgadas, cápsulas, servicios | `2 LB` ≈ `907 gr`, `24 oz` ≈ `700ml`, `1TB` = `1024GB` | algún valor coincide con tolerancia del 4% |
| Dimensiones con unidad (`900x400x3mm`) | mousepad L vs XL | algún par de lados coincide |
| Talle en letra (`s`…`xxxl`) y `talle N` | mousepad **S** vs **XL** | algún valor coincide |
| Número de modelo suelto de 3–5 dígitos | RTX **5060** vs **5080**, Elite **301** vs **600** | un conjunto incluye al otro (`860` ≈ `860 2280`) |
| Código corto letra+dígito | Throne **R1** vs **R20**, MWE **V2** vs **V3** | un conjunto incluye al otro |
| Tier de GPU (`ti`, `super`, `xt`, `xtx`) | 5060 vs 5060 **Ti** | iguales, sólo si los dos tienen número de modelo |

Ruido descartado a propósito: velocidades (`6000MB/s` no es talle S ni capacidad), `M.2`, separador de miles
(`20.000 DPI`), cantidad de fans o packs (`x3`, `4x`: las tiendas los informan distinto para el mismo gabinete),
`AxB` sin unidad (ambiguo).

Medido con una réplica del algoritmo sobre la base de dev (81.6k activos, 2026-11-14): 64 de 3178 grupos
multi-sitio cambian; revisados a mano, todos cortes correctos (placas de video 5060 / 5060 Ti / 5080 mezcladas en un
grupo, mousepad S con XL, whey 2 lb con 5 lb, RAM 3000 con 3200 MHz, mochila 25 L con 8 L).

## Medición (dev, 33.5k activos, 2026-10-13)

| | Antes | Ahora |
|---|---|---|
| Grupos multi-sitio | 1717 | 2761 |
| Catálogo entero sin filtros | 169 ms | 333 ms (cacheado en `CatalogoDerivadoCache`) |

Con `NameSpecs` (2026-11-14, 81.6k activos, mediana caliente, A/B intercalado en la misma máquina): 697 → 902 ms
(+29%). Se paga una vez por clave cacheada.

## Límites conocidos

- Nombres genéricos pueden juntar de más: "Remera Topper Basic" ≈ "Run Basic".
- "8gb"/"16gb" cuentan como código de modelo: la misma GPU con otra memoria no agrupa (a propósito). El mismo
  efecto deja afuera `3200mhz` vs `3200 MHz` o `1kg` vs `1000 g`: sacar las unidades de los códigos sumaría 49
  grupos, pero junta memorias de marcas distintas cuando la tienda deja `marca` vacía. No se hizo.
- Un nombre que no declara la spec abstiene: "Mousepad Raptor Ultra Glide" sigue agrupando con el XL.
