# Combo de suplementos

> Subtipos, pick por categoría, marca y $/g, y rotación de "Regenerar". Separado de [`docs/OUTFITS.md`](./OUTFITS.md) (2026-09-29).

## Subtipos y ranking

`SupplementCombo` arma el combo: **33 subtipos** en 6 grupos
(Proteína · Vitaminas · Aderezos · Bebidas · Alimentos · Otros). Cada producto se
asigna a **exactamente un** subtipo en una pasada por precedencia (específico
antes que genérico — una barra de proteína es una barra, no un polvo). El nombre
manda; `p.categoria()` es fallback. Ranking del pick: marca preferida → precio
por unidad de medida → `baseMlScore` → url.

## Subtipos de comida

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

## Categoría de proteína

**El pick de proteína elige primero la CATEGORÍA y recién adentro la marca.**
Desde `V32` hay tres (`Proteína Isolada` · `Proteína` · `Proteína Vegetal`) y no
son intercambiables para quien compra: `SUPLEMENTO_CATEGORIA_PRIORIDAD` pone la
isolada arriba y `SUPLEMENTO_CATEGORIA_ULTIMO_RECURSO` deja la vegetal para
cuando no hay ninguna otra cosa en el pool. **La vegetal es de-preferencia, no
veto** — con 21 filas contra 143 no gana nunca en la práctica, pero un pool que
sólo tenga vegetal devuelve un pick en vez de dejar el slot vacío, igual que
`mejorGrupoDeMarca` cae a todos los candidatos cuando ninguna marca preferida
tiene stock. Para Creatina, Magnesio y el resto es un no-op.

## Marca preferida

La **marca preferida tiene dos escalones**. Arriba, **BSN** (marca, o la
**línea** Syntha-6): si hay stock gana siempre, sin mirar el $/g — pedido
explícito del usuario (2026-09-17), porque con el conjunto plano no salía
nunca: es la más cara por gramo de las cinco en las dos categorías de proteína
($209/g contra $77/g de la Star isolada). Abajo, un CONJUNTO sin orden: ENA ·
Gold Nutrition · Star Nutrition · Xtrenght compiten entre sí y **el precio por
unidad de medida decide**. Sigue siendo un filtro DURO contra las no listadas
—una marca de confianza le gana a una desconocida por barata que esté— y
adentro de cada escalón no hay más jerarquía que el $/g.

Era un orden hasta `feat/supplement-pick-by-price-per-gram`, y ahí estaba el
problema: `mejorGrupoDeMarca` se quedaba con la primera marca **con stock**, así
que con una sola whey de ENA en el pool, Star, Gold y BSN quedaban descartadas
**antes de que el $/g las mirara**.

## "Regenerar"

**"Regenerar" rota de marca** en Proteína en Polvo y Creatina
(`SUBTIPOS_CON_ROTACION_DE_MARCA`, pedido del usuario 2026-09-29). Excluir sólo
URLs no alcanzaba: con varios potes de BSN, cada click caía en otro BSN. Ahora
se cuenta cuántas veces se mostró cada marca preferida y gana la menos vista
—BSN primero en el empate, el resto por $/g—: BSN → la mejor $/g sin mostrar → … → BSN
(otro pote)… Una marca sin stock se saltea.

## Syntha-6 y `BrandExtractor`

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
