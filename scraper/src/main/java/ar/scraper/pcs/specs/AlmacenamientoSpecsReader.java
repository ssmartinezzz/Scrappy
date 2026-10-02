package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AlmacenamientoSpecsReader implements LectorDeSpecs {

    private static final Pattern CAPACIDAD = Pattern.compile("^(\\d+)(gb|tb)$");

    /**
     * SSDs enterprise declaran la capacidad en TB decimal ("1.92TB", "3.84TB", "7.68TB") —
     * Tokens.array() la pierde: el punto es un separador como cualquier otro, así que "1.92TB"
     * tokeniza a {@code "1"}+{@code "92tb"}, y el patrón de un solo token de arriba leía "92tb"
     * como 92 TB (94208 GB) en vez de 1.92 TB (1966 GB).
     */
    private static final Pattern CAPACIDAD_DECIMAL_TB = Pattern.compile("(\\d+)\\.(\\d+)\\s*tb\\b");

    @Override
    public String categoria() {
        return "Almacenamiento";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return TechSpecs.builder().capacidadGb(capacidadGb(tokens)).tipoAlmacenamiento(tipo(tokens)).build();
    }

    /**
     * Un disco externo USB no es el disco de la PC que se está armando, así que abstiene la
     * TECNOLOGÍA en vez de declararse NVMe/SSD/HDD.
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
     * "M.2" tokeniza a "m" + "2" (Tokens.de parte en todo no-alfanumérico) — nunca matchear el "2"
     * solo:
     */
    private static boolean tieneM2(Tokens tokens) {
        String[] arr = tokens.array();
        for (int i = 0; i < arr.length - 1; i++) {
            if (arr[i].equals("m") && arr[i + 1].equals("2")) return true;
        }
        return false;
    }

    /**
     * Capacidad: primero la forma decimal en TB (ver {@link #CAPACIDAD_DECIMAL_TB}), y si no hay,
     * un token que ES enteramente dígitos+gb/tb — TB se normaliza a GB ×1024. Ruido real medido que
     * esto tiene que ignorar:
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
