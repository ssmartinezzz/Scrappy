package ar.scraper.aggregator.normalize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code NormalizerService}'s classifier, context guards, and pack-quantity detector (still living
 * in {@code NormalizerService} until later work units) reference these arrays via static import.
 */
public final class GarmentTaxonomy {

    private GarmentTaxonomy() {}

    // KW_*_MODELO: standalone, unambiguous shoe-model/proper names — match WITHOUT requiring
    // esZapatilla co-occurrence (the name itself is the shoe signal, e.g. "ultraboost", "pegasus",
    // "old skool").
    public static final String[] KW_RUNNING_MODELO = {
        "ultraboost","adizero","solarboost","duramo",
        "pegasus","vomero","air zoom","free run","air max",
        "gel-kayano","gel-nimbus","gel-cumulus","gel-pulse",
        "glycerin","beast","ghost","adrenaline","levitate",
        "triumph","endorphin","kinvara","ride",
        "clifton","bondi","speedgoat","hoka mach"," mach 4"," mach 5"," mach 6",
        "fresh foam"," 1080 "," 1080v","990","880","860",
        "wave rider","wave inspire","wave horizon",
        "speedcross"," sense ","x-ultra"
    };

    public static final String[] KW_RUNNING_GENERICO = {
        "running","correr","corrida","maraton","marathon","trail",
        "atletismo","atletica","ligera","velocidad"
    };

    public static final String[] KW_TRAINING_MODELO = {
        "metcon","free metcon","superrep",
        "adipower","powerlift"," nano","legacy lifter"
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

    // "patinaje" es GENERICO, no MODELO, y la distinción importa: dcshoes lo usa como prefijo de
    // catálogo en gorras ("Patinaje Dc Shoes University Cap"), gorros y baggies, no sólo en
    // calzado.
    public static final String[] KW_SKATE_GENERICO = {
        "skate","skateboarding","patinaje"
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
        "air jordan","travis scott","off-white","fragment","union la","chicago",
        "bred","university blue","royal",
        "yeezy","boost 350","boost 700","foam runner",
        "dunk low","dunk high","sb dunk","panda dunk",
        "air max 1 ","air max 90","air max 95","air max 97",
        "new balance 550","new balance 990","new balance 2002",
        "new balance 574","new balance 327"
    };

    public static final String[] KW_SNEAKER_GENERICO = {
        "hype","retro"," og ","collab","limited","drop","release","sneaker"
    };

    // Plain contains() is safe: these strings appear nowhere else in the file and are not common
    // word fragments.
    public static final String[] KW_BOTIN = {
        "botin ","botines","cleats","tachon","tachos","chimpun",
        "bota futbol","bota de futbol","predator","mercurial",
        "phantom","nemeziz"
    };

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

    // Solo clasificar como Borcego si hay contexto de calzado en el mismo nombre.
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
        "sandalia"," sandal ","sandals","diapositiva"," slide "," slides "
    };

    // Tier B — "Reef" es marca de indumentaria/accesorios de playa que también vende mochilas,
    // gorras, buzos y billeteras, no solo ojotas/sandalias.
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

    public static final String[] KW_SWEATER = {
        "sweater","pulover","pullover","jersey","knit","tejido",
        "tricot","cardigan"," lana","merino","crochet"
    };

    public static final String[] KW_BUZO = {
        " buzo "," buzos ","hoodie","hoody","sweatshirt","sudadera",
        "fleece","polar","zip hoodie","full zip","half zip","canguro"
    };

    // Variantes con espacio/"de" en set/kit/pack evitan falsos positivos por substring ("settler",
    // "kitsch", "package").
    public static final String[] KW_CONJUNTO = {
        // "set "/"kit "/"pack " sin espacio adelante se comían "Sun(set)", "Mind(set)",
        // "Wind(kit)", "Triple(kit)" y "Doy(pack)" — un doypack de creatina y una campera
        // rompeviento entraban como Conjunto.
        "conjunto","combo"," set ","set de"," kit "," pack ","dos piezas","2 piezas"
    };

    public static final String[] KW_PUFFER = {
        "puffer","plumon"," pluma "," plumas ","down jacket","down coat",
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
        " bomber ","track jacket","tricota"
    };

    public static final String[] KW_CHALECO = {
        "chaleco","gilet"," vest ","waistcoat","chaleco de abrigo",
        "chaleco inflable","chaleco pluma","chaleco polar",
        "chaleco tejido","chaleco cuero"
    };

    public static final String[] KW_SACO = {
        "saco ","sacos ","blazer","americana","sport coat",
        "tuxedo","saco de vestir","saco formal","saco lino",
        "saco tweed","saco sastre"
    };

    // Intencionalmente excluido de la detección de combos — los trajes siempre resuelven a "Traje",
    // ver ADR-4 / tasks.md 0.1 (confirmado por el product owner).
    public static final String[] KW_TRAJE = {
        "traje","suit "," terno ","smoking","smocking"
    };

    public static final String[] KW_CHOMBA = {
        "chomba","polo shirt","polera","rugby shirt",
        "pique polo","lacoste polo","fred perry polo"
    };

    // Solo clasificar como Chomba vía este keyword si no hay un sustantivo de accesorio explícito
    // en el mismo título — mirrors el patrón KW_BORCEGO_MARCA/esContextoBorcego.
    public static final String[] KW_CHOMBA_MARCA = {
        " polo "
    };

    public static final String[] KW_CASACA = {
        "casaca","camiseta de futbol","camiseta futbol","jersey futbol",
        "camiseta seleccion","camiseta club","replica","kit futbol",
        "camiseta oficial","camiseta de juego","camiseta deportiva",
        "casaca deportiva","camiseta nba","jersey nba"
    };

    public static final String[] KW_MUSCULOSA = {
        "musculosa","tank top","camiseta de tirantes","sin mangas"
    };

    /** Explicit sport tops: always "Top Deportivo", ahead of the lingerie keywords ("sports bra" holds " bra "). */
    public static final String[] KW_TOP_DEPORTIVO = {"top deportivo","sports bra","corpino deportivo"};

    /** A bare "top", whole word. Lingerie, sport or neither decides Corpino / Top Deportivo / Top. */
    public static final String[] KW_TOP = {" top "};

    /** A bare top that also names lingerie is a Corpino. */
    public static final String[] KW_TOP_LENCERIA = {" encaje"," lencer"," puntilla"," interior"," bralette"};

    /** Sport signals in the name of a bare top (leading space = word start, so "run" does not match "brunch"). */
    public static final String[] KW_TOP_SENAL_DEPORTIVA = {
        " deportiv"," training"," workout"," running"," run "," gym"," fitness"," yoga"," soporte"," impacto",
        " dri-fit"," dri fit"," techfit"," compression"," compresion"," sport "," sports "," sportswear",
        " crossback"
    };

    /** Sport brands: a bare top from one of them is a sport top. */
    public static final String[] KW_TOP_MARCA_DEPORTIVA = {
        " adidas "," nike "," puma "," asics "," saucony "," under armour "," topper "," reebok "," new balance ",
        " atomik "," fila "," mizuno "," umbro "," lotto "," kappa "," diadora "
    };

    // Palabras 100% culinarias — corren al inicio de clasificar() para que keywords genéricos de
    // ropa (" top ", "knit", "fleece") no clasifiquen salsas, condimentos o alimentos como
    // indumentaria.
    public static final String[] KW_ALIMENTO_TEMPRANO = {
        "salsa ","ketchup","mostaza ","mayonesa","vinagre ","mermelada ","pudding","chia ",
        // Sustantivos culinarios inequívocos — para que comidas sin marca conocida tampoco las robe
        // el bloque de indumentaria (ej. "Pancake Protein Top" → Pancake Proteico, no Musculosa).
        "pancake","panqueque","cookie","brownie","galleta","muffin",
        "cereal","granola","avena","palmito","palmitos","pure de ",
        "syrup","sirope","maple","barrita"," mani ","peanut","topping"
    };

    // Marcas de alimento/suplemento — el nombre de la marca ES señal de nutrición aunque el título
    // no traiga sustantivo de comida (ej. "SmartDIET Puré de Palmitos", "NUTREMAX Hydromax", "LA
    // GANEXA", "Diabla Cookie").
    public static final String[] KW_MARCA_ALIMENTO = {
        "mr taste","mrs taste","smartdiet","smart diet",
        "diabla",
        "ganexa","nutremax","granger"
    };

    public static final String[] KW_REMERA = {
        "remera","t-shirt","t shirt","tshirt"," shirt ","tee","camiseta",
        "top cuello","manga corta","basic tee"
    };

    /**
     * El " shirt " sin calificador se mudó a {@code KW_REMERA}, que es el default honesto: si nada
     * en el nombre dice camisa, no hay razón para archivarla como camisa.
     */
    public static final String[] KW_CAMISA = {
        "camisa","camisaco","oxford","flannel","chambray","denim shirt",
        "dress shirt","lumberjack","camisa lenador","button down","overshirt",
        "over shirt"
    };

    public static final String[] KW_CORPINO = {
        "corpino","corpino","bralette"," bra ","sosten",
        "top interior","ropa interior femenina","sujetador","bikini top"
    };

    /**
     * Women's bottom underwear. "tanga" used to live in KW_CALZONCILLO and filed women's thongs as men's
     * underwear. Swimwear bottoms ("bombacha de bikini", "bombacha de bano") stay Malla and "pantalon culotte"
     * is trousers, so KW_BOMBACHA_VETO keeps both out; so does the gaucho "bombacha de campo".
     */
    public static final String[] KW_BOMBACHA = {
        "bombacha"," panty "," panties ","culotte","colaless","cola less","vedetina","tanga"
    };
    public static final String[] KW_BOMBACHA_VETO = {
        "bikini","bano","malla","playa","swim","pantalon","jean","bermuda"," short ",
        "campo","gaucho","gabardina","rural"
    };

    public static final String[] KW_CALZONCILLO = {
        "calzoncillo","calzoncillos","boxer","short interior",
        "slip ","ropa interior masculina","brief","trunk ",
        "underwear","jockstrap","cueca"
    };

    public static final String[] KW_MALLA = {
        " malla ","mallas ","malla de bano","malla enteriza",
        "bikini","traje de bano",
        "one piece","swimsuit","swimwear","beachwear","tankini",
        "ropa de playa",
    };

    public static final String[] KW_BAGGY = {
        "baggy","wide leg","pierna ancha","balloon","paperbag",
        "oversize jean","oversized jean","barrel","loose fit",
        "baggy pant","wide pant","cargo pant","pantalon carpintero","jean carpintero",
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

    public static final String[] KW_VESTIDO_VETO_LIDER = { " bolso "," bolsos "," bolsa "," mochila " };

    public static final String[] KW_ENTERITO = {
        "enterito"," mono ","jumpsuit","overol","romper","mameluco",
        "enterito largo","catsuit"
    };

    public static final String[] KW_PANTALON = {
        "pantalon","pant ","trouser","cargo "," chino ","formal pant"
    };

    public static final String[] KW_BOLSO = {
        " bolso "," bolsos ","cartera","handbag","tote bag","clutch","minibag",
        "bolsa de mano","bandolera","shoulder bag","crossbody","lonchera"
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
        "cinturon","cinturon","belt ","belts"," cinto ","faja ",
        "correa pantalon","leather belt"
    };

    public static final String[] KW_GORRO = {
        "gorro","beanie","gorro de lana","gorro tejido","knit hat",
        "winter hat","gorro invierno","pompom hat",
    };

    public static final String[] KW_GORRA = {
        "gorra"," cap "," hat ","sombrero","boina","snapback",
        "bucket hat"," buff ","balaclava","vincha","visera","dad hat"," bandana "
    };

    /**
     * Gym-brand names with no garment noun ("Morley Tank", "Boxy Fit Boxing", "CROPTOP 4REAL"),
     * evaluated after every other apparel and accessory block so a name that already matched
     * something ("Think Tank" bag, "Jean Slim Fit") keeps its category.
     */
    public static final String[] KW_MUSCULOSA_TARDIA = {" tank ", " stringer ", " musculos "};
    public static final String[] KW_REMERA_TARDIA = {" boxy ", " long sleeve ", " slim fit ", " v-neck "};
    public static final String[] KW_CORPINO_TARDIO = {" bras "};
    /** A crop top is a fashion top, not lingerie. */
    public static final String[] KW_TOP_TARDIO = {" croptop ", " crop top "};

    public static final String[] KW_BUFANDA = {
        "bufanda","scarf","panuelo cuello","echarpe","cuello polar",
        "snood"," gola ","pashmina"
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
        " media ","medias","sock","socks","calcetin","calcetines","tobillera sock"
    };

    public static final String[] KW_ACCESORIO_DEPORTIVO = {
        "munequera","muñequera","rodillera","codillera","tobillera deportiva",
        "vendaje deportivo","cinta deportiva","soporte rodilla","soporte muneca",
        "shaker","bidon","bidón","botella deportiva","botella termica","termo deportivo"
    };

    public static final String[] KW_NOTEBOOK = {
        "notebook","laptop","netbook","macbook","chromebook",
        "computadora portatil"
    };

    // Van padded con espacios porque anyMatch es un contains() pelado sobre un texto que
    // clasificar() ya padeó: el espacio ES el word boundary.

    public static final String[] KW_SILLA = {
        " silla ","sillas ","silla ergonomica","sillon ergonomico"," stool ","banqueta "
    };

    /**
     * "escritorio" PELADO no puede entrar acá. Sólo formas que nombran el mueble sin ambigüedad.
     */
    public static final String[] KW_ESCRITORIO = {
        "standing desk","escritorio elevable","escritorio regulable","escritorio ajustable"
    };

    /** Una PARTE o un SERVICIO de escritorio no es un escritorio. */
    public static final String[] KW_ESCRITORIO_PARTE = {
        "servicio de ","servicio ","tapa "," ruedas ","ruedas "
    };

    public static final String[] KW_SOPORTE_MONITOR = {
        "brazo de monitor","brazo monitor","soporte de monitor","soporte monitor",
        "estante para monitor","soporte para monitor"
    };

    public static final String[] KW_SOPORTE_LAPTOP = {
        "soporte para laptop","soporte de laptop","soporte laptop",
        "soporte para notebook","soporte de notebook","soporte notebook",
        "stand para laptop","laptop stand"
    };

    public static final String[] KW_PLANTA_ARTIFICIAL = {
        " jardin vertical"," planta artificial"," plantas artificiales"
    };
    public static final String[] KW_PLANTA_ARTIFICIAL_LIDER = {
        " ave de paraiso "," bambu "," olivo "," palmera "
    };
    public static final String[] KW_CESPED_LIDER = {
        " cesped sintetico "," cesped artificial "
    };
    public static final String[] KW_ADHESIVO_LIDER = {
        " adhesivo polimero"," adhesivo sellador"," adhesivo hibrido"," adhesivo de montaje",
        " cartucho adhesivo"," sellador "," adhesivo cementicio"," adhesivo impermeable",
        " adhesivo multimaterial"," adhesivo instantaneo"," adhesivo para ceramic",
        " adhesivo para porcelanato"," pegamento "," cola vinilica"," cola de contacto",
        " cemento de contacto"," silicona "," espuma de poliuretano"," espuma poliuretano"
    };
    public static final String[] KW_MOLDURA_LIDER = {
        " moldura "," cinta/moldura "," zocalo "," zocalos "," guardacanto"," listel",
        " guarda decorativa"," guarda de pared"
    };
    public static final String[] KW_PISO = {
        " piso autoadhesivo"," piso pvc"," piso vinilico"," pisos vinilicos"," piso spc",
        " pisos spc"," piso flotante"," pisos flotantes"," piso laminado"," pisos laminados"
    };
    public static final String[] KW_PISO_LIDER = {
        " deck wpc"," deck baldosa"," deck de madera"," deck de eucalipto"," deck de pino"," deck de pvc",
        " deck de exterior"," deck antideslizante"," deck modular"," deck flotante"
    };
    public static final String[] KW_PLACA_DECORATIVA = {
        " placa decorativa"," placas decorativas"," wall panel"," paneles acusticos",
        " panel acustico"," panel marmol"," azulejo"," mosaico autoadhesivo"," mosaico sticker",
        " piedra de pvc"," rollo piedra"," rollo de piedra"
    };
    public static final String[] KW_PLACA = { " placa "," placas " };
    public static final String[] KW_PLACA_CALIFICADOR = {
        " marmol"," autoadhesiv"," espejo"," simil "," piedra"
    };
    public static final String[] KW_PLACA_VETO = {
        " de video"," de red"," madre"," solar"," wifi"," de sonido"," capturadora"
    };
    public static final String[] KW_VINILO_DECORATIVO = {
        " vinilo "," vinilos "," papel marmol"," papel texturado"," rollo marmol",
        " rollo autoadhesivo"," rollo lino"," rollo esmerilado"," rollo velvet"," texturado simil ",
        " vinilico autoadhesivo"," papel autoadhesivo"
    };

    public static final String[] KW_CERAMICO_LIDER = {
        " ceramico "," ceramicos "," ceramica "," ceramicas "," cer."," cer "," piso ceramico",
        " revestimiento ceramico"," baldosa "," baldosas "
    };
    public static final String[] KW_PORCELANATO_LIDER = { " porcelanato"," porcelanatos"," porc " };
    public static final String[] KW_PASTINA_LIDER = { " pastina "," pastinas " };
    public static final String[] KW_REVESTIMIENTO_LIDER = {
        " revestimiento "," revestimientos "," base coat"," placa pvc"
    };
    public static final String[] KW_TABLERO_LIDER = {
        " placa mdf"," placas mdf"," placa osb"," placa de osb"," placa fenolica"," placa melaminica",
        " placa superboard"," tablero mdf"," tablero de mdf"," tablero osb"," tablero melaminico",
        " tablero aglomerado"," tablero de aglomerado"," tablero de pino"," tablero alistonado",
        " tablero fenolico"," tablero de madera"," terciado "," multilaminado "," fibrofacil"
    };
    public static final String[] KW_PLACA_DE_YESO_LIDER = {
        " placa yeso"," placa de yeso"," placas de yeso"," placa durlock"," placa knauf"
    };
    public static final String[] KW_AISLANTE_LIDER = {
        " aislante "," aislantes "," aislacion "," lana de vidrio"," lana mineral"," lana de roca",
        " ladrillo telgopor"," ladrillos telgopor"
    };
    public static final String[] KW_LADRILLO_LIDER = {
        " ladrillo "," ladrillos "," bloque cemento"," bloque de cemento"," bloques de cemento",
        " bloque de hormigon"," bloque ceramico"," bloque liso"," bloque tabique"," bloque de vidrio",
        " bloque vidrio"," adoquin "," adoquines "," tejuela"
    };
    public static final String[] KW_ARIDO_LIDER = {
        " arena fina"," arena gruesa"," arena mediana"," arena lavada"," arena de rio"," arena para obra",
        " bolsa de arena"," bolson arena"," bolson de arena"," piedra partida"," canto rodado",
        " ripio "," granza "," cascote"," agregado liviano"," agregado "
    };
    public static final String[] KW_CEMENTO_LIDER = {
        " cemento "," cementos "," cal hidraulica"," cal hidrat"," cal viva"," mortero "," morteros ",
        " revoque "," hormigon "," plasticor"
    };
    public static final String[] KW_HIERRO_LIDER = {
        " hierro redondo"," hierro nervado"," hierro aletado"," hierro adn"," hierros "," hierro del "," planchuela"," planchuelas",
        " malla sima"," malla electrosoldada"," barra de hierro"
    };
    public static final String[] KW_IMPERMEABILIZANTE_LIDER = {
        " impermeabilizante "," impermeabilizantes "," membrana asfaltica"," membrana aluminio"," membrana geo"," membrana liquida"," membrana fibrada"," membrana poliuretanica"," membrana impermeabilizante"," membrana no crack"," membrana para techo"," antihumedad",
        " hidrofugo"," pintura impermeabilizante"," pintura impermeable"," aerosol membrana",
        " pintura asfaltica"," emulsion asfaltica"
    };
    public static final String[] KW_CHAPA_LIDER = {
        " chapa acanalada"," chapas acanaladas"," chapa galvanizada"," chapa trapezoidal",
        " chapa sinusoidal"," chapa cincalum"," chapa ondulada"," chapa de zinc"," chapa lisa"
    };
    public static final String[] KW_MADERA_LIDER = {
        " madera pino"," madera de pino"," madera cepillada"," madera dura"," liston pino"," liston de pino"," liston cepillado"," liston de madera"," listones de pino"," listones de madera"," machimbre"," pino cepillado"," pino elliotis"," pino parana"," eucalipto grandis"," eucalipto cepillado"," saligna ",
        " tabla saligna"," tabla de pino"," tablas de pino"," tablon "," tirante pino"," tirante de pino",
        " tirantes de pino"," tirante eucalipto"," tirante de eucalipto"," tirante saligna",
        " tirante de madera"," poste de eucalipto"," poste de madera"," viga de madera"
    };
    public static final String[] KW_PERFILERIA_LIDER = {
        " montante "," montantes "," cantonera "," buna perimetral"," perfil omega"," perfil pvc",
        " perfil moldura"," perfil u "," perfil c "," perfil de aluminio"," perfiles de aluminio"
    };
    public static final String[] KW_SOLERA_LIDER = { " solera " };
    public static final String[] KW_SOLERA_CALIFICADOR = { " mm"," rigid"," e0"," 35 "," 70 "," 90 " };
    public static final String[] KW_ENDUIDO_LIDER = { " enduido "," enduidos "," masilla "," masillas " };
    public static final String[] KW_DILUYENTE_LIDER = { " diluyente"," aguarras "," thinner " };
    public static final String[] KW_ACCESORIO_PINTURA_LIDER = {
        " pinceleta"," set brocha"," protector de superficie"," bandeja para pintor"," bandeja pintor"
    };
    public static final String[] KW_PINCEL_BROCHA_LIDER = { " pincel "," pinceles "," brocha "," brochas " };
    public static final String[] KW_PINCEL_BROCHA_CALIFICADOR = {
        " n°"," cerda"," profesional"," para pintar"," de pintar"," pintor"," virola"
    };
    public static final String[] KW_RODILLO_LIDER = { " rodillo "," rodillos " };
    public static final String[] KW_RODILLO_CALIFICADOR = {
        " antigota"," lanar"," lana "," microfibra"," pelo "," poliester"," n°"," simil"," texturado",
        " para pintar"," de pintar"," pintor"
    };
    public static final String[] KW_PINTURA_LIDER = {
        " pintura "," latex interior"," latex exterior"," latex acrilico"," latex lavable"," latex para"," latex cielorraso"," latex satinado"," esmalte sintetico"," esmalte al agua"," esmalte acrilico",
        " esmalte antioxido"," esmalte convertidor"," barniz "," barnices "," lasur "," impregnante ",
        " impregnantes "," fijador al agua"," fijador sellador"," fijador sintetico"," fijador acrilico"," fijador de pared"," entonador "," entonadores "," colorante liquido",
        " colorante universal"," convertidor de oxido"," antioxido"
    };
    public static final String[] KW_PINTURA_VETO = {
        " facial"," corporal"," dedos"," cara "," rostro"," tela "," unas"," pelo"," cabello"
    };
    public static final String[] KW_AEROSOL_LIDER = { " aerosol " };
    public static final String[] KW_AEROSOL_PINTURA_CALIFICADOR = {
        " brillante"," mate "," satinado"," esmalte"," pintura"
    };
    public static final String[] KW_EMPAPELADO_LIDER = {
        " papel tapiz"," papel pintado"," papel de pared"," empapelado"," empapelados"
    };
    public static final String[] KW_PUERTA_LIDER = { " puerta "," puertas "," pta " };
    public static final String[] KW_PUERTA_VETO = {
        " heladera"," freezer"," horno"," auto "," camioneta"," cocina"," lavarropas"," mueble"," placard",
        " ropero"," vitrina"," microondas"," rejilla"," barrera"," mascota"," perro"," gato"," jaula",
        " ducha"," mampara"," baulera"," bebe"," juguete"
    };
    public static final String[] KW_VENTANA_LIDER = { " ventana "," ventanas "," aireador"," aireadores " };
    public static final String[] KW_VENTANA_VETO = { " auto "," moto "," camion"," polarizad" };
    public static final String[] KW_PORTON_LIDER = { " porton "," portones " };
    public static final String[] KW_MOSQUITERO_LIDER = { " mosquitero "," mosquiteros " };
    public static final String[] KW_MOSQUITERO_VETO = {
        " cuna"," cochecito"," bebe"," electric"," lampara"," carpa"," repelente"," raqueta"
    };
    public static final String[] KW_TOLDO_LIDER = { " toldo "," toldos "," alero "," aleros " };
    public static final String[] KW_TOLDO_VETO = { " camping"," carpa"," playa"," pileta"," mascota"," perro" };
    public static final String[] KW_ESCALERA_LIDER = { " escalera "," escaleras " };
    public static final String[] KW_ESCALERA_VETO = {
        " mascota"," perr"," gat"," cama"," piscina"," pileta"," bebe"," juguete"," muneca"," casita"
    };

    public static final String[] KW_TERMICA_LIDER = {
        " interruptor termomagnetico"," interruptor diferencial"," interruptor automatico"," termomagnetica",
        " termomagneticas"," termica "," termicas "," disyuntor"," llave termomagnetica"," llave termica",
        " guardamotor"
    };
    public static final String[] KW_CAJA_ELECTRICA_LIDER = {
        " caja de embutir"," caja de pase"," caja octogonal"," caja exterior"," caja estanca",
        " caja rectangular"," caja cuadrada"," caja mignon"
    };
    public static final String[] KW_TAPA_CIEGA_LIDER = { " tapa ciega"," tapas ciegas"," tapon ciego" };
    public static final String[] KW_CAJA_CALIFICADOR = { " caja " };
    public static final String[] KW_TAPA_SANITARIA_CALIFICADOR = { " pvc "," polipropileno"," amanco" };
    public static final String[] KW_LLAVE_DE_LUZ_LIDER = {
        " linea armada"," lineas armadas"," llave armada"," llave combinada"," tomacorriente"," tomacorrientes",
        " toma doble"," toma simple"," toma corriente"," tapa modulo"," placa civil"
    };
    public static final String[] KW_MODULO_LIDER = { " modulo "," modulos " };
    public static final String[] KW_MODULO_CALIFICADOR = {
        " bastidor"," toma"," interruptor"," tecla"," 10a"," 10 a"," civil"
    };
    public static final String[] KW_TECLA_LIDER = { " tecla "," teclas " };
    public static final String[] KW_TECLA_CALIFICADOR = {
        " monaco"," tomacorriente"," canal"," 250v"," modulo"," 10a"," 10 a"," interruptor"
    };
    public static final String[] KW_INTERRUPTOR_LIDER = { " interruptor "," interruptores " };
    public static final String[] KW_INTERRUPTOR_CALIFICADOR = {
        " modulo"," 10 a"," 10a"," tecla"," monaco"," 250v"," 220v"," de luz"," combinado"
    };
    public static final String[] KW_FICHA_LIDER = {
        " ficha macho"," ficha hembra"," prolongador"," prolongadores"," alargue"," alargues"
    };
    public static final String[] KW_ZAPATILLA_ELECTRICA_LIDER = { " zapatilla "," zapatillas " };
    public static final String[] KW_ZAPATILLA_ELECTRICA_CALIFICADOR = { " tomas"," toma " };
    public static final String[] KW_CABLECANAL_LIDER = { " cablecanal"," cable canal"," cablecanales" };
    public static final String[] KW_CABLE_ELECTRICO_LIDER = {
        " cable unipolar"," cable bipolar"," cable tripolar"," cable tetrapolar"," cable taller",
        " cable tipo taller"," cable subterraneo"," cable preensamblado"," cable paralelo"," cable perfil"," cable textil"," cable velador"," cable armado"
    };
    public static final String[] KW_CANALETA = { " canaleta"," canaletas" };
    public static final String[] KW_CANALETA_VETO = { " cable"," electric"," organiz" };
    public static final String[] KW_FLEXIBLE_LIDER = { " flexible "," flexibles " };
    public static final String[] KW_FLEXIBLE_CALIFICADOR = {
        " agua"," gas "," mallado"," mallada"," inox"," 1/2"," 3/4"
    };
    public static final String[] KW_REGULADOR_GAS_LIDER = {
        " regulador de gas"," regulador gas"," regulador para gas"," regulador de garrafa",
        " regulador para garrafa"," regulador envasado"," regulador de presion gas"
    };
    public static final String[] KW_LLAVE_DE_PASO_LIDER = {
        " llave de paso"," llave esferica"," valvula esferica"," valvula de paso"," valvula de retencion",
        " llave de corte"
    };
    public static final String[] KW_TANQUE_LIDER = { " tanque "," tanques "," cisterna "," camara septica" };
    public static final String[] KW_TANQUE_CALIFICADOR = {
        " multicapa"," tricapa"," rotoplas"," agua"," cisterna"," lts"," litros"," septica"
    };
    public static final String[] KW_BOMBA_LIDER = { " bomba "," bombas "," electrobomba"," motobomba" };
    public static final String[] KW_BOMBA_CALIFICADOR = {
        " presuriz"," periferica"," centrifuga"," de agua"," sumergible"," hp "," cloacal"," elevadora",
        " electrobomba"," motobomba"
    };
    public static final String[] KW_CANO_LIDER = { " cano "," canos " };
    public static final String[] KW_CANO_VETO = { " escape"," silenciador"," pesca"," catalitico" };
    public static final String[] KW_TUBO_LIDER = { " tubo "," tubos " };
    public static final String[] KW_TUBO_CALIFICADOR = {
        " pvc"," polipropileno"," ppr"," termofusion"," thermofusion"," fusion"," magnum"," pn20"," pn 20",
        " epoxi"," bicapa"," corrugado"," cobre"," galvanizado"," acqua"," tubofusion"," sigas"
    };
    public static final String[] KW_ACCESORIO_CANO_LIDER = {
        " codo "," codos "," union "," uniones "," te "," tee "," buje "," bujes "," cupla "," cuplas ",
        " curva "," curvas "," conector "," conectores "," tapon "," tapones "," rosca "," tuerca ",
        " niple "," reduccion "," insercion "
    };
    public static final String[] KW_ACCESORIO_CANO_CALIFICADOR = {
        " polipropileno"," pvc"," ppr"," termofusion"," thermofusion"," fusion"," pulgada"," 1/2"," 3/4",
        " hierro"," epox"," acqua"," duratop"," genrod"," sigas"," bicapa"," roscad"," tubohogar"," amanco",
        " latyn"," pluvitubo"
    };
    public static final String[] KW_TAPA_LIDER = { " tapa "," tapas " };
    public static final String[] KW_TAPA_CALIFICADOR = { " mm polipropileno"," mm ppr"," mm pvc"," pvc" };
    public static final String[] KW_GRAMPA_LIDER = { " grampa ", " grampas " };
    public static final String[] KW_GRAMPA_CALIFICADOR = { " omega"," clip"," c-clip" };
    public static final String[] KW_SOPORTE_CANO_LIDER = { " soporte media cana"," teflon" };

    public static final String[] KW_ILUMINACION_LIDER = {
        " plafon"," plafones"," spot "," spots "," kit spot"," velador"," veladores"," farola"," farolas",
        " portalampara"," lamparita"," lamparitas"," dicroica"," dicroicas"," led "," luz led"," estaca solar",
        " estacas solares"," estaca ext"," estaca led"," proy solar"," tira led"," vela led"," tubo led",
        " panel led"," reflector led"," reflector solar"," cinta led"
    };
    public static final String[] KW_ILUMINACION_VETO = {
        " tv "," monitor"," televisor"," pantalla led"," notebook"," hdmi"," wifi"," bluetooth"," lumens"," 4k",
        " cajon"," cajones"," mueble"," melamina"
    };
    public static final String[] KW_COLGANTE_LIDER = { " colgante "," colgantes " };
    public static final String[] KW_COLGANTE_CALIFICADOR = { " luz"," luces"," led"," e27"," lampara" };
    public static final String[] KW_APLIQUE_LIDER = { " aplique "," apliques " };
    public static final String[] KW_APLIQUE_CALIFICADOR = {
        " luz"," luces"," e27"," gu10"," solar"," interior"," exterior"," led"," pared"," sensor"
    };
    public static final String[] KW_DIFUSOR_LIDER = { " difusor "," difusores " };
    public static final String[] KW_DIFUSOR_CALIFICADOR = { " bidireccional"," e27"," gu10"," luces" };
    public static final String[] KW_FAROL_LIDER = { " farol "," faroles " };
    public static final String[] KW_FAROL_CALIFICADOR = {
        " colonial"," pared"," solar"," led"," exterior"," jardin"," decorativo"," hierro"
    };
    public static final String[] KW_ANTORCHA_LIDER = { " antorcha "," antorchas " };
    public static final String[] KW_ANTORCHA_CALIFICADOR = { " solar"," led" };
    public static final String[] KW_GUIRNALDA_LIDER = { " guirnalda "," guirnaldas " };
    public static final String[] KW_GUIRNALDA_CALIFICADOR = {
        " luces"," led"," solar"," luz"," bombillo"," portalampara"
    };
    public static final String[] KW_PROYECTOR_LIDER = { " proyector "," proyectores "," reflector "," reflectores " };
    public static final String[] KW_PROYECTOR_CALIFICADOR = {
        " ip65"," ip66"," ip44"," luz fria"," luz calida"," solar"," sensor"," exterior"," halogeno"
    };
    public static final String[] KW_ROSCA_DE_LAMPARA = { " e27 "," gu10 " };

    public static final String[] KW_TALADRO_LIDER = {
        " taladro "," taladros "," rotomartillo"," atornillador"," atornilladora"," martillo perforador",
        " martillo demoledor",
        " destornillador electrico"," destornillador inalambrico"
    };
    public static final String[] KW_AMOLADORA_LIDER = {
        " amoladora"," amoladoras"," pulidora"," pulidoras"," esmeril "," esmeriladora"
    };
    public static final String[] KW_SIERRA_LIDER = {
        " sierra circular"," sierra caladora"," sierra ingletadora"," sierra sensitiva"," sierra sable",
        " sierra de banco"," sierra de mesa"," sierra cinta"," sierra electrica"," sierra inalambrica",
        " caladora"," caladoras"," ingletadora"," ingleteadora"," ingletadoras"," ingleteadoras"
    };
    public static final String[] KW_SOLDADORA_LIDER = {
        " soldadora"," soldadoras"," equipo de soldar"," equipo de soldadura"," maquina de soldar"
    };
    public static final String[] KW_HIDROLAVADORA_LIDER = {
        " hidrolavadora"," hidrolavadoras"," lavadora de alta presion"
    };
    public static final String[] KW_HERRAMIENTA_ELECTRICA_LIDER = {
        " lijadora"," lijadoras"," compresor de aire"," compresor electrico"," motocompresor"," pistola de calor",
        " hormigonera"," hormigoneras"," mezcladora de cemento"," grupo electrogeno"," grupos electrogenos",
        " termofusora"," termofusoras"," clavadora"," clavadoras"," cepilladora"," fresadora"," pistola para pintar",
        " pistola de pintar"," equipo de pintar"," equipo de pintura"," mezclador de pintura"," mezclador electrico",
        " sopladora"," vibrador de hormigon"," minitorno"," multiherramienta"
    };
    public static final String[] KW_ACCESORIO_HERRAMIENTA_LIDER = {
        " disco de corte"," disco de desbaste"," disco flap"," disco diamantado"," disco abrasivo",
        " broca "," brocas "," juego de mechas"," set de mechas"," punta para atornillar"," puntas para atornillar",
        " puntas de destornillador"," hoja de sierra"," hojas de sierra"," sierra copa"," sierra de copa"," electrodo ",
        " electrodos"," alambre para soldadura"," alambre de soldadura"," mandril"," cincel"," cinceles",
        " cargador para bateria"
    };
    public static final String[] KW_MECHA_LIDER = { " mecha "," mechas " };
    public static final String[] KW_MECHA_CALIFICADOR = {
        " cemento"," metal"," madera"," widia"," hormigon"," sds"," acero"," pared"," vidrio"
    };
    public static final String[] KW_BATERIA_HERRAMIENTA_LIDER = { " bateria "," baterias "," cargador "," cargadores " };
    public static final String[] KW_BATERIA_HERRAMIENTA_CALIFICADOR = {
        " 18v"," 20v"," 18 v"," 20 v"," pxc"," power x"," 4.0ah"," 2.0ah"
    };
    public static final String[] KW_LIJA_LIDER = {
        " lija "," lijas "," hoja de lija"," hojas de lija"," banda de lija"," bandas de lija"," papel de lija",
        " disco de lija"," discos de lija"," set discos lija"," set de discos de lija"," tela esmeril"," rollo de lija",
        " esponja de lija"," taco de lija"," taco lijador"
    };
    public static final String[] KW_CAJA_HERRAMIENTAS_LIDER = {
        " caja de herramientas"," caja para herramientas"," caja portaherramientas"," caja porta herramientas",
        " cajas de herramientas"," gabinete para herramientas"," gabinete organizador de herramientas",
        " gabinete de herramientas"," carro portaherramientas"," carro porta herramientas"," carro de herramientas",
        " maletin de herramientas"," maletin portaherramientas"," bolso portaherramientas"," bolso para herramientas",
        " organizador de herramientas"," banco de trabajo"," bancos de trabajo"," cinturon portaherramientas"," cinturon porta herramientas"
    };
    public static final String[] KW_HERRAMIENTA_MANUAL_LIDER = {
        " destornillador"," destornilladores"," martillo "," martillos "," maza de "," mazas de "," alicate"," alicates",
        " tenaza"," tenazas"," cortafrio"," pinza pelacable"," pinza amperometrica"," pinza de punta"," pinza universal",
        " pinza cortante"," pinza para colocar"," pinza de presion"," pinza ajustable"," pinza de mecanico",
        " pinza de electricista"," pinza multiple"," pinza crimp"," pelacable"," pelacables"," llana "," llanas ",
        " fratacho"," fratachos"," cuchara de albanil"," cucharas de albanil"," cucharin"," serrucho"," serruchos",
        " sierra de mano"," sierra manual"," arco de sierra"," cutter "," cutters "," repuesto para cutter",
        " repuesto cutter"," tijera corta chapa"," tijera para chapa"," prensa sargento"," sargento "," morsa "," morsas ",
        " tornillo de banco"," flexometro"," flexometros"," cinta metrica"," metro plegable"," llave inglesa",
        " llave francesa"," llave tubo"," llave de tubo"," llave allen"," llaves allen"," llave torx"," llave combinada",
        " llave stilson"," llave cruz"," llave de copa"," juego de llaves"," set de llaves"," juego de destornilladores",
        " set de destornilladores"," set de puntas"," dado "," dados "," matraca"," crique"," barreta"," pata de cabra",
        " formon"," formones"," escofina"," escofinas"," cepillo de acero"," cepillo de alambre"," cepillo de carpintero",
        " piedra para afilar"," piedra de afilar"," machete"," machetes"," cuchilla metali"," cuchilla retractil"," balde para albanil"," balde albanil",
        " balde de albanil"," pistola calafateadora"," pistola aplicadora"," cortadora de ceramicos",
        " cortadora de azulejos"," cortavidrio"," cortador de vidrio"," cortatubos"," corta tubos"," cortacano",
        " remachadora"," plomada"," set de herramientas"," juego de herramientas"," set herramientas"
    };
    public static final String[] KW_CARRETILLA_LIDER = { " carretilla "," carretillas " };
    public static final String[] KW_CARRETILLA_CALIFICADOR = {
        " lts"," litros"," rueda"," albanil"," reforzada"," obra"," 100l"," 90l"," 80l"
    };
    public static final String[] KW_ESPATULA_LIDER = { " espatula "," espatulas " };
    public static final String[] KW_ESPATULA_CALIFICADOR = {
        " flexible"," acero"," masilla"," enduido"," pintor"," albanil"
    };
    public static final String[] KW_ESPATULA_VETO = { " silicona"," cocina"," reposteria"," madera"," bambu"," nylon" };
    public static final String[] KW_NIVEL_LIDER = { " nivel "," niveles " };
    public static final String[] KW_NIVEL_CALIFICADOR = { " burbuja"," aluminio"," laser"," magnetico"," torpedo"," albanil" };

    public static final String[] KW_TORNILLO_LIDER = {
        " tornillo "," tornillos "," tirafondo"," tirafondos"," autoperforante"," autoperforantes"," bulon "," bulones",
        " perno "," pernos "
    };
    public static final String[] KW_TARUGO_LIDER = {
        " tarugo "," tarugos "," anclaje"," anclajes"," ancla de expansion"," ancla quimica"
    };
    public static final String[] KW_TARUGO_VETO = { " tubeless"," plug"," tappabuco"," effetto" };
    public static final String[] KW_TACO_LIDER = { " taco "," tacos "," blister tacos"," blister de tacos" };
    public static final String[] KW_TACO_CALIFICADOR = {
        " fischer"," nylon"," mamposteria"," hormigon"," ladrillo"," tornillo"," expansivo"," duopower"
    };
    public static final String[] KW_CLAVO_LIDER = { " clavo "," clavos " };
    public static final String[] KW_FIJACION_LIDER = {
        " alambre "," alambres "," tuerca "," tuercas "," arandela "," arandelas "," remache "," remaches ",
        " abrazadera "," abrazaderas "," caja de fijacion"," caja fijacion"," soporte l "," soporte angulo",
        " angulo de fijacion"," grillete"," precinto "," precintos "," grilletes"," mosqueton"," mosquetones"," varilla roscada"," cable de acero"," cable acero"
    };
    public static final String[] KW_BISAGRA_LIDER = { " bisagra "," bisagras " };
    public static final String[] KW_CERRADURA_LIDER = {
        " cerradura"," cerraduras"," kit cerradura"," kit de cerradura"," picaporte"," picaportes"," manija doble",
        " manijas doble"," manija simple"," manija para puerta"," cerrojo"," cerrojos"," falleba"," pestillo"
    };
    public static final String[] KW_CANDADO_LIDER = {
        " candado "," candados "," caja porta llaves"," caja portallaves"," cofre porta"," cofre de seguridad"
    };
    public static final String[] KW_HERRAJE_LIDER = {
        " corredera"," correderas"," tirador "," tiradores"," piston a gas"," pistones a gas"," pata regulable",
        " patas regulables"," regaton"," regatones"," soporte para barral"," soporte para estante",
        " soporte de estante"," mensula"," mensulas"," kit corredizo"," tapacanto"," fieltro autoadhesivo",
        " fieltro circular"," tope adhesivo"," topes adhesivos"," tope para puerta"," cierrapuertas"
    };
    public static final String[] KW_PATA_LIDER = { " pata "," patas " };
    public static final String[] KW_PATA_CALIFICADOR = {
        " regulable"," para mesa"," para mueble"," mesa"," mueble"," plastica"
    };
    public static final String[] KW_RUEDA_LIDER = { " rueda "," ruedas " };
    public static final String[] KW_RUEDA_CALIFICADOR = {
        " polipropileno"," giratoria"," fija "," pivotante"," goma"," carro"," mueble"," freno"
    };
    public static final String[] KW_CINTA_ADHESIVA_LIDER = {
        " cinta adhesiva"," cinta de embalar"," cinta embalaje"," cinta de papel"," cinta papel"," cinta de enmascarar",
        " cinta enmascarar"," cinta de pintor"," cinta aisladora"," cinta de aluminio"," cinta aluminio",
        " cinta doble faz"," cinta de espuma"," cinta espuma"," cinta polietileno"," cinta de peligro",
        " cinta demarcatoria"," cinta de seguridad"," cinta antideslizante"," cinta de tela"," cinta de carrocero",
        " cinta de empalme"," cinta autoadhesiva"," cinta butilica"," cinta asfaltica"," cinta de montaje",
        " cinta de vinilo"," tira adhesiva"
    };
    public static final String[] KW_GUANTE_LIDER = { " guante "," guantes ", " par de guantes" };
    public static final String[] KW_GUANTE_TRABAJO_CALIFICADOR = {
        " vaqueta"," moteado"," latex"," nitrilo"," pvc"," trabajo"," soldador"," anticorte"," mecanico"," descarne",
        " carnaza"," neoprene"," dielectrico"," rigger"," jardiner"," proteccion"," multiuso"," sin costura"," jardin"," rosas"," poliuretano"
    };
    public static final String[] KW_PROTECCION_LIDER = {
        " casco de seguridad"," casco seguridad"," casco industrial"," casco de obra"," casco dielectrico",
        " lentes de seguridad"," gafas de seguridad"," anteojos de seguridad"," protector facial"," mascara de soldar",
        " careta de soldar"," careta para soldar"," careta protectora"," careta facial"," protector auditivo"," protectores auditivos"," tapones auditivos",
        " tapon auditivo"," barbijo"," barbijos"," respirador"," respiradores"," mascara respirador"," mascara para polvo",
        " arnes de seguridad"," arnes anticaidas"," chaleco refractario"," chaleco de seguridad"," chaleco reflectivo",
        " chaleco de trabajo"," botines de seguridad"," zapatos de seguridad"," calzado de seguridad"," bota de seguridad",
        " botas de seguridad"," botin de seguridad"," zapato de trabajo"," ropa de trabajo"," overol"," mameluco de trabajo",
        " mamelucos de trabajo"," orejeras de proteccion"," orejera de proteccion"," faja lumbar"," cinturon liniero"
    };
    public static final String[] KW_ANTIPARRA_LIDER = { " antiparra "," antiparras " };
    public static final String[] KW_ANTIPARRA_PROTECCION_CALIFICADOR = {
        " seguridad"," trabajo"," proteccion"," policarbonato"," industrial"," antiempanante"
    };

    public static final String[] KW_SANITARIO_LIDER = {
        " inodoro"," inodoros"," bidet "," bidets "," deposito de colgar"," deposito para inodoro",
        " deposito de inodoro"," deposito doble descarga"," lavatorio "," lavatorios "," pedestal ",
        " columna de bano"," asiento de inodoro"," tapa de inodoro"," tapa inodoro"," asiento inodoro",
        " mingitorio"," piedra de lavatorio"
    };
    public static final String[] KW_GRIFERIA_LIDER = {
        " griferia"," griferias"," canilla "," canillas "," combo griferia"," monocomando "," mezcladora de cocina",
        " mezclador de cocina"," mezclador monocomando"
    };
    public static final String[] KW_DUCHA_LIDER = {
        " ducha "," duchas "," columna de ducha"," flor de ducha"," set de ducha"," duchador"," telefono de ducha",
        " telefono ducha"," ducha de pared"
    };
    public static final String[] KW_DUCHA_VETO = { " solar"," portatil"," jardin"," camping" };
    public static final String[] KW_BANERA_LIDER = { " banera "," baneras "," hidromasaje"," jacuzzi" };
    public static final String[] KW_BANERA_VETO = {
        " bebe"," baby"," infantil"," ninos"," plegable"," inflable"," mascota"," perro"," gato"," pileta"
    };
    public static final String[] KW_MAMPARA_LIDER = {
        " mampara"," mamparas"," cabina de ducha"," cabina de bano"," box de ducha"," cabina ducha"
    };
    public static final String[] KW_VANITORY_LIDER = {
        " vanitory"," vanitorys"," vanitories"," mueble de bano"," mueble para bano"," mueble vanitory"
    };
    public static final String[] KW_BACHA_VETO = { " perro"," gato"," mascota"," pet "," comedero" };
    public static final String[] KW_BACHA_LIDER = {
        " bacha "," bachas "," pileta de cocina"," pileta de apoyar"," pileta de acero"," pileta simple",
        " pileta doble"," pileta de embutir"," pileta para cocina"
    };
    public static final String[] KW_ACCESORIO_BANO_LIDER = {
        " toallero"," toalleros"," jabonera"," jaboneras"," portacepillo"," portacepillos"," porta cepillo",
        " porta cepillos"," portarrollo"," portarrollos"," porta rollo"," porta rollos"," dispenser de jabon",
        " dispenser doble jabon"," dispenser jabon"," dispenser de papel higienico"," escobilla"," escobillas",
        " set de accesorios bano"," set accesorios bano"," set de bano"," accesorios de bano"," accesorio de bano",
        " percha de bano"," perchero de bano"," gancho de bano"," repisa de bano"," estante de bano"
    };

    public static final String[] KW_ESTANTERIA_VETO = { " monitor"," notebook"," laptop"," tv "," televisor"," pc " };
    public static final String[] KW_PLACARD_LIDER = {
        " placard"," placares"," closet"," closets"," ropero"," roperos"," armario"," armarios"," guardarropa"
    };
    public static final String[] KW_CAMA_LIDER = {
        " cama "," camas "," sommier"," sommiers"," cucheta"," cuchetas"," camarote"," cama box"
    };
    public static final String[] KW_CAMA_VETO = {
        " perro"," gato"," mascota"," canil"," pet "," elastica"," solar"," inflable"," plegable de camping",
        " camping"," bebe"," cuna"," masaje"," maquina"," pack "
    };
    public static final String[] KW_COLCHON_LIDER = { " colchon "," colchones "," colchon de " };
    public static final String[] KW_COLCHON_VETO = { " inflable"," camping"," aire "," perro"," mascota"," bebe"," yoga" };
    public static final String[] KW_MUEBLE_LIDER = {
        " comoda "," comodas "," rack para tv"," rack tv"," rack de tv"," centro de entretenimiento"," biblioteca ",
        " bibliotecas "," librero "," libreros "," despensero"," bajo mesada"," alacena "," mesa de luz"," mesas de luz",
        " mesita de luz"," mesita de noche"," zapatero "," zapateros "," mueble "," muebles "," arrimo ",
        " aparador"," vitrina "," rinconero"," esquinero "," tocador "," cristalero"," repisero"," centro de trabajo",
        " centro trabajo"
    };
    public static final String[] KW_ESCRITORIO_LIDER = { " escritorio "," escritorios " };
    public static final String[] KW_MUEBLE_VETO = { " gamer"," camping"," pc gamer"," exhibidor"," vestuario" };
    public static final String[] KW_MESA_LIDER = {
        " mesa "," mesas "," mesa ratona"," mesa de comedor"," comedor "," comedores "," juego de comedor",
        " set mesa"," set de mesa"," set 2 mesas"," set de 2 mesas"," set de mesas"," mesada de"
    };
    public static final String[] KW_MESA_VETO = {
        " ping"," pong"," billar"," pool"," camping"," masaje"," camilla"," tenis de mesa"," pesca"," picnic"," pic nic",
        " corte"," soldar"," dibujo"," mezcla"," mezclar"," dj "," cocina de"," pesas"," perro"," gato"," mascota",
        " tablero"," trabajo"," herramienta"," pack "," gamer"," gaming"
    };
    public static final String[] KW_SILLON_LIDER = {
        " sillon "," sillones "," futon "," futones "," puff "," puffs "," pouf "," poufs "," sitial "," sitiales ",
        " sofa "," sofas "," sofa cama"," love seat"," butaca "," butacas "
    };
    public static final String[] KW_SILLON_VETO = {
        " gamer"," gaming"," masaje"," ergonomico"," oficina"," ejecutivo"," dental"," peluqueria"," inflable"," camping",
        " playa"," perro"," gato"," mascota"," bebe"," lactancia"," odontolog"," dentista"
    };

    public static final String[] KW_ESTANTERIA_LIDER = {
        " estanteria"," estanterias"," estante "," estantes "," repisa "," repisas "," mega rack"," metal rack",
        " rack metalico"," rack de estantes"," estante flotante"," biblioteca flotante"
    };
    public static final String[] KW_CAJA_CANASTO_LIDER = {
        " canasto "," canastos "," cesto "," cestos "," hamper "," hampers "," baul "," baules "," cajas plasticas",
        " cajas rigidas"," cajas organizadoras"," bolsa organizadora"," bolsas organizadoras",
        " carro organizador"," carro org "," canasto org"," bolsa lavado"," bolsa de lavado"," tapa canasto"
    };
    public static final String[] KW_CAJA_LIDER = {
        " caja plastica"," caja rigida"," caja alta"," caja baja"," caja promocional"," caja apilable",
        " caja organizadora"," caja org "," caja de almacenamiento"," caja contenedora"," caja tela"
    };
    public static final String[] KW_CAJA_CANASTO_VETO = {
        " moto "," auto "," camioneta"," pick up"," pickup"," bici"," bicicleta"," herramienta"," pesca"," gamer"," cooler"," manubrio"," bontrager"
    };
    public static final String[] KW_BASURERO_LIDER = {
        " basurero"," basureros"," tacho de basura"," tacho para basura"," cesto de basura"," cesto basura",
        " papelero"," papeleros"," set basurero"," tacho de residuos"," cesto de residuos"
    };
    public static final String[] KW_TENDEDERO_LIDER = {
        " tendedero"," tendederos"," tender "," tendal "," tendales "," secarropas de pared"
    };
    public static final String[] KW_COLGADOR_LIDER = {
        " colgador "," colgadores "," percha "," perchas "," perchero"," percheros"," perchera"," gancho "," ganchos ",
        " funda ropa"," funda abrigo"," funda de ropa"," set 20 perchas"," set de perchas"
    };
    public static final String[] KW_COLGADOR_VETO = {
        " pesca"," bici"," bicicleta"," camping"," moto"," auto "," camioneta"," pared de gimnasio"," arnes"," anclaje"
    };
    public static final String[] KW_LIMPIEZA_LIDER = {
        " mopa "," mopas "," repuesto mopa"," repuesto de mopa"," escoba "," escobas "," secador de piso",
        " esponja "," esponjas "," trapo "," trapos "," trapo de piso"," limpia inodoro"," limpia inodoros"," limpia vidrios",
        " limpia pisos"," sacapelusas"," bolsas para escombros"," bolsas de residuos"," bolsa de residuos",
        " bolsas para residuos"," bolsas de consorcio"," bolsas de basura"," bolsa de basura"," bloque adhesivo",
        " pala de residuos"," cepillo de limpieza"," lampazo"," lampazos"," pano de limpieza"," panos de limpieza",
        " rejilla de piso"," desinfectante"," guante de limpieza"," guante limpieza"," guantes de limpieza"," lavandina"," detergente"
    };
    public static final String[] KW_LIMPIEZA_VETO = {
        " maquillaje"," corporal"," bano "," ducha"," facial"," exfoliante"," pelo"," cabello"," beauty"," cosmetic"
    };
    public static final String[] KW_SISTEMA_MODULAR_LIDER = {
        " riel ensamble"," riel metalico ensamble"," barra superior ensamble"," barra superior"," escuadra ensamble",
        " estructura sistema"," marco decorativo retractil"," bandeja retractil"," magneto tablero"," magneto tableros",
        " gancho para tablero"," tablero riel"," bandeja tablero"," bandeja malla para tablero",
        " organizador malla tablero"," canasto para tablero"," canasto tablero"," repisa ensamble"," repisa ventilada",
        " divisor repisas"," kit sistema modular"," canasto marco malla"," canasto malla"," canasto modular",
        " tapa canasto modular"," cajonera modular"," canasto metal malla"," canasto metal alambre"," canasto alambre",
        " repisa canasto"," canasto apilable metalico"," caja organizadora modular"," caja rigida modular",
        " sistema modular"," sistema retractil"," riel ensamble"
    };

    public static final String[] KW_ESPEJO_LIDER = { " espejo "," espejos " };
    public static final String[] KW_ESPEJO_VETO = {
        " retrovisor"," auto "," moto "," bicicleta"," bici "," telescopio"," dental"," luz led"," camara"
    };
    public static final String[] KW_CORTINA_LIDER = {
        " cortina "," cortinas "," persiana "," persianas "," barral "," barrales "," kit barral",
        " barra metal extendible"," protector cortina"," riel para cortina"," riel cortina"
    };
    public static final String[] KW_CORTINA_VETO = {
        " ducha solar"," metalica de seguridad"," garage"," kit de ducha"
    };
    public static final String[] KW_ROLLER_LIDER = { " roller "," rollers " };
    public static final String[] KW_ROLLER_CALIFICADOR = { " blackout"," sunscreen"," screen"," translucido"," cm " };
    public static final String[] KW_ROLLER_VETO = {
        " patin"," skate"," ruedas"," inline"," masaje"," espuma"," muscular"," pelo"," maquillaje"," pintura"," rodillo"
    };
    public static final String[] KW_ROPA_DE_CAMA_LIDER = {
        " sabana "," sabanas "," juego de sabanas"," plumon "," plumones "," funda plumon"," funda de plumon"," almohada "," almohadas ",
        " acolchado "," acolchados "," edredon"," cubrecama"," cubre cama"," cobertor"," frazada"," colcha "," colchas ",
        " cubrecolchon"," cubre colchon"," protector de colchon"," protector colchon"," sobrecama"," duvet "
    };
    public static final String[] KW_ROPA_DE_CAMA_VETO = {
        " hombre"," mujer"," campera"," chaleco"," buzo"," nino"," nina"," cervical"," viaje"," masaje"," deportiv",
        " running"," sleeping"," camping"," bebe"," lactancia"," mascota"," perro"," gato"," talle"
    };
    public static final String[] KW_TOALLA_LIDER = {
        " toalla "," toallas "," toallon"," toallones "," juego de toallas"," toalla de playa"," toalla de bano"
    };
    public static final String[] KW_TOALLA_VETO = {
        " gym"," training"," entrenamiento"," fitness"," nike"," adidas"," puma"," deportiv"," microfiber "
    };
    public static final String[] KW_DECORACION_LIDER = {
        " cuadro "," cuadros "," canvas simil"," florero"," floreros"," farol "," faroles "," vela "," velas ",
        " portarretrato"," portarretratos"," marco de fotos"," marco para fotos"," esfera "," esferas "," adorno ",
        " adornos "," adorno navideno"," arbol de navidad"," arbol navidad"," cascanuez"," cascanueces"," guirnalda",
        " punta de arbol"," muneco navideno"," bota de navidad"," pick decorativo"," plato base"," carrusel musical",
        " centro de mesa"," difusor de ambiente"," figura decorativa"," objeto decorativo"," macetero decorativo"
    };
    public static final String[] KW_ARBOL_CALIFICADOR = { "ramas"," navidad"," navideno"," nav2"," nav1" };
    public static final String[] KW_ARBOL_LIDER = { " arbol " };
    public static final String[] KW_DECORACION_VETO = {
        " bici"," bicicleta"," rodado"," luz "," electrico"," termico"," ignicion"," encendido"," bujia"," motor"," delantero",
        " trasero"," auto "," moto "," pelo"," cabello"," torta"," reloj"," watch"," gimnasio"," proteina"," yoga",
        " ejercicio"," camping"," recargable"," linterna"
    };
    public static final String[] KW_BAZAR_LIDER = {
        " frasco "," frascos "," copa de "," copas de "," copa "," copas "," juego de copas"," jarra "," jarras ",
        " vajilla"," juego de vasos"," vasos de vidrio"," juego de platos"," juego de tazas"," vaso "," vasos "
    };
    public static final String[] KW_BAZAR_VETO = {
        " shaker"," proteina"," medidora"," gimnasio"," suplemento"," perfume"," mundial"," trofeo"," copa del mundo",
        " copa libertadores"," pelota"
    };

    public static final String[] KW_MASCOTA_CONTEXTO = {
        " mascota"," para perro"," para gato"," p/perro"," p/gato"," perros "," gatos "," de perro"," de gato"," canino"," felino",
        " pet's "," pets "
    };
    public static final String[] KW_MASCOTA_VETO = {
        " figura"," adorno"," hidraulico"," remera"," pijama"," buzo"," campera"," zapatilla"," peluche de"
    };
    public static final String[] KW_MASCOTA_ALIMENTO_LIDER = {
        " alimento "," alimentos "," snack "," snacks "," galleta "," galletas "," hueso "," huesos "
    };
    public static final String[] KW_MASCOTA_CAMA_LIDER = {
        " cama "," camas "," colchoneta "," casa "," cojin "," cucha "," cuchas "," hamaca "," transportadora ",
        " transportadoras "," bolso "," jaula "," canil "
    };
    public static final String[] KW_MASCOTA_HIGIENE_LIDER = {
        " dispensador "," pala "," bandeja "," bolsas "," cepillo "," piedras "," sanitario "," limpiador "," shampoo ",
        " arena "," acondicionador "," kit sanitario"," contenedor "," carpeta "," pañal"," panal"
    };
    public static final String[] KW_MASCOTA_ACCESORIO_LIDER = {
        " collar "," collares "," correa "," correas "," arnes "," juguete "," juguetes "," plato "," platos ",
        " comedero "," bebedero "," frisbee "," chaleco "," chaqueta "," conjunto "," mat "," lick "," peluche ",
        " polar "," cobertor "," estaca "," accesorios de mascotas"," accesorios para mascotas",
        " collares y arneses"
    };

    public static final String[] KW_AUTO_CONTEXTO = {
        " auto "," autos "," automotor"," vehiculo"," camioneta"," coche "," auto,"," para moto"
    };
    public static final String[] KW_LUBRICANTE_LIDER = {
        " lubricante "," lubricantes "," aceite "," aceites "," grasa "," grafito "," desoxidante "," anticorrosivo "
    };
    public static final String[] KW_LUBRICANTE_CALIFICADOR = {
        " lubricante"," motor"," sintetico"," 2t"," 4t"," w40"," w30"," w50"," 10w"," 15w"," 20w"," 5w"," transmision",
        " cadena"," cortadora"," motosierra"," hidraulico"," multiuso"," grasa "," grafito "," desoxidante "," anticorrosivo "
    };
    public static final String[] KW_LUBRICANTE_VETO = {
        " intimo"," esencial"," oliva"," girasol"," siliconada"," cbd"," masaje"," coco"," aromatico"," termica"," termico"
    };
    public static final String[] KW_LIQUIDO_AUTO_LIDER = {
        " refrigerante "," refrigerantes "," liquido de freno"," liquido para freno"," liquido refrigerante",
        " agua destilada"," agua desmineralizada"," agua para bateria"," aditivo "," aditivos "," lavaparabrisas ",
        " lava parabrisas"," limpia inyectores"," limpia radiador"," arranca motor"
    };
    public static final String[] KW_LIQUIDO_AUTO_VETO = { " sram"," shimano"," finish line"," bici"," mtb"," fibra"," vitamin"," proteina" };
    public static final String[] KW_BATERIA_AUTO_LIDER = {
        " bateria "," baterias "," cargador de bateria"," cargador bateria"," cargador para bateria",
        " cargador inteligente"," cargador de auto"," arrancador "," cable puente"," cables puente",
        " cable para bateria"," cables para bateria"," cable de bateria"," cables de bateria"
    };
    public static final String[] KW_BATERIA_AUTO_CALIFICADOR = {
        " 12v"," 12 v"," 6v"," 24v"," amp"," arrancador "," cargador"," puente"," automotor"," auto "," para bateria",
        " de bateria"," moto "
    };
    public static final String[] KW_BATERIA_AUTO_VETO = {
        " notebook"," celular"," bicicleta"," sram"," herramienta"," taladro"," kit bateria"," cortadora"," iphone",
        " magsafe"," usb"," cocina"," tablet"
    };
    public static final String[] KW_ESCOBILLA_AUTO_LIDER = { " escobilla "," escobillas "," limpiaparabrisas " };
    public static final String[] KW_ESCOBILLA_AUTO_VETO = { " inodoro"," bano"," wc"," pileta"," piscina"," piso"," ducha"," mango"," manual" };
    public static final String[] KW_LIMPIEZA_AUTO_LIDER = {
        " aromatizante "," aromatizantes "," autopolish "," polish "," revividor "," renovador "," abrillantador ",
        " lavacoche "," lavacoches "," cera para auto"," cera liquida"," cera "," silicona para auto"," silicona automotor",
        " shampoo para auto"," shampoo con cera"," shampoo auto"," manopla "," limpia tablero"," limpia tapizados",
        " limpiadores y lava parabrisas"," brillo y lustre"," accesorios de limpieza automotor"," limpia llantas"," limpiador de llantas"," removedor de"," plumero para auto"," pano microfibra"," panos microfibra",
        " pano de microfibra"," panos de microfibra"," pano para auto"," kit de limpieza para auto"," aspiradora de auto",
        " aspiradora para auto"," aspiradora auto"," aspiradoras de auto"
    };
    public static final String[] KW_LIMPIEZA_AUTO_VETO = {
        " cartucho"," selladora"," selladores"," neutra"," acetica"," hogar"," depilar"," vela"," madera"," bano"," sanitario",
        " piso"," pisos"," uso general"," transparente"," blanca"," negra"," pistola"," aplicador"," caulk"," termo"," depila"," modeladora"," de abeja"," para pelo"," capilar"," unas"," esmalte"
    };
    public static final String[] KW_ACCESORIO_AUTO_LIDER = {
        " cubre auto"," cubreauto"," cubre volante"," cubre asiento"," cubre asientos"," funda cubre"," parasol "," parasoles ",
        " portabicicleta "," porta equipaje"," portaequipaje"," realce "," apoyabrazos "," tuerca antirrobo"," tuercas antirrobo",
        " accesorios para autos"," soporte para celular de auto"," fundas y cobertores"
    };
    public static final String[] KW_ACCESORIO_AUTO_EN_CONTEXTO_LIDER = {
        " funda "," fundas "," alfombra "," alfombras "," almohada "," almohadon "," amohadon "," cubre "," organizador ",
        " cortina "," cortinas "," espejo "," embudo "," soporte "," tuerca "," tuercas "," asiento "," mat "," lampara "," lamparas "," juego de "
    };
    public static final String[] KW_HERRAMIENTA_AUTO_LIDER = {
        " crique "," criquet "," criquets "," cinta crique"," caballete con"," caballete para auto"," caballete de auto"," caballete automotor"," camilla mecanico",
        " camilla para mecanico"," compresor para auto"," minicompresor "," inflador de neumatico"," inflador para auto"," inflador de auto"," inflador auto"," tensor "," tensores ",
        " cincha "," cinchas "," linga "," lingas "," cinta de remolque"," cable de remolque"," calibrador de neumatico",
        " medidor de presion"," kit parche"," kit de parche"," cadena para nieve"," cadenas para nieve"," cadena de nieve",
        " compresor de aire para auto"," gata "," gato hidraulico"," gato carpintero"
    };
    public static final String[] KW_HERRAMIENTA_AUTO_VETO = { " elastico"," fitness"," ejercicio"," gym"," bici"," mtb"," ruta"," entrenamiento" };
    public static final String[] KW_SEGURIDAD_VIAL_LIDER = {
        " matafuego "," matafuegos "," extintor "," kit reglamentario"," kit de seguridad vial"," kit vial"," baliza "," balizas ",
        " botiquin "," botiquines "," trabavolante "," traba volante"," chaleco reflectivo"," bandolera reflectiva"
    };

    public static final String[] KW_TV_AUDIO_LIDER = {
        " smart tv"," televisor "," televisores "," tv led"," tv 4k"," soundbar "," barra de sonido"," parlante "," parlantes ",
        " antena tv"," antena para tv"," antena de tv"," antena digital"," antena "," home theater"
    };
    public static final String[] KW_TV_AUDIO_VETO = {
        " notebook"," auricular"," para bicicleta"," wifi"," router"," repuesto"," celular"
    };
    public static final String[] KW_TERMOTANQUE_LIDER = { " termotanque "," termotanques "," termo tanque" };
    public static final String[] KW_CALEFON_LIDER = { " calefon "," calefones " };
    public static final String[] KW_CALEFACTOR_LIDER = {
        " calefactor "," calefactores "," estufa "," estufas "," caloventor "," caloventores "," panel calefactor",
        " panel electrico"," panel ceramico"," panel radiante"," panel vitroceramico"," panel de calefaccion"," convector ",
        " convectores "," radiador electrico"," hogar a lena"," hogar a gas"," hogar electrico"," hogar de lena"," hogar lena",
        " hogar a"," hogar tiraje"," estufas, caloventores"," toallero electrico"," toallero calefactor"
    };
    public static final String[] KW_VENTILADOR_LIDER = {
        " ventilador "," ventiladores "," turboventilador "," enfriador de aire"," enfriadores de aire"
    };
    public static final String[] KW_VENTILADOR_VETO = {
        " pc "," gamer"," rgb"," cpu"," gabinete"," notebook"," fan "," 120mm"," 140mm"," usb"," cooler"
    };
    public static final String[] KW_AIRE_ACONDICIONADO_LIDER = {
        " aire acondicionado"," aires acondicionados"," aire split"," aire portatil"," aire frio"," split inverter"
    };
    public static final String[] KW_HELADERA_LIDER = {
        " heladera "," heladeras "," freezer "," freezers "," exhibidora "," cava de vino"," cava "
    };
    public static final String[] KW_LAVARROPAS_LIDER = {
        " lavarropas "," lavarropa "," secarropas "," secarropa "," lavavajillas "," lavasecarropas "
    };
    public static final String[] KW_COCINA_LIDER = {
        " cocina "," cocinas "," anafe "," anafes "," horno "," hornos "," microondas "
    };
    public static final String[] KW_COCINA_CALIFICADOR = {
        " gas"," electric"," electrica"," induccion"," vitro"," hornallas"," hornalla"," empotrar"," horno "," hornos ",
        " anafe "," anafes "," microondas "," litros"," lts"," lt "," cm "," cocinas "," whirlpool"," longvie"," orbis"," domec",
        " escorial"," drean"," philips"
    };
    public static final String[] KW_COCINA_VETO = {
        " pizza "," de barro"," juguete"," infantil"," nina"," juego "," utensilio"," mueble"," repisa"," organizador",
        " pan "," panadera"," camping"," portatil de camping"," cartucho"
    };
    public static final String[] KW_CAMPANA_LIDER = {
        " campana "," campanas "," extractor de aire"," extractor para"," extractor "," extractores "," purificador de cocina",
        " purificador de aire"," purificador "," campanas y extractores"
    };
    public static final String[] KW_CAMPANA_VETO = {
        " extractor de jugo"," jugo"," agua "," navidad"," de vidrio decorativa"," maleza"," yuyo"," clavos"," tornillo"
    };
    public static final String[] KW_ASPIRADORA_LIDER = {
        " aspiradora "," aspiradoras "," asp "," lavadora de alfombra"," bolsas para aspiradora"," bolsa para aspiradora",
        " bolsa de aspiradora"," bolsas de aspiradora"," filtro para aspiradora"," filtro de aspiradora"," robot aspirador"
    };
    public static final String[] KW_PEQUENO_ELECTRO_LIDER = {
        " freidora "," freidoras "," tostadora "," tostadoras "," sandwichera "," sandwicheras "," pava electrica",
        " cafetera "," cafeteras "," licuadora "," licuadoras "," batidora "," batidoras "," procesadora "," procesadoras ",
        " molinillo "," picadora "," fondue "," deshidratador "," deshidratadora "," maquina de pan"," fabrica de pastas",
        " plancha de ropa"," plancha a vapor"," exprimidor electrico"," yogurtera "," pequenos electrodomesticos"
    };

    public static final String[] KW_PILETA_VETO = {
        " de cocina"," de apoyar"," de acero"," simple"," doble"," de embutir"," para cocina"," lavadero"," de lavar",
        " desagote"," desague"," sifon"," griferia"," canilla"," de patio"," zapato"," ojota"," malla"," lona de "
    };
    public static final String[] KW_PILETA_LIDER = {
        " pileta "," piletas "," piscina "," piscinas "," pisc "," jacuzzi "," spa "
    };
    public static final String[] KW_QUIMICO_PILETA_LIDER = {
        " cloro "," alguicida "," alguicidas "," clarificador "," clarificadores "," reductor de ph"," elevador de ph",
        " regulador de ph"," reductor ph"," elevador ph"," regulador ph"," pastillas de cloro"," pastillas cloro",
        " pastilla de cloro"," pastillas multiaccion"," pastillas triple"," pastillas para pileta",
        " pastillas de 200"," pastillas 200"," floculante "," clorador "," limpieza y mantenimiento"
    };
    public static final String[] KW_ACCESORIO_PILETA_LIDER = {
        " sacahojas "," saca hojas"," limpiafondo "," limpiafondos "," limpia fondo"," pertiga "," pertigas ",
        " cartucho filtro"," cartucho de filtro"," cubre pileta"," cubre piscina"," accesorios para piletas"," filtros y bombas para piletas"," boya "," boyas "," expendedor "
    };
    public static final String[] KW_ACCESORIO_PILETA_CALIFICADOR = {
        " pileta"," piscina"," pelopincho"," filtrante"
    };
    public static final String[] KW_ACCESORIO_PILETA_EN_CONTEXTO_LIDER = {
        " bomba "," filtro "," cartucho "," manguera "," cepillo "," escobilla "," escobillon "," kit "," red "," cubre ",
        " mango "," inflador "," terminal "," dispensador "," manga "," tope "," equipo "," set "
    };
    public static final String[] KW_PILETA_CONTEXTO = { " para pileta"," para piletas"," para piscina"," bajo pileta"," barredor " };
    public static final String[] KW_PILETA_CONTEXTO_VETO = {
        " ojota"," gorra"," gorro"," anteojo"," antiparra"," toalla"," bolso"," sandalia"," zapatilla"," campera"," bata "," short"," bikini"," malla "," de cocina"
    };
    public static final String[] KW_INFLABLE_LIDER = {
        " flotador "," flotadores "," colchoneta inflable"," colchoneta para pileta"," colchoneta de agua"," colchoneta pileta",
        " deslizador "," alitas "," inflables "," inflable "," bombitas "," bote inflable"," salvavidas "," aro inflable"
    };
    public static final String[] KW_INFLABLE_VETO = { " arena friends"," arena "," bicicleta"," ruta"," mtb"," cisterna"," inodoro"," deposito"," tanque" };

    public static final String[] KW_PARRILLA_LIDER = {
        " parrilla "," parrillas "," parilla "," kamado "," fogonero "," fogoneros "," fogon "," fogones "," brasero "," braseros ",
        " asador "," parrillas a carbon"," fogones y braseros"," disco de arado"," disco arado"," disco de parrilla"
    };
    public static final String[] KW_PARRILLA_VETO = {
        " auto"," rack"," techo"," portaequipaje"," moto"," bici"," horno de"," para horno"
    };
    public static final String[] KW_ACCESORIO_PARRILLA_LIDER = {
        " set parrillero"," set de parrilla"," set para parrilla"," set para asado"," set de asado"," set asado"," fuente parrillera"," provoletera ",
        " provoletero "," atizador "," atizadores "," pinche "," pinches "," plancheta "," planchetas "," accesorios para parrillas",
        " tablas y cuchillas"," tabla para asado"," tabla de asado"," tabla asado"," tabla parrillera"," tabla de picada",
        " tabla para picada"," tabla picada"," garras "," kit parrillero"," cepillo para parrilla"," cepillo de parrilla",
        " cepillo limpia parrilla"," pala para parrilla"," pala parrillera"," rejilla para parrilla"," rejilla parrilla",
        " funda para parrilla"," funda parrilla"," guante parrillero"," guantes parrilleros"," guante para parrilla",
        " guantes para parrilla"," cuchillo parrillero"," cuchillo asador"," cuchillo de asado"," cuchillo para asado",
        " set cuchillo"," set cuchillos"," set de cuchillo"," set de cuchillos"," tenedor parrillero"," tenedor asador",
        " tenedor para asado"," pinza parrillera"," pinza para asado"," pinza parrilla"," pinza de asado"," espatula parrillera",
        " espatula para parrilla"," espatula para asado"," plancha para parrilla"," plancha parrillera"," plancha de hierro"
    };
    public static final String[] KW_ACCESORIO_PARRILLA_EN_CONTEXTO_LIDER = {
        " cepillo "," pala "," rejilla "," funda "," guante "," guantes "," cuchillo "," cuchillos "," tenedor "," pinza ",
        " espatula "," plancha "," tabla "," tablas "," cobertor "
    };
    public static final String[] KW_ACCESORIO_PARRILLA_CONTEXTO = {
        " parrilla"," parrillero"," parrillera"," asado"," asador"," carne"," grill"," picada"," parrilleros"," bbq"," cenizas"," asar"," weber"," alta temperatura"
    };
    public static final String[] KW_CARBON_LIDER = {
        " carbon "," bolsa de carbon"," bolsa carbon"," astillas "," lena "," pastillas de encendido"," pastillas encendido",
        " pastillas para encender"," encendedor de carbon"," iniciador de fuego"," briquetas "
    };
    public static final String[] KW_CARBON_VETO = {
        " pasta "," mascarilla"," jabon"," filtro"," fibra de"," zapatilla"," cuadro"," cuadros"," mtb"," ruta"
    };

    public static final String[] KW_CARPA_LIDER = { " carpa "," carpas "," carpa4p "," iglu " };
    public static final String[] KW_CARPA_VETO = { " juguete"," infantil"," ninos"," gimnasio"," indio"," castillo"," princesa"," gamer"," bebe"," techo de" };
    public static final String[] KW_BOLSA_DORMIR_LIDER = {
        " bolsa de dormir"," bolsas de dormir"," bolsa dormir"," bolsa/saco"," saco de dormir"," sacos de dormir"," aislante de camping",
        " aislante camping"," aislante para camping"," aislante termico para carpa"," aislante individual"," aislante aluminizado",
        " aislante plegable"," aislante enrollable"," aislante para bolsa"," bolsas de dormir y aislantes"
    };
    public static final String[] KW_CONSERVADORA_LIDER = {
        " conservadora "," conservadoras "," bolsa cooler"," bolso cooler"," cooler para botella"," lunchera cooler"," bolsa termica",
        " gel refrigerante"," gel frio"," gel para conservadora"," conservadoras y luncheras"
    };
    public static final String[] KW_TERMO_BOTELLA_LIDER = {
        " termo "," termos "," botella "," botellas "," vaso termico"," vaso termo"," vaso outdoor"," vaso de acero"," vaso acero"," vaso de plastico",
        " vasos termicos"," mug "," vasos y botellas"," bidon termico"," bidon polipropileno"," bidon plastico"
    };
    public static final String[] KW_TERMO_BOTELLA_VETO = {
        " tinta"," toner"," shaker"," proteina"," whey"," vino"," vinos"," ink"," impresora"," suplemento"," creatina"," pintura"," aceite"," agua mineral"," cerveza",
        " tanque"," epson"," brother"," hp "," lacteo"," deportiva"," termica deportiva"," reebok"," de combustible"," nafta"," combustible"
    };
    /** A cup is Bazar unless a thermal-tumbler signal comes with it (Termo y Botella). */
    public static final String[] KW_VASO_TERMICO_LIDER = { " vaso "," vasos "," tumbler "," quencher " };
    public static final String[] KW_VASO_TERMICO_CALIFICADOR = {
        " tumbler"," quencher"," termic"," stanley"," camelbak"," hydrate"," contigo"," yeti"," flip straw"
    };
    public static final String[] KW_CAMPING_LIDER = {
        " anafe camping"," anafe portatil"," anafe a gas portatil"," linterna "," linternas "," farol de camping"," farol camping",
        " mosqueton "," mosquetones "," set mosquetones"," multiherramienta "," garrafa camping"," garrafa de camping"," utensilio de camping",
        " hamaca paraguaya"," cartucho de gas"," silla camping"," articulos para camping"," cuchillo camping",
        " utensilio "," manta picnic"," anafe a cartucho"," cartucho gas"," soplete cartucho"," pala plegable"," canasta picnic"," canasta de picnic"," kit de camping"," set de camping"
    };
    public static final String[] KW_CAMPING_CALIFICADOR = {
        " camping"," outdoor"," acampar"," supervivencia"," trekking"," trek "
    };
    public static final String[] KW_CAMPING_EN_CONTEXTO_LIDER = {
        " anafe "," farol "," lampara "," garrafa "," manta "," soga "," pala "," toalla "," hamaca "
    };
    public static final String[] KW_CAMPING_VETO = { " perro"," gato"," mascota"," bano "," bicicleta"," bici"," led para bici"," ciclismo"," pesca"," hogar"," nafta"};

    public static final String[] KW_PLANTA_LIDER = {
        " planta "," plantas "," plantin "," plantines "," ramo "," ramos "," arbol "," arbusto "," cactus "," suculenta "
    };
    public static final String[] KW_PLANTA_VETO = { " artificial"," de pie para"," del pie"," plantilla"," navidad"," navideno"," navidena"," sintetica"," decorativa",
        " alta presion"," tratamiento"," electrica"};
    public static final String[] KW_SEMILLA_LIDER = { " semilla "," semillas "," bulbo "," bulbos " };
    public static final String[] KW_SEMILLA_VETO = { " chia"," lino "," sesamo"," girasol pelada"," calabaza pelada"," alimento"," cereal"," barra de"," quinoa"," mix de"," proteina"," de lino"," organic"};
    public static final String[] KW_TIERRA_LIDER = {
        " sustrato "," sustratos "," tierra "," tierras "," compost "," humus "," perlita "," vermiculita "," fertilizante "," fertilizantes ",
        " abono "," sulfato de"," pometina "," acondicionador de suelo"," acondicionador para suelo"," turba "," mantillo "," insecticida ",
        " fungicida "," herbicida "," hormiguicida "," plantas, tierra y semillas"
    };
    public static final String[] KW_TIERRA_VETO = { " diatomea"," del fuego"," santa"," pantalon"," remera"," media ","rara"," batalla"," juego"," libro"," sulfato de magnesio"};
    public static final String[] KW_MACETA_LIDER = {
        " maceta "," macetas "," macetero "," maceteros "," porta maceta"," portamaceta",
        " base para maceta"," soporte para maceta"," soporte de maceta"
    };
    public static final String[] KW_MACETA_VETO = { " pantalon"," overol"," pack jean"," jean"," babucha"," remera"," cargo"," mom"," denim"," tiro "};
    public static final String[] KW_RIEGO_LIDER = {
        " manguera "," mangueras "," aspersor "," aspersores "," regadera "," regaderas "," regador "," rociador "," rociadores ",
        " pistola de riego"," pistola riego"," pistola para riego"," pistola regadora"," pistola de agua para riego"," pistola para manguera",
        " set pistola"," set manguera"," set de manguera"," set de riego"," set accesorios riego"," set 2 aspersores"," set regador"," set lanza"," adaptador bronce"," adaptador plastico"," adaptador hembra"," adaptador macho"," adaptador de riego"," adaptador para canilla"," acople rapido"," acople para manguera"," acople manguera"," acople "," enrollador ",
        " riego "," conector para manguera"," conector rapido"," conector manguera"," union para manguera"," union manguera"," gotero ",
        " goteros "," kit riego"," kit de riego"," programador de riego"," timer de riego"
    };
    public static final String[] KW_RIEGO_VETO = {
        " hidrolavadora"," lavarropas"," gas "," de gas"," jeep"," ducha"," inodoro"," aire comprimido"," agua fria"," inflador"," aire "," compresor",
        " pileta"," piscina"," radiador"," auto "," moto "," usb"," hdmi"," vga"," rj45"," displayport"," ethernet"," audio"," notebook"," celular"
    };
    public static final String[] KW_PULVERIZADOR_LIDER = { " pulverizador "," pulverizadores "," fumigador "," mochila pulverizadora"," mochila fumigadora" };
    public static final String[] KW_CERCO_LIDER = {
        " media sombra"," medias sombras"," cerco "," cercos "," rafia "," limitador de cesped"," limitador para cesped"," limitador "," malla de sombra"," malla sombra",
        " malla cerco"," malla plastica"," malla de jardin"," malla para jardin"," malla para cerco"," polietileno negro"," polietileno cristal",
        " polietileno "," broche para media"," broches para media"," broches "," tela antimaleza"," tela anti maleza"," tela para sombra"," tela media"," tela "," cobertor para plantas"," cobertor invernal"," cobertor de plantas",
        " cobertor "," cercos, mallas y medias sombras"," grampas para media"," grampas para malla"," grampas "
    };
    public static final String[] KW_CERCO_CALIFICADOR = {
        " media sombra"," cerco"," jardin"," cesped"," antimaleza"," anti maleza"," agricola"," plantas"," sombra"," invernadero"," limitador",
        " rafia "," polietileno"," invernal"," plantas"," huerta"
    };
    public static final String[] KW_DECO_JARDIN_LIDER = {
        " fuente decorativa"," fuente de agua"," fuente de jardin"," fuente piedras"," fuente con"," fuente led"," fuente solar "," fuente de pared"," fuente de piso"," fuente poliresina",
        " figura decorativa"," figura de jardin"," figura arcilla"," figura "," figuras "," antorcha "," antorchas "," piedra decorativa"," piedras decorativas",
        " bolson de piedra"," bolson piedra"," deco jardin"," sahumerio "," sahumerios "," estatua "," enano de jardin"," cesped decorativo"," pasto decorativo"," cerco decorativo"
    };
    public static final String[] KW_DECO_JARDIN_VETO = { " de poder"," atx"," gamer"," de alimentacion"," watts"," pc "," fuente 12"," fuente de luz"," de accion"," accion "," coleccion"," funko"," batman"," anime"};
    public static final String[] KW_MAQUINA_JARDIN_LIDER = {
        " bordeadora "," bordeadoras "," desmalezadora "," desmalezadoras "," desmalezador "," motoguadana "," ahoyador "," ahoyadora "," orilladora "," orilladoras ",
        " desbrozadora "," desbrozadoras "," motosierra "," motosierras "," electrosierra "," electrosierras "," podadora "," podadoras "," cortacerco "," cortacercos ",
        " cortacesped "," cortadora de cesped"," cortadoras de cesped"," cortadora cesped"," cortadora a nafta"," cortadora electrica de cesped",
        " cortadora de pasto"," cortadora bateria"," cortadora 18v"," cortadora inalambrica"," tractor cortacesped"," tractor de jardin"," robot cortacesped"," robot cortador"," soplador "," sopladores ",
        " sopladora "," soplaspirador "," aspirador de hojas"," tanza "," tanzas "," carretel para bordeadora"," carretel de bordeadora"," multiherramienta ",
        " cuchilla para cortadora"," cuchilla de cortadora"," cuchilla de bordeadora"," cuchilla para bordeadora"," rueda para cortadora"," rueda de cortadora"," rueda cortadora"
    };
    public static final String[] KW_MAQUINA_JARDIN_VETO = {
        " de cocina"," cocina"," juguete"," aspirador de polvo"," limpiafondo"," de piso"," robot aspirador"," aspiradora"," transformer"," educativo"," lego",
        " de ceramica"," de pizza"," cocinar"," de carne"," parrillero"," patin"," bicicleta"," de pesca"," de bicicleta"," bici"," trek"," banda"
    };
    public static final String[] KW_HERRAMIENTA_JARDIN_LIDER = {
        " rastrillo "," rastrillos "," azada "," azadas "," azadon "," barrehojas "," barre hojas"," hacha "," hachas "," picota "," picotas "," carretilla "," carretillas ",
        " tijeron "," tijerones "," tijera de podar"," tijera podar"," tijera para podar"," tijera de poda"," tijera poda"," tijera de jardin"," tijera jardin"," tijera para cesped"," tijera de cesped",
        " tijera cortacesped"," serrucho de poda"," serrucho poda"," serrucho para poda"," serrucho podador"," pala de jardin"," pala jardin"," pala punta"," pala ancha"," pala corazon"," pala cuadrada"," pala de punta",
        " pala "," palas "," palita "," plantador "," escardillo "," transplantador "," set de jardineria"," set jardineria"," set herramientas de jardin"," set herramientas jardin",
        " set de herramientas de jardin"," set de herramientas para jardin"," kit jardineria"," kit de jardineria"," extractor de maleza"," extractor de yuyo"," extractor de malezas",
        " guantes de jardin"," guante de jardin"," guantes de jardineria"," guante de jardineria"," guantes para jardin"," guante para jardin"," herramientas manuales de jardin"," desmalezador manual"
    };
    public static final String[] KW_HERRAMIENTA_JARDIN_VETO = {
        " playa"," juguete"," mascota"," perro"," gato"," de nieve"," pizza"," parrilla"," pala de padel"," padel"," tenis"," beach"," ping"," tortas"," cocina"," horno"," ninos",
        " bebe"," carne"," parrillera"," bicicleta"," carbon"," frisbee"," zapatilla"," stihl de nieve"," bebes"," infantil"
    };
    public static final String[] KW_GAZEBO_LIDER = {
        " gazebo "," gazebos "," pergola "," pergolas "," sombrilla "," sombrillas "," quitasol "," quitasoles "," base para sombrilla"," base de sombrilla",
        " base sombrilla"," estaca sombrilla"," estaca para sombrilla"," gazebos y pergolas"," sombrillas y reposeras"
    };
    public static final String[] KW_REPOSERA_LIDER = {
        " reposera "," reposeras "," hamaca "," hamacas "," columpio "," columpios "," set reposeras"," set de reposeras"," silla playera"
    };
    public static final String[] KW_REPOSERA_VETO = { " perro"," gato"," mascota"," de camping"," paraguaya camping"," bebe"," para bebe"," plaza"," infantil"," ninos"," discovery" };
    public static final String[] KW_MUEBLE_EXTERIOR_LIDER = {
        " set living"," set de living"," juego de living"," juego living"," set conversacion"," set de conversacion"," set bistro"," set de bistro"," set bar "," set de bar ",
        " juego de jardin"," set de jardin"," set ratan"," set rattan"," banqueta "," banquetas "," banco de jardin"," banco de plaza"," banco jardin"," banco "," bancos "," set comedor"," set de comedor"," juego de comedor exterior",
        " muebles de exterior"," cobertor para mesa"," funda para living"," frapera "," set de muebles"," sillas, sillones y bancos"," set sillas"
    };
    public static final String[] KW_MUEBLE_EXTERIOR_VETO = {
        " de trabajo"," de carpintero"," de sangre"," ejercicio"," pesas"," gimnasio"," plano"," musculacion"," abdominales"," press"," sentadilla"," multifuncional"," gym"," camiseta"," de datos"," de energia",
        " carga"," de baterias"," prueba"," power"," inclinable"," ajustable"," banco de pesas"," de pesas"," fitness"," de banquetas"," mecanico"," tornillo"," herramienta"," morsa"," whisky"," vino"," coctel"," cocteleria"
    };
    public static final String[] KW_DEPOSITO_JARDIN_LIDER = {
        " gabinete polipropileno"," gabinete rattan"," gabinete ratan"," gabinete resina"," gabinete plastico"," bodega "," bodegas "," deposito de jardin"," deposito para jardin"," deposito jardin"," deposito exterior"," deposito "," depositos "," invernadero "," invernaderos "," depositos de jardin"
    };
    public static final String[] KW_DEPOSITO_JARDIN_VETO = {
        " inodoro"," agua "," mochila"," dual"," bano"," sanitario"," descarga"," ferrum"," externo"," tanque"," bari"," pared"," ideal"," colgar"," para mochila"," cisterna"," loza"
    };

    public static final String[] KW_FUNDA_LIDER = { " funda "," fundas " };
    public static final String[] KW_FUNDA_CONTEXTO = {
        " almohadon"," silla"," sillon"," respaldo"," sofa"," cama"," cuerpos"," pana "," tusor "
    };
    public static final String[] KW_RESPALDO_LIDER = { " respaldo " };
    public static final String[] KW_RESPALDO_CONTEXTO = { " cama "," modular "," cabecera " };
    public static final String[] KW_ALMOHADON_LIDER = { " almohadon "," almohadones "," pillow "," cojin "," cojines " };
    public static final String[] KW_MANTA_LIDER = { " manta "," mantas " };
    public static final String[] KW_MANTEL_LIDER = {
        " mantel "," manteles "," servilleta "," servilletas "
    };
    public static final String[] KW_ALFOMBRA_LIDER = {
        " alfombra "," alfombras "," protector bajo escritorio "," felpudo "," felpudos "," limpiapies "
    };
    public static final String[] KW_ALFOMBRA_VETO = {
        " gamer "," floor mat "," para silla "," simulador"," cockpit"
    };
    public static final String[] KW_FUNDA_VETO = { " gamer " };

    public static final String[] KW_ILUMINACION = {
        "lampara"," lamp ","luminaria","paneles led","panel led","tira led","aro de luz"
    };

    public static final String[] KW_MAT_ESCRITORIO = {
        "mat de escritorio","desk mat","mat antifatiga","alfombrilla de escritorio",
        " mat board ","mousepad xl"
    };

    public static final String[] KW_ORGANIZACION = {
        "organizador","pasacable","pasacables","cubre cable","cubrecable",
        "portacables","porta cables","cable tray","pegboard","cajonera",
        "cajon ","bandeja ","soporte de cpu","soporte cpu"
    };

    public static final String[] KW_MONITOR = {
        "monitor ","pantalla pc","display pc","led gaming","monitor gaming",
        "monitor 4k","monitor curvo","monitor 144hz","monitor 27","monitor 24"
    };

    // " teclado " pelado FALTABA: 453 teclados vivían en `Otros` porque "Teclado Logitech K120 USB"
    // no matcheaba ninguna de las formas compuestas.
    public static final String[] KW_TECLADO = {
        " teclado ","teclados ","teclado mecanico","teclado gamer","keyboard",
        "mechanical keyboard","teclado rgb","teclado inalambrico","teclado bluetooth"
    };

    // Las formas COMPUESTAS no necesitan guard: nombran el periférico solas.
    public static final String[] KW_MOUSE = {
        "mouse gamer","mouse gaming","raton gamer","gaming mouse",
        "mouse inalambrico","mouse bluetooth","mouse rgb"
    };

    /**
     * " mouse " pelado FALTABA (302 filas en {@code Otros}), pero pelado también se lleva puesto un
     * ratón que no es un periférico. El bloque TECH corre antes que el de ropa, así que sin guard
     * esas tres se archivaban como periférico.
     */
    public static final String[] KW_MOUSE_GENERICO = { " mouse " };

    /** Lo que NUNCA es un mouse aunque diga mouse. Guard de {@link #KW_MOUSE_GENERICO}. */
    public static final String[] KW_MOUSE_VETO = {
        "mickey","minnie","disney","mouse de "
    };

    public static final String[] KW_AURICULAR = {
        "auricular","auriculares","headset","headphone","earphone",
        "earbud","earbuds","inalambrico bt","over-ear","in-ear","on-ear"
    };

    public static final String[] KW_WEBCAM = {
        "webcam","camara web","web cam","facecam"
    };

    public static final String[] KW_GPU = {
        "gpu","tarjeta de video","video card","graphics card",
        " rtx "," gtx "," rx ","radeon","geforce"," arc ",
        "placa de video","placa video"
    };

    public static final String[] KW_RAM = {
        " ram ","memoria ram"," dimm ","ddr4","ddr5","sodimm",
        "memoria ddr","modulo ram"
    };

    // Corre DESPUÉS de KW_COOLER: un cooler de CPU no es un CPU.
    public static final String[] KW_CPU = {
        "procesador"," cpu ","core i3","core i5","core i7","core i9","core ultra",
        " ryzen "," intel "," amd ",
        " i3 "," i5 "," i7 "," i9 ","threadripper"
    };

    public static final String[] KW_GABINETE = {
        "gabinete","case pc","tower pc","chasis pc","computer case",
        "gabinete gamer","gabinete atx","gabinete micro atx"
    };

    public static final String[] KW_PC = {
        "pc gamer","computadora de escritorio","desktop pc",
        "pc completa","equipo de escritorio","all in one pc"
    };

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

    // ══════════════════════════════════════════════════════════════════ SUPLEMENTOS / NUTRICIÓN
    // Subcategorías de suplemento — corren ANTES de KW_SUPLEMENTO en clasificar()
    // ══════════════════════════════════════════════════════════════════

    public static final String[] KW_CREATINA = {
        "creatina","creatine","monohidrato de creatina"
    };

    public static final String[] KW_PROTEINA_BARRA = {
        "barra proteica","protein bar","barra de proteina","barita proteica",
        "bar proteico","barrita proteica","barrita de proteina","barritas de proteina",
        "barras de proteina","barras proteina","barra de whey","crisp bar","layered bar"
    };

    public static final String[] KW_PROTEINA_PANCAKE = {
        "pancake","panqueque proteico","waffle mix","waffle proteico","waffle protein",
        "mezcla para pancake","mezcla para panqueque","mix de pancake"
    };

    public static final String[] KW_PROTEINA_SNACK = {
        "snack proteico","cookie proteica","galleta proteica","brownie proteico",
        "muffin proteico","torta de arroz proteica","snack fit",
        "alfajor proteico","protein cookie"
    };

    /**
     * Origen vegetal, Tier A — el token nombra la proteína, así que clasifica solo. Corre ANTES de
     * {@link #KW_PROTEINA_ISOLADA} y de {@link #KW_PROTEINA}: un aislado de arveja es las dos
     * cosas, y para quien compra manda el origen.
     */
    public static final String[] KW_PROTEINA_VEGETAL = {
        "proteina vegetal","proteina vegana","vegetal protein","plant protein",
        "pea protein","proteina de arveja","proteina de soja","proteina de arroz"
    };

    /**
     * Sólo clasifican cuando co-ocurre una cabeza de proteína
     * ({@code CategoryClassifier.esContextoProteina}).
     */
    public static final String[] KW_PROTEINA_VEGETAL_RECLAMO = {
        " vegana "," vegano "," vegan "," veggie ","plant based",
        "de arveja","de soja"
    };

    /**
     * {@code "itholate"} no es un typo nuestro: RAW publica así sus cinco SKUs ("RAW Proteína
     * Itholate 2lb"), y sin el token quedaban en el bucket genérico.
     */
    public static final String[] KW_PROTEINA_ISOLADA = {
        "isolate","isolada","aislada","itholate","iso whey","whey iso","isoprot"
    };

    /**
     * Sólo clasifica con una cabeza de proteína presente
     * ({@code CategoryClassifier.esContextoProteina}).
     */
    public static final String[] KW_PROTEINA_ISOLADA_PROCESO = {
        "hidroliz","hydroliz","hydro whey"
    };

    /**
     * Líneas de producto cuyo NOMBRE significa "proteína aislada" sin decir "isolate". Mismo guard
     * que {@link #KW_PROTEINA_ISOLADA_PROCESO}: sólo clasifican con una cabeza de proteína
     * presente.
     */
    public static final String[] KW_PROTEINA_ISOLADA_MARCA = {
        "isopure","iso protein","iso gold"
    };

    /**
     * {@code " protein "} va padeado de los DOS lados y no es prolijidad: sin el espacio de
     * adelante se metía adentro de "MYPROTEIN" y "The Protein Lab", y archivaba como proteína un
     * shaker de 600 ml, un omega 3 y un zinc — 17 filas medidas sobre el catálogo vivo
     * (2026-09-02).
     */
    public static final String[] KW_PROTEINA = {
        " proteina "," protein ","whey","isolate","caseina","casein",
        "proteina isolada","proteina hidrolizada"
    };

    /**
     * Marcas cuyo NOMBRE contiene una palabra de proteína sin que el producto lo sea. Se borran del
     * texto antes de clasificar nutrición, así que lo que decide es lo que el título dice del
     * producto, no cómo se llama quien lo vende.
     */
    public static final String[] KW_MARCA_CON_PROTEINA_EN_EL_NOMBRE = {
        "natural whey","myprotein","my protein","the protein lab"
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
        "vitamina ","vitamin ","vitaminas ","vitamins ",
        "multivitaminico","omega 3","omega3","omega-3"
    };

    public static final String[] KW_QUEMADORES = {
        "quemador de grasa","fat burner","termogenico","l-carnitina","l carnitina",
        "carnitina"," cla ","lipo6","lipo 6","lipo-6"
    };

    public static final String[] KW_GAINERS = {
        "mass gainer","hipercalorico","gainer","true-mass","true mass"
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
        "quemador","fat burner","termogenico","l-carnitina","carnitina"," cla ",
        "suplemento","supplement","nutri","proteico","proteica"
    };

    public static final String[] KW_COMIDA = {
        "yerba","cafe"," te verde","infusion","cereal","granola",
        "frutos secos","almendra"," mani ","cacao","chocolate proteico",
        "avena","harina de avena","pasta","arroz",
        "salsa ","ketchup","mostaza","condimento","aderezo","mayonesa","vinagre",
        "maple","jarabe de arce","sirope","topping proteico","topping fit",
        "pudding","chia ","semillas","fruta","miel","mermelada","dulce de",
        "snack saludable","galletita","galleta","tostada","pan proteico"
    };

    public static final String[] KW_PERFUME = {
        "perfume"," colonia ","eau de toilette","eau de parfum","fragancia",
        "desodorante ","antitranspirante","body splash","body mist"
    };

    // `KW_TECLADO` no tenía la palabra "teclado" pelada — sólo "teclado gamer"/"teclado mecanico" —
    // así que un "Teclado Logitech K120" no matcheaba nada.

    /**
     * La diferencia no es estilística: "Fuente Segotep 500W ATX Cables Largos 23a Cooler 120mm" y
     * "Cable Splitter PWM Mallado para Fan Cooler" contienen los dos la palabra cable, y sólo el
     * segundo ES un cable.
     */
    public static final String[] KW_CABLE_LIDER = {
        " cable ", " cables ", " adaptador ", " adaptadores ", " ficha ",
        " patchcord ", " conversor ", " prolongador ", " extension usb "
    };

    public static final String[] KW_CABLE_VETO = { " control de bloqueo"," riego"," pulgada"," tanque"," taladro"," rodillo"," espatula"," magnetico"," angular"," copa"," fijacion"," velador"," perilla"," heavy duty"," union doble" };
    public static final String[] KW_GABINETE_VETO = { " bano"," cocina"," colgante"," herramienta"," mueble"," zapatero"," cm "," embutir"," puertas"," medidor"," modulos"," ip65"," monofasico"," trifasico"," din "," gas "," entrada gabinete" };
    public static final String[] KW_FUENTE_VETO = { " horno"," vidrio"," loza"," ensalad"," servir"," madera"," tira" };

    public static final String[] KW_GABINETE_ACCESORIO_LIDER = {
        " bracket ", " filtro ", " soporte ", " kit "
    };

    /** Líder ⇒ no Notebook: "Cargador Pared 140W … Macbook Notebook" nombra la notebook que alimenta. */
    public static final String[] KW_CARGADOR_LIDER = { " cargador "," cargadores " };

    /** Líder ⇒ accesorio, sin necesidad de que el título nombre su destino. */
    public static final String[] KW_ACCESORIO_LIDER = { " bracket ", " controladora "," videoportero "," caja fuerte "," reja protectora "," gabinete protector "," base eleva "," base para notebook " };

    /** Líder ⇒ Almacenamiento, antes de {@link #KW_COOLER}: */
    public static final String[] KW_ALMACENAMIENTO_LIDER = { " hd ", " ssd ", " disco " };

    /** Líder ⇒ Auricular, antes de {@link #KW_COOLER}: */
    public static final String[] KW_AURICULAR_LIDER = { " auricular ", " auriculares " };

    /** Líder ⇒ Joystick, antes de {@link #KW_COOLER}: */
    public static final String[] KW_JOYSTICK_LIDER = { " joystick " };

    public static final String[] KW_SERVICIO_LIDER = { " service ", " servicio ", " armado " };
    public static final String[] KW_FUENTE_LIDER = { " fuente " };
    public static final String[] KW_PC_LIDER = { " pc " };

    /**
     * Líder ⇒ Mini PC, antes de {@link #KW_PC_LIDER} y del bloque CPU/Monitor. "nuc"/"brix"/ "cubi"
     * cubren los barebones que no se anuncian como "mini pc".
     */
    public static final String[] KW_MINIPC_LIDER = { " mini pc ", " minipc ", " nuc ", " brix ", " cubi " };

    /**
     * Líder ⇒ NO es hardware: salta el bloque TECH entero. Medido sobre la base (2026-10-27): 27
     * filas activas con una prenda adelante caían en GPU ("Gtx" = Gore-Tex, "Curry Arc"), Notebook
     * ("Portanotebook"), Motherboard ("Canguro Mother"), CPU ("Intel 30k") o Cooler.
     */
    public static final String[] KW_PRENDA_LIDER = {
        " zapatilla", " remera ", " mochila ", " sweater ", " campera ", " chaqueta",
        " pelota", " lonchera "
    };

    public static final String[] KW_CPU_LIDER = {
        " procesador ", " microprocesador ", " micro amd ", " micro intel "
    };

    /**
     * Intel XMP 3.0 / AMD EXPO" tienen " amd "/" intel " (el perfil de overclock, no la marca de un
     * procesador) y caían en CPU vía {@code KW_CPU} — 35 filas medidas.
     */
    public static final String[] KW_RAM_LIDER = { " memoria " };

    /**
     * "red" es un color en inglés y el nombre de un switch mecánico de teclado — "Teclado Mecánico
     * Raptor Fireclaw M87 Red Red Switch" tiene las dos palabras y no es un router.
     */
    public static final String[] KW_RED = {
        "router","modem","repetidor","access point","placa de red",
        "extensor de rango","adaptador wifi","adaptador usb wifi",
        "adaptador de red","adaptador bluetooth","antena wifi","powerline",
        "placa wifi"
    };

    /** "switch" sólo es de red cuando hay señal de red. */
    public static final String[] KW_RED_SWITCH = { " switch " };

    /** Guard de {@link #KW_RED_SWITCH}: lo que un switch de red dice y un teclado no. */
    public static final String[] KW_RED_CONTEXTO = {
        "gigabit","puerto","puertos","poe","ethernet","10/100","rj45","rj-45","omada"
    };

    public static final String[] KW_MOTHERBOARD = {
        "motherboard"," mother ","placa madre","mainboard"," mobo "
    };

    public static final String[] KW_FUENTE = {
        "fuente atx","fuente de alimentacion"," fuente ","fuentes ",
        " psu ","fuente modular","fuente 80 plus"
    };

    /**
     * Corre DESPUÉS de Gabinete y Fuente y ANTES de CPU, y las tres posiciones están medidas, no
     * elegidas:
     */
    public static final String[] KW_COOLER = {
        "cooler","watercooling","water cooling","refrigeracion liquida",
        "disipador"," aio ","fan cooler","ventilador de gabinete",
        "ventilador para gabinete","grasa termica","pasta termica",
        "thermal pad","pad termico","thermal paste"
    };

    public static final String[] KW_ALMACENAMIENTO = {
        " ssd "," hdd ","disco solido","disco rigido","disco duro",
        " nvme ","pendrive","memoria microsd","microsd","micro sd",
        "tarjeta de memoria"," m.2 ","disco externo","carry disk"
    };

    public static final String[] KW_IMPRESION = {
        "impresora","impresoras"," toner","cartucho","tinta alternativa",
        "botella tinta","botella de tinta","multifuncion laser","sistema continuo"
    };

    public static final String[] KW_UPS = {
        " ups ","estabilizador de tension"
    };

    public static final String[] KW_TABLET = {
        " tablet "
    };

    /** Corre ANTES de Mouse: */
    public static final String[] KW_MOUSEPAD = {
        "mouse pad","mousepad","pad mouse","alfombrilla mouse","mouse-pad"
    };

    public static final String[] KW_JOYSTICK = {
        "joystick","gamepad","racing wheel","driving force","pedalera",
        "palanca de cambios","control xbox","control ps4","control ps5"
    };

    /** Sólo cuenta con contexto de gaming/racing. */
    public static final String[] KW_VOLANTE_GENERICO = { " volante ", " volantes " };

    public static final String[] KW_VOLANTE_CONTEXTO = {
        "pedalera","racing","driving","simulador","logitech","thermaltake",
        "ps4","ps5","xbox","cockpit"
    };

    public static final String[] KW_MICROFONO = {
        "microfono","microfonos"," mic "," mic-"
    };

    /**
     * Cámaras de seguridad/IP. Corre ANTES de Monitor porque "Camara Wifi Ezviz BM1 2mp Baby Call
     * Monitor" —un producto real— termina en la palabra monitor.
     */
    public static final String[] KW_CAMARA = {
        "camara ip","camara wifi","camara de seguridad","camara seguridad",
        "camara exterior","camara interior","camara de vigilancia",
        "camara domo","camara bullet"
    };

    /**
     * Cámaras de acción/digitales/de auto: el dispositivo se nombra a sí mismo. {@link CamarasClassifier}
     * corre antes de {@code NonTextileGuard}, que si no vetaba "Colgante Magnético Para Cámara Insta360".
     */
    public static final String[] KW_CAMARA_DISPOSITIVO = {
        " camara de accion"," camaras de accion"," camara deportiva"," camara digital"," camara de fotos"," camara fotografica",
        " camara corporal"," camara para auto"," camara para moto"," dashcam"," dash cam"," body cam"
    };
    /** Marcas que sólo venden cámaras: un accesorio que las nombra es un accesorio de cámara. */
    public static final String[] KW_CAMARA_MARCA = { " sjcam"," insta360"," gopro "," go pro " };
    /** Una tarjeta de memoria con la marca de la cámara sigue siendo Almacenamiento. */
    public static final String[] KW_CAMARA_VETO_LIDER = { " memoria "," tarjeta "," micro sd " };

    public static final String[] KW_RELOJ = {
        "smartwatch","smart watch","reloj inteligente","smart band","smartband",
        " reloj ","relojes "
    };

    // "Paleta De Pádel adidas Adipower Ctrl Team 3.3" caía en `Zapatilla Entrenamiento` porque
    // "adipower" es un KW_TRAINING_MODELO y el fallback de calzado la agarraba primero.

    public static final String[] KW_PELOTA = {
        "pelota","pelotas","balon","balones"
    };

    public static final String[] KW_PALETA = {
        " paleta ","paletas ","paleta de padel","paleta de ping pong",
        "paleta de tenis de mesa"," pala de padel"
    };

    public static final String[] KW_BICICLETA_LIDER = {
        " bicicleta "," bici "," mountain bike "," triciclo "
    };
    public static final String[] KW_BICICLETA_VETO = {
        " soporte "," funda "," candado "," cubre "," kit "," guante"," remera "," zapatill "," casco "
    };
    public static final String[] KW_MONOPATIN_LIDER = {
        " monopatin "," monopatines "," scooter "," scooters "," triscooter "," waveboard "," skateboard ",
        " skate board "," skate penny "," skate retro "," patineta "," longboard "," hoverboard "
    };
    public static final String[] KW_MONOPATIN_VETO = {
        " zapatill"," gorra"," mochila"," bermuda"," bolso"," remera"," vespa"," nafta"," cc "," repuesto"
    };
    public static final String[] KW_REPUESTO_BICI_PROPIO_LIDER = {
        " cassette "," casete "," pinon "," pinones "," descarrilador "," cambio trasero "," cambio delantero ",
        " caja pedalera "," cajas pedaleras "," pastilla de freno "," pastillas de freno "," pastilla de cambio ",
        " pedalier "," biela "," bielas "," porta eje "," cinta de manubrio "," canasto para manubrio ",
        " porta caramanola "," caramanola "," ciclocomputador "," pin conector de cadena ",
        " inyector de sellante "," inyector sellante ",
        " funda de cambio "," cinta para tubelizar "," set de freno "
    };
    public static final String[] KW_REPUESTO_BICI_GENERICO_LIDER = {
        " cadena "," cable "," pedal "," pedales "," asiento "," sillin "," casco "," luz "," luces "," timbre ",
        " bocina "," inflador "," bomba "," horquilla "," stem "," manubrio "," maza "," eje "," freno ",
        " frenos "," liquido de freno "," disco de freno "," discos de freno "," puños "," punos "," grip ",
        " palanca "," shifter "," bateria "," rueda "," ruedas "," rotor "," herramienta "," multiherramienta ",
        " sellante "," estuche "," funda para asiento "," kit reparacion "," kit de reparacion "," set de herramientas "," plato "," platos "
    };
    public static final String[] KW_REPUESTO_BICI_CONTEXTO = {
        " bicicleta"," bici "," bicis "," mtb "," ciclismo"," ciclista"," shimano "," sram "," trek "," bontrager ",
        " rockshox "," suntour "," zefal "," m wave "," m-wave "," presta "," eslabones "," sigma ",
        " deore "," roadtube"," sportube"," tubeless"," dual lock "
    };
    public static final String[] KW_NEUMATICO_BICI_LIDER = { " cubierta "," cubiertas "," camara "," camaras " };
    public static final String[] KW_NEUMATICO_BICI_CONTEXTO = {
        " bicicleta"," bici "," mtb ","-622"," 700x"," presta "," roadtube"," sportube"," smartube"," tubeless",
        " scorpion "," p zero "," prowall "," cinturato "
    };
    public static final String[] KW_REPUESTO_BICI_VETO = {
        " impresora "," notebook "," gabinete "," auto "," moto "," camioneta "," pc "
    };

    public static final String[] KW_PESAS_LIDER = {
        " mancuerna "," mancuernas "," pesa rusa "," pesas rusas "," kettlebell "," kettlebells "," pesas "," pesa ",
        " barra z "," barra ez "," barra olimpica"," barra romana"," disco olimpico "," discos olimpicos "," disco martillado "," topes para disco "
    };
    public static final String[] KW_PESAS_VETO = {
        " digital "," balanza "," bascula "," cocina "," bebe "," bano "," joya "," persona "," corporal "
    };
    public static final String[] KW_DISCO_PESA_LIDER = { " disco "," discos " };
    public static final String[] KW_DISCO_PESA_CALIFICADOR = {
        " kg"," olimpic"," martillad"," bumper"," engomad"," fitness"," atletic"," libras"
    };
    public static final String[] KW_DISCO_PESA_VETO = {
        " corte "," flap "," diamant"," lija"," abras"," freno"," sierra"," ssd "," hdd "," duro "," solido "," rigido ",
        " sata "," nvme "," externo "," interno "," vinilo "," volador "," pulid"," desbaste "," amoladora "," tct "
    };
    public static final String[] KW_TOBILLERA_LIDER = { " tobillera "," tobilleras " };
    public static final String[] KW_TOBILLERA_PESO = { "kg "," kgs"," lastrad"," peso " };
    public static final String[] KW_BARRA_LIDER = { " barra "," barras " };
    public static final String[] KW_BARRA_PESAS_CALIFICADOR = {
        " olimpica"," ez "," romana"," atletic"," con rosca"," con tope"," mir fitness"," w con"
    };
    public static final String[] KW_BARRA_ACCESORIO_CALIFICADOR = {
        " ejercicio"," entrenamiento"," dominadas"," amurable"," proyec "
    };
    public static final String[] KW_ACCESORIO_FITNESS_LIDER = {
        " banda elastica "," bandas elasticas "," banda con manija "," set bandas elasticas "," set de bandas elasticas ",
        " soga de salto "," soga para saltar "," soga salto "," cuerda de salto "," cuerda para saltar ",
        " rueda abdominal "," rueda yoga "," rueda de yoga "," mat yoga "," mat de yoga "," mat pilates ",
        " mat de pilates "," set mat yoga "," bosu "," mini bosu "," ejercitador "," cono de entrenamiento ",
        " conos de entrenamiento "," valla plastica "," escalera de agilidad "," octagonos de coordinacion ",
        " rodillo de masaje "," foam roller "," rodillo foam "
    };
    public static final String[] KW_COLCHONETA_LIDER = { " colchoneta "," colchonetas " };
    public static final String[] KW_COLCHONETA_FITNESS_CALIFICADOR = {
        " gym "," fit "," fitness"," yoga"," pilates"," ejercicio"," proyec "," entrenamiento"
    };
    public static final String[] KW_COLCHONETA_FITNESS_VETO = {
        " camping"," inflable"," mascota"," pileta"," bestway"," cuna"," bebe"
    };
    public static final String[] KW_LADRILLO_YOGA_LIDER = { " ladrillo " };
    public static final String[] KW_LADRILLO_YOGA_CALIFICADOR = { " silicona "," yoga " };

    public static final String[] KW_EQUIPAMIENTO_DEPORTIVO_LIDER = {
        " arco de futbol"," arcos de futbol"," arco futbol"," arco de handball"," arco de hockey"," arco de pelota",
        " aro de basquet"," aro basquet"," aro de basket"," tablero de basquet"," tablero con aro",
        " red de basquet"," red basquet"," red de ping pong"," red ping pong"," red de ping-pong"," red de voley",
        " red de tenis"," red de futbol"," red de padel"," red de handball"," red de badminton"," red de hockey",
        " mesa de ping pong"," mesa ping pong"," mesa de pingpong"," mesa de tenis de mesa"," mesa de pool",
        " mesa pool"," tablero de dardos"," tablero dardos"," dardos "," set de dardos",
        " set ping pong"," set de ping pong"," set de ping-pong"," set tenis de mesa"," set de tenis de mesa",
        " set spikeball"," spikeball "," juego de tenis"," bodyboard"," tabla de surf"," tabla de bodyboard"
    };

    public static final String[] KW_EQUIPAMIENTO_DEPORTIVO_VETO = { " inflable" };

    /** Shin guards: the leading noun wins over "medias", a boot model or a boxer cut further on. */
    public static final String[] KW_CANILLERA_LIDER = { " canillera "," canilleras " };

    public static final String[] KW_KAYAK_LIDER = { " kayak "," kayaks "," canoa "," canoas " };
    public static final String[] KW_BOTE_LIDER = { " bote "," botes " };
    public static final String[] KW_BOTE_CALIFICADOR = { " remo"," pesca"," semirigido"," goma "," kayak" };

    public static final String[] KW_CAMA_ELASTICA_LIDER = { " cama elastica "," camas elasticas "," trampolin " };
    public static final String[] KW_JUGUETE_LIDER = {
        " juguete "," juguetes "," set de juguetes"," set juguetes"," muneco "," muneca "," munecos "," munecas ",
        " lego "," hot wheels "," rompecabezas "," puzzle "," puzles "," pistola de agua"," pistolas de agua",
        " lanza agua"," lanzador de agua"," balde castillo"," balde de playa"," balde playa"," baldes de playa",
        " set de playa"," set playa"," juego de playa"," set paletas playa"," set de paletas playa",
        " set de tennis playa"," set de tenis playa"," frisbee"," frisbi"," disco volador"," mini golf",
        " torre apilable"," juego de peces"," juego de lanzamiento"," peces de juguete"," casita juguete",
        " casita infantil"," guante excavador"," carpa infantil"," carpa carruaje"," carpa princesa"," camion volcador",
        " camion de juguete"
    };
    public static final String[] KW_JUGUETE_VETO = {
        " mascota"," para perro"," para gato"," p/perro"," p/gato"," perros "," gatos "," de perro"," de gato",
        " pet's "," pets "," gato "," perro "," catnip "," riego"," manguera"," presion"," hidrolavadora"," para jardin"," navid"," decorativ"," deco "
    };
    public static final String[] KW_JUGUETE_CON_MARCA_LIDER = {
        " camion "," carro de arrastre "," pala y rastrillo "," pala "," balde "," baldes "," set de jardin "
    };
    public static final String[] KW_JUGUETE_MARCA_CALIFICADOR = {
        " luni "," chichess "," juguete"," infantil"," bestway fun"
    };

    // Coleccionables. Bare " figura " is NOT a leader: in a home-center it is a garden ornament (Jardin).
    public static final String[] KW_JUEGO_DE_MESA_LIDER = {
        " juego de mesa"," juegos de mesa"," board game"," boardgame"," juego de cartas"," juegos de cartas",
        " juego de tablero"," juegos de tablero"," card game"
    };
    /** " tcg:" / " tcg," are separate entries because the space is the word boundary. */
    public static final String[] KW_JUEGO_DE_MESA_CALIFICADOR = {
        " tcg "," tcg:"," tcg,"," tcg-"," trading card"," card game"," booster pack"," booster box"," booster bundle",
        " booster display"," board game"," booster)"," elite trainer box"," etb)"," cartas coleccionables"
    };
    public static final String[] KW_PELUCHE_LIDER = {
        " peluche "," peluches "," peluchito"," plush "," plush,"," plushie"," plushies "
    };
    public static final String[] KW_PELUCHE_CALIFICADOR = { " plush "," plush,"," plushie"," plushies " };
    public static final String[] KW_PELUCHE_VETO = {
        " llavero"," keychain"," keyring"," dangler"," colgante"," medias "," remera"," funda"," taza "," mochila",
        " cartera"," gorra "," pijama"," estuche"," hanger"," clip "," pin ",
        " colchon"," inflable"," frazada"," manta "," sabana"," jibbitz"
    };
    public static final String[] KW_FIGURA_LIDER = {
        " funko "," pop! "," pop "," amiibo "," amiibos "," world of nintendo"," nendoroid"," figma ",
        " figure "," figures "," figurine"," figurines"," action figure"," gashapon"," q posket"," qposket"
    };
    /** Brand tokens that name a collectible figure wherever they appear in the title. */
    public static final String[] KW_FIGURA_CALIFICADOR = {
        " q posket"," qposket"," banpresto"," ichibansho"," nendoroid"," figma "," prize figure"," funko "," world of nintendo",
        " action figure"," figura articulada"," figuras articuladas"," vinyl figure"," figure model"," blind box"," soft vinyl",
        " gashapon",
        " iron studios"," art scale"," demi art"," minico "
    };
    public static final String[] KW_FIGURA_VETO = {
        " llavero"," keychain"," keyring"," dangler"," medias "," remera"," camiseta"," taza "," mug "," vaso"," mantel",
        " mochila"," gorra "," cuaderno"," sticker"," pin "," decorativ"," jardin"," arcilla"," ceramic"," yeso",
        " pop it"," popit"
    };

    // Gaming. The platform is the only signal a game title carries.
    public static final String[] KW_PLATAFORMA = {
        " nintendo switch"," switch 2"," - switch"," switch limited run"," nintendo ds"," 3ds"," 2ds"," wii"," (wii)",
        " ps vita"," psvita"," playstation"," ps1"," ps2"," ps3"," ps4"," ps5"," xbox"," gamecube"," game cube"," n64",
        " game boy"," gameboy"," nes "," snes"," sega genesis"," sega saturn"," sega mega drive"," dreamcast"," atari "
    };
    /** Anything that is the object of a platform mention rather than a game. */
    public static final String[] KW_ACCESORIO_GAMING = {
        " cable"," cargador"," charger"," adapter"," adaptador"," funda"," estuche"," case "," case)"," cases "," carrying",
        " protector"," grip"," soporte"," stand "," dock"," carcasa"," skin "," converter"," conversor"," camera"," camara",
        " headset"," auricular"," headphone"," volante"," wheel"," pedalera"," bateria"," battery"," bolso"," mochila",
        " llavero"," keychain"," hanger"," sticker"," pouch"," sleeve"," paladone"," mantel"," vaso"," taza "," mug ",
        " fanatec"," motor base"," cockpit"," pedal "," pedales"," sensor"," reader"," nfc "," action replay"," power stick"," memoria"," memory card"," microsd"," sd card",
        " teclado"," keyboard"," mouse "," monitor"," hdmi"," ssd "," disco "," hdd "," pantalla"," silla"," capturadora",
        " capture card"," parlante"," lampara"," lamp "," membership"," membresia"," gift card"," tarjeta"
    };
    /** Narrower: a console's own spec line legitimately says HDMI, microSD, SSD or screen size. */
    public static final String[] KW_ACCESORIO_CONSOLA = {
        " cable"," cargador"," charger"," adapter"," adaptador"," funda"," estuche"," case "," case)"," cases "," carrying",
        " protector"," grip"," soporte"," stand "," dock"," carcasa"," skin "," converter"," conversor"," camera"," camara",
        " headset"," auricular"," hanger"," sticker"," llavero"," keychain"," bolso"," mochila"," reader"," bateria"," battery"
    };
    public static final String[] KW_CONTROL_GAMING = {
        " joystick"," joypad"," joy-con"," joycon"," gamepad"," controller"," controllers"," controles "," horipad"," dualsense",
        " dualshock"," arcade stick"," mando "," mandos "," control n64"," control gamecube"," control game cube",
        " control snes"," control nes "," control wii"," control switch"," control ps"," control xbox"," control inalambrico",
        " control wireless"," control usb"," control arcade"," control bluetooth"," arcade control"," wireless"," inalambric"
    };
    public static final String[] KW_MARCA_GAMING = {
        " power a","(power a)"," hori"," 8bitdo"," retro-bit"," retrobit"," hyperkin"," nyxi"," retrolink"," retro link"," teknogame",
        " nintendo"," sony"," dualsense"," dualshock"
    };
    public static final String[] KW_CONSOLA_LIDER = {
        " steam deck"," rog ally"," legion go"," anbernic"," powkiddy"," miyoo"," retroid"," nes mini"," nes classic"," snes mini",
        " snes classic edition"," neo geo mini"," genesis mini"," sega genesis mini"," new 3ds"," new nintendo 3ds"," 2ds "," nintendo 2ds",
        " nintendo switch lite"," nintendo switch oled"," nintendo switch -"," switch lite"," switch oled"
    };
    /** Platform models that are consoles unless a controller token says otherwise. */
    public static final String[] KW_CONSOLA_MODELO_LIDER = {
        " xbox series x"," xbox series s"," xbox one "," playstation 5"," playstation 4"," ps5 slim"," ps5 digital"
    };
    /** " consola " alone is also a console TABLE, so it needs a gaming qualifier. */
    public static final String[] KW_CONSOLA_NOMBRE_LIDER = {
        " consola "," consolas "," console "," game console"," handheld "
    };
    public static final String[] KW_CONSOLA_CALIFICADOR = {
        " retro"," juegos"," gaming"," gamer"," portatil"," handheld"," arcade"," emulador"," ps4"," ps5"," playstation"," xbox",
        " nintendo"," switch"," sega"," atari"," game boy"," steam"
    };
    public static final String[] KW_CONSOLA_PALABRA = { " console"," consola" };
    /** A trailing bare " switch " is a game, unless the text is about a network switch. */
    public static final String[] KW_SWITCH_DE_RED = {
        " kvm"," gigabit"," poe "," puertos"," ports"," ethernet"," rj45"," administrable"," tp-link"," tplink"," tenda",
        " mercusys"," d-link"," red "
    };
    public static final String[] KW_VIDEOJUEGO_LIDER = { " videojuego "," videojuegos " };
    /** A case or bag for a console: not a game, and must not reach the garment families ("hard shell" -> Campera). */
    public static final String[] KW_ESTUCHE_GAMING = {
        " case "," case)"," cases "," carrying"," travel bag"," travel case"," hard shell"
    };

    // Libros. Manga titles have no leading noun; the signal is a publisher, a format word or a known series + volume.
    public static final String[] KW_LIBRO_LIDER = {
        " libro "," libros "," comic "," comics "," artbook "," novela ligera "," light novel"," tomo "
    };
    public static final String[] KW_LIBRO_CALIFICADOR = {
        " ivrea"," ovni press"," kemuri"," distrito manga"," planeta comic"," norma editorial"," milky way ediciones",
        " panini manga"," kanzenban"," databook"," data book"," artbook"," art book"," light novel"," novela ligera",
        " activity book"," sticker book"," companion guide"," tapa dura"," tapa blanda"," hardcover"," paperback",
        " paginas"," tomo "," vol. "," inio asano"," junji ito"," urasawa",
        " the legend of zelda 01:"," the legend of zelda 02:"," the legend of zelda 03:"," the legend of zelda 04:",
        " the legend of zelda 05:"," the legend of zelda 06:"," the legend of zelda 07:"," the legend of zelda 08:",
        " the legend of zelda 09:"," the legend of zelda 10:"
    };
    /** "Manga" alone is not a signal: in Spanish it is a sleeve or a hose. */
    public static final String[] KW_LIBRO_VETO = { " decorativ" };
    /** Series that only count as a book when the title ends in a volume number. */
    public static final String[] KW_MANGA_SERIE = {
        " one piece"," dandadan"," death note"," evangelion"," frieren"," fullmetal alchemist"," demon slayer"," kimetsu",
        " jujutsu"," naruto"," bleach"," berserk"," chainsaw man"," spy x family"," spyxfamily"," hunter x hunter",
        " yu yu hakusho"," dragon ball"," attack on titan"," my hero academia"," one punch"," sailor moon"," tokyo ghoul",
        " vagabond"," slam dunk"," uzumaki"," fire force"," haikyu"," blue lock"," captain tsubasa"," cardcaptor sakura",
        " card captor"," dr stone"," vinland saga"
    };
    /** A garment, furniture or household noun that leads the name wins over a book word further on. */
    public static final String[] KW_LIBRO_VETO_LIDER = {
        " remera"," camiseta"," buzo"," campera"," malla"," short "," medias"," mochila"," gorra"," zapatilla"," estanteria",
        " biblioteca"," mueble"," funda"," shampoo"," taza"," llavero"," keychain"," poster"," lampara"," vaso"," mousepad",
        " funko"," peluche"," plush"
    };

    public static final String[] TORSO_KEYWORDS_FLAT = concatKeywords(
        KW_PUFFER, KW_PILOTO, KW_SACO, KW_CHALECO, KW_CAMPERA, KW_SWEATER,
        KW_BUZO, KW_CASACA, KW_CHOMBA, KW_MUSCULOSA, KW_TOP_DEPORTIVO, KW_TOP, KW_CAMISA, KW_REMERA);
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
     * Swim shorts and trunks. They are Malla even though "short"/"boxer" are leading nouns of Short and
     * Calzoncillos, so IndumentariaClassifier checks them before both. Accent-stripped, plural included
     * ("trajes de bano" does not contain KW_MALLA's "traje de bano").
     */
    public static final String[] KW_MALLA_SHORT_BANO = {
        "swim short","swim trunk","short de bano","shorts de bano","boxer de bano","boxers de bano",
        "bermuda de bano","bermudas de bano","trajes de bano"
    };
    public static final String[] KW_MALLA_BANO = { " bano ","banos " };
    public static final String[] KW_MALLA_BANO_PRENDA = { "short","traje","slip","boxer","bermuda","calza","ropa" };
    public static final String[] KW_PROTEINA_ISOLADA_VETO = { " pinza "," electrodo" };
    public static final String[] KW_MALLA_VETO = {
        " mts"," cm "," mm "," m2"," lts"," kg "," grs"
    };
    public static final String[] KW_MOCHILA_VETO = {
        " inodoro"," deposito"," valvula"," fuelle"," obturador"," flapper"," conexion"," pastilla"," limpiador"," bloque"," tirador"," fijacion"
    };
    public static final String[] KW_SWEATER_VETO = { "romboidal"," alambre"," no tejido"," tejido plastico"," rombo"," bonete" };
    public static final String[] KW_PILOTO_VETO = {
        " brazo"," bide"," sensor"," caja "," funda "," fondo "," protector "," transf "," pintura "," membrana "," impregnante "," sellador "
    };
    public static final String[] KW_BUZO_VETO = { " cm "," manta " };
    public static final String[] KW_MEDIAS_VETO = { " cana"," sombra"," manija"," luna" };
    public static final String[] KW_REMERA_VETO = {
        " mm "," pvc"," rosca"," hierro "," ips"," bronce"," cromad"," npg"," polipropileno "," reduccion "
    };
    public static final String[] KW_CAMISA_VETO = { " tela oxford"," funda "," costado "," banquito " };
    public static final String[] KW_PANTALON_VETO = { "pantalonero" };
    public static final String[] KW_TRAJE_VETO = { " funda " };
    public static final String[] KW_SACO_VETO = { " inflable"," dormir"," hidroquimica" };
    public static final String[] KW_ACCESORIO_DEPORTIVO_VETO = { " combustible"," tapa de seguridad"," pico vertedor" };
    public static final String[] KW_MOCHILA_SANITARIA_CALIFICADOR = {
        " inodoro"," valvula"," fuelle"," obturador"," flapper"," conexion"," deposito"," brazo"," tirador"," fijacion"
    };
    public static final String[] KW_IMPRESION_VETO = {
        " monocomando"," filtro"," montaje"," adhesivo"," ceramic"," grifer"," valvula"," soplete"," gases"
    };
    public static final String[] KW_COMIDA_VETO = {
        " cm "," mm "," cc "," lts"," esmalte"," adhesivo"," dispensador"," cuchara"," roble "," desayunadora"," antigotera"," cementicia"," cama "
    };
    public static final String[] KW_SUPLEMENTO_VETO = { " paellero"," a gas"," de gas"," gas " };
    public static final String[] KW_RELOJ_DECO_LIDER = { " reloj " };
    public static final String[] KW_RELOJ_DECO_CALIFICADOR = {
        " cm "," pared"," decorativ"," madera"," ceramica"," despertador"
    };

    public static boolean anyMatch(String text, String[] keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }
}
