# Armadores de outfits y suplementos

> Política de pesos, MCKP, coherencia visual, vetos y combo de suplementos. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Armadores de outfits

Dos algoritmos con objetivos distintos, ambos leyendo el catálogo en memoria
(no la DB), igual que `/api/data` y `/api/mejores`.

| | `OutfitService.armar` | `OutfitBudgetBuilder.armarPorCategorias` |
|---|---|---|
| Superficie | Gym (`/api/outfits`) | Presupuesto (`/api/outfits/builder`) |
| Objetivo | Variedad entre recargas | Óptimo global bajo presupuesto duro |
| Algoritmo | Muestreo aleatorio ponderado | MCKP con branch-and-bound (+ modo greedy) |
| Slots | torso, piernas, calzado + accesorio best-effort | Sub-slots: torso-base/outer, piernas, calzado, accesorio-head/feet/body |

**Los dos armadores comparten UNA política de pesos, y vive en `OutfitRules`.**
Todos los factores son neutros en 1.0 cuando no hay señal y ninguno es un filtro:
cercanía a un centro de precio (±30%) × boost de likes (cap 4.0) × `mlFactor`
(oportunidad ML, cap 2.5) × `VisualCoherence` (estampado/fit/color) ×
`diversidadDeMarca` (×0.7 por marca repetida en el outfit). Lo único que cambia
entre armadores es cuál es el centro de precio: en `armar` es la mediana del pool
elegible; en el builder es **`presupuesto / slots abiertos`**, el reparto
equitativo de lo que queda por gastar.

⚠️ **Hasta `outfit-builder-pick-quality` el builder por presupuesto NO seguía esa
política**: maximizaba `baseMlScore` **crudo**, sin acotar. Y `baseMlScore` es
`(100 - scoreP) + bonus`, donde `scoreP` es el **percentil de PRECIO** dentro de
categoría+género y los cuatro bonus son también observaciones de precio. O sea que
la única función objetivo de una superficie cuyo punto entero es gastar un
presupuesto era *"qué tan barato está esto para su categoría"*. Tres consecuencias
que nadie pidió, y las tres se veían como "el builder elige cualquier cosa":

1. **El presupuesto quedaba sin usar.** Cada peso de más BAJABA el objetivo, así
   que el techo era algo que el solver tenía incentivo a esquivar. Con $100.000 y
   dos candidatos —uno de $10.000 y uno de $95.000— elegía el de $10.000. Está
   fijado en `OutfitBudgetBuilderPickQualityTest`.
2. **Los likes no existían.** `RecommendationService` tiene dos scores:
   `baseMlScore` (público) y `finalScore` (privado, = base × boost de likes). El
   builder llamaba al primero, así que `boostLikeCount` llegaba adentro del
   `FeedbackModel` y se descartaba. Los dislikes sí andaban —son vetos duros
   aguas arriba—, con lo cual el feedback era **asimétrico**: se podía sacar, no
   se podía pedir.
3. **Cero diversidad de marca**, y el objetivo empujaba justo para el otro lado:
   el sitio más agresivo del catálogo se llevaba los cuatro slots.

**El término de presupuesto vive en el score cacheado, no en `aporte`** — y no es
prolijidad. Depende sólo del precio del candidato, así que meterlo ahí arregla
además el **pool**: rankear el top-60 por ML crudo lo llenaba con la cola más
barata de cada categoría, y ningún término posterior puede elegir un producto que
nunca llegó a ser candidato.

`mlFactor` = `clamp(baseMlScore(p)/50.0, 0.5, 2.5)`. **50.0 es
`baseMlScore(MlScore.EMPTY)`** — anclar ahí hace que un producto sin datos de ML
dé exactamente 1.0, así que un catálogo sin pipeline conserva los pesos previos.
El cap queda por debajo del de likes a propósito: un like es gusto, un badge es
una observación de precio.

**`VisualCoherence`** (pura, estática, compartida por los tres armadores) aplica
tres reglas sobre `Product.visual()`: un solo estampado por outfit (×0.5),
sin repetir fit extremo (×0.7, solo torso/piernas — `regular` es el neutro y
oversize-arriba/entallado-abajo es un look válido), y coordinación de color
(×0.7) por **rueda de tonos** (`rojo naranja amarillo verde celeste azul violeta
rosa`, circular; armonía = distancia ≤ 2). Los neutros (`negro blanco gris beige
marron`) no tienen posición en la rueda y combinan con todo. Un atributo vacío
**nunca** dispara una regla: vienen de un clasificador que se abstiene.

En el MCKP las penalizaciones de coordinación (coherencia visual × diversidad de
marca) se aplican como **resta de un monto no-negativo**, así que la cota superior
del branch-and-bound sigue siendo válida y no se poda ninguna rama óptima. Todo
término que se agregue a `aporte` en el futuro tiene que conservar esa propiedad:
un factor que pueda pasar de 1.0 empieza a podar el óptimo **en silencio**.

El greedy también rankeaba mal: elegía por **coherencia sola** entre los
asequibles y cortaba en el primero perfectamente coherente. Como la mayoría del
catálogo se abstiene en atributos visuales, en la práctica era "el primero que
entra en el pool barajado" — ignorando el score que acababa de calcular.

**Vetos duros** (estos sí son filtros, y corren aguas arriba del peso):
`genero=infantil` nunca es elegible · `Mochila`/`Bolso` fuera de accesorio ·
`Botines` fuera de calzado · marca `DC` fuera de calzado en Gym · el par
`marca|categoria` con dislike queda excluido de forma permanente.

**Combo de suplementos** (`SupplementCombo`): **33 subtipos** en 6 grupos
(Proteína · Vitaminas · Aderezos · Bebidas · Alimentos · Otros). Cada producto se
asigna a **exactamente un** subtipo en una pasada por precedencia (específico
antes que genérico — una barra de proteína es una barra, no un polvo). El nombre
manda; `p.categoria()` es fallback. Ranking del pick: marca preferida → precio
por unidad de medida → `baseMlScore` → url.

**Los 12 subtipos de comida se declaran con `SubtipoSuplemento.comida(...)`, y la
bandera arrastra dos consecuencias**: (1) quedan fuera del combo que acompaña al
outfit de Gym —`OutfitsEndpoints` pide `TIPOS_COMBO_OUTFIT` explícito, así que esa
grilla ya no crece sola con cada tipo nuevo—, y (2) heredan el veto
`esElSaborDeUnPolvo`. Ese veto es el **espejo exacto** de
`esProteinaAgregadaAUnAlimento`: un sustantivo culinario detrás de la cabeza de
proteína es el SABOR del polvo, no el producto ("Whey Protein sabor Dulce de
Leche" no es una mermelada). Sin él, cada keyword de comida le robaba productos al
bucket de proteína. Se deriva de la bandera y **no se lista a mano** a propósito:
un subtipo de comida nuevo no puede olvidarse el veto. Porqué completo en
[`docs/ARCHITECTURE.md`](./ARCHITECTURE.md).

**El pick de proteína elige primero la CATEGORÍA y recién adentro la marca.**
Desde `V32` hay tres (`Proteína Isolada` · `Proteína` · `Proteína Vegetal`) y no
son intercambiables para quien compra: `SUPLEMENTO_CATEGORIA_PRIORIDAD` pone la
isolada arriba y `SUPLEMENTO_CATEGORIA_ULTIMO_RECURSO` deja la vegetal para
cuando no hay ninguna otra cosa en el pool. **La vegetal es de-preferencia, no
veto** — con 21 filas contra 143 no gana nunca en la práctica, pero un pool que
sólo tenga vegetal devuelve un pick en vez de dejar el slot vacío, igual que
`mejorGrupoDeMarca` cae a todos los candidatos cuando ninguna marca preferida
tiene stock. Para Creatina, Magnesio y el resto es un no-op.

La **marca preferida tiene dos escalones**. Arriba, **BSN** (marca, o la
**línea** Syntha-6): si hay stock gana siempre, sin mirar el $/g — pedido
explícito del usuario (2026-09-17), porque con el conjunto plano no salía
nunca: es la más cara por gramo de las cinco en las dos categorías de proteína
($209/g contra $77/g de la Star isolada). Abajo, un CONJUNTO sin orden: ENA ·
Gold Nutrition · Star Nutrition · Xtrenght compiten entre sí y **el precio por
unidad de medida decide**. Sigue siendo un filtro DURO contra las no listadas
—una marca de confianza le gana a una desconocida por barata que esté— y
adentro de cada escalón no hay más jerarquía que el $/g.

**"Regenerar" rota de marca** en Proteína en Polvo y Creatina
(`SUBTIPOS_CON_ROTACION_DE_MARCA`, pedido del usuario 2026-09-29). Excluir sólo
URLs no alcanzaba: con varios potes de BSN, cada click caía en otro BSN. Ahora
se cuenta cuántas veces se mostró cada marca preferida y gana la menos vista
—BSN primero en el empate, el resto por $/g—: BSN → la mejor $/g sin mostrar → … → BSN
(otro pote)… Una marca sin stock se saltea.

Era un orden hasta `feat/supplement-pick-by-price-per-gram`, y ahí estaba el
problema: `mejorGrupoDeMarca` se quedaba con la primera marca **con stock**, así
que con una sola whey de ENA en el pool, Star, Gold y BSN quedaban descartadas
**antes de que el $/g las mirara**.

⚠️ **Syntha-6 es una LÍNEA, no una marca**, y por eso vive en un array aparte
(`SUPLEMENTO_LINEAS_PREFERIDAS`) que matchea contra el nombre normalizado:
`Product.marca()` de un Syntha-6 dice `BSN`, que es quien lo fabrica, así que
meterla en el conjunto de marcas arrastraría el catálogo entero de BSN con ella.
`BSA` salió del conjunto — el usuario confirmó que fue un typo, y no matcheaba
un solo producto del catálogo. Compara contra `Product.marca()`, que sale de
`BrandExtractor` — así que una marca sólo puede ganar acá si además está en
`BrandExtractor.MARCAS`. Las dos listas viajan juntas o la preferencia es código
muerto (lo fue: hasta 2026-08-11 la lista curada no tenía ni una marca de
suplementos, y todos caían al fallback por sitio). Ahí van sólo formas que se
sostienen solas bajo `\b`: `Star` y `Gold` pelados matchearían "All Star" y
"Gold Standard".

> ⚠️ `NO_ALFANUMERICO` tiene que seguir siendo el **primer** campo estático de
> `SupplementCombo`: varios inicializadores debajo normalizan keywords al
> construirse, y un `Pattern` declarado después llega null a su propio uso.
> `ExceptionInInitializerError` es el único síntoma.

---
