package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads storage technology + capacity off an Almacenamiento part's name.
 * New in pc-builder-gama T3b — phase 1 ({@code TechSpecsParser}) abstained
 * this category entirely.
 */
public final class AlmacenamientoSpecsReader implements LectorDeSpecs {

    private static final Pattern CAPACIDAD = Pattern.compile("^(\\d+)(gb|tb)$");

    /**
     * SSDs enterprise declaran la capacidad en TB decimal ("1.92TB",
     * "3.84TB", "7.68TB") — Tokens.array() la pierde: el punto es un
     * separador como cualquier otro, así que "1.92TB" tokeniza a
     * {@code "1"}+{@code "92tb"}, y el patrón de un solo token de arriba
     * leía "92tb" como 92 TB (94208 GB) en vez de 1.92 TB (1966 GB). Corre
     * sobre {@link Tokens#original()}, que todavía tiene el punto — {@code
     * \s*} admite tanto "1.92TB" pegado como "1.92 TB" con espacio, y el
     * "tb" tiene que seguir inmediatamente (sin otra palabra en el medio),
     * así que no puede confundir un "2.5\"" de form factor con una fracción
     * de capacidad aunque después, en cualquier lugar del nombre, aparezca
     * un token en TB.
     */
    private static final Pattern CAPACIDAD_DECIMAL_TB = Pattern.compile("(\\d+)\\.(\\d+)\\s*tb\\b");

    @Override
    public String categoria() {
        return "Almacenamiento";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, capacidadGb(tokens), "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA, 0, tipo(tokens));
    }

    /**
     * Un disco externo USB no es el disco de la PC que se está armando, así
     * que abstiene la TECNOLOGÍA en vez de declararse NVMe/SSD/HDD. Se
     * resuelve por abstención y no por una regla de veto nueva, que es la
     * misma política que los pendrives: la abstención es el último escalón
     * del eje, así que se hunde solo y sigue siendo elegible como último
     * recurso si no hay nada más.
     *
     * <p>Medido sobre la dev DB (2026-09-22): 18 de las 266 filas activas de
     * Almacenamiento son externas, y {@code externo}/{@code externa} sola las
     * cubre a las 18 — {@code portable}/{@code portatil} no suma ninguna por
     * su cuenta, así que no entran al vocabulario y no pueden traer falsos
     * positivos. Con un piso de capacidad pedido, un "HD HDD EXTERNO 4TB
     * SEAGATE PORTABLE USB 3.0" le ganaba el slot a los discos internos.</p>
     */
    private static boolean esExterno(Tokens tokens) {
        return tokens.has("externo") || tokens.has("externa");
    }

    private static TipoAlmacenamiento tipo(Tokens tokens) {
        if (esExterno(tokens)) return TipoAlmacenamiento.DESCONOCIDO;
        if (tokens.has("nvme") || tieneM2(tokens)) return TipoAlmacenamiento.NVME;
        if (tokens.has("ssd")) return TipoAlmacenamiento.SSD;
        if (tokens.has("hdd")) return TipoAlmacenamiento.HDD;
        String padded = tokens.padded();
        if (padded.contains(" disco duro ") || padded.contains(" disco rigido ")) return TipoAlmacenamiento.HDD;
        return TipoAlmacenamiento.DESCONOCIDO;
    }

    /**
     * "M.2" tokeniza a "m" + "2" (Tokens.de parte en todo no-alfanumérico) —
     * nunca matchear el "2" solo: "Gen4 x4"/"PCIe 4.0"/un form factor como
     * "2280" también dejan un "2" o un "4" sueltos dando vueltas. El PAR
     * exacto de tokens contiguos "m","2" es la única forma segura de leer
     * NVMe por form factor en vez de por la palabra explícita.
     */
    private static boolean tieneM2(Tokens tokens) {
        String[] arr = tokens.array();
        for (int i = 0; i < arr.length - 1; i++) {
            if (arr[i].equals("m") && arr[i + 1].equals("2")) return true;
        }
        return false;
    }

    /**
     * Capacidad: primero la forma decimal en TB (ver {@link
     * #CAPACIDAD_DECIMAL_TB}), y si no hay, un token que ES enteramente
     * dígitos+gb/tb — TB se normaliza a GB ×1024. Ruido real medido que esto
     * tiene que ignorar: "3500MB/S" (sufijo "mb", no "gb"/"tb"),
     * "2280"/"SN3000" (dígitos sin sufijo o con letras), "SATA III 2.5\"" y
     * "Gen4 x4" (ningún token entero matchea el patrón).
     */
    private static int capacidadGb(Tokens tokens) {
        Matcher decimal = CAPACIDAD_DECIMAL_TB.matcher(tokens.original());
        if (decimal.find()) {
            double tb = Double.parseDouble(decimal.group(1) + "." + decimal.group(2));
            return (int) Math.round(tb * 1024);
        }
        for (String t : tokens.array()) {
            Matcher m = CAPACIDAD.matcher(t);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                return m.group(2).equals("tb") ? n * 1024 : n;
            }
        }
        return 0;
    }
}
