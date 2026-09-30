package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Lee las keyword taxonomies desde {@link GarmentTaxonomy} — misma instancia de
 * {@code TORSO_KEYWORDS_FLAT}/{@code PIERNAS_KEYWORDS_FLAT} que consume
 * {@link PackQuantityDetector} (ADR-1, single source of truth, sin copias por clase).
 */
@Component
public class CategoryClassifier {

    private static final Pattern PESO_VOLUMEN =
        Pattern.compile("\\d+\\s*(g|ml|kg|mg|oz|l)\\s*$", Pattern.CASE_INSENSITIVE);

    public String normalizarCategoria(String raw, String nombre) {
        String fromName = clasificar(nombre);
        if (!fromName.isEmpty()) return fromName;

        if (StringUtils.isNotBlank(raw)) {
            String fromRaw = clasificar(raw);
            if (!fromRaw.isEmpty()) return fromRaw;
            // Si no matchea ningún keyword → limpiar la categoría cruda Quitar nombres de tienda
            // (VCP, Sporting, etc.), flechas, separadores
            String cleaned = raw.replaceAll("(?i)\\b(vcp|sporting|vaypol|freres|batuk|city|bulks|"
                           + "midway|tussy|bullbenny|dcshoes|eldon|entreno|fuark)\\b", "")
                           .replaceAll("[>|/\\\\]+", " ")
                           .replaceAll("\\s{2,}", " ")
                           .trim();
            // Ahora sólo se acepta lo que tenga alias conocido hacia el canon.
            String alias = CategoryAliases.canonical(cleaned.split("\\s+")[0]);
            if (alias != null) return alias;
            String aliasCompleto = CategoryAliases.canonical(cleaned);
            if (aliasCompleto != null) return aliasCompleto;
        }
        if (tieneIndicadorPeso(nombre)) return "Alimentos";
        return "Otros";
    }

    /**
     * Orden interno, también load-bearing y también sacado de nombres reales: Organización antes
     * que Escritorio — "Cajón Standing Desk" y "Soporte de CPU para Standing Desk" nombran el
     * mueble al que se enganchan, no lo que son.
     */
    private String clasificarOficina(String t) {
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ILUMINACION))      return "Iluminación";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SOPORTE_MONITOR))  return "Soporte Monitor";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SOPORTE_LAPTOP))   return "Soporte Laptop";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ORGANIZACION))     return "Organización";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MAT_ESCRITORIO))   return "Mat Escritorio";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SILLA))            return "Silla";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ESCRITORIO)
                && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ESCRITORIO_PARTE)) {
            return "Escritorio";
        }
        return "";
    }

    private boolean tieneIndicadorPeso(String nombre) {
        if (StringUtils.isBlank(nombre)) return false;
        return PESO_VOLUMEN.matcher(nombre.trim()).find();
    }

    /** El orden de evaluación determina el resultado cuando hay ambigüedad. */
    private String clasificar(String texto) {
        if (StringUtils.isBlank(texto)) return "";
        if (NonTextileGuard.esClaramenteNoTextil(texto)) return "";
        // Padding con espacios: permite matchear keywords cortas como "top" por palabra completa ("
        // top ") sin falsos positivos contra "laptop"/"desktop", que no tienen espacio antes de
        // "top".
        String t = " " + texto.toLowerCase()
                        .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                        .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                        .replaceAll("[úùü]","u").replaceAll("[ñ]","n") + " ";

        // ── PORTÓN DE NUTRICIÓN (antes de ropa) ───────────────────────────── Señal fuerte de
        // comida/suplemento: sustantivo culinario inequívoco o nombre de marca de alimento.
        if (esContextoNutricion(t)) {
            String nutriTemprano = clasificarNutricion(t);
            return nutriTemprano.isEmpty() ? "Alimentos" : nutriTemprano;
        }

        // ── OFICINA (ANTES de TECH — el orden es load-bearing) ────────────── Cuatro colisiones
        // REALES del catálogo de INPRO obligan a que esto corra primero, y las cuatro son de
        // sustantivos compartidos, no de keywords mal elegidas:
        String oficina = clasificarOficina(t);
        if (!oficina.isEmpty()) return oficina;

        // ── TECH (antes de textil para evitar falsos positivos) ─────── El ORDEN de acá abajo es
        // load-bearing y está MEDIDO contra las 16.830 filas activas, no elegido por prolijidad.
        String tech = clasificarTech(t);
        if (!tech.isEmpty()) return tech;

        // ── COMBO / MULTI-PIEZA (ver ADR-4) — antes de ropa/calzado, para que un SKU combo no
        // quede first-matched como una sola pieza;
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CONJUNTO)) return "Conjunto";
        if (matchesTorsoBlock(t) && matchesPiernasBlock(t)) return "Conjunto";

        // ── EQUIPAMIENTO DEPORTIVO (antes del bloque de ropa Y del fallback de calzado): "Paleta
        // De Pádel adidas Adipower Ctrl Team 3.3" caía en Zapatilla Entrenamiento porque "adipower"
        // es un KW_TRAINING_MODELO.
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PELOTA))    return "Pelota";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PALETA))    return "Paleta";

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

        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CALZONCILLO)) return "Calzoncillos";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CORPINO))     return "Corpino";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MALLA))       return "Malla";

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

        // Un producto llega acá solo si ningún bloque de ropa lo matcheó antes.
        String nutri = clasificarNutricion(t);
        if (!nutri.isEmpty()) return nutri;
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PERFUME))         return "Perfume";

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

        // ── CALZADO POR MODELO/MARCA (fallback, sin sustantivo explícito) ─ Corre AL FINAL,
        // después de todos los sustantivos explícitos de arriba: Por eso GENERICO solo cuenta si
        // esZapatilla también matchea — nunca solo.
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SNEAKER_MODELO)) return "Sneaker";

        boolean esZapatilla = t.contains("zapatilla") || t.contains("sneaker")
                || t.contains("calzado") || (" " + t + " ").contains(" shoe ")
                || t.contains(" shoes ")
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
     * El orden es dato medido, no estilo. "Fuente Segotep 500W ATX Cables Largos" nombra los suyos
     * y no es un cable. 130 de los 136 productos con "cable" en {@code Otros} lo tienen como
     * primera palabra.
     */
    private String clasificarTech(String t) {
        // Gabinete es gabinete: un accesorio o servicio que sólo NOMBRA "para gabinete" no es el
        // producto — abstiene de TODO el bloque tech, no sólo de Gabinete, así que un bracket con
        // "disco ssd" en el nombre tampoco cae en Almacenamiento más abajo.
        if ((t.contains(" para gabinete ") && startsWithAny(t, GarmentTaxonomy.KW_GABINETE_ACCESORIO_LIDER))
                || startsWithAny(t, GarmentTaxonomy.KW_ACCESORIO_LIDER)
                || startsWithAny(t, GarmentTaxonomy.KW_SERVICIO_LIDER))
            return "";
        if (startsWithAny(t, GarmentTaxonomy.KW_MINIPC_LIDER))                return "Mini PC";
        if (startsWithAny(t, GarmentTaxonomy.KW_PC_LIDER))                    return "PC";
        if (startsWithAny(t, GarmentTaxonomy.KW_CPU_LIDER))                   return "CPU";
        // "memoria" líder + un token DDR real gana antes que KW_CPU — sin esto, "AMD EXPO"/"Intel
        // XMP" (el perfil de overclock del fabricante de CPU) hacía caer el stick en CPU vía " amd
        // "/" intel ".
        if (startsWithAny(t, GarmentTaxonomy.KW_RAM_LIDER) && GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RAM))
            return "RAM";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RED)
                || (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RED_SWITCH) && esContextoRed(t)))
            return "Red";
        if (esCableLider(t))                                                  return "Cable";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_NOTEBOOK))         return "Notebook";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TABLET))           return "Tablet";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMARA))           return "Cámara";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PC))               return "PC";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MONITOR))          return "Monitor";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GPU))              return "GPU";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOTHERBOARD))      return "Motherboard";
        if (startsWithAny(t, GarmentTaxonomy.KW_FUENTE_LIDER))                return "Fuente";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GABINETE))         return "Gabinete";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_FUENTE))           return "Fuente";
        // Almacenamiento/Auricular/Joystick por sustantivo líder ganan antes que KW_COOLER — ahí
        // "cooler"/"disipador" es la marca ("Cooler Master") o un accesorio del producto ("con
        // disipador"), no lo que el producto ES.
        if (startsWithAny(t, GarmentTaxonomy.KW_ALMACENAMIENTO_LIDER)
                && GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ALMACENAMIENTO))
            return "Almacenamiento";
        if (startsWithAny(t, GarmentTaxonomy.KW_AURICULAR_LIDER))            return "Auricular";
        if (startsWithAny(t, GarmentTaxonomy.KW_JOYSTICK_LIDER))             return "Joystick";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COOLER))           return "Cooler";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CPU))              return "CPU";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RAM))              return "RAM";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ALMACENAMIENTO))   return "Almacenamiento";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_IMPRESION))        return "Impresión";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_UPS))              return "UPS";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JOYSTICK)
                || (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VOLANTE_GENERICO) && esContextoVolante(t)))
            return "Joystick";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MICROFONO))        return "Micrófono";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_WEBCAM))           return "Webcam";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_AURICULAR))        return "Auricular";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOUSEPAD))         return "Mousepad";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_TECLADO))          return "Teclado";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOUSE)
                || (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOUSE_GENERICO) && !esRatonDeDisney(t)))
            return "Mouse";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RELOJ))            return "Reloj";
        return "";
    }

    /** Deliberadamente no es un {@code anyMatch}: */
    private boolean esCableLider(String t) {
        for (String kw : GarmentTaxonomy.KW_CABLE_LIDER) {
            if (t.startsWith(kw)) return true;
        }
        return false;
    }

    /**
     * Mismo contrato que {@link #esCableLider}, salvo que "outlet" al frente no cuenta como el
     * sustantivo: es una etiqueta de venta ("Outlet Procesador Intel..."), así que también se
     * prueba el texto sin ese prefijo.
     */
    private boolean startsWithAny(String t, String[] keywords) {
        for (String kw : keywords) {
            if (t.startsWith(kw)) return true;
        }
        String sinOutlet = sinLiderOutlet(t);
        if (sinOutlet != null) {
            for (String kw : keywords) {
                if (sinOutlet.startsWith(kw)) return true;
            }
        }
        return false;
    }

    /** Texto sin el prefijo " outlet " (y separadores sueltos), o null si no lo tenía. */
    private String sinLiderOutlet(String t) {
        if (!t.startsWith(" outlet ")) return null;
        return " " + t.substring(" outlet ".length()).replaceFirst("^[-\\s]+", "");
    }

    /**
     * Guard de {@code KW_RED_SWITCH} (Tier B). "switch" es tanto un switch de red como el tipo de
     * switch de un teclado mecánico — "Teclado Mecánico Raptor Fireclaw M87 Red Red Switch" tiene
     * las dos palabras y no es un router.
     */
    private boolean esContextoRed(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RED_CONTEXTO);
    }

    /** Guard de {@code KW_VOLANTE_GENERICO} (Tier B). */
    private boolean esContextoVolante(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VOLANTE_CONTEXTO);
    }

    /**
     * Un ratón de Disney no es un periférico, y "mouse de chocolate" quiso decir mousse. El bloque
     * TECH corre antes que el de ropa, así que sin este veto una zapatilla de Mickey y una mochila
     * de Minnie entraban al catálogo como mouse.
     */
    private boolean esRatonDeDisney(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOUSE_VETO);
    }

    /**
     * Usa solo {@code KW_ALIMENTO_TEMPRANO} (nouns culinarios sin colisión con ropa) y
     * {@code KW_MARCA_ALIMENTO} (marcas de comida/suplemento curadas).
     */
    private boolean esContextoNutricion(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ALIMENTO_TEMPRANO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MARCA_ALIMENTO);
    }

    /**
     * Gainer y Pre-Workout corren ANTES de KW_PROTEINA (cambio deliberado sobre el orden del bloque
     * inline original). Tener proteína no es ser proteína: cuando la identidad del producto es
     * inequívoca, gana la identidad.
     */
    private String clasificarNutricion(String texto) {
        String t = sinMarcasQueNombranProteina(texto);
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CREATINA))         return "Creatina";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_BARRA))  return "Barra Proteica";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_PANCAKE)) return "Pancake Proteico";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_SNACK))  return "Snack Proteico";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GAINERS))         return "Gainer";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PRE_WORKOUT_SUP)) return "Pre-Workout";
        boolean cabezaDeProteina = esContextoProteina(t);
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_VEGETAL)
                || (cabezaDeProteina
                    && GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_VEGETAL_RECLAMO)))
            return "Proteína Vegetal";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_ISOLADA)
                || (cabezaDeProteina
                    && (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_ISOLADA_PROCESO)
                     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_ISOLADA_MARCA))))
            return "Proteína Isolada";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA))        return "Proteína";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COLAGENO))        return "Colágeno";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MAGNESIO))        return "Magnesio";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BCAA_SUP))        return "BCAA";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VITAMINAS))       return "Vitaminas";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_QUEMADORES))      return "Quemadores";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SUPLEMENTO))      return "Suplemento";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COMIDA))          return "Alimentos";
        return "";
    }

    /**
     * Borra del texto los nombres de marca que contienen una palabra de proteína
     * ({@code KW_MARCA_CON_PROTEINA_EN_EL_NOMBRE}) antes de resolver la subcategoría de nutrición.
     */
    /**
     * Guard de los Tier B de {@code Proteína Vegetal} e {@code Proteína Isolada}: ¿el texto nombra
     * una proteína, y no sólo un atributo del envase?
     */
    private boolean esContextoProteina(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA);
    }

    private String sinMarcasQueNombranProteina(String t) {
        String limpio = t;
        for (String marca : GarmentTaxonomy.KW_MARCA_CON_PROTEINA_EN_EL_NOMBRE) {
            if (limpio.contains(marca)) limpio = limpio.replace(marca, " ");
        }
        return limpio;
    }

    /** Footwear/football context guard for {@code KW_BOTIN_GENERICO} (Tier B). */
    private boolean esContextoBotin(String t) {
        return t.contains("botin") || t.contains("futbol") || t.contains("tachon")
            || t.contains("cleats") || t.contains("cancha");
    }

    private boolean esContextoBorcego(String t) {
        return t.contains("borcego") || t.contains("bota") || t.contains("boot")
            || t.contains("hiker") || t.contains("hiking") || t.contains("calzado");
    }

    /** Footwear context guard for {@code KW_OJOTA_MARCA} (Tier B). */
    private boolean esContextoOjota(String t) {
        return t.contains("ojota") || t.contains("sandalia") || t.contains("chancla")
            || t.contains("chinelo") || t.contains("slide") || t.contains("flip flop")
            || t.contains("zueco") || t.contains("rasteira") || t.contains("babucha");
    }

    /** Brand-name guard for {@code KW_CHOMBA_MARCA} (Tier B). */
    private boolean esContextoChomba(String t) {
        return !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MEDIAS)   && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GORRA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GORRO)    && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOCHILA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BOLSO)    && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BILLETERA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CINTURON) && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BUFANDA)
            && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GUANTES)  && !GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_LENTES);
    }

    /**
     * Espeja los keywords de la sección "INDUMENTARIA SUPERIOR" del clasificador secuencial,
     * EXCEPTO KW_TRAJE — un traje nunca debe disparar el check (b) de combo, ver Open Question 0.1
     * (resuelta).
     */
    private boolean matchesTorsoBlock(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PUFFER)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PILOTO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SACO)     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHALECO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMPERA)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SWEATER)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BUZO)     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CASACA)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CHOMBA)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MUSCULOSA)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CAMISA)   || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_REMERA);
    }

    private boolean matchesPiernasBlock(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CALZA)    || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BAGGY)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JEAN)     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_JOGGING)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BERMUDA)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SHORT)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VESTIDO)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ENTERITO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_POLLERA)  || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PANTALON);
    }

    private String capitalize(String s) {
        if (StringUtils.isBlank(s)) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
