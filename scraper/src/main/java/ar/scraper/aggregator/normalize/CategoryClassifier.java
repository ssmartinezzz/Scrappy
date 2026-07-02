package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Clasificador de categoría profunda post-scraping.
 *
 * Principio de diseño:
 *   Las reglas más ESPECÍFICAS van primero.
 *   "Zapatilla Running" antes que "Zapatilla".
 *   "Buzo" y "Sweater" son categorías DISTINTAS.
 *   El nombre del producto tiene prioridad sobre la categoría cruda del sitio.
 *
 * <p>Extraído verbatim (if-chain y keyword arrays relocados, no reescritos)
 * de {@code NormalizerService.clasificar}/{@code normalizarCategoria} y sus
 * context guards (Work Unit 5 de la modularización SOLID del aggregator).
 * Lee las keyword taxonomies desde {@link GarmentTaxonomy} — misma instancia
 * de {@code TORSO_KEYWORDS_FLAT}/{@code PIERNAS_KEYWORDS_FLAT} que consume
 * {@link PackQuantityDetector} (ADR-1, single source of truth, sin copias
 * por clase).</p>
 */
@Component
public class CategoryClassifier {

    private static final Pattern PESO_VOLUMEN =
        Pattern.compile("\\d+\\s*(g|ml|kg|mg|oz|l)\\s*$", Pattern.CASE_INSENSITIVE);

    public String normalizarCategoria(String raw, String nombre) {
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
        if (tieneIndicadorPeso(nombre)) return "Alimentos";
        return "Indumentaria";
    }

    private boolean tieneIndicadorPeso(String nombre) {
        if (nombre == null || nombre.isBlank()) return false;
        return PESO_VOLUMEN.matcher(nombre.trim()).find();
    }

    /**
     * Clasificador por palabras clave ordenado de ESPECÍFICO a GENERAL.
     * El orden de evaluación determina el resultado cuando hay ambigüedad.
     */
    private String clasificar(String texto) {
        if (texto == null || texto.isBlank()) return "";
        if (NonTextileGuard.esClaramenteNoTextil(texto)) return "";
        // Padding con espacios: permite matchear keywords cortas como "top" por
        // palabra completa (" top ") sin falsos positivos contra "laptop"/"desktop",
        // que no tienen espacio antes de "top".
        String t = " " + texto.toLowerCase()
                        .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                        .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                        .replaceAll("[úùü]","u").replaceAll("[ñ]","n") + " ";

        // ── PRE-CHECK CULINARIO (antes de ropa) ─────────────────────────────
        // Palabras 100% culinarias que no pueden ser colores/materiales de ropa.
        // Corre antes del bloque de indumentaria para evitar que " top " clasifique
        // "MRS TASTE BBQ Salsa Top Chef" como Musculosa.
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ALIMENTO_TEMPRANO)) return "Alimentos";

        // ── COMBO / MULTI-PIEZA (ver ADR-4) — corre ANTES de cualquier otro
        // bloque para que un SKU combo nunca quede first-matched como una sola
        // pieza (torso o piernas). KW_TRAJE queda deliberadamente afuera del
        // bloque torso usado en (b): un traje siempre resuelve a "Traje".
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CONJUNTO)) return "Conjunto";
        if (matchesTorsoBlock(t) && matchesPiernasBlock(t)) return "Conjunto";

        // ── TECH (antes de textil para evitar falsos positivos) ───────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_NOTEBOOK))  return "Notebook";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PC))        return "PC";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MONITOR))   return "Monitor";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GPU))       return "GPU";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CPU))       return "CPU";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RAM))       return "RAM";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GABINETE))  return "Gabinete";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TECLADO))   return "Teclado";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOUSE))     return "Mouse";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_AURICULAR)) return "Auricular";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_WEBCAM))    return "Webcam";

        // ── CALZADO (más específico primero) ──────────────────────────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BOTIN))     return "Botines";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BOTIN_GENERICO) && esContextoBotin(t)) return "Botines";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BORCEGO))   return "Borcego";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BORCEGO_MARCA) && esContextoBorcego(t)) return "Borcego";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PANTUFLA))  return "Pantufla";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ZAPATO))    return "Zapato";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOCASIN))   return "Mocasin";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SANDALIA))  return "Sandalia";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_OJOTA) || (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_OJOTA_MARCA) && esContextoOjota(t)))
            return "Ojotas";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BOTA))      return "Botas";

        // ── ROPA INTERIOR / BAÑO ──────────────────────────────────────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CALZONCILLO)) return "Calzoncillos";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CORPINO))     return "Corpino";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MALLA))       return "Malla";

        // ── INDUMENTARIA SUPERIOR (más específico primero) ────────────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PUFFER))   return "Puffer";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PILOTO))   return "Piloto";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TRAJE))    return "Traje";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SACO))     return "Saco";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHALECO))  return "Chaleco";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMPERA))  return "Campera";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SWEATER))  return "Sweater";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BUZO))     return "Buzo";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CASACA))   return "Casaca";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHOMBA) || (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHOMBA_MARCA) && esContextoChomba(t)))
            return "Chomba";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MUSCULOSA)) return "Musculosa";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMISA))   return "Camisa";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_REMERA))   return "Remera";

        // ── INDUMENTARIA INFERIOR ─────────────────────────────────────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CALZA))    return "Calza";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BAGGY))    return "Baggy";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JEAN))     return "Jean";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JOGGING))  return "Jogging";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BERMUDA))  return "Bermuda";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SHORT))    return "Short";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VESTIDO))  return "Vestido";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ENTERITO)) return "Enterito";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_POLLERA))  return "Pollera";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PANTALON)) return "Pantalón";

        // ── SUPLEMENTOS / NUTRICIÓN (específico → genérico) ──────────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CREATINA))         return "Creatina";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_BARRA))  return "Barra Proteica";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_PANCAKE)) return "Pancake Proteico";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_SNACK))  return "Snack Proteico";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA))        return "Proteína";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COLAGENO))        return "Colágeno";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MAGNESIO))        return "Magnesio";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PRE_WORKOUT_SUP)) return "Pre-Workout";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BCAA_SUP))        return "BCAA";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VITAMINAS))       return "Vitaminas";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_QUEMADORES))      return "Quemadores";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GAINERS))         return "Gainer";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SUPLEMENTO))      return "Suplemento";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COMIDA))          return "Alimentos";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PERFUME))         return "Perfume";

        // ── ACCESORIOS (más específico primero) ───────────────────────
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BILLETERA))  return "Billetera";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RINONERA))   return "Riñonera";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOCHILA))    return "Mochila";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BOLSO))      return "Bolso";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CINTURON))   return "Cinturón";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BUFANDA))    return "Bufanda";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GUANTES))    return "Guantes";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_LENTES))     return "Lentes";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GORRO))      return "Gorro";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GORRA))      return "Gorra";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MEDIAS))     return "Medias";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ACCESORIO_DEPORTIVO)) return "Accesorio Deportivo";

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
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SNEAKER_MODELO)) return "Sneaker";

        boolean esZapatilla = t.contains("zapatilla") || t.contains("sneaker")
                || t.contains("calzado") || (" " + t + " ").contains(" shoe ")
                || t.contains("tenis") || t.contains("footwear");

        boolean shoe = esZapatilla
                || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RUNNING_MODELO) || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TRAINING_MODELO)
                || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SKATE_MODELO)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_URBANA_MODELO);

        if (shoe) {
            if (tieneIndicadorPeso(texto)) return "Alimentos";
            if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RUNNING_MODELO)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RUNNING_GENERICO))  return "Zapatilla Running";
            if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TRAINING_MODELO) || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TRAINING_GENERICO)) return "Zapatilla Entrenamiento";
            if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SKATE_MODELO)    || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SKATE_GENERICO))    return "Zapatilla Skate";
            if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_URBANA_MODELO)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_URBANA_GENERICO))   return "Zapatilla Urbana";
            if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SNEAKER_GENERICO)) return "Sneaker";
            if (esZapatilla) return "Zapatilla";
        }

        return "";
    }

    /**
     * Footwear/football context guard for {@code KW_BOTIN_GENERICO} (Tier B).
     * These tokens are ambiguous dictionary words ("ace", "copa", "tiempo",
     * "future") that also appear inside unrelated words (Embrace, Copacabana,
     * entretiempo) or as common nouns — they only classify as "Botines" when
     * a footwear/football-specific signal also co-occurs in the title.
     */
    private boolean esContextoBotin(String t) {
        return t.contains("botin") || t.contains("futbol") || t.contains("tachon")
            || t.contains("cleats") || t.contains("cancha");
    }

    private boolean esContextoBorcego(String t) {
        return t.contains("borcego") || t.contains("bota") || t.contains("boot")
            || t.contains("hiker") || t.contains("hiking") || t.contains("calzado");
    }

    /**
     * Footwear context guard for {@code KW_OJOTA_MARCA} (Tier B). "Reef" is
     * both a sandal brand and a beachwear/accessories brand (mochilas,
     * gorras, buzos, billeteras) — only classify as Ojotas via the bare
     * brand keyword when an explicit footwear signal co-occurs.
     */
    private boolean esContextoOjota(String t) {
        return t.contains("ojota") || t.contains("sandalia") || t.contains("chancla")
            || t.contains("chinelo") || t.contains("slide") || t.contains("flip flop")
            || t.contains("zueco") || t.contains("rasteira") || t.contains("babucha");
    }

    /**
     * Brand-name guard for {@code KW_CHOMBA_MARCA} (Tier B). "Polo" is both
     * a garment word (chomba/polo shirt) and a brand/línea name used on
     * accessories that are NOT indumentaria superior (medias, gorras,
     * mochilas, bolsos, billeteras, cinturones, bufandas, guantes, lentes).
     * Only classify as Chomba via the bare "polo" keyword when none of those
     * accessory nouns co-occur in the same title.
     */
    private boolean esContextoChomba(String t) {
        return !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MEDIAS)   && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GORRA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GORRO)    && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOCHILA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BOLSO)    && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BILLETERA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CINTURON) && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BUFANDA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GUANTES)  && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_LENTES);
    }

    /**
     * Bloque torso usado por la detección de combos (ADR-4). Espeja los keywords
     * de la sección "INDUMENTARIA SUPERIOR" del clasificador secuencial,
     * EXCEPTO KW_TRAJE — un traje nunca debe disparar el check (b) de combo,
     * ver Open Question 0.1 (resuelta).
     */
    private boolean matchesTorsoBlock(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PUFFER)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PILOTO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SACO)     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHALECO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMPERA)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SWEATER)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BUZO)     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CASACA)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHOMBA)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MUSCULOSA)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMISA)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_REMERA);
    }

    /**
     * Bloque piernas usado por la detección de combos (ADR-4). Espeja los
     * keywords de la sección "INDUMENTARIA INFERIOR" del clasificador
     * secuencial.
     */
    private boolean matchesPiernasBlock(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CALZA)    || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BAGGY)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JEAN)     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JOGGING)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BERMUDA)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SHORT)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VESTIDO)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ENTERITO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_POLLERA)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PANTALON);
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
