# Brand logos

Served as static files by the frontend (`/brand-logos/<file>`); the brand → file map lives in `src/lib/brandLogos.js`.
Adding one: drop the file here, add it to `BRAND_LOGO_FILES` (and to `LOGOS_SOBRE_OSCURO` if it is white/light).

A store needs no wiring: a file named `<site key>.<ext>` here is picked up through `src/lib/brandLogos.generated.js`.
`node scripts/fetch-site-logos.mjs` downloads the logo from the store's own homepage for every site in
`config.properties` that has none and regenerates that file (`--check` only lists the missing ones). Still check the
result by eye and add white marks to `LOGOS_SOBRE_OSCURO`. Stores behind a WAF (403) need the file dropped in by hand.

| Brand | File | Source | License |
|---|---|---|---|
| Adidas | `adidas.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/adidas.svg | CC0 simple-icons |
| Puma | `puma.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/puma.svg | CC0 simple-icons |
| Nike | `nike.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/nike.svg | CC0 simple-icons |
| Under Armour | `under-armour.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/underarmour.svg | CC0 simple-icons |
| Fila | `fila.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/fila.svg | CC0 simple-icons |
| Reebok | `reebok.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/reebok.svg | CC0 simple-icons |
| New Balance | `new-balance.svg` | https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/newbalance.svg | CC0 simple-icons |
| Salomon | `salomon.svg` | https://upload.wikimedia.org/wikipedia/commons/9/9b/Salomon_logo_2022.svg | wikimedia Public domain |
| Topper | `topper.svg` | https://upload.wikimedia.org/wikipedia/commons/c/c8/Topper_logo_%28horizontal%29.svg | wikimedia Public domain |
| Vans | `vans.svg` | https://upload.wikimedia.org/wikipedia/commons/9/91/Vans-logo.svg | wikimedia Public domain |
| Kappa | `kappa.svg` | https://upload.wikimedia.org/wikipedia/commons/3/32/Kappa_logo.svg | wikimedia Public domain |
| Umbro | `umbro.svg` | https://upload.wikimedia.org/wikipedia/commons/2/22/Umbro_logo_%28current%29.svg | wikimedia Public domain |
| Converse | `converse.svg` | https://upload.wikimedia.org/wikipedia/commons/3/30/Converse_logo.svg | wikimedia Public domain |
| Mizuno | `mizuno.svg` | https://upload.wikimedia.org/wikipedia/commons/5/53/MIZUNO_logo.svg | wikimedia Public domain |
| Lacoste | `lacoste.svg` | https://upload.wikimedia.org/wikipedia/commons/6/62/Lacoste_wordmark_1984.svg | wikimedia Public domain |
| Brooks | `brooks.svg` | https://upload.wikimedia.org/wikipedia/commons/5/55/Brooks_Sports_logo.svg | wikimedia Public domain |
| Le Coq Sportif | `le-coq-sportif.svg` | https://upload.wikimedia.org/wikipedia/commons/3/36/Wordmark_Le_Coq_Sportif.svg | wikimedia Public domain |
| Cellucor | `cellucor.png` | https://upload.wikimedia.org/wikipedia/commons/4/49/Cellucor_logo.jpg | wikimedia Public domain |
| Saucony | `saucony.png` | https://www.saucony.com.ar/ | brand site |
| DC | `dc.png` | https://cdn.dcshoes.com.ar/media/logo/stores/2/logo-dcshoes.png | brand site |
| Columbia | `columbia.png` | https://columbiasportswear.com.ar/ | brand site |
| Crocs | `crocs.png` | https://www.crocs.com.ar/ | brand site |
| Champion | `champion.png` | https://www.champion.com/ | brand site |
| Quiksilver | `quiksilver.png` | https://cdn.quiksilver.com.ar/media/logo/stores/1/logo-quiksilver.png | brand site |
| Timberland | `timberland.png` | https://timberland.com.ar/ | brand site |
| Star Nutrition | `star-nutrition.png` | https://starnutrition.com.ar/ | brand site |
| Gold Nutrition | `gold-nutrition.png` | https://www.goldnutrition.com.ar/imagenes/logo_web_gold_nutrition.webp | brand site |
| Gentech | `gentech.png` | https://www.gentech.com.ar/ | brand site |
| Optimum Nutrition | `optimum-nutrition.svg` | https://www.optimumnutrition.com/cdn/shop/files/on-logo-white.svg | brand site |
| Crudda | `crudda.png` | https://www.crudda.com.ar/ | brand site |
| Labs Nutrition | `labs-nutrition.png` | https://www.labsnutrition.com.ar/ | brand site |
| Natuliv | `natuliv.svg` | https://www.natuliv.com.ar/cdn/shop/files/logo-inverso.svg | brand site |
| Innovanaturals | `innovanaturals.png` | https://innovanaturals.com.ar/wp-content/uploads/2021/01/innovanaturals.png | brand site |
| Muecas | `muecas.png` | https://www.muecas.com.ar/ | brand site |
| Universal Nutrition | `universal-nutrition.svg` | https://www.universalnutrition.com/wp-content/themes/bootscore-child/assets/img/logo/logo.svg | brand site |
| Entrenuts | `entrenuts.png` | https://entrenuts.com.ar/wp-content/uploads/2021/10/LOGO-04.png | brand site |
| Asics | `asics.svg` | https://www.asics.com.ar/ | brand site |
| ENA | `ena.svg` | https://www.enasport.com/cdn/shop/files/logo-ena-negro.svg | brand site |
| Leguilab | `leguilab.png` | https://leguilab.com/wp-content/uploads/2025/04/logo.png | brand site |
| Nutrex | `nutrex.svg` | https://nutrex.com/ | brand site |
| Grosz Nutrition | `grosz-nutrition.png` | https://www.grosznutrition.com.ar/ | brand site |
| Mervick | `mervick.png` | https://mervicklab.com.ar/wp-content/uploads/2023/10/mervick-web.png | brand site |
| Penalty | `penalty.svg` | https://www.penalty.com.br/ | brand site |
| Freres | `freres.svg` | https://freres.ar/ (store header logo) | brand site |
| Fuark | `fuark.png` | https://www.fuark.com.ar/ (store header logo) | brand site |
| Mmartinez | `mmartinez.png` | https://mmartinez.com.ar/ (store header logo) | brand site |
| Monkyforce | `monkyforce.png` | https://www.monkyforce.com/ (store header logo) | brand site |
| Harvey | `harvey.png` | https://www.harveywillys.com/ (store header logo) | brand site |
| Bulks | `bulks.png` | https://www.bulkblanks.com.ar/ (store header logo) | brand site |
| Bullbenny | `bullbenny.png` | https://www.bullbenny.com.ar/ (store header logo) | brand site |
| Batuk | `batuk.png` | https://www.batuk.com.ar/ (store header logo) | brand site |
| Tussy | `tussy.png` | https://tussy.com.ar/ (store header logo) | brand site |
| Foreverbstrd | `foreverbstrd.png` | https://foreverbstrd.com/ (store header logo) | brand site |
| Forever | `forever.png` | https://www.forever.com.ar/ (store header logo) | brand site |
| Fursten | `fursten.png` | https://tienda.fursten.com.ar/ (store header logo) | brand site |
| Barnes | `barnes.png` | https://barnesindustries.com.ar/ (store header logo) | brand site |
| Vcp | `vcp.png` | https://vcp.com.ar/ (store header logo) | brand site |
| Eldon | `eldon.png` | https://www.eldon.com.ar/ (store header logo) | brand site |
| Midway | `midway.png` | https://www.midway.com.ar/ (store header logo) | brand site |
| Inpro | `inpro.svg` | https://inpro.ar/logo.svg (store header logo) | brand site |
| Flowin | `flowin.png` | https://flowin.com.ar/ (store header logo) | brand site |
| Vayzenhome | `vayzenhome.png` | https://vayzenhome.com/ (store header logo) | brand site |
| Mrplacadeco | `mrplacadeco.png` | https://www.mrplacadeco.com/ (store header logo) | brand site |
| Ambiancedeco | `ambiancedeco.png` | https://www.ambiancedeco.com.ar/ (store header logo) | brand site |
| Ayrun | `ayrun.webp` | https://acdn-us.mitiendanube.com/stores/001/756/547/themes/common/logo-179694154926073495-1784134295-eb467f1b0751830255b1819b371826411784134295-480-0.webp | store site |
| Burton | `burtonargentina.webp` | https://acdn-us.mitiendanube.com/stores/006/391/590/themes/common/logo-1890182272-1755718627-17a48bd80ff1b03a21cbaec25e3989d01755718627-480-0.webp | store site |
| Baesics | `baesics.png` | https://baesics.ar/cdn/shop/files/Logo_web_f21a8516-4a7b-46d8-8875-83fef7ca83d7.png?height=36&v=1778691075 | store site |
| Dellorean | `dellorean.webp` | https://acdn-us.mitiendanube.com/stores/006/255/725/themes/common/logo-1617557643-1767640550-ea209a5fba299e0a13bdfb9b0e8c533d1767640551-320-0.webp | store site |
| Entreno | `entreno.webp` | https://acdn-us.mitiendanube.com/stores/006/011/728/themes/common/logo-71597579-1748284392-847b58a9e014af6b3bbc5ca96f5bfec51748284392-480-0.webp | store site |
| Armytech | `armytech.png` | https://www.armytech.com.ar/img/logo-1683567127.jpg | store site |
| El Mundo del Juguete | `elmundodeljuguete.png` | https://mundojuguete.vtexassets.com/assets/vtex.file-manager-graphql/images/6ff20bb7-e07e-49a7-9979-38e50a70f977___f3d8d83b1477f02bbedd456246292e7f.png | store site |
| GamerFactory | `gamerfactory.svg` | https://www.gamerfactory.com.ar/logo.svg | store site |
| Equus | `equus.svg` | https://equusio.vtexassets.com/assets/vtex.file-manager-graphql/images/6c39b8dc-b1db-4ed6-aae1-476523311603___9e78c770efb9e6ab77d82c3effada5bf.svg | store site |
| Hadriatica | `hadriatica.png` | https://dcdn-us.mitiendanube.com/stores/113/368/themes/common/logo-977581553-1656244794-7d4c0553735abf1d247b45a80bc96ff21656244794-480-0.png?0 | store site |
| Las Pepas | `laspepas.svg` | https://laspepas.com.ar/static/version1793041027/frontend/Mgs/laspepas/es_AR/images/logo.svg | store site |
| Kevingston | `kevingston.png` | https://images.kevingston.com/imagenes/varios/fav-eshop-24-apple-180x180-touch-icon.png | store site |
| Maximus | `maximus.png` | https://www.maximus.com.ar/Temp/App_WebSite/App_PictureFiles/apple-touch-icon.png | store site |
| NotebooksPro | `notebookspro.webp` | https://notebookspro.ar/wp-content/uploads/2024/04/Logo-Notebooks-Proooks-Pro.webp | store site |
| Morashop | `morashop.png` | https://d2az8otjr0j19j.cloudfront.net/templates/002/268/228/twig/static/images/morashop-icono-180.png | store site |
| One Place World | `oneplaceworld.webp` | https://acdn-us.mitiendanube.com/stores/006/103/959/themes/common/logo-7102246213814116682-1785512058-fe5d3fef9f79094c82cdd4313ade29f01785512058-320-0.webp | store site |
| RocketHard | `rockethard.png` | https://statics.qloud.com.ar/rockethardstore-07-2020/153_22-09-2023-02-09-36-recurso-2.png | store site |
| Zentra | `zentra.webp` | https://acdn-us.mitiendanube.com/stores/005/728/539/themes/common/logo-177958221-1742228719-5e5aca4ae570d959f06f940f706615e21742228719-320-0.webp | store site |
| City | `city.webp` | https://www.somoscity.com.ar/brandlogos/city.webp | store site |
| CompraGamer | `compragamer.png` | https://compragamer.com/favicon.ico | store site |
| Sporting | `sporting.svg` | https://sporting.vtexassets.com/arquivos/logo-sporting-white.svg | store site |
| Vaypol | `vaypol.webp` | https://www.vaypol.com.ar/brandlogos/vaypol.webp | store site |
| King of the Kongo | `kingofthekongo.webp` | https://acdn-us.mitiendanube.com/stores/219/431/themes/common/logo-3825714704076569041-1790371576-82ef3042ce34e21fbe74c881bce1bf181790371576-320-0.webp | store site |
| Easy | `easy.svg` | https://arcencohogareasy.vtexassets.com/assets/vtex.file-manager-graphql/images/70762c9e-9f1a-4061-9113-144b83dd8370___389e812d088de026179c879e8ddb85da.svg | store site |
| Arredo | `arredo.png` | https://arredo.vteximg.com.br/arquivos/favicon_2026.png | store site |
| Sportline | `sportline.webp` | https://sportline.vtexassets.com/arquivos/sportline-logo.webp | store site |
