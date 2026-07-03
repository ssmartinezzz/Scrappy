package ar.scraper.aggregator.normalize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for every garment/category {@code KW_*} keyword
 * array and the derived {@code TORSO_KEYWORDS_FLAT}/{@code PIERNAS_KEYWORDS_FLAT}
 * taxonomy views (ADR-1).
 *
 * <p>Extracted verbatim from {@code NormalizerService} (Work Unit 3 of the
 * aggregator SOLID modularization) — pure relocation of constants, no
 * behavior change. {@code NormalizerService}'s classifier, context guards,
 * and pack-quantity detector (still living in {@code NormalizerService}
 * until later work units) reference these arrays via static import.</p>
 *
 * <p>Before this extraction, adding a garment keyword to the classifier
 * did NOT propagate to the pack-quantity detector — two independently
 * duplicated arrays could silently drift apart. Centralizing them here is
 * the one real DRY win of the whole refactor: both
 * {@code CategoryClassifier} (Work Unit 5) and {@code PackQuantityDetector}
 * (Work Unit 4) will read the SAME {@code TORSO_KEYWORDS_FLAT}/
 * {@code PIERNAS_KEYWORDS_FLAT} arrays exposed here.</p>
 */
public final class GarmentTaxonomy {

    private GarmentTaxonomy() {}

    // ══════════════════════════════════════════════════════════════════
    // CALZADO — keywords ordenados de más específico a más genérico
    // ══════════════════════════════════════════════════════════════════

    // KW_*_MODELO: standalone, unambiguous shoe-model/proper names — match
    // WITHOUT requiring esZapatilla co-occurrence (the name itself is the
    // shoe signal, e.g. "ultraboost", "pegasus", "old skool").
    // KW_*_GENERICO: bare/generic words reused across apparel/accessories by
    // the same brands — require esZapatilla co-occurrence in clasificar()
    // (e.g. "running"/"training" alone must NOT classify "Running Sleeves"
    // or "Training Gloves" as a shoe). See NormalizerService.clasificar().
    public static final String[] KW_RUNNING_MODELO = {
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

    public static final String[] KW_RUNNING_GENERICO = {
        "running","correr","corrida","maraton","marathon","trail",
        "atletismo","atletica","ligera","velocidad"
    };

    public static final String[] KW_TRAINING_MODELO = {
        "metcon","free metcon","superrep",
        "adipower","powerlift","nano","legacy lifter"
    };

    public static final String[] KW_TRAINING_GENERICO = {
        "cross training","crossfit","training","cross","gym",
        "hiit","funcional","multideporte","indoor",
        "weightlift","levantamiento"
    };

    public static final String[] KW_SKATE_MODELO = {
        "old skool","sk8-hi","era vans","authentic vans",
        "pure dc","dc court","etnies","emerica",
        "half cab","full cab"
    };

    public static final String[] KW_SKATE_GENERICO = {
        "skate","skateboarding"
    };

    public static final String[] KW_URBANA_MODELO = {
        "air force 1","af1","air force one",
        "stan smith","superstar","campus","gazelle","samba",
        "forum","nmd","continental","ozweego",
        "chuck taylor","all star","converse",
        "suede puma","basket puma","cali puma","rs-x",
        "classic leather","aztrek",
        "cortez","waffle"
    };

    public static final String[] KW_URBANA_GENERICO = {
        "urbana","casual","lifestyle","street","everyday",
        "clasica","clasico","moda","fashion"
    };

    public static final String[] KW_SNEAKER_MODELO = {
        "jordan 1","jordan 4","jordan 11","jordan 3","jordan 6","jordan 5",
        "air jordan","travis scott","off-white","fragment","union","chicago",
        "bred","shadow","university blue","royal",
        "yeezy","boost 350","boost 700","foam runner",
        "dunk low","dunk high","sb dunk","panda dunk",
        "air max 1 ","air max 90","air max 95","air max 97",
        "new balance 550","new balance 990","new balance 2002",
        "new balance 574","new balance 327"
    };

    public static final String[] KW_SNEAKER_GENERICO = {
        "hype","retro","og ","collab","limited","drop","release","sneaker"
    };

    // Tier A — unambiguous, distinctive football-boot tokens. Plain contains()
    // is safe: these strings appear nowhere else in the file and are not
    // common word fragments.
    public static final String[] KW_BOTIN = {
        "botin","cleats","tachon","tachos","chimpun",
        "bota futbol","bota de futbol","predator","mercurial",
        "phantom","nemeziz"
    };

    // Tier B — ambiguous dictionary-like tokens reused inside unrelated words
    // ("ace"⊂Embrace, "copa"⊂Copacabana, "tiempo"⊂entretiempo, "future" is a
    // common English word). Only classify as Botines when esContextoBotin()
    // also matches — mirrors the KW_*_GENERICO + esZapatilla guard pattern.
    public static final String[] KW_BOTIN_GENERICO = {
        "ace","copa","tiempo","future"
    };

    public static final String[] KW_BOTA = {
        "bota ","botas ","boot ","boots ","bucanera",
        "botita","ankle","chelsea boot","desert boot","ugg"
    };

    public static final String[] KW_BORCEGO = {
        "borcego","borcegos","hiker","hiking","work boot",
        "dr martens","martens","dr. martens","1460","chunky boot",
        "plataforma alta","lug sole","bota alta","boot alta"
    };

    // Tier B — marcas que venden TAMBIÉN ropa/camperas (timberland). Solo
    // clasificar como Borcego si hay contexto de calzado en el mismo nombre.
    public static final String[] KW_BORCEGO_MARCA = {
        "timberland"
    };

    public static final String[] KW_SANDALIA = {
        "sandalia de tiras","sandalia con tiras","sandalia plana","sandalia taco",
        "sandalia cuero","sandalia verano","sandalia mujer","sandalia hombre",
        "sandalia goma","tiras cuero","tiras cruzadas"
    };

    public static final String[] KW_OJOTA = {
        "ojota","ojotas","flip flop","chancleta",
        "birkenstock","crocs","havaianas","ipanema","kenner",
        "zueco","clogs","clog","slide sandal","pool slide",
        "rasteira","chinelo","badeleta","babuchas","suela plana",
        "sandalia","sandal","diapositiva","slide"
    };

    // Tier B — "Reef" es marca de indumentaria/accesorios de playa que también
    // vende mochilas, gorras, buzos y billeteras, no solo ojotas/sandalias.
    // Solo clasificar como Ojotas vía este keyword si hay contexto de calzado
    // en el mismo título — mirrors el patrón KW_BORCEGO_MARCA/esContextoBorcego.
    public static final String[] KW_OJOTA_MARCA = {
        "reef "
    };

    public static final String[] KW_MOCASIN = {
        "mocasin","moccasin","loafer","boat shoe","driving shoe",
        "slip on cuero","mocasin cuero","penny loafer"
    };

    public static final String[] KW_ZAPATO = {
        "zapato de vestir","zapato formal","oxford shoe","derby shoe",
        "brogue","monk strap","zapato cuero","zapato de cuero",
        "balerina","flat shoe","kitten heel","taco alto","stiletto",
        "zapato taco","zapato plataforma","chanel shoe"
    };

    public static final String[] KW_PANTUFLA = {
        "pantufla","pantuflas","slipper","slippers","babuchas casa",
        "zapatilla de casa","zapatilla casa"
    };

    // ══════════════════════════════════════════════════════════════════
    // INDUMENTARIA SUPERIOR
    // ══════════════════════════════════════════════════════════════════

    public static final String[] KW_SWEATER = {
        "sweater","pulover","pullover","jersey","knit","tejido",
        "tricot","cardigan","lana","merino","crochet"
    };

    public static final String[] KW_BUZO = {
        "buzo","hoodie","hoody","sweatshirt","sudadera",
        "fleece","polar","zip hoodie","full zip","half zip","canguro"
    };

    // Combo/multi-pieza — ver ADR-4. Variantes con espacio/"de" en set/kit/pack
    // evitan falsos positivos por substring ("settler", "kitsch", "package").
    public static final String[] KW_CONJUNTO = {
        "conjunto","combo","set ","set de","kit ","pack ","dos piezas","2 piezas"
    };

    public static final String[] KW_PUFFER = {
        "puffer","plumon","pluma","down jacket","down coat",
        "campera inflable","chaleco inflable","abrigo inflable","parka inflable",
        "acolchada","acolchado",
        "anorak termico"
    };

    public static final String[] KW_PILOTO = {
        "piloto","impermeable","lluvia","rain jacket","waterproof jacket",
        "chubasquero","k-way","kway","raincoat"
    };

    public static final String[] KW_CAMPERA = {
        "campera","jacket","chaqueta","cortaviento","rompeviento",
        "windbreaker","anorak","softshell","shell",
        "bomber","track jacket","tricota"
    };

    public static final String[] KW_CHALECO = {
        "chaleco","gilet","vest ","waistcoat","chaleco de abrigo",
        "chaleco inflable","chaleco pluma","chaleco polar",
        "chaleco tejido","chaleco cuero"
    };

    public static final String[] KW_SACO = {
        "saco ","sacos ","blazer","americana","sport coat",
        "tuxedo","saco de vestir","saco formal","saco lino",
        "saco tweed","saco sastre"
    };

    // Intencionalmente excluido de la detección de combos — los trajes siempre
    // resuelven a "Traje", ver ADR-4 / tasks.md 0.1 (confirmado por el product owner).
    public static final String[] KW_TRAJE = {
        "traje","suit ","terno","smoking","smocking"
    };

    public static final String[] KW_CHOMBA = {
        "chomba","polo shirt","polera","rugby shirt",
        "pique polo","lacoste polo","fred perry polo"
    };

    // Tier B — "polo" suelto también es nombre de marca/línea en accesorios que
    // no son indumentaria superior ("Medias Polo Green", "Gorra US Polo Assn",
    // "Mochila Polo Club"). Solo clasificar como Chomba vía este keyword si no
    // hay un sustantivo de accesorio explícito en el mismo título — mirrors el
    // patrón KW_BORCEGO_MARCA/esContextoBorcego.
    public static final String[] KW_CHOMBA_MARCA = {
        "polo "
    };

    public static final String[] KW_CASACA = {
        "casaca","camiseta de futbol","camiseta futbol","jersey futbol",
        "camiseta seleccion","camiseta club","replica","kit futbol",
        "camiseta oficial","camiseta de juego","camiseta deportiva",
        "casaca deportiva","camiseta nba","jersey nba"
    };

    public static final String[] KW_MUSCULOSA = {
        "musculosa","tank top","camiseta de tirantes","sin mangas",
        "top deportivo","sports bra","corpino deportivo",
        " top " // "Top" suelto (sin "deportivo"/"interior"/"cuello") — palabra completa
    };

    // Palabras 100% culinarias — corren al inicio de clasificar() para que
    // keywords genéricos de ropa (" top ", "knit", "fleece") no clasifiquen
    // salsas, condimentos o alimentos como indumentaria. Se agregan aquí y no
    // a KW_COMIDA porque KW_COMIDA se evalúa DESPUÉS del bloque de indumentaria.
    //
    // IMPORTANTE: solo tokens INEQUÍVOCOS (no colisionan con vocabulario de
    // ropa vía substring). No mover acá tokens amplios de KW_COMIDA como
    // "mate" (⊂ "material") o "fruta" (⊂ "frutal") — esos deben seguir
    // corriendo DESPUÉS del bloque de indumentaria.
    public static final String[] KW_ALIMENTO_TEMPRANO = {
        "salsa ","ketchup","mostaza ","mayonesa","vinagre ","mermelada ","pudding","chia ",
        // Sustantivos culinarios inequívocos — para que comidas sin marca
        // conocida tampoco las robe el bloque de indumentaria (ej. "Pancake
        // Protein Top" → Pancake Proteico, no Musculosa).
        "pancake","panqueque","waffle","cookie","brownie","galleta","muffin",
        "cereal","granola","avena","palmito","palmitos","pure de ",
        "syrup","sirope","maple","barrita"," mani ","peanut","topping"
    };

    // Marcas de alimento/suplemento — el nombre de la marca ES señal de
    // nutrición aunque el título no traiga sustantivo de comida (ej.
    // "SmartDIET Puré de Palmitos", "NUTREMAX Hydromax", "LA GANEXA",
    // "Diabla Cookie"). Gatean el portón de nutrición en CategoryClassifier.
    // Lista curada y conservadora (confirmada por el product owner): solo
    // marcas cuyo nombre no colisiona con vocabulario de indumentaria.
    // Comparadas sobre el texto normalizado (lowercase, sin acentos).
    public static final String[] KW_MARCA_ALIMENTO = {
        "mr taste","mrs taste","smartdiet","smart diet",
        // CANDIDATA A REVISAR: "diabla" es la marca más ambigua del set —
        // podría aparecer en lencería/merch de ropa. Si algún scrape muestra
        // indumentaria mal taggeada como Alimentos por esta palabra, quitarla.
        "diabla",
        "ganexa","nutremax"
    };

    public static final String[] KW_REMERA = {
        "remera","t-shirt","tee","camiseta","top cuello","manga corta","basic tee"
    };

    public static final String[] KW_CAMISA = {
        "camisa","shirt","oxford","flannel","chambray","denim shirt"
    };

    public static final String[] KW_CORPINO = {
        "corpino","corpino","bralette","bra ","sosten",
        "top interior","ropa interior femenina","sujetador","bikini top"
    };

    public static final String[] KW_CALZONCILLO = {
        "calzoncillo","calzoncillos","boxer","short interior",
        "slip ","tanga","ropa interior masculina","brief","trunk ",
        "underwear","jockstrap","cueca"
    };

    public static final String[] KW_MALLA = {
        "malla","bikini","traje de bano","bano ","bano ",
        "one piece","swimsuit","swimwear","beachwear","tankini",
        "ropa de playa","pileta"
    };

    // ══════════════════════════════════════════════════════════════════
    // INDUMENTARIA INFERIOR
    // ══════════════════════════════════════════════════════════════════

    public static final String[] KW_BAGGY = {
        "baggy","wide leg","pierna ancha","balloon","paperbag",
        "oversize jean","oversized jean","barrel","loose fit",
        "baggy pant","wide pant","cargo pant","carpintero",
        "parachute pant","jogger baggy"
    };

    public static final String[] KW_JEAN = {
        "jean","denim","jeans","vaquero","skinny jean",
        "slim jean","bootcut","straight jean"
    };

    public static final String[] KW_JOGGING = {
        "jogging","pantalon deportivo","sweatpant",
        "jogger","pantalon de buzo","pantalon de entrenamiento",
        "bottoms","track pant","training pant"
    };

    public static final String[] KW_CALZA = {
        "calza","legging","leggin","tight","malla deportiva",
        "capri","culote"
    };

    public static final String[] KW_BERMUDA = {
        "bermuda","bermudas","short largo","short 3/4","walk short"
    };

    public static final String[] KW_SHORT = {
        "short","cargo short","swim short","boxer deportivo"
    };

    public static final String[] KW_POLLERA = {
        "pollera","falda","skirt","minifalda","midi skirt",
        "maxi falda","falda plisada","mini pollera"
    };

    public static final String[] KW_VESTIDO = {
        "vestido","dress ","playero","maxidress","midi dress",
        "vestido largo","vestido corto","vestido de noche"
    };

    public static final String[] KW_ENTERITO = {
        "enterito","mono ","jumpsuit","overol","romper","mameluco",
        "enterito largo","catsuit"
    };

    public static final String[] KW_PANTALON = {
        "pantalon","pant ","trouser","cargo ","chino ","formal pant"
    };

    // ══════════════════════════════════════════════════════════════════
    // ACCESORIOS — separados en categorías específicas
    // ══════════════════════════════════════════════════════════════════

    public static final String[] KW_BOLSO = {
        "bolso","cartera","handbag","tote bag","clutch","minibag",
        "bolsa de mano","bandolera","shoulder bag","crossbody"
    };

    public static final String[] KW_MOCHILA = {
        "mochila","backpack","daypack","hiking pack","school bag","laptop bag"
    };

    public static final String[] KW_RINONERA = {
        "rinonera","rinonera","waist bag","waist pack","fanny pack",
        "hip bag","belt bag","sling bag"
    };

    public static final String[] KW_BILLETERA = {
        "billetera","wallet","cartera hombre","portamonedas",
        "billetera cuero","card holder","tarjetero","porta tarjeta",
        "monedero"
    };

    public static final String[] KW_CINTURON = {
        "cinturon","cinturon","belt ","belts","cinto ","faja ",
        "correa pantalon","leather belt"
    };

    public static final String[] KW_GORRO = {
        "gorro","beanie","gorro de lana","gorro tejido","knit hat",
        "winter hat","gorro invierno","pompom hat","toque"
    };

    public static final String[] KW_GORRA = {
        "gorra","cap ","hat ","sombrero","boina","snapback",
        "bucket hat","buff","balaclava","vincha","visera","dad hat"
    };

    public static final String[] KW_BUFANDA = {
        "bufanda","scarf","panuelo cuello","echarpe","cuello polar",
        "snood","gola","pashmina"
    };

    public static final String[] KW_GUANTES = {
        "guantes","guante","gloves","mittens","guantes de cuero",
        "guantes invierno","guantes ski","guantes moto"
    };

    public static final String[] KW_LENTES = {
        "lentes","anteojos","gafas","sunglasses","sunglass",
        "lentes de sol","anteojos de sol","goggles","polarizados",
        "antiparras","antiparra"
    };

    public static final String[] KW_MEDIAS = {
        "media","medias","sock","socks","calcetin","calcetines","tobillera sock"
    };

    public static final String[] KW_ACCESORIO_DEPORTIVO = {
        "munequera","muñequera","rodillera","codillera","tobillera deportiva",
        "vendaje deportivo","cinta deportiva","soporte rodilla","soporte muneca",
        "shaker","bidon","bidón","botella deportiva","botella termica","termo deportivo"
    };

    // ══════════════════════════════════════════════════════════════════
    // TECH
    // ══════════════════════════════════════════════════════════════════

    public static final String[] KW_NOTEBOOK = {
        "notebook","laptop","netbook","macbook","chromebook",
        "portatil","computadora portatil"
    };

    public static final String[] KW_MONITOR = {
        "monitor ","pantalla pc","display pc","led gaming","monitor gaming",
        "monitor 4k","monitor curvo","monitor 144hz","monitor 27","monitor 24"
    };

    public static final String[] KW_TECLADO = {
        "teclado mecanico","teclado gamer","keyboard","mechanical keyboard",
        "teclado rgb","teclado inalambrico","teclado bluetooth"
    };

    public static final String[] KW_MOUSE = {
        "mouse gamer","mouse gaming","raton gamer","gaming mouse",
        "mouse inalambrico","mouse bluetooth","mouse rgb"
    };

    public static final String[] KW_AURICULAR = {
        "auricular","auriculares","headset","headphone","earphone",
        "earbud","earbuds","inalambrico bt","over-ear","in-ear","on-ear"
    };

    public static final String[] KW_WEBCAM = {
        "webcam","camara web","web cam"
    };

    public static final String[] KW_GPU = {
        "gpu","tarjeta de video","video card","graphics card",
        "rtx ","gtx ","rx ","radeon","geforce","arc "
    };

    public static final String[] KW_RAM = {
        "ram ","memoria ram","dimm","ddr4","ddr5","sodimm",
        "memoria ddr","modulo ram"
    };

    public static final String[] KW_CPU = {
        "procesador","cpu ","core i","ryzen ","intel ","amd ",
        "i3 ","i5 ","i7 ","i9 ","threadripper"
    };

    public static final String[] KW_GABINETE = {
        "gabinete","case pc","tower pc","chasis pc","computer case",
        "gabinete gamer","gabinete atx","gabinete micro atx"
    };

    public static final String[] KW_PC = {
        "pc gamer","computadora de escritorio","desktop pc",
        "pc completa","equipo de escritorio","all in one pc"
    };

    // Training / Gym — ropa de gym y pesas (distinto de running)
    public static final String[] KW_TRAINING_ROPA = {
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
    // SUPLEMENTOS / NUTRICIÓN
    // Subcategorías de suplemento — corren ANTES de KW_SUPLEMENTO en clasificar()
    // ══════════════════════════════════════════════════════════════════

    public static final String[] KW_CREATINA = {
        "creatina","creatine","monohidrato de creatina"
    };

    public static final String[] KW_PROTEINA_BARRA = {
        "barra proteica","protein bar","barra de proteina","barita proteica",
        "bar proteico"
    };

    public static final String[] KW_PROTEINA_PANCAKE = {
        "pancake","panqueque proteico","waffle mix","mezcla para pancake",
        "mezcla para panqueque","mix de pancake"
    };

    public static final String[] KW_PROTEINA_SNACK = {
        "snack proteico","cookie proteica","galleta proteica","brownie proteico",
        "muffin proteico","torta de arroz proteica","snack fit"
    };

    public static final String[] KW_PROTEINA = {
        "proteina ","protein ","whey","isolate","concentrate","caseina","casein",
        "proteina isolada","proteina hidrolizada"
    };

    public static final String[] KW_COLAGENO = {
        "colageno","collagen","hidrolizado de colageno","colageno marino"
    };

    public static final String[] KW_MAGNESIO = {
        "magnesio","magnesium","citrato de magnesio","bisglicinato de magnesio"
    };

    public static final String[] KW_PRE_WORKOUT_SUP = {
        "pre workout","preworkout","pre-workout","pre entreno","cafeina en polvo"
    };

    public static final String[] KW_BCAA_SUP = {
        "bcaa","aminoacido","amino acid","glutamina","glutamine"
    };

    public static final String[] KW_VITAMINAS = {
        "vitamina ","vitamin ","multivitaminico","omega 3","omega3","omega-3"
    };

    public static final String[] KW_QUEMADORES = {
        "quemador de grasa","fat burner","termogenico","l-carnitina","l carnitina",
        "carnitina","cla "
    };

    public static final String[] KW_GAINERS = {
        "mass gainer","hipercalorico"
    };

    public static final String[] KW_SUPLEMENTO = {
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

    public static final String[] KW_COMIDA = {
        "yerba","mate","cafe","te verde","infusion","cereal","granola",
        "frutos secos","almendra","mani","cacao","chocolate proteico",
        "avena","harina de avena","pasta","arroz",
        "salsa ","ketchup","mostaza","condimento","aderezo","mayonesa","vinagre",
        "maple","jarabe de arce","sirope","topping proteico","topping fit",
        "pudding","chia ","semillas","fruta","miel","mermelada","dulce de",
        "snack saludable","galletita","galleta","tostada","pan proteico"
    };

    public static final String[] KW_PERFUME = {
        "perfume","colonia","eau de toilette","eau de parfum","fragancia",
        "desodorante ","antitranspirante","splash","body mist"
    };

    // ══════════════════════════════════════════════════════════════════
    // Vistas derivadas (ADR-1) — usadas por CategoryClassifier (Work Unit 5)
    // y PackQuantityDetector (Work Unit 4), un solo origen para evitar drift.
    // ══════════════════════════════════════════════════════════════════

    public static final String[] TORSO_KEYWORDS_FLAT = concatKeywords(
        KW_PUFFER, KW_PILOTO, KW_SACO, KW_CHALECO, KW_CAMPERA, KW_SWEATER,
        KW_BUZO, KW_CASACA, KW_CHOMBA, KW_MUSCULOSA, KW_CAMISA, KW_REMERA);
    public static final String[] PIERNAS_KEYWORDS_FLAT = concatKeywords(
        KW_CALZA, KW_BAGGY, KW_JEAN, KW_JOGGING, KW_BERMUDA, KW_SHORT,
        KW_VESTIDO, KW_ENTERITO, KW_POLLERA, KW_PANTALON);

    private static String[] concatKeywords(String[]... groups) {
        List<String> flat = new ArrayList<>();
        for (String[] group : groups) flat.addAll(Arrays.asList(group));
        return flat.toArray(new String[0]);
    }

    public static String[] torsoFlat() { return TORSO_KEYWORDS_FLAT; }
    public static String[] piernasFlat() { return PIERNAS_KEYWORDS_FLAT; }

    /**
     * Shared keyword-containment check, hoisted from the byte-identical
     * per-class {@code anyMatch} copies previously in
     * {@code CategoryClassifier} and {@code GymratTagger} — both already
     * depend on this class for their keyword arrays, so this is the natural
     * shared home (post-review cleanup, no logic change).
     */
    public static boolean anyMatch(String text, String[] keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }
}
