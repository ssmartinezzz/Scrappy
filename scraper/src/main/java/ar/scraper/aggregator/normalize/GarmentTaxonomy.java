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
        "air jordan","travis scott","off-white","fragment","union","chicago",
        "bred","shadow","university blue","royal",
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
        "botin","cleats","tachon","tachos","chimpun",
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
        "sandalia","sandal","diapositiva","slide"
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
        "tricot","cardigan","lana","merino","crochet"
    };

    public static final String[] KW_BUZO = {
        "buzo","hoodie","hoody","sweatshirt","sudadera",
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

    // Intencionalmente excluido de la detección de combos — los trajes siempre resuelven a "Traje",
    // ver ADR-4 / tasks.md 0.1 (confirmado por el product owner).
    public static final String[] KW_TRAJE = {
        "traje","suit ","terno","smoking","smocking"
    };

    public static final String[] KW_CHOMBA = {
        "chomba","polo shirt","polera","rugby shirt",
        "pique polo","lacoste polo","fred perry polo"
    };

    // Solo clasificar como Chomba vía este keyword si no hay un sustantivo de accesorio explícito
    // en el mismo título — mirrors el patrón KW_BORCEGO_MARCA/esContextoBorcego.
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

    public static final String[] KW_CALZONCILLO = {
        "calzoncillo","calzoncillos","boxer","short interior",
        "slip ","tanga","ropa interior masculina","brief","trunk ",
        "underwear","jockstrap","cueca"
    };

    public static final String[] KW_MALLA = {
        " malla ","mallas ","malla de bano","malla enteriza",
        "bikini","traje de bano"," bano ","banos ",
        "one piece","swimsuit","swimwear","beachwear","tankini",
        "ropa de playa","pileta"
    };

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

    public static final String[] KW_BOLSO = {
        "bolso","cartera","handbag","tote bag","clutch","minibag",
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
        "cinturon","cinturon","belt ","belts","cinto ","faja ",
        "correa pantalon","leather belt"
    };

    public static final String[] KW_GORRO = {
        "gorro","beanie","gorro de lana","gorro tejido","knit hat",
        "winter hat","gorro invierno","pompom hat","toque"
    };

    public static final String[] KW_GORRA = {
        "gorra"," cap "," hat ","sombrero","boina","snapback",
        "bucket hat","buff","balaclava","vincha","visera","dad hat"," bandana "
    };

    /**
     * Gym-brand names with no garment noun ("Morley Tank", "Boxy Fit Boxing", "CROPTOP 4REAL"),
     * evaluated after every other apparel and accessory block so a name that already matched
     * something ("Think Tank" bag, "Jean Slim Fit") keeps its category.
     */
    public static final String[] KW_MUSCULOSA_TARDIA = {" tank ", " stringer ", " musculos "};
    public static final String[] KW_REMERA_TARDIA = {" boxy ", " long sleeve ", " slim fit ", " v-neck "};
    public static final String[] KW_CORPINO_TARDIO = {" croptop ", " crop top ", " bras "};

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

    public static final String[] KW_NOTEBOOK = {
        "notebook","laptop","netbook","macbook","chromebook",
        "portatil","computadora portatil"
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
        " hierro redondo"," hierro nervado"," hierro aletado"," hierro adn"," hierros "," hierro del ",
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
        " cable tipo taller"," cable subterraneo"," cable preensamblado"," cable paralelo"
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

    public static final String[] KW_FUNDA_LIDER = { " funda "," fundas " };
    public static final String[] KW_FUNDA_CONTEXTO = {
        " almohadon"," silla"," sillon"," respaldo"," sofa"," cama"," cuerpos"," pana "," tusor "
    };
    public static final String[] KW_RESPALDO_LIDER = { " respaldo " };
    public static final String[] KW_RESPALDO_CONTEXTO = { " cama "," modular "," cabecera " };
    public static final String[] KW_ALMOHADON_LIDER = { " almohadon "," almohadones "," pillow " };
    public static final String[] KW_MANTA_LIDER = { " manta "," mantas " };
    public static final String[] KW_MANTEL_LIDER = {
        " mantel "," manteles "," servilleta "," servilletas "
    };
    public static final String[] KW_ALFOMBRA_LIDER = {
        " alfombra "," alfombras "," protector bajo escritorio "
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
        " ram ","memoria ram","dimm","ddr4","ddr5","sodimm",
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
        "carnitina","cla ","lipo6","lipo 6","lipo-6"
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
        "quemador","fat burner","termogenico","l-carnitina","carnitina","cla ",
        "suplemento","supplement","nutri","proteico","proteica"
    };

    public static final String[] KW_COMIDA = {
        "yerba","cafe","te verde","infusion","cereal","granola",
        "frutos secos","almendra"," mani ","cacao","chocolate proteico",
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

    public static final String[] KW_GABINETE_ACCESORIO_LIDER = {
        " bracket ", " filtro ", " soporte ", " kit "
    };

    /** Líder ⇒ accesorio, sin necesidad de que el título nombre su destino. */
    public static final String[] KW_ACCESORIO_LIDER = { " bracket ", " controladora " };

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
        "impresora","impresoras","toner","cartucho","tinta alternativa",
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
        "paleta de tenis de mesa"
    };

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

    public static boolean anyMatch(String text, String[] keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }
}
