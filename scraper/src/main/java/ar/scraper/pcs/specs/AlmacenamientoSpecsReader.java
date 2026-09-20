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

    @Override
    public String categoria() {
        return "Almacenamiento";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, capacidadGb(tokens), "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA, 0, tipo(tokens));
    }

    private static TipoAlmacenamiento tipo(Tokens tokens) {
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
     * Capacidad: sólo un token que ES enteramente dígitos+gb/tb — TB se
     * normaliza a GB ×1024. Ruido real medido que esto tiene que ignorar:
     * "3500MB/S" (sufijo "mb", no "gb"/"tb"), "2280"/"SN3000" (dígitos sin
     * sufijo o con letras), "SATA III 2.5\"" y "Gen4 x4" (ningún token
     * entero matchea el patrón).
     */
    private static int capacidadGb(Tokens tokens) {
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
