package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
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

    // KW_*_MODELO: standalone, unambiguous shoe-model/proper names — match
    // WITHOUT requiring esZapatilla co-occurrence (the name itself is the
    // shoe signal, e.g. "ultraboost", "pegasus", "old skool").
    // KW_*_GENERICO: bare/generic words reused across apparel/accessories by
    // the same brands — require esZapatilla co-occurrence in clasificar()
    // (e.g. "running"/"training" alone must NOT classify "Running Sleeves"
    // or "Training Gloves" as a shoe). See clasificar() L~775 for the gate.
    private static final String[] KW_RUNNING_MODELO = {
        "ultraboost","adizero","solarboost","duramo",
        "pegasus","vomero","air zoom","free run","air max",
        "gel-kayano","gel-nimbus","gel-cumulus","gel-pulse",
        "glycerin","beast","ghost","adrenaline","levitate",
        "triumph","endorphin","kinvara","ride",
        "clifton","bondi","speedgoat","mach",
        "fresh foam","1080","990","880","860",
        "wave rider","wave inspire","wave horizon",
        "speedcross","sense","x-ultra"
    };

    private static final String[] KW_RUNNING_GENERICO = {
        "running","correr","corrida","maraton","marathon","trail",
        "atletismo","atletica","ligera","velocidad"
    };

    private static final String[] KW_TRAINING_MODELO = {
        "metcon","free metcon","superrep",
        "adipower","powerlift","nano","legacy lifter"
    };

    private static final String[] KW_TRAINING_GENERICO = {
        "cross training","crossfit","training","cross","gym",
        "hiit","funcional","multideporte","indoor",
        "weightlift","levantamiento"
    };

    private static final String[] KW_SKATE_MODELO = {
        "old skool","sk8-hi","era vans","authentic vans",
        "pure dc","dc court","etnies","emerica",
        "half cab","full cab"
    };

    private static final String[] KW_SKATE_GENERICO = {
        "skate","skateboarding"
    };

    private static final String[] KW_URBANA_MODELO = {
        "air force 1","af1","air force one",
        "stan smith","superstar","campus","gazelle","samba",
        "forum","nmd","continental","ozweego",
        "chuck taylor","all star","converse",
        "suede puma","basket puma","cali puma","rs-x",
        "classic leather","aztrek",
        "cortez","waffle"
    };

    private static final String[] KW_URBANA_GENERICO = {
        "urbana","casual","lifestyle","street","everyday",
        "clasica","clasico","moda","fashion"
    };

    private static final String[] KW_SNEAKER_MODELO = {
        "jordan 1","jordan 4","jordan 11","jordan 3","jordan 6","jordan 5",
        "air jordan","travis scott","off-white","fragment","union","chicago",
        "bred","shadow","university blue","royal",
        "yeezy","boost 350","boost 700","foam runner",
        "dunk low","dunk high","sb dunk","panda dunk",
        "air max 1 ","air max 90","air max 95","air max 97",
        "new balance 550","new balance 990","new balance 2002",
        "new balance 574","new balance 327"
    };

    private static final String[] KW_SNEAKER_GENERICO = {
        "hype","retro","og ","collab","limited","drop","release","sneaker"
    };

    // Tier A — unambiguous, distinctive football-boot tokens. Plain contains()
    // is safe: these strings appear nowhere else in the file and are not
    // common word fragments.
    private static final String[] KW_BOTIN = {
        "botin","cleats","tachon","tachos","chimpun",
        "bota futbol","bota de futbol","predator","mercurial",
        "phantom","nemeziz"
    };

    // Tier B — ambiguous dictionary-like tokens reused inside unrelated words
    // ("ace"⊂Embrace, "copa"⊂Copacabana, "tiempo"⊂entretiempo, "future" is a
    // common English word). Only classify as Botines when esContextoBotin()
    // also matches — mirrors the KW_*_GENERICO + esZapatilla guard pattern.
    private static final String[] KW_BOTIN_GENERICO = {
        "ace","copa","tiempo","future"
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
        "sandalia","sandal","diapositiva","slide"
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
        "top deportivo","sports bra","corpino deportivo",
        " top " // "Top" suelto (sin "deportivo"/"interior"/"cuello") — palabra completa
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
        "lentes de sol","anteojos de sol","goggles","polarizados",
        "antiparras","antiparra"
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

    private static final String[] KW_WEBCAM = {
        "webcam","camara web","web cam"
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

    // Word-boundary patterns (no substring matches) — evita falsos positivos
    // como "DC" matcheando dentro de "Hardcore" o "HDCP" (ver bug category-brand-quality-fixes).
    private static final List<Pattern> MARCA_PATTERNS = MARCAS.stream()
            .map(m -> Pattern.compile("\\b" + Pattern.quote(m.toLowerCase()) + "\\b"))
            .collect(Collectors.toList());

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
        "magnesio","magnesium","citrato de magnesio",
        "quemador","fat burner","termogenico","l-carnitina","carnitina","cla ",
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
        String genero = normalizarGenero(p.genero(), nombre, cat);
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
        int cantidadUnidades = detectarCantidadUnidades(nombre, cat);

        return new Product(p.sitio(), nombre, p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), cat, genero, talles,
                p.ml(), marca, rubro, gymrat, marcaPremium, p.senal(),
                p.finan(), cantidadUnidades);
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
            String trimmed = kw.trim();
            // Word-boundary match on BOTH sides: avoids false positives like
            // "Asics Gel-Kayano" matching the cosmetic "gel " filter via a bare
            // " gel" substring (no closing boundary) — a real false-positive
            // surfaced by the KW_RUNNING_MODELO regression test (Issue 3).
            if (inicio.startsWith(trimmed + " ") || inicio.equals(trimmed)
                    || inicio.contains(" " + trimmed + " ")) {
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
        // Padding con espacios: permite matchear keywords cortas como "top" por
        // palabra completa (" top ") sin falsos positivos contra "laptop"/"desktop",
        // que no tienen espacio antes de "top".
        String t = " " + texto.toLowerCase()
                        .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                        .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                        .replaceAll("[úùü]","u").replaceAll("[ñ]","n") + " ";

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
        if (anyMatch(t, KW_WEBCAM))    return "Webcam";

        // ── CALZADO (más específico primero) ──────────────────────────
        if (anyMatch(t, KW_BOTIN))     return "Botines";
        if (anyMatch(t, KW_BOTIN_GENERICO) && esContextoBotin(t)) return "Botines";
        if (anyMatch(t, KW_BORCEGO))   return "Borcego";
        if (anyMatch(t, KW_PANTUFLA))  return "Pantufla";
        if (anyMatch(t, KW_ZAPATO))    return "Zapato";
        if (anyMatch(t, KW_MOCASIN))   return "Mocasin";
        if (anyMatch(t, KW_SANDALIA))  return "Sandalia";
        if (anyMatch(t, KW_OJOTA))     return "Ojotas";
        if (anyMatch(t, KW_BOTA))      return "Botas";

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

        // ── CALZADO POR MODELO/MARCA (fallback, sin sustantivo explícito) ─
        // Corre AL FINAL, después de todos los sustantivos explícitos de arriba:
        // KW_*_MODELO mezcla nombres de modelo de zapatilla (ultraboost, old
        // skool, air force 1) que SON el sustantivo (no requieren esZapatilla).
        // KW_*_GENERICO son palabras genéricas (training, gym, skate, urbana)
        // que las marcas reusan en mochilas, bolsos y ropa (ej. "Mochila Vans
        // Old Skool", "Bolso Training Barrel", "Running Sleeves"). Por eso
        // GENERICO solo cuenta si esZapatilla también matchea — nunca solo.
        // Si MODELO/esZapatilla corriera después de los sustantivos de arriba,
        // esos productos quedaban mal clasificados como zapatillas. Puesto acá,
        // cualquier sustantivo explícito de arriba (mochila, buzo, musculosa...)
        // gana siempre sobre esta inferencia por palabra clave.
        if (anyMatch(t, KW_SNEAKER_MODELO)) return "Sneaker";

        boolean esZapatilla = t.contains("zapatilla") || t.contains("sneaker")
                || t.contains("calzado") || t.contains("shoe") || t.contains("tenis")
                || t.contains("footwear");

        boolean shoe = esZapatilla
                || anyMatch(t, KW_RUNNING_MODELO) || anyMatch(t, KW_TRAINING_MODELO)
                || anyMatch(t, KW_SKATE_MODELO)   || anyMatch(t, KW_URBANA_MODELO);

        if (shoe) {
            if (anyMatch(t, KW_RUNNING_MODELO)  || anyMatch(t, KW_RUNNING_GENERICO))  return "Zapatilla Running";
            if (anyMatch(t, KW_TRAINING_MODELO) || anyMatch(t, KW_TRAINING_GENERICO)) return "Zapatilla Entrenamiento";
            if (anyMatch(t, KW_SKATE_MODELO)    || anyMatch(t, KW_SKATE_GENERICO))    return "Zapatilla Skate";
            if (anyMatch(t, KW_URBANA_MODELO)   || anyMatch(t, KW_URBANA_GENERICO))   return "Zapatilla Urbana";
            if (anyMatch(t, KW_SNEAKER_GENERICO)) return "Sneaker";
            if (esZapatilla) return "Zapatilla";
        }

        return "";
    }

    private boolean anyMatch(String text, String[] keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    /**
     * Footwear/football context guard for {@link #KW_BOTIN_GENERICO} (Tier B).
     * These tokens are ambiguous dictionary words ("ace", "copa", "tiempo",
     * "future") that also appear inside unrelated words (Embrace, Copacabana,
     * entretiempo) or as common nouns — they only classify as "Botines" when
     * a footwear/football-specific signal also co-occurs in the title.
     */
    private boolean esContextoBotin(String t) {
        return t.contains("botin") || t.contains("futbol") || t.contains("tachon")
            || t.contains("cleats") || t.contains("cancha");
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
    // Cantidad de unidades (packs / combos) — ver design pack-pricing-detection
    // ──────────────────────────────────────────────────────────────────

    /** Tope sano: por encima de esto, el número probablemente es un modelo/SKU, no una cantidad. */
    private static final int MAX_CANTIDAD_UNIDADES = 12;

    /**
     * Marcador explícito de pack/combo/set/kit, opcionalmente seguido de "de"
     * y luego "x" o directamente el número: "pack x3", "combo x2", "pack de 3",
     * "set x2", "kit x4".
     */
    private static final java.util.regex.Pattern PACK_KEYWORD_COUNT = java.util.regex.Pattern.compile(
        "\\b(?:pack|combo|set|kit)\\s*(?:de\\s*)?x?\\s*(\\d{1,2})\\b");

    /**
     * Keyword pack/combo/set/kit con la prenda en el medio y el "xN" más
     * adelante: "Pack Remeras x3", "Combo Buzo Canguro x2". El hueco entre
     * keyword y "xN" se limita a 20 caracteres para que un "x2" perdido en
     * otra parte de un título largo (otro producto, otro talle) no se
     * acople falsamente con un "pack"/"combo" lejano y no relacionado.
     */
    private static final java.util.regex.Pattern KEYWORD_NEAR_X_COUNT = java.util.regex.Pattern.compile(
        "\\b(?:pack|combo|set|kit)\\b.{0,20}?\\bx\\s*(\\d{1,2})\\b");

    /** "N piezas/prendas/unidades": "set 2 piezas", "3 prendas", "2 unidades". */
    private static final java.util.regex.Pattern N_PIEZAS = java.util.regex.Pattern.compile(
        "\\b(\\d{1,2})\\s*(?:piezas|prendas|unidades)\\b");

    /**
     * Mapa singular→plural de raíces de prenda, DERIVADO de las keywords
     * canónicas ya usadas en {@code matchesTorsoBlock}/{@code matchesPiernasBlock}
     * (primer término "limpio" de cada KW_* del bloque torso/piernas). Se
     * mantiene como mapa explícito (no como lista paralela libre) para evitar
     * el riesgo de drift documentado en el design: agregar una prenda nueva al
     * clasificador NO actualiza automáticamente esta detección de cantidad,
     * pero al menos las raíces ya existentes están centralizadas en un solo lugar.
     */
    private static final Map<String, String> GARMENT_PLURAL_ROOTS = new LinkedHashMap<>();
    /** Patrones de {@link #GARMENT_PLURAL_ROOTS} precompilados una sola vez al cargar la clase. */
    private static final List<java.util.regex.Pattern> GARMENT_PLURAL_PATTERNS = new ArrayList<>();
    static {
        GARMENT_PLURAL_ROOTS.put("remera", "remeras");
        GARMENT_PLURAL_ROOTS.put("buzo", "buzos");
        GARMENT_PLURAL_ROOTS.put("musculosa", "musculosas");
        GARMENT_PLURAL_ROOTS.put("camisa", "camisas");
        GARMENT_PLURAL_ROOTS.put("campera", "camperas");
        GARMENT_PLURAL_ROOTS.put("chomba", "chombas");
        GARMENT_PLURAL_ROOTS.put("calza", "calzas");
        GARMENT_PLURAL_ROOTS.put("jean", "jeans");
        GARMENT_PLURAL_ROOTS.put("pantalon", "pantalones");
        GARMENT_PLURAL_ROOTS.put("short", "shorts");
        GARMENT_PLURAL_ROOTS.put("bermuda", "bermudas");
        GARMENT_PLURAL_ROOTS.put("media", "medias");
        GARMENT_PLURAL_ROOTS.put("calzoncillo", "calzoncillos");
        GARMENT_PLURAL_ROOTS.put("boxer", "boxers");
        for (String plural : GARMENT_PLURAL_ROOTS.values()) {
            GARMENT_PLURAL_PATTERNS.add(java.util.regex.Pattern.compile(
                "\\b(\\d{1,2})\\s+" + java.util.regex.Pattern.quote(plural) + "\\b"));
        }
    }

    /**
     * Keywords de torso/piernas en una sola lista cada uno, derivados de los
     * mismos arrays que usa {@link #matchesTorsoBlock}/{@link #matchesPiernasBlock}
     * (sin lista paralela). Usados SOLO por la detección de cantidad — la
     * clasificación de categoría "Conjunto" (línea ~689) sigue usando el check
     * booleano laxo a propósito; ahí un falso positivo es cosmético (categoría
     * mal etiquetada), pero en cantidad un falso positivo corrompe el precio
     * unitario, así que acá exigimos además un conector explícito (ver
     * {@link #COMBO_CONNECTOR}).
     */
    private static final String[] TORSO_KEYWORDS_FLAT = concatKeywords(
        KW_PUFFER, KW_PILOTO, KW_SACO, KW_CHALECO, KW_CAMPERA, KW_SWEATER,
        KW_BUZO, KW_CASACA, KW_CHOMBA, KW_MUSCULOSA, KW_CAMISA, KW_REMERA);
    private static final String[] PIERNAS_KEYWORDS_FLAT = concatKeywords(
        KW_CALZA, KW_BAGGY, KW_JEAN, KW_JOGGING, KW_BERMUDA, KW_SHORT,
        KW_VESTIDO, KW_ENTERITO, KW_POLLERA, KW_PANTALON);

    /**
     * Conector explícito entre dos prendas distintas: "+", "/", "y", "e".
     * Deliberadamente NO incluye "con" ni "," — "Campera CON capucha jogger"
     * describe UNA prenda con un detalle, no dos prendas combinadas; incluir
     * "con" reintroduciría el mismo tipo de falso positivo que este check
     * busca evitar.
     */
    private static final java.util.regex.Pattern COMBO_CONNECTOR = java.util.regex.Pattern.compile(
        "\\+|/|\\by\\b|\\be\\b");

    /** Ventana máxima entre el final de una prenda y el inicio de la otra para considerar el conector relacionado. */
    private static final int MAX_COMBO_CONNECTOR_GAP = 30;

    private static String[] concatKeywords(String[]... groups) {
        List<String> flat = new ArrayList<>();
        for (String[] group : groups) flat.addAll(Arrays.asList(group));
        return flat.toArray(new String[0]);
    }

    /** Posición [inicio, fin) de la primera (más temprana) keyword que matchea, o null si ninguna matchea. */
    private int[] firstMatchSpan(String t, String[] keywords) {
        int bestIdx = -1, bestLen = 0;
        for (String kw : keywords) {
            int idx = t.indexOf(kw);
            if (idx >= 0 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx;
                bestLen = kw.length();
            }
        }
        return bestIdx == -1 ? null : new int[] { bestIdx, bestIdx + bestLen };
    }

    /**
     * Combo de prendas distintas (torso+piernas) con conector explícito entre
     * ambas → 2 fijo. Requiere que el texto ENTRE el final de una prenda y el
     * inicio de la otra contenga un conector (ver {@link #COMBO_CONNECTOR}) y
     * que esa distancia no supere {@link #MAX_COMBO_CONNECTOR_GAP} caracteres
     * — sin esto, "Buzo Canguro Jogger Hombre" (un solo buzo cuyo corte se
     * describe como "jogger") se detectaba falsamente como pack de 2.
     */
    private boolean matchesTorsoPiernasComboConConector(String t) {
        int[] torso = firstMatchSpan(t, TORSO_KEYWORDS_FLAT);
        int[] piernas = firstMatchSpan(t, PIERNAS_KEYWORDS_FLAT);
        if (torso == null || piernas == null) return false;

        int gapStart = Math.min(torso[1], piernas[1]);
        int gapEnd = Math.max(torso[0], piernas[0]);
        if (gapEnd <= gapStart || gapEnd - gapStart > MAX_COMBO_CONNECTOR_GAP) return false;

        return COMBO_CONNECTOR.matcher(t.substring(gapStart, gapEnd)).find();
    }

    /**
     * Detecta la cantidad de unidades de un producto (packs/combos) a partir
     * de su nombre. Orden de prioridad: (1) keyword pack/combo/set/kit + número
     * explícito, (2) "N + prenda en plural" adyacente, (3) "N piezas/prendas/
     * unidades", (4) combo de prendas distintas (torso+piernas) con conector
     * explícito → 2 fijo.
     * Ante cualquier ambigüedad (SKU, rango de talle, conteo de colores,
     * cantidad fuera del tope sano) devuelve 1 (conservador).
     */
    int detectarCantidadUnidades(String texto, String categoriaResuelta) {
        if (texto == null || texto.isBlank()) return 1;
        if (esClaramenteNoTextil(texto)) return 1;

        String t = " " + normalizarAcentos(texto) + " ";

        // Guards negativos primero: rangos de talle y conteo de colores nunca
        // deben colarse como cantidad, sin importar qué patrón los matchee.
        if (esRangoDeTalle(t) || esConteoDeColor(t)) return 1;

        Integer porKeyword = extraerCantidad(PACK_KEYWORD_COUNT, t);
        if (porKeyword != null) return cap(porKeyword);

        Integer porX = extraerCantidad(KEYWORD_NEAR_X_COUNT, t);
        if (porX != null) return cap(porX);

        Integer porPrendaPlural = detectarPrendaPluralAdyacente(t);
        if (porPrendaPlural != null) return cap(porPrendaPlural);

        Integer porPiezas = extraerCantidad(N_PIEZAS, t);
        if (porPiezas != null) return cap(porPiezas);

        if (matchesTorsoPiernasComboConConector(t)) return 2;

        return 1;
    }

    /** Devuelve la cantidad si el regex matchea, o null. No aplica el cap todavía. */
    private Integer extraerCantidad(java.util.regex.Pattern pattern, String t) {
        java.util.regex.Matcher m = pattern.matcher(t);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Busca un entero pequeño inmediatamente adyacente (antes) a una de las
     * raíces de prenda pluralizadas conocidas. Adyacencia estricta: el número
     * y la prenda deben estar separados solo por un espacio, evitando que un
     * número de modelo/talle alejado del sustantivo se cuele.
     */
    private Integer detectarPrendaPluralAdyacente(String t) {
        for (java.util.regex.Pattern adyacente : GARMENT_PLURAL_PATTERNS) {
            Integer cantidad = extraerCantidad(adyacente, t);
            if (cantidad != null) return cantidad;
        }
        return null;
    }

    /** "talle 38 a 42", "talles 2 al 3", "talle 38-40": números de rango de talle, no cantidad. */
    private static final java.util.regex.Pattern RANGO_TALLE = java.util.regex.Pattern.compile(
        "\\btalle[s]?\\s+\\d{1,3}\\s*(?:-|a|al)\\s*\\d{1,3}\\b");

    private boolean esRangoDeTalle(String t) {
        return RANGO_TALLE.matcher(t).find();
    }

    /** "3 colores", "disponible en 2 colores": conteo de variantes de color, no cantidad de prendas. */
    private static final java.util.regex.Pattern CONTEO_COLOR = java.util.regex.Pattern.compile(
        "\\b\\d{1,2}\\s*colores\\b");

    private boolean esConteoDeColor(String t) {
        return CONTEO_COLOR.matcher(t).find();
    }

    private int cap(int cantidad) {
        return (cantidad >= 2 && cantidad <= MAX_CANTIDAD_UNIDADES) ? cantidad : 1;
    }

    // ──────────────────────────────────────────────────────────────────
    // Género
    // ──────────────────────────────────────────────────────────────────

    // Categorias cuya naturaleza es predominantemente femenina en este catálogo
    // (confirmado por revisión del usuario). Cuando una de estas categorias aparece
    // SIN señal masculina explícita en el nombre del producto, el género se fuerza a
    // "mujer" antes de que el combined-check (que incluye raw VTEX) lo pise.
    // Ver outfits-v2 design — R1/T1.
    private static final Set<String> FEMININE_CODED_CATEGORIES =
            Set.of("Calza", "Pollera", "Vestido", "Enterito", "Corpino", "Malla");

    String normalizarGenero(String raw, String nombre, String categoria) {
        String nombreNorm = normalizarAcentos(nombre != null ? nombre : "");
        String combined = normalizarAcentos((raw != null ? raw : "") + " " + (nombre != null ? nombre : ""));

        // Infantil primero: "niños"/"niñas" no debe perderse contra ningún otro
        // match (gym armador los excluye explícitamente — no son adultos).
        if (combined.contains("nino") || combined.contains("nina") ||
            combined.contains("kids") || combined.contains("infantil") ||
            combined.contains("bebe")) return "infantil";

        // Señal explícita "de hombre"/"de mujer" en el NOMBRE del producto gana
        // sobre un spec de género del sitio (raw) que puede estar mal taggeado
        // a nivel catálogo — bug encontrado en vivo: "Calza Nike One De Mujer"
        // (Sporting) traía raw="Hombre" del spec VTEX y el combined check de
        // abajo (que mira raw+nombre con "hombre" primero) lo pisaba, mostrando
        // una calza de mujer en outfits de hombre.
        if (nombreNorm.contains("de mujer") || nombreNorm.contains("para mujer")) return "mujer";
        if (nombreNorm.contains("de hombre") || nombreNorm.contains("para hombre")) return "hombre";

        // Feminine-coded category override: si la categoría es inherentemente femenina
        // Y el nombre del producto no tiene señal masculina explícita, forzar "mujer"
        // ANTES de que combined.contains("hombre") lo pise con el raw VTEX.
        // Cubre: Calza, Pollera, Vestido, Enterito, Corpino, Malla.
        boolean hasExplicitMascSignal = nombreNorm.contains("de hombre")
                || nombreNorm.contains("para hombre")
                || nombreNorm.contains(" hombre")
                || nombreNorm.contains("masculino")
                || nombreNorm.contains("caballero");
        if (FEMININE_CODED_CATEGORIES.contains(categoria) && !hasExplicitMascSignal) {
            return "mujer";
        }

        if (combined.contains("hombre") || combined.contains("masculino") ||
            combined.contains(" men")   || combined.contains("male")      ||
            combined.contains("caballero") || combined.contains("varones")) return "hombre";

        if (combined.contains("mujer")  || combined.contains("femenino") ||
            combined.contains("women")  || combined.contains("female")   ||
            combined.contains("dama")   || combined.contains("damas")) return "mujer";

        if (combined.contains("unisex") || combined.contains("neutro")) return "unisex";

        // Inferir por nombre de producto (modelos icónicos)
        if (combined.contains("wmns") || combined.contains("w ") ||
            combined.contains(" w)")) return "mujer";

        if (raw != null && !raw.isBlank()) return raw.trim().toLowerCase();

        // Sin ninguna señal de género (ni nombre ni spec del sitio): Calza es,
        // en este catálogo, predominantemente de mujer (80 mujer vs. un puñado
        // de hombre, todas estas últimas con tag explícito) — confirmado por el
        // usuario tras ver calzas sin género coladas en outfits de hombre.
        if ("Calza".equals(categoria)) return "mujer";

        return "";
    }

    private String normalizarAcentos(String s) {
        return s.toLowerCase()
                .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
    }

    // ──────────────────────────────────────────────────────────────────
    // Marca
    // ──────────────────────────────────────────────────────────────────

    public String extraerMarca(String nombre, String sitio) {
        if (nombre == null || nombre.isBlank()) return "";
        String lower = nombre.toLowerCase();

        for (int i = 0; i < MARCAS.size(); i++) {
            if (MARCA_PATTERNS.get(i).matcher(lower).find()) return MARCAS.get(i);
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
