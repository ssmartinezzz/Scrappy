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
| Guardas | Nunca agrupa: marca o código de modelo distintos, niños vs adulto (`nino`, `kids`, `bebe`, `baby`, `jr`… en el nombre), ni un nombre que sólo dice marca + categoría ("Buzo Lacoste"). Con `marca` vacía (99.9% de tecnología) la marca sale del nombre con una lista de fabricantes propia del agrupado (`NameBrands`, sin NVIDIA/AMD/Intel); los conjuntos de marcas deben cruzarse ("HP HyperX" ≈ "HyperX"). Un lado vacío abstiene | `JaccardSimilarity.compatibles`, `NameBrands` |
| Specs del nombre | Nunca agrupa dos nombres que **declaran** una spec distinta (tabla de abajo). Si sólo un nombre la declara, abstiene | `NameSpecs` |
| Absorción | Por sitio, sólo los candidatos de mayor puntaje. Los empates entran juntos sólo si son casi idénticos entre sí (Jaccard ≥ 0.8 y compatibles: variantes de color); si no, el match es ambiguo y ese sitio no entra | `subAgruparPorJaccard` |
| Candidatos | Prefix filtering sobre los tokens más raros. Exacto para Jaccard ≥ t | `subAgruparPorJaccard` |

## Specs del nombre (`NameSpecs`)

Se extraen del nombre en minúsculas y sin tildes. Una spec veta el par sólo si **los dos** nombres la declaran.

| Spec | Ejemplos | Compatibles si |
|---|---|---|
| Unidades normalizadas: capacidad (TB→GB, `2x8gb`=16), peso (kg/lb→g), volumen (l/oz→ml), W, Hz, MHz, GHz, mAh, DPI, pulgadas, cápsulas, servicios | `2 LB` ≈ `907 gr`, `24 oz` ≈ `700ml`, `1TB` = `1024GB`, `7''` = `7"` | algún valor coincide con tolerancia del 4% |
| Dimensiones con unidad (`900x400x3mm`) | mousepad L vs XL | algún par de lados coincide |
| Talle en letra (`s`…`xxxl`) y `talle N` | mousepad **S** vs **XL** | algún valor coincide |
| Número de modelo suelto de 3–5 dígitos | RTX **5060** vs **5080**, Elite **301** vs **600** | un conjunto incluye al otro (`860` ≈ `860 2280`) |
| Código corto letra+dígito | Throne **R1** vs **R20**, MWE **V2** vs **V3** | un conjunto incluye al otro |
| Tier de GPU (`ti`, `super`, `xt`, `xtx`) | 5060 vs 5060 **Ti** | iguales, sólo si los dos tienen número de modelo |
| Versión de 1–2 dígitos: romanos a número, `3.0` = `3`, `2.5` es un solo valor; ignora `talle N`, SATA III, 80 Plus, USB/PCIe/ATX/Gen x.y, DDR, pulgadas | Caven **2.0** vs **III**, Copa Pure **IV** vs **3** | un conjunto incluye al otro; además versión ∪ número de modelo (New Balance **80** vs **370**) |
| Edición (`elite`, `pro`, `academy`, `club`, `league`, `lite`, `plus`, `max`, `mini`, `ultra`, `base`, `alpha`, `platform`, `se`) | Mercurial **Elite** vs **Club**, Viper V3 Pro vs Pro **SE** | iguales |

Ruido descartado a propósito: velocidades (`6000MB/s` no es talle S ni capacidad), `M.2`, separador de miles
(`20.000 DPI`), cantidad de fans o packs (`x3`, `4x`: las tiendas los informan distinto para el mismo gabinete),
`AxB` sin unidad (ambiguo).

Antes la versión se comparaba por igualdad estricta: "ATX 3.1 PCIe 5.1" en un solo lado bastaba para que una fuente
no agrupe. Medido con una réplica del algoritmo sobre la base de dev (81.6k activos, 2026-11-14), primera ronda: 64 de 3178 grupos
multi-sitio cambian; revisados a mano, todos cortes correctos (placas de video 5060 / 5060 Ti / 5080 mezcladas en un
grupo, mousepad S con XL, whey 2 lb con 5 lb, RAM 3000 con 3200 MHz, mochila 25 L con 8 L). Segunda ronda (versiones, edición, niños, sólo-marca): 3172 → 3216 grupos
multi-sitio; recupera procesadores y fuentes que la igualdad estricta separaba. Tercera ronda (marca del nombre, empates
ambiguos): 3224 → 3260 grupos, 10 367 → 9 911 productos agrupados; grupos con precio máx/mín ≥ 1.6: 482 → 422.

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
- `NameBrands` es independiente de `BrandExtractor.MARCAS` (ésa es la de preferencias de armadores e indumentaria).
- "8500G" (Ryzen) se lee como 8500 g: inofensivo, los dos lados lo leen igual.
- Ropa con nombre genérico ("Remera adidas Boca Juniors") sigue mezclando: el nombre no alcanza.
- Un nombre que no declara la spec abstiene: "Mousepad Raptor Ultra Glide" sigue agrupando con el XL.
