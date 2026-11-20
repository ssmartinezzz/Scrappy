package ar.scraper.aggregator.normalize;

import java.util.Map;

/**
 * Sub-category of bedding and mattresses, in the words Argentine stores use. A piece of bedding is read from its LEADING noun
 * ("Funda Plumón" is a Funda, "Cover Las Calas" a Cubrecama); a mattress always leads with colchón or sommier, so its sub is the SIZE,
 * read from anywhere in the name: "1 1/2 plazas", "plaza y media" or the dimensions stores print instead (100x190, 1.00x0.20x1.90).
 * Same mechanism as {@link ElectroSubcategoryResolver} ({@link LeadingNounRules}: the leader is the product, {@code any} the size).
 * {@code VOCABULARIO} is the text V130 backfills from, and {@code V130CamaAgreementTest} fails if the two drift.
 */
final class CamaSubcategoryResolver {

    private static final String ROPA_DE_CAMA = "Ropa de Cama";
    private static final String COLCHON = "Colchón";
    private static final String VOCABULARIO =
        "rc|Juego de Sábanas| juego de sabana, juego sabana, juego de cama , set de sabana, sabana||;" +
        "rc|Acolchado| acolchado||;" +
        "rc|Cubrecama| cubrecama, cubre cama, cover , quilt ||;" +
        "rc|Protector| protector, cubrecolchon, cubre colchon, cubresommier, cubre sommier||;" +
        "rc|Protector| funda , fundas | colchon , sommier |;" +
        "rc|Almohada| almohada , almohadas ||;" +
        "rc|Funda| funda , fundas || silla , sillas , sillon , sofa ;" +
        "rc|Frazada y Manta| frazada, manta , mantas , cobertor, piecera, pie de cama||;" +
        "rc|Plumón| plumon, edredon, nordico, duvet ||;" +
        "co|1 1/2 Plaza| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 100x190, 100 x 190, 100x200, 100 x 200, 1.00x, 1 plaza y media, plaza y media, 1 1 2 plaza, 1.5 plaza, 1.5 pz, 1 5 plaza|;" +
        "co|2 1/2 Plazas| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 150x190, 150 x 190, 150x200, 150 x 200, 1.50x, 1.50 , 2 1 2 plaza, 2.5 plaza, 2 plazas y media, 2 plaza y media|;" +
        "co|1 Plaza| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 80x190, 80 x 190, 080x190, 080 x 190, 80x200, 80 x 200, 080x200, 080 x 200, 90x190, 90 x 190, 090x190, 090 x 190, 90x200, 90 x 200, 090x200, 090 x 200, 0.80x, 0.90x, 0.80 , 0.90 , 1 plaza|;" +
        "co|2 Plazas| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 130x190, 130 x 190, 130x200, 130 x 200, 140x190, 140 x 190, 140x200, 140 x 200, 1.30x, 1.40x, 1.30 , 1.40 , 130c190, 2 plaza|;" +
        "co|Queen| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 160x190, 160 x 190, 160x200, 160 x 200, 1.60x, 1.60 , queen |;" +
        "co|King| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 180x190, 180 x 190, 180x200, 180 x 200, 200x190, 200 x 190, 200x200, 200 x 200, 1.80x, 1.80 , 2.00x, king size , super king |";

    static final LeadingNounRules REGLAS = new LeadingNounRules(VOCABULARIO, Map.of(ROPA_DE_CAMA, "rc", COLCHON, "co"), "");
}
