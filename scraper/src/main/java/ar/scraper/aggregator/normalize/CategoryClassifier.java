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
            // Antes: `capitalize(cleaned.split("\\s+")[0])` — la primera palabra
            // del breadcrumb. Eso hacía que el vocabulario de `categoria` fuera
            // ABIERTO: cada tienda nueva podía inventar una categoría, y las que
            // inventó estaban mal ("Mini" para un morral, "Pc" para una microSD).
            // Ahora sólo se acepta lo que tenga alias conocido hacia el canon.
            String alias = CategoryAliases.canonical(cleaned.split("\\s+")[0]);
            if (alias != null) return alias;
            String aliasCompleto = CategoryAliases.canonical(cleaned);
            if (aliasCompleto != null) return aliasCompleto;
        }
        if (tieneIndicadorPeso(nombre)) return "Alimentos";
        // "Indumentaria" era un RUBRO usado como categoría: un "no sé"
        // disfrazado de dato, que además mentía sobre un producto tech.
        return "Otros";
    }

    /**
     * Bloque del rubro {@code oficina} (add-inpro-office-store).
     *
     * <p>Orden interno, también load-bearing y también sacado de nombres
     * reales:</p>
     * <ol>
     *   <li><b>Iluminación antes que Soporte Monitor</b> — "Lámpara de Monitor
     *       LED" es una lámpara, no un brazo.</li>
     *   <li><b>Soporte Laptop antes que Silla</b> — "Soporte de Notebook para
     *       Silla Ergonómica" es un soporte, no una silla.</li>
     *   <li><b>Organización antes que Escritorio</b> — "Cajón Standing Desk" y
     *       "Soporte de CPU para Standing Desk" nombran el mueble al que se
     *       enganchan, no lo que son.</li>
     *   <li><b>Escritorio último</b>, y sólo si no es una PARTE ni un SERVICIO:
     *       "Servicio de instalación de Standing Desk" ($60k), "Tapa Premium
     *       Standing Desk" ($167k) y "Ruedas Standing Desk" ($50k) son tres
     *       productos reales que, contados como escritorios, corren la mediana
     *       de la categoría hacia abajo contra escritorios de $800k-$2.4M.</li>
     * </ol>
     *
     * @return la categoría de oficina, o {@code ""} si el texto no es de
     *         oficina — abstención, que deja seguir la cadena ({@code CODE-5}).
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

        // ── PORTÓN DE NUTRICIÓN (antes de ropa) ─────────────────────────────
        // Señal fuerte de comida/suplemento: sustantivo culinario inequívoco o
        // nombre de marca de alimento. Cuando dispara, resolvemos la subcategoría
        // de nutrición ANTES del bloque de ropa (la subcategoría específica gana;
        // si ninguna matchea → "Alimentos"), evitando que keywords genéricos de
        // indumentaria (" top ", "knit", "fleece") roben salsas, cookies, purés o
        // marcas como MR TASTE / SmartDIET / Diabla / Nutremax.
        //
        // Se gatea SOLO con tokens inequívocos (KW_ALIMENTO_TEMPRANO +
        // KW_MARCA_ALIMENTO). Los tokens amplios de KW_COMIDA/KW_SUPLEMENTO
        // ("mate" ⊂ "material", "protein" en merch de gym) siguen corriendo
        // DESPUÉS del bloque de ropa para no robar indumentaria legítima.
        if (esContextoNutricion(t)) {
            String nutriTemprano = clasificarNutricion(t);
            return nutriTemprano.isEmpty() ? "Alimentos" : nutriTemprano;
        }

        // ── COMBO / MULTI-PIEZA (ver ADR-4) — corre ANTES de cualquier otro
        // bloque para que un SKU combo nunca quede first-matched como una sola
        // pieza (torso o piernas). KW_TRAJE queda deliberadamente afuera del
        // bloque torso usado en (b): un traje siempre resuelve a "Traje".
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CONJUNTO)) return "Conjunto";
        if (matchesTorsoBlock(t) && matchesPiernasBlock(t)) return "Conjunto";

        // ── OFICINA (ANTES de TECH — el orden es load-bearing) ──────────────
        // Cuatro colisiones REALES del catálogo de INPRO obligan a que esto
        // corra primero, y las cuatro son de sustantivos compartidos, no de
        // keywords mal elegidas:
        //   "Brazo de Monitor"                  contiene "monitor " -> KW_MONITOR
        //   "Soporte de CPU para Standing Desk" contiene "cpu "     -> KW_CPU
        // Un soporte de monitor NO es una pantalla y un soporte de CPU NO es un
        // procesador: si el bloque TECH los ve primero, entran al catálogo con
        // la categoría equivocada Y con la distribución de precios de otra
        // categoría, que es de lo que se alimenta el pipeline ML.
        String oficina = clasificarOficina(t);
        if (!oficina.isEmpty()) return oficina;

        // ── TECH (antes de textil para evitar falsos positivos) ───────
        // El ORDEN de acá abajo es load-bearing y está MEDIDO contra las 16.830
        // filas activas, no elegido por prolijidad. Ver `clasificarTech`.
        String tech = clasificarTech(t);
        if (!tech.isEmpty()) return tech;

        // ── EQUIPAMIENTO DEPORTIVO (antes del bloque de ropa Y del fallback
        // de calzado): "Paleta De Pádel adidas Adipower Ctrl Team 3.3" caía en
        // Zapatilla Entrenamiento porque "adipower" es un KW_TRAINING_MODELO.
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PELOTA))    return "Pelota";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PALETA))    return "Paleta";

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
        // Ruta post-ropa: tokens amplios (protein, whey, mate…) que NO gatean
        // el portón temprano para no colisionar con indumentaria. Un producto
        // llega acá solo si ningún bloque de ropa lo matcheó antes.
        String nutri = clasificarNutricion(t);
        if (!nutri.isEmpty()) return nutri;
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
                // " shoes " en plural faltaba, y era la única señal de calzado
                // en los 40 "Patinaje Dc Shoes <modelo>" que quedaban en Otros.
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
     * Bloque del rubro {@code tecnologia} (richer-category-taxonomy).
     *
     * <p><b>El orden es dato medido, no estilo.</b> Cada posición de abajo
     * corrige una colisión real de las 16.830 filas activas:</p>
     *
     * <ol>
     *   <li><b>Red antes que Cable</b> — un "Adaptador Wifi" es de red; el
     *       resto de los adaptadores son cables.</li>
     *   <li><b>Cable por sustantivo LÍDER</b> ({@link #esCableLider}) y no por
     *       aparición: "Fuente Segotep 500W ATX <i>Cables</i> Largos" nombra
     *       los suyos y no es un cable. 130 de los 136 productos con "cable"
     *       en {@code Otros} lo tienen como primera palabra.</li>
     *   <li><b>Cámara antes que Monitor</b> — "Camara Wifi Ezviz BM1 2mp Baby
     *       Call <i>Monitor</i>" termina en la palabra monitor.</li>
     *   <li><b>Gabinete antes que Fuente antes que Cooler</b> — 268 gabinetes
     *       nombran sus fans, 23 traen fuente incluida, 27 fuentes nombran su
     *       cooler. El contenedor gana sobre lo que contiene.</li>
     *   <li><b>Cooler antes que CPU</b> — era el bug: 321 de las 646 filas de
     *       {@code CPU} eran disipadores.</li>
     *   <li><b>Mousepad antes que Mouse</b> — "Mouse Pad Fantech MP64".</li>
     *   <li><b>Teclado antes que el switch de red</b> ya no hace falta porque
     *       {@code KW_RED_SWITCH} está gateado, pero el guard es el que
     *       protege: "Teclado Mecánico Raptor Fireclaw M87 Red Red Switch" no
     *       es un switch de red.</li>
     * </ol>
     *
     * @return la categoría tech, o {@code ""} si el texto no es tech —
     *         abstención, que deja seguir la cadena ({@code CODE-5}).
     */
    private String clasificarTech(String t) {
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
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GABINETE))         return "Gabinete";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_FUENTE))           return "Fuente";
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

    /**
     * ¿El texto ARRANCA con un sustantivo de cable/adaptador?
     *
     * <p>Deliberadamente no es un {@code anyMatch}: "cable" adentro del título
     * describe un accesorio del producto, no al producto. El texto ya llega
     * padeado con un espacio adelante, así que los keywords de
     * {@code KW_CABLE_LIDER} vienen padeados de los dos lados y esto es un
     * {@code startsWith} exacto sobre palabra completa.</p>
     */
    private boolean esCableLider(String t) {
        for (String kw : GarmentTaxonomy.KW_CABLE_LIDER) {
            if (t.startsWith(kw)) return true;
        }
        return false;
    }

    /**
     * Guard de {@code KW_RED_SWITCH} (Tier B). "switch" es tanto un switch de
     * red como el tipo de switch de un teclado mecánico — "Teclado Mecánico
     * Raptor Fireclaw M87 Red Red Switch" tiene las dos palabras y no es un
     * router. Sólo cuenta con señal de red en el mismo título.
     */
    private boolean esContextoRed(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_RED_CONTEXTO);
    }

    /**
     * Guard de {@code KW_VOLANTE_GENERICO} (Tier B). Un "volante" es tanto el
     * de un simulador de carreras como el vuelo de tela de un vestido.
     */
    private boolean esContextoVolante(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VOLANTE_CONTEXTO);
    }

    /**
     * Veto de {@code KW_MOUSE_GENERICO} (Tier B). Un ratón de Disney no es un
     * periférico, y "mouse de chocolate" quiso decir mousse. El bloque TECH
     * corre antes que el de ropa, así que sin este veto una zapatilla de Mickey
     * y una mochila de Minnie entraban al catálogo como mouse.
     */
    private boolean esRatonDeDisney(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MOUSE_VETO);
    }

    /**
     * ¿El texto trae señal INEQUÍVOCA de nutrición? Gatea el portón temprano de
     * {@link #clasificar(String)}. Usa solo {@code KW_ALIMENTO_TEMPRANO} (nouns
     * culinarios sin colisión con ropa) y {@code KW_MARCA_ALIMENTO} (marcas de
     * comida/suplemento curadas). Deliberadamente NO consulta {@code KW_COMIDA}
     * ni {@code KW_SUPLEMENTO}: sus tokens amplios ("mate" ⊂ "material",
     * "protein" en merch de gym) robarían indumentaria si corrieran antes del
     * bloque de ropa.
     */
    private boolean esContextoNutricion(String t) {
        return GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_ALIMENTO_TEMPRANO)
            || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MARCA_ALIMENTO);
    }

    /**
     * Clasificador de subcategoría de nutrición (suplemento/alimento), de
     * ESPECÍFICO a GENÉRICO. Devuelve la categoría canónica o {@code ""} si el
     * texto no matchea ningún token de nutrición. Compartido por las dos rutas
     * de {@link #clasificar(String)}: el portón temprano (gateado por
     * {@link #esContextoNutricion(String)}) y el bloque post-ropa.
     *
     * <p><b>Gainer y Pre-Workout corren ANTES de KW_PROTEINA</b> (cambio deliberado
     * sobre el orden del bloque inline original). Los dos publicitan su contenido de
     * proteína en el título — un mass gainer dice "50g de proteína por porción" — así
     * que el token genérico les ganaba y quedaban archivados como "Proteína". Tener
     * proteína no es ser proteína: cuando la identidad del producto es inequívoca,
     * gana la identidad.</p>
     *
     * <p>BCAA y Colágeno NO se promovieron, y no por olvido: {@code KW_BCAA_SUP} trae
     * "aminoacido", que las etiquetas de whey usan todo el tiempo ("aminoácidos
     * esenciales"), y hay whey fortificada con colágeno. Promoverlos convertiría cada
     * proteína en un BCAA. Un BCAA o un colágeno que NO nombra proteína ya clasificaba
     * bien desde acá abajo.</p>
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
     * ({@code KW_MARCA_CON_PROTEINA_EN_EL_NOMBRE}) antes de resolver la
     * subcategoría de nutrición. Sin esto, quien vende decide la categoría: 30
     * de las 201 filas de "Proteína" eran magnesio, omega 3, vitamina C, taurina
     * y hasta un shaker de 600 ml, todas de "MYPROTEIN" o "Natural Whey"
     * (medido sobre el catálogo vivo, 2026-09-02).
     *
     * <p>Se reemplaza por un espacio, no por vacío: unir los caracteres vecinos
     * fabricaría palabras que el título no dice.</p>
     */
    /**
     * Guard de los Tier B de {@code Proteína Vegetal} e {@code Proteína Isolada}:
     * ¿el texto nombra una proteína, y no sólo un atributo del envase?
     *
     * <p>Los dos Tier B son adjetivos, no sustantivos de producto: "vegano" es
     * un reclamo dietario que llevan cápsulas de magnesio y galletas, e
     * "hidrolizado" es un proceso que el colágeno usa más que la whey. Sin este
     * guard, la salsa de soja de MRS TASTE entraba como proteína vegetal y los
     * 11 colágenos hidrolizados del catálogo como proteína aislada — le robaban
     * las filas a {@code Colágeno}, que corre debajo de {@code KW_PROTEINA} a
     * propósito porque hay whey fortificada con colágeno.</p>
     *
     * <p>Es el mismo patrón que {@link #esContextoBotin}/{@link #esContextoOjota}:
     * un token ambiguo clasifica sólo cuando co-ocurre la señal específica.</p>
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
