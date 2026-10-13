# Agrupado de `/api/grupos`

Paquete `ar.scraper.aggregator.grouping`. Decide qué publicaciones de distintos sitios son el mismo producto.

## Criterio

| Paso | Regla | Dónde |
|---|---|---|
| Bloque | Familia de categoría: primera palabra de `categoria` | `ProductIdentity` |
| Firma | Palabras significativas − palabras de la propia categoría − relleno | `JaccardSimilarity.firma`, `StopWords` |
| Umbral | Jaccard ≥ 0.60 | `JaccardSimilarity` |
| Guardas | Nunca agrupa: marca, código de modelo o versión distintos, niños vs adulto. Un lado vacío abstiene. `talle N` no es versión | `JaccardSimilarity.compatibles` |
| Absorción | Por sitio, sólo los candidatos de mayor puntaje; los empates (variantes de color) entran todos | `subAgruparPorJaccard` |
| Candidatos | Prefix filtering sobre los tokens más raros. Exacto para Jaccard ≥ t | `subAgruparPorJaccard` |

## Medición (dev, 33.5k activos, 2026-10-13)

| | Antes | Ahora |
|---|---|---|
| Grupos multi-sitio | 1717 | 2761 |
| Catálogo entero sin filtros | 169 ms | 333 ms (cacheado en `CatalogoDerivadoCache`) |

## Límites conocidos

- Nombres genéricos pueden juntar de más: "Remera Topper Basic" ≈ "Run Basic".
- "8gb"/"16gb" cuentan como código de modelo: la misma GPU con otra memoria no agrupa (a propósito).
