package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RISK MITIGATION (ADR-1 guard, Work Unit 3 task 3.6).
 *
 * <p>Asserts {@link GarmentTaxonomy#torsoFlat()}/{@link GarmentTaxonomy#piernasFlat()}
 * are byte-identical to a snapshot frozen INDEPENDENTLY of
 * {@code GarmentTaxonomy}'s own {@code concatKeywords} derivation — the
 * expected arrays below are transcribed directly from the composing
 * {@code KW_*} arrays as they existed the moment this extraction happened.
 * If a future change to {@code GarmentTaxonomy} silently adds, removes, or
 * reorders a keyword in any of the composing arrays, THIS test must fail
 * and force a conscious decision — it is the single source of truth that
 * both {@code CategoryClassifier} (Work Unit 5) and
 * {@code PackQuantityDetector} (Work Unit 4) will come to depend on
 * (previously duplicated and prone to silent drift, per the design's ADR-1).</p>
 */
class GarmentTaxonomyFlatDerivationGuardTest {

    // Frozen snapshot — mirrors NormalizerService's pre-extraction
    // TORSO_KEYWORDS_FLAT = concatKeywords(KW_PUFFER, KW_PILOTO, KW_SACO,
    // KW_CHALECO, KW_CAMPERA, KW_SWEATER, KW_BUZO, KW_CASACA, KW_CHOMBA,
    // KW_MUSCULOSA, KW_CAMISA, KW_REMERA) — transcribed independently of
    // GarmentTaxonomy's own derivation.
    private static final String[][] TORSO_COMPOSING_ARRAYS = {
        {"puffer","plumon","pluma","down jacket","down coat",
         "campera inflable","chaleco inflable","abrigo inflable","parka inflable",
         "acolchada","acolchado","anorak termico"},
        {"piloto","impermeable","lluvia","rain jacket","waterproof jacket",
         "chubasquero","k-way","kway","raincoat"},
        {"saco ","sacos ","blazer","americana","sport coat",
         "tuxedo","saco de vestir","saco formal","saco lino",
         "saco tweed","saco sastre"},
        {"chaleco","gilet","vest ","waistcoat","chaleco de abrigo",
         "chaleco inflable","chaleco pluma","chaleco polar",
         "chaleco tejido","chaleco cuero"},
        {"campera","jacket","chaqueta","cortaviento","rompeviento",
         "windbreaker","anorak","softshell","shell",
         "bomber","track jacket","tricota"},
        {"sweater","pulover","pullover","jersey","knit","tejido",
         "tricot","cardigan","lana","merino","crochet"},
        {"buzo","hoodie","hoody","sweatshirt","sudadera",
         "fleece","polar","zip hoodie","full zip","half zip","canguro"},
        {"casaca","camiseta de futbol","camiseta futbol","jersey futbol",
         "camiseta seleccion","camiseta club","replica","kit futbol",
         "camiseta oficial","camiseta de juego","camiseta deportiva",
         "casaca deportiva","camiseta nba","jersey nba"},
        {"chomba","polo shirt","polera","rugby shirt",
         "pique polo","lacoste polo","fred perry polo"},
        {"musculosa","tank top","camiseta de tirantes","sin mangas",
         "top deportivo","sports bra","corpino deportivo"," top "},
        {"camisa","shirt","oxford","flannel","chambray","denim shirt"},
        {"remera","t-shirt","tee","camiseta","top cuello","manga corta","basic tee"}
    };

    // Frozen snapshot — mirrors NormalizerService's pre-extraction
    // PIERNAS_KEYWORDS_FLAT = concatKeywords(KW_CALZA, KW_BAGGY, KW_JEAN,
    // KW_JOGGING, KW_BERMUDA, KW_SHORT, KW_VESTIDO, KW_ENTERITO, KW_POLLERA,
    // KW_PANTALON).
    private static final String[][] PIERNAS_COMPOSING_ARRAYS = {
        {"calza","legging","leggin","tight","malla deportiva","capri","culote"},
        {"baggy","wide leg","pierna ancha","balloon","paperbag",
         "oversize jean","oversized jean","barrel","loose fit",
         "baggy pant","wide pant","cargo pant","carpintero",
         "parachute pant","jogger baggy"},
        {"jean","denim","jeans","vaquero","skinny jean",
         "slim jean","bootcut","straight jean"},
        {"jogging","pantalon deportivo","sweatpant",
         "jogger","pantalon de buzo","pantalon de entrenamiento",
         "bottoms","track pant","training pant"},
        {"bermuda","bermudas","short largo","short 3/4","walk short"},
        {"short","cargo short","swim short","boxer deportivo"},
        {"vestido","dress ","playero","maxidress","midi dress",
         "vestido largo","vestido corto","vestido de noche"},
        {"enterito","mono ","jumpsuit","overol","romper","mameluco",
         "enterito largo","catsuit"},
        {"pollera","falda","skirt","minifalda","midi skirt",
         "maxi falda","falda plisada","mini pollera"},
        {"pantalon","pant ","trouser","cargo ","chino ","formal pant"}
    };

    private static String[] flatten(String[][] groups) {
        List<String> flat = new ArrayList<>();
        for (String[] group : groups) flat.addAll(Arrays.asList(group));
        return flat.toArray(new String[0]);
    }

    @Test
    void torsoKeywordsFlatIsByteIdenticalToFrozenSnapshot() {
        assertThat(GarmentTaxonomy.torsoFlat()).containsExactly(flatten(TORSO_COMPOSING_ARRAYS));
    }

    @Test
    void piernasKeywordsFlatIsByteIdenticalToFrozenSnapshot() {
        assertThat(GarmentTaxonomy.piernasFlat()).containsExactly(flatten(PIERNAS_COMPOSING_ARRAYS));
    }
}
