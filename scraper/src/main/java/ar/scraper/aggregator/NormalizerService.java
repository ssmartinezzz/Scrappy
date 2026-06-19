package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Normalización profunda post-scraping.
 *
 * Principio de diseño:
 *   Las reglas más ESPECÍFICAS van primero.
 *   "Zapatilla Running" antes que "Zapatilla".
 *   "Buzo" y "Sweater" son categorías DISTINTAS.
 *   El nombre del producto tiene prioridad sobre la categoría cruda del sitio.
 */
@Component
public class NormalizerService {

    // ══════════════════════════════════════════════════════════════════
    // CALZADO — keywords ordenados de más específico a más genérico
    // ══════════════════════════════════════════════════════════════════

    private static final String[] KW_RUNNING = {
        "ultraboost","adizero","solarboost","duramo",
        "pegasus","vomero","air zoom","free run","air max",
        "gel-kayano","gel-nimbus","gel-cumulus","gel-pulse",
        "glycerin","beast","ghost","adrenaline","levitate",
        "triumph","endorphin","kinvara","ride",
        "clifton","bondi","speedgoat","mach",
        "fresh foam","1080","990","880","860",
        "wave rider","wave inspire","wave horizon",
        "speedcross","sense","x-ultra",
        "running","correr","corrida","maraton","marathon","trail",
        "atletismo","atletica","ligera","velocidad"
    };

    private static final String[] KW_TRAINING = {
        "metcon","free metcon","superrep",
        "adipower","powerlift","nano","legacy lifter",
        "cross training","crossfit","training","cross","gym",
        "hiit","funcional","multideporte","indoor",
        "weightlift","levantamiento"
    };

    private static final String[] KW_SKATE = {
        "old skool","sk8-hi","era vans","authentic vans",
        "pure dc","dc court","skateboarding",
        "skate","etnies","emerica",
        "half cab","full cab"
    };

    private static final String[] KW_URBANA = {
        "air force 1","af1","air force one",
        "stan smith","superstar","campus","gazelle","samba",
        "forum","nmd","continental","ozweego",
        "chuck taylor","all star","converse",
        "suede puma","basket puma","cali puma","rs-x",
        "classic leather","aztrek",
        "cortez","waffle",
        "urbana","casual","lifestyle","street","everyday",
        "clasica","clasico","moda","fashion"
    };

    private static final String[] KW_SNEAKER = {
        "jordan 1","jordan 4","jordan 11","jordan 3","jordan 6","jordan 5",
        "air jordan","travis scott","off-white","fragment","union","chicago",
        "bred","shadow","university blue","royal",
        "yeezy","boost 350","boost 700","foam runner",
        "dunk low","dunk high","sb dunk","panda dunk",
        "air max 1 ","air max 90","air max 95","air max 97",
        "new balance 550","new balance 990","new balance 2002",
        "new balance 574","new balance 327",
        "hype","retro","og ","collab","limited","drop","release","sneaker"
    };

    private static final String[] KW_BOTIN = {
        "botin","botin ","botin","cleats","tachon","tachos","chimpun",
        "bota futbol","bota de futbol","future","predator","mercurial",
        "tiempo","phantom","ace","copa","nemeziz"
    };

    private static final String[] KW_BOTA = {
        "bota ","botas ","boot ","boots ","bucanera",
        "botita","ankle","chelsea boot","desert boot","ugg"
    };

    private static final String[] KW_BORCEGO = {
        "borcego","borcegos","hiker","hiking","work boot","timberland",
        "dr martens","martens","dr. martens","1460","chunky boot",
        "plataforma alta","lug sole","bota alta","boot alta"
    };

    private static final String[] KW_SANDALIA = {
        "sandalia de tiras","sandalia con tiras","sandalia plana","sandalia taco",
        "sandalia cuero","sandalia verano","sandalia mujer","sandalia hombre",
        "sandalia goma","tiras cuero","tiras cruzadas"
    };

    private static final String[] KW_OJOTA = {
        "ojota","ojotas","flip flop","chancleta",
        "birkenstock","crocs","havaianas","reef ","ipanema","kenner",
        "zueco","clogs","clog","slide sandal","pool slide",
        "rasteira","chinelo","badeleta","babuchas","suela plana",
        "sandalia","sandal"
    };

    private static final String[] KW_MOCASIN = {
        "mocasin","moccasin","loafer","boat shoe","driving shoe",
        "slip on cuero","mocasin cuero","penny loafer"
    };

    private static final String[] KW_ZAPATO = {
        "zapato de vestir","zapato formal","oxford shoe","derby shoe",
        "brogue","monk strap","zapato cuero","zapato de cuero",
        "balerina","flat shoe","kitten heel","taco alto","stiletto",
        "zapato taco","zapato plataforma","chanel shoe"
    };

    private static final String[] KW_PANTUFLA = {
        "pantufla","pantuflas","slipper","slippers","babuchas casa",
        "zapatilla de casa","zapatilla casa"
    };

    // ══════════════════════════════════════════════════════════════════
    // INDUMENTARIA SUPERIOR
    // ══════════════════════════════════════════════════════════════════

    private static final String[] KW_SWEATER = {
        "sweater","pulover","pullover","jersey","knit","tejido",
        "tricot","cardigan","lana","merino","crochet"
    };

    private static final String[] KW_BUZO = {
        "buzo","hoodie","hoody","sweatshirt","sudadera",
        "fleece","polar","zip hoodie","full zip","half zip","canguro"
    };

    // Combo/multi-pieza — ver ADR-4. Variantes con espacio/"de" en set/kit/pack
    // evitan falsos positivos por substring ("settler", "kitsch", "package").
    private static final String[] KW_CONJUNTO = {
        "conjunto","combo","set ","set de","kit ","pack ","dos piezas","2 piezas"
    };

    private static final String[] KW_PUFFER = {
        "puffer","plumon","pluma","down jacket","down coat",
        "inflable","acolchada","acolchado","inflado",
        "anorak termico","termica","puffy","quilted"
    };

    private static final String[] KW_PILOTO = {
        "piloto","impermeable","lluvia","rain jacket","waterproof jacket",
        "chubasquero","k-way","kway","raincoat"
    };

    private static final String[] KW_CAMPERA = {
        "campera","jacket","chaqueta","cortaviento","rompeviento",
        "windbreaker","anorak","softshell","shell",
        "bomber","track jacket","tricota"
    };

    private static final String[] KW_CHALECO = {
        "chaleco","gilet","vest ","waistcoat","chaleco de abrigo",
        "chaleco inflable","chaleco pluma","chaleco polar",
        "chaleco tejido","chaleco cuero"
    };

    private static final String[] KW_SACO = {
        "saco ","sacos ","blazer","americana","sport coat",
        "tuxedo","saco de vestir","saco formal","saco lino",
        "saco tweed","saco sastre"
    };

    // Intencionalmente excluido de la detección de combos — los trajes siempre
    // resuelven a "Traje", ver ADR-4 / tasks.md 0.1 (confirmado por el product owner).
    private static final String[] KW_TRAJE = {
        "traje","suit ","terno","smoking","smocking"
    };

    private static final String[] KW_CHOMBA = {
        "chomba","polo ","polo shirt","polera","rugby shirt",
        "pique polo","lacoste polo","fred perry polo"
    };

    private static final String[] KW_CASACA = {
        "casaca","camiseta de futbol","camiseta futbol","jersey futbol",
        "camiseta seleccion","camiseta club","replica","kit futbol",
        "camiseta oficial","camiseta de juego","camiseta deportiva",
        "casaca deportiva","camiseta nba","jersey nba"
    };

    private static final String[] KW_MUSCULOSA = {
        "musculosa","tank top","camiseta de tirantes","sin mangas",
        "top deportivo","sports bra","corpino deportivo"
    };

    private static final String[] KW_REMERA = {
        "remera","t-shirt","tee","camiseta","top cuello","manga corta","basic tee"
    };

    private static final String[] KW_CAMISA = {
        "camisa","shirt","oxford","flannel","chambray","denim shirt"
    };

    private static final String[] KW_CORPINO = {
        "corpino","corpino","bralette","bra ","sosten",
        "top interior","ropa interior femenina","sujetador","bikini top"
    };

    private static final String[] KW_CALZONCILLO = {
        "calzoncillo","calzoncillos","boxer","short interior",
        "slip ","tanga","ropa interior masculina","brief","trunk ",
        "underwear","jockstrap","cueca"
    };

    private static final String[] KW_MALLA = {
        "malla","bikini","traje de bano","bano ","bano ",
        "one piece","swimsuit","swimwear","beachwear","tankini",
        "ropa de playa","pileta"
    };

    // ══════════════════════════════════════════════════════════════════
    // INDUMENTARIA INFERIOR
    // ══════════════════════════════════════════════════════════════════

    private static final String[] KW_BAGGY = {
        "baggy","wide leg","pierna ancha","balloon","paperbag",
        "oversize jean","oversized jean","barrel","loose fit",
        "baggy pant","wide pant","cargo pant","carpintero",
        "parachute pant","jogger baggy"
    };

    private static final String[] KW_JEAN = {
        "jean","denim","jeans","vaquero","skinny jean",
        "slim jean","bootcut","straight jean"
    };

    private static final String[] KW_JOGGING = {
        "jogging","pantalon deportivo","sweatpant",
        "jogger","pantalon de buzo","pantalon de entrenamiento",
        "bottoms","track pant","training pant"
    };

    private static final String[] KW_CALZA = {
        "calza","legging","leggin","tight","malla deportiva",
        "capri","culote"
    };

    private static final String[] KW_BERMUDA = {
        "bermuda","bermudas","short largo","short 3/4","walk short"
    };

    private static final String[] KW_SHORT = {
        "short","cargo short","swim short","boxer deportivo"
    };

    private static final String[] KW_POLLERA = {
        "pollera","falda","skirt","minifalda","midi skirt",
        "maxi falda","falda plisada","mini pollera"
    };

    private static final String[] KW_VESTIDO = {
        "vestido","dress ","playero","maxidress","midi dress",
        "vestido largo","vestido corto","vestido de noche"
    };

    private static final String[] KW_ENTERITO = {
        "enterito","mono ","jumpsuit","overol","romper","mameluco",
        "enterito largo","catsuit"
    };

    private static final String[] KW_PANTALON = {
        "pantalon","pant ","trouser","cargo ","chino ","formal pant"
    };

    // ══════════════════════════════════════════════════════════════════
    // ACCESORIOS — separados en categorías específicas
    // ══════════════════════════════════════════════════════════════════

    private static final String[] KW_BOLSO = {
        "bolso","cartera","handbag","tote bag","clutch","minibag",
        "bolsa de mano","bandolera","shoulder bag","crossbody"
    };

    private static final String[] KW_MOCHILA = {
        "mochila","backpack","daypack","hiking pack","school bag","laptop bag"
    };

    private static final String[] KW_RINONERA = {
        "rinonera","rinonera","waist bag","waist pack","fanny pack",
        "hip bag","belt bag","sling bag"
    };

    private static final String[] KW_BILLETERA = {
        "billetera","wallet","cartera hombre","portamonedas",
        "billetera cuero","card holder","tarjetero","porta tarjeta",
        "monedero"
    };

    private static final String[] KW_CINTURON = {
        "cinturon","cinturon","belt ","belts","cinto ","faja ",
        "correa pantalon","leather belt"
    };

    private static final String[] KW_GORRO = {
        "gorro","beanie","gorro de lana","gorro tejido","knit hat",
        "winter hat","gorro invierno","pompom hat","toque"
    };

    private static final String[] KW_GORRA = {
        "gorra","cap ","hat ","sombrero","boina","snapback",
        "bucket hat","buff","balaclava","vincha","visera","dad hat"
    };

    private static final String[] KW_BUFANDA = {
        "bufanda","scarf","panuelo cuello","echarpe","cuello polar",
        "snood","gola","pashmina"
    };

    private static final String[] KW_GUANTES = {
        "guantes","guante","gloves","mittens","guantes de cuero",
        "guantes invierno","guantes ski","guantes moto"
    };

    private static final String[] KW_LENTES = {
        "lentes","anteojos","gafas","sunglasses","sunglass",
        "lentes de sol","anteojos de sol","goggles","polarizados"
    };

    private static final String[] KW_MEDIAS = {
        "media","medias","sock","socks","calcetin","calcetines","tobillera sock"
    };

    // ══════════════════════════════════════════════════════════════════
    // TECH
    // ══════════════════════════════════════════════════════════════════

    private static final String[] KW_NOTEBOOK = {
        "notebook","laptop","netbook","macbook","chromebook",
        "portatil","computadora portatil"
    };

    private static final String[] KW_MONITOR = {
        "monitor ","pantalla pc","display pc","led gaming","monitor gaming",
        "monitor 4k","monitor curvo","monitor 144hz","monitor 27","monitor 24"
    };

    private static final String[] KW_TECLADO = {
        "teclado mecanico","teclado gamer","keyboard","mechanical keyboard",
        "teclado rgb","teclado inalambrico","teclado bluetooth"
    };

    private static final String[] KW_MOUSE = {
        "mouse gamer","mouse gaming","raton gamer","gaming mouse",
        "mouse inalambrico","mouse bluetooth","mouse rgb"
    };

    private static final String[] KW_AURICULAR = {
        "auricular","auriculares","headset","headphone","earphone",
        "earbud","earbuds","inalambrico bt","over-ear","in-ear","on-ear"
    };

    private static final String[] KW_GPU = {
        "gpu","tarjeta de video","video card","graphics card",
        "rtx ","gtx ","rx ","radeon","geforce","arc "
    };

    private static final String[] KW_RAM = {
        "ram ","memoria ram","dimm","ddr4","ddr5","sodimm",
        "memoria ddr","modulo ram"
    };

    private static final String[] KW_CPU = {
        "procesador","cpu ","core i","ryzen ","intel ","amd ",
        "i3 ","i5 ","i7 ","i9 ","threadripper"
    };

    private static final String[] KW_GABINETE = {
        "gabinete","case pc","tower pc","chasis pc","computer case",
        "gabinete gamer","gabinete atx","gabinete micro atx"
    };

    private static final String[] KW_PC = {
        "pc gamer","computadora de escritorio","desktop pc",
        "pc completa","equipo de escritorio","all in one pc"
    };

    // Training / Gym — ropa de gym y pesas (distinto de running)
    private static final String[] KW_TRAINING_ROPA = {
        "training","gym","workout","crossfit","weightlifting",
        "powerlifting","fuerza","pesas","calistenia",
        "tight de gym","musculosa gym","remera gym","top gym",
        "sports bra","corpino deportivo","bralette deportivo",
        "short gym","tight training","legging gym",
        "gimnasio","functional","dri-fit","dry-fit","compression","compresion",
        "performance","athletic","activewear","active wear",
        "para entrenar","de entrenamiento","para el gym","uso deportivo",
        "halterofilia","spinning","cardio gym",
        "musculosa de gym","remera de gym","short deportivo","short de gym",
        "calza de gym","buzo de entrenamiento","top de gym"
    };

    // ══════════════════════════════════════════════════════════════════
    // MARCAS conocidas en Argentina
    // ══════════════════════════════════════════════════════════════════

    private static final List<String> MARCAS = List.of(
        "Nike","Adidas","Puma","Reebok","New Balance","Asics","Saucony","Brooks",
        "Hoka","On Running","Salomon","Mizuno","Under Armour","Fila","Umbro",
        "Vans","Converse","DC","Etnies","Volcom","Quiksilver","Billabong",
        "The North Face","Columbia","Patagonia","Timberland","Merrell",
        "Topper","Flecha","Jaguar","Gola","Penalty","Olympikus",
        "Lacoste","Tommy","Calvin Klein","Levi's","Levis","Wrangler",
        "Champion","Kappa","Ellesse","Le Coq Sportif","Fred Perry",
        "Caterpillar","Keen","Palladium","Crocs","Birkenstock",
        "Bulks","Fuark","Harvey Willys","Harvey"
    );

    // ══════════════════════════════════════════════════════════════════
    // SITIOS PREMIUM — tag transversal aditivo "marcaPremium"
    // Por sitio (tienda), no por marca extraída del nombre del producto:
    // la mayoría de los productos de una tienda premium no llevan el
    // nombre de la tienda en el título (ej. Harvey Willys vende "Soquete
    // Ozzy Black", no "Harvey Willys Ozzy Black").
    // NO altera ni reordena la cadena de prioridad de `badge` en ml_pipeline.py.
    // ══════════════════════════════════════════════════════════════════

    private static final Set<String> SITIOS_PREMIUM = Set.of(
        "harvey"
    );

    private static final Set<String> NO_MARCA = Set.of(
        "zapatillas","zapatilla","remera","campera","pantalon","short","buzo","calza",
        "pollera","medias","gorra","mochila","accesorio","bota","botin","ojota",
        "sandalia","musculosa","top","chaleco","bermuda","jean","cargo","jogger",
        "hoodie","sweater","jacket","ropa","calzado","talle","modelo","hombre",
        "mujer","unisex","nuevo","original","importado","coleccion"
    );

    // ══════════════════════════════════════════════════════════════════
    private static final String[] KW_SUPLEMENTO = {
        "proteina","protein","whey","isolate","concentrate",
        "creatina","creatine","monohidrato",
        "bcaa","aminoacido","amino acid","glutamina","glutamine",
        "pre workout","preworkout","pre-workout","pre entreno",
        "mass gainer","gainer","hipercalorico",
        "vitamina","vitamin","multivitaminico","omega 3","omega3",
        "colageno","collagen","hidrolizado",
        "barra proteica","barra energetica","snack proteico",
        "suplemento","supplement","nutri","proteico","proteica"
    };

    private static final String[] KW_COMIDA = {
        "yerba","mate","cafe","te verde","infusion","cereal","granola",
        "frutos secos","almendra","mani","cacao","chocolate proteico",
        "avena","harina de avena","pasta","arroz"
    };

    private static final String[] KW_PERFUME = {
        "perfume","colonia","eau de toilette","eau de parfum","fragancia",
        "desodorante ","antitranspirante","splash","body mist"
    };

    // Talles
    // ══════════════════════════════════════════════════════════════════

    private static final Map<String, String> TALLE_MAP = new LinkedHashMap<>();
    static {
        TALLE_MAP.put("extra small", "XS"); TALLE_MAP.put("extrasmall","XS"); TALLE_MAP.put("xxs","XXS");
        TALLE_MAP.put("small","S");   TALLE_MAP.put("chico","S");   TALLE_MAP.put("ch","S");
        TALLE_MAP.put("medium","M");  TALLE_MAP.put("mediano","M"); TALLE_MAP.put("med","M");
        TALLE_MAP.put("large","L");   TALLE_MAP.put("grande","L");  TALLE_MAP.put("gr","L");
        TALLE_MAP.put("extra large","XL"); TALLE_MAP.put("extralarge","XL");
        TALLE_MAP.put("xxlarge","XXL"); TALLE_MAP.put("extra extra large","XXL");
        TALLE_MAP.put("xxxlarge","XXXL"); TALLE_MAP.put("3xl","XXXL"); TALLE_MAP.put("3 xl","XXXL");
        TALLE_MAP.put("talle unico","Único"); TALLE_MAP.put("unico","Único"); TALLE_MAP.put("unique","Único");
        TALLE_MAP.put("default title","");
    }

    // ══════════════════════════════════════════════════════════════════
    // Normalización principal
    // ══════════════════════════════════════════════════════════════════

    public List<Product> normalizar(List<Product> productos) {
        return productos.stream()
                .map(this::normalizarProducto)
                .collect(Collectors.toList());
    }

    // Sitios cuyo rubro se fuerza independientemente de lo que traiga el scraper
    private static final java.util.Set<String> TECH_SITIOS = java.util.Set.of(
        "compragamer","fullh4rd","maximus","foreverbstrd",
        "compragamer.com","fullh4rd.com.ar","maximus.com.ar"
    );
    private static final java.util.Set<String> SUPPL_SITIOS = java.util.Set.of(
        "entreno","entreno.com.ar"
    );

    // Sitios 100% orientados a ropa/indumentaria de gimnasio
    private static final java.util.Set<String> GYM_SITIOS = java.util.Set.of(
        "bulks", "fuark"
    );

    private Product normalizarProducto(Product p) {
        String nombre = p.nombre() != null ? p.nombre() : "";
        String cat    = normalizarCategoria(p.categoria(), nombre);
        String genero = normalizarGenero(p.genero(), nombre);
        List<String> talles = normalizarTalles(p.talles());
        String marca  = (p.marca() == null || p.marca().isBlank())
                        ? extraerMarca(nombre, p.sitio())
                        : p.marca();

        // Determinar rubro: forzar por sitio, luego por categoría, luego usar existente
        String sitioKey = (p.sitio() != null ? p.sitio() : "").toLowerCase()
                          .replaceAll("[^a-z0-9]","");
        boolean catEsTextil = esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = "Suplemento".equals(cat) || "Alimentos".equals(cat);

        String rubro;
        if (TECH_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]","")))) {
            rubro = "tecnologia";
        } else if (catEsSuppl) {
            rubro = "suplementos";
        } else if (SUPPL_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]","")))
                   && !catEsTextil) {
            rubro = "suplementos";
        } else if (p.rubro() != null && !p.rubro().isBlank()) {
            rubro = p.rubro();
        } else {
            rubro = "indumentaria";
        }

        boolean gymrat       = esGymrat(nombre, sitioKey, cat, rubro);
        boolean marcaPremium = SITIOS_PREMIUM.contains(sitioKey);

        return new Product(p.sitio(), nombre, p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), cat, genero, talles,
                p.ml(), marca, rubro, gymrat, marcaPremium, p.senal());
    }

    /**
     * Tag transversal "gymrat": ropa pensada para entrenar.
     * Aditivo — NO altera categoria ni rubro.
     *
     * Reglas (OR), con guard de calzado:
     *   1) keyword de KW_TRAINING_ROPA en el nombre, O
     *   2) sitio en GYM_SITIOS (bulks, fuark), O
     *   3) sitio == entreno Y el producto es indumentaria (no Suplemento/Alimentos)
     * Guard duro: si la categoria es calzado → false (gymrat es ROPA, no calzado).
     */
    private boolean esGymrat(String nombre, String sitioKey, String cat, String rubro) {
        if (esCalzado(cat)) return false;
        if ("suplementos".equals(rubro) || "Suplemento".equals(cat) || "Alimentos".equals(cat))
            return false;

        String n = (nombre != null ? nombre : "").toLowerCase()
            .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
            .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
            .replaceAll("[úùü]","u").replaceAll("[ñ]","n");

        if (anyMatch(n, KW_TRAINING_ROPA)) return true;
        if (GYM_SITIOS.stream().anyMatch(sitioKey::contains)) return true;
        if (sitioKey.contains("entreno")) return true;
        return false;
    }

    /** Categorías de calzado — excluidas de gymrat (el tag es para ROPA). */
    private boolean esCalzado(String cat) {
        if (cat == null) return false;
        return cat.startsWith("Zapatilla") || cat.equals("Botines") || cat.equals("Borcego")
            || cat.equals("Botas") || cat.equals("Ojotas") || cat.equals("Sneaker")
            || cat.equals("Sandalia") || cat.equals("Mocasin") || cat.equals("Zapato")
            || cat.equals("Pantufla");
    }

    /** Categorías reconocidas como indumentaria o calzado (no suplemento/alimentos). */
    private boolean esIndumentariaOCalzado(String cat) {
        if (cat == null || cat.isBlank()) return false;
        return esCalzado(cat)
            || java.util.Set.of(
                   "Puffer","Campera","Sweater","Buzo","Musculosa","Camisa","Remera",
                   "Chomba","Casaca","Chaleco","Saco","Traje","Piloto",
                   "Calza","Baggy","Jean","Jogging","Short","Bermuda","Pollera",
                   "Vestido","Enterito","Pantalón",
                   "Calzoncillos","Corpino","Malla",
                   "Mochila","Bolso","Riñonera","Billetera","Cinturón",
                   "Bufanda","Guantes","Gorro","Gorra","Lentes","Medias"
               ).contains(cat);
    }

    // ──────────────────────────────────────────────────────────────────
    // Categoría profunda
    // ──────────────────────────────────────────────────────────────────


    /**
     * Primera palabra(s) que indican que el producto NO es indumentaria/calzado.
     * Si el nombre empieza con estas palabras → no clasificar como ropa.
     */
    private static final String[] NO_TEXTIL_INICIO = {
        // Deportes / equipamiento
        "pelota","balon","ball ","palo ","stick ","raqueta","bate ","arco ",
        "red ","valla ","cono ","bolsa deportiva","costurero",
        "guantes boxing","guantes portero","casco bici","casco skate",
        // Joyería / bijouterie
        "cadena ","collar ","pulsera ","anillo ","aros ","aro ","colgante ",
        "brazalete ","tobillera joya","piercing","broche ",
        // Accesorios no-ropa
        "maletin","valija","paraguas","bastón","baston ","cinturon portaherramientas",
        // Cosméticos / higiene (perfume/colonia/desodorante ahora tienen categoría propia)
        "crema ","locion ","loción ","gel ","shampoo","jabon ","jabón ","protector solar",
        // Equipos / electrónica
        "router ","teclado mecanico","mouse gamer","monitor led","fuente atx",
        // Suplementos que no son ropa
        "whey ","proteina ","creatina ","bcaa ","vitamina ","pre-workout",
    };

    /**
     * Verifica si el nombre indica claramente un producto NO textil.
     * Solo revisa las primeras 3 palabras para no sobrebloquear.
     */
    private boolean esClaramenteNoTextil(String texto) {
        if (texto == null || texto.isBlank()) return false;
        String lower = texto.toLowerCase()
            .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
            .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
            .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
        // Solo mirar inicio (primeras 3 palabras = ~25 chars)
        String inicio = lower.length() > 35 ? lower.substring(0, 35) : lower;
        for (String kw : NO_TEXTIL_INICIO) {
            if (inicio.startsWith(kw.trim()) || inicio.contains(" " + kw.trim())) {
                return true;
            }
        }
        return false;
    }

    String normalizarCategoria(String raw, String nombre) {
        // Buscar primero en el NOMBRE del producto (más confiable)
        String fromName = clasificar(nombre);
        if (!fromName.isEmpty()) return fromName;

        // Luego en la categoría cruda del sitio (limpia primero)
        if (raw != null && !raw.isBlank()) {
            String fromRaw = clasificar(raw);
            if (!fromRaw.isEmpty()) return fromRaw;
            // Si no matchea ningún keyword → limpiar la categoría cruda
            // Quitar nombres de tienda (VCP, Sporting, etc.), flechas, separadores
            String cleaned = raw.replaceAll("(?i)\\b(vcp|sporting|vaypol|freres|batuk|city|bulks|"
                           + "midway|tussy|bullbenny|dcshoes|eldon|entreno|fuark)\\b", "")
                           .replaceAll("[>|/\\\\]+", " ")  // quitar separadores
                           .replaceAll("\\s{2,}", " ")
                           .trim();
            if (!cleaned.isBlank() && cleaned.length() >= 3) {
                return capitalize(cleaned.split("\\s+")[0]); // Solo primera palabra
            }
        }
        return "Indumentaria";
    }

    /**
     * Clasificador por palabras clave ordenado de ESPECÍFICO a GENERAL.
     * El orden de evaluación determina el resultado cuando hay ambigüedad.
     */
    private String clasificar(String texto) {
        if (texto == null || texto.isBlank()) return "";
        if (esClaramenteNoTextil(texto)) return "";
        String t = texto.toLowerCase()
                        .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                        .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                        .replaceAll("[úùü]","u").replaceAll("[ñ]","n");

        // ── COMBO / MULTI-PIEZA (ver ADR-4) — corre ANTES de cualquier otro
        // bloque para que un SKU combo nunca quede first-matched como una sola
        // pieza (torso o piernas). KW_TRAJE queda deliberadamente afuera del
        // bloque torso usado en (b): un traje siempre resuelve a "Traje".
        if (anyMatch(t, KW_CONJUNTO)) return "Conjunto";
        if (matchesTorsoBlock(t) && matchesPiernasBlock(t)) return "Conjunto";

        // ── TECH (antes de textil para evitar falsos positivos) ───────
        if (anyMatch(t, KW_NOTEBOOK))  return "Notebook";
        if (anyMatch(t, KW_PC))        return "PC";
        if (anyMatch(t, KW_MONITOR))   return "Monitor";
        if (anyMatch(t, KW_GPU))       return "GPU";
        if (anyMatch(t, KW_CPU))       return "CPU";
        if (anyMatch(t, KW_RAM))       return "RAM";
        if (anyMatch(t, KW_GABINETE))  return "Gabinete";
        if (anyMatch(t, KW_TECLADO))   return "Teclado";
        if (anyMatch(t, KW_MOUSE))     return "Mouse";
        if (anyMatch(t, KW_AURICULAR)) return "Auricular";

        // ── CALZADO (más específico primero) ──────────────────────────
        if (anyMatch(t, KW_BOTIN))     return "Botines";
        if (anyMatch(t, KW_BORCEGO))   return "Borcego";
        if (anyMatch(t, KW_PANTUFLA))  return "Pantufla";
        if (anyMatch(t, KW_ZAPATO))    return "Zapato";
        if (anyMatch(t, KW_MOCASIN))   return "Mocasin";
        if (anyMatch(t, KW_SANDALIA))  return "Sandalia";
        if (anyMatch(t, KW_OJOTA))     return "Ojotas";
        if (anyMatch(t, KW_BOTA))      return "Botas";
        if (anyMatch(t, KW_SNEAKER))   return "Sneaker";

        boolean esZapatilla = t.contains("zapatilla") || t.contains("sneaker")
                || t.contains("calzado") || t.contains("shoe") || t.contains("tenis")
                || t.contains("footwear");

        if (esZapatilla || anyMatch(t, KW_RUNNING) || anyMatch(t, KW_TRAINING)
                        || anyMatch(t, KW_SKATE)   || anyMatch(t, KW_URBANA)) {
            if (anyMatch(t, KW_OJOTA))    return "Ojotas";
            if (anyMatch(t, KW_BOTIN))    return "Botines";
            if (anyMatch(t, KW_RUNNING))  return "Zapatilla Running";
            if (anyMatch(t, KW_TRAINING)) return "Zapatilla Entrenamiento";
            if (anyMatch(t, KW_SKATE))    return "Zapatilla Skate";
            if (anyMatch(t, KW_SNEAKER))  return "Sneaker";
            if (anyMatch(t, KW_URBANA))   return "Zapatilla Urbana";
            if (esZapatilla)              return "Zapatilla";
        }

        // ── ROPA INTERIOR / BAÑO ──────────────────────────────────────
        if (anyMatch(t, KW_CALZONCILLO)) return "Calzoncillos";
        if (anyMatch(t, KW_CORPINO))     return "Corpino";
        if (anyMatch(t, KW_MALLA))       return "Malla";

        // ── INDUMENTARIA SUPERIOR (más específico primero) ────────────
        if (anyMatch(t, KW_PUFFER))   return "Puffer";
        if (anyMatch(t, KW_PILOTO))   return "Piloto";
        if (anyMatch(t, KW_TRAJE))    return "Traje";
        if (anyMatch(t, KW_SACO))     return "Saco";
        if (anyMatch(t, KW_CHALECO))  return "Chaleco";
        if (anyMatch(t, KW_CAMPERA))  return "Campera";
        if (anyMatch(t, KW_SWEATER))  return "Sweater";
        if (anyMatch(t, KW_BUZO))     return "Buzo";
        if (anyMatch(t, KW_CASACA))   return "Casaca";
        if (anyMatch(t, KW_CHOMBA))   return "Chomba";
        if (anyMatch(t, KW_MUSCULOSA)) return "Musculosa";
        if (anyMatch(t, KW_CAMISA))   return "Camisa";
        if (anyMatch(t, KW_REMERA))   return "Remera";

        // ── INDUMENTARIA INFERIOR ─────────────────────────────────────
        if (anyMatch(t, KW_CALZA))    return "Calza";
        if (anyMatch(t, KW_BAGGY))    return "Baggy";
        if (anyMatch(t, KW_JEAN))     return "Jean";
        if (anyMatch(t, KW_JOGGING))  return "Jogging";
        if (anyMatch(t, KW_BERMUDA))  return "Bermuda";
        if (anyMatch(t, KW_SHORT))    return "Short";
        if (anyMatch(t, KW_VESTIDO))  return "Vestido";
        if (anyMatch(t, KW_ENTERITO)) return "Enterito";
        if (anyMatch(t, KW_POLLERA))  return "Pollera";
        if (anyMatch(t, KW_PANTALON)) return "Pantalón";

        // ── SUPLEMENTOS / NUTRICIÓN ───────────────────────────────────
        if (anyMatch(t, KW_SUPLEMENTO)) return "Suplemento";
        if (anyMatch(t, KW_COMIDA))     return "Alimentos";
        if (anyMatch(t, KW_PERFUME))    return "Perfume";

        // ── ACCESORIOS (más específico primero) ───────────────────────
        if (anyMatch(t, KW_BILLETERA))  return "Billetera";
        if (anyMatch(t, KW_RINONERA))   return "Riñonera";
        if (anyMatch(t, KW_MOCHILA))    return "Mochila";
        if (anyMatch(t, KW_BOLSO))      return "Bolso";
        if (anyMatch(t, KW_CINTURON))   return "Cinturón";
        if (anyMatch(t, KW_BUFANDA))    return "Bufanda";
        if (anyMatch(t, KW_GUANTES))    return "Guantes";
        if (anyMatch(t, KW_LENTES))     return "Lentes";
        if (anyMatch(t, KW_GORRO))      return "Gorro";
        if (anyMatch(t, KW_GORRA))      return "Gorra";
        if (anyMatch(t, KW_MEDIAS))     return "Medias";

        return "";
    }

    private boolean anyMatch(String text, String[] keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    /**
     * Bloque torso usado por la detección de combos (ADR-4). Espeja los keywords
     * de la sección "INDUMENTARIA SUPERIOR" del clasificador secuencial
     * (L696-708 al momento de escribir esto), EXCEPTO KW_TRAJE — un traje nunca
     * debe disparar el check (b) de combo, ver Open Question 0.1 (resuelta).
     */
    private boolean matchesTorsoBlock(String t) {
        return anyMatch(t, KW_PUFFER)   || anyMatch(t, KW_PILOTO)
            || anyMatch(t, KW_SACO)     || anyMatch(t, KW_CHALECO)
            || anyMatch(t, KW_CAMPERA)  || anyMatch(t, KW_SWEATER)
            || anyMatch(t, KW_BUZO)     || anyMatch(t, KW_CASACA)
            || anyMatch(t, KW_CHOMBA)   || anyMatch(t, KW_MUSCULOSA)
            || anyMatch(t, KW_CAMISA)   || anyMatch(t, KW_REMERA);
    }

    /**
     * Bloque piernas usado por la detección de combos (ADR-4). Espeja los
     * keywords de la sección "INDUMENTARIA INFERIOR" del clasificador
     * secuencial (L711-720 al momento de escribir esto).
     */
    private boolean matchesPiernasBlock(String t) {
        return anyMatch(t, KW_CALZA)    || anyMatch(t, KW_BAGGY)
            || anyMatch(t, KW_JEAN)     || anyMatch(t, KW_JOGGING)
            || anyMatch(t, KW_BERMUDA)  || anyMatch(t, KW_SHORT)
            || anyMatch(t, KW_VESTIDO)  || anyMatch(t, KW_ENTERITO)
            || anyMatch(t, KW_POLLERA)  || anyMatch(t, KW_PANTALON);
    }

    // ──────────────────────────────────────────────────────────────────
    // Género
    // ──────────────────────────────────────────────────────────────────

    String normalizarGenero(String raw, String nombre) {
        String combined = ((raw != null ? raw : "") + " " + (nombre != null ? nombre : "")).toLowerCase()
                .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n");

        if (combined.contains("hombre") || combined.contains("masculino") ||
            combined.contains(" men")   || combined.contains("male")      ||
            combined.contains("caballero") || combined.contains("varones") ||
            combined.contains("de hombre")) return "hombre";

        if (combined.contains("mujer")  || combined.contains("femenino") ||
            combined.contains("women")  || combined.contains("female")   ||
            combined.contains("dama")   || combined.contains("damas")    ||
            combined.contains("de mujer")) return "mujer";

        if (combined.contains("unisex") || combined.contains("neutro")) return "unisex";

        // Inferir por nombre de producto (modelos icónicos)
        if (combined.contains("wmns") || combined.contains("w ") ||
            combined.contains(" w)") || combined.contains("para mujer")) return "mujer";

        return raw != null ? raw.trim().toLowerCase() : "";
    }

    // ──────────────────────────────────────────────────────────────────
    // Marca
    // ──────────────────────────────────────────────────────────────────

    public String extraerMarca(String nombre, String sitio) {
        if (nombre == null || nombre.isBlank()) return "";
        String lower = nombre.toLowerCase();

        for (String marca : MARCAS) {
            if (lower.contains(marca.toLowerCase())) return marca;
        }

        // Fallback: primera palabra en mayúscula que no es categoría
        for (String w : nombre.trim().split(" +")) {
            String wl = w.toLowerCase().replaceAll("[^a-záéíóúñ]", "");
            if (wl.length() >= 3 && !NO_MARCA.contains(wl)
                    && w.length() > 0 && Character.isUpperCase(w.charAt(0))) {
                return w.replaceAll("[^a-zA-ZáéíóúñÁÉÍÓÚÑ'\\-]", "");
            }
        }

        return sitio != null ? sitio : "";
    }

    // ──────────────────────────────────────────────────────────────────


    // Talles
    // ──────────────────────────────────────────────────────────────────

    List<String> normalizarTalles(List<String> talles) {
        if (talles == null) return List.of();
        return talles.stream()
                .map(this::normalizarTalle)
                .filter(t -> !t.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizarTalle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.trim().toLowerCase();
        String mapped = TALLE_MAP.get(lower);
        if (mapped != null) return mapped;
        // Devolver el talle original en mayúsculas si parece válido
        String clean = raw.trim().toUpperCase().replaceAll("[^A-Z0-9./]", "");
        return clean.length() <= 6 ? clean : "";
    }

    // ──────────────────────────────────────────────────────────────────
    // Utilidades
    // ──────────────────────────────────────────────────────────────────

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
