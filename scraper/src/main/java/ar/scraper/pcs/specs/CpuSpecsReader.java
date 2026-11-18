package ar.scraper.pcs.specs;

import ar.scraper.model.SocketDeNombre;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

import java.util.regex.Matcher;

public final class CpuSpecsReader implements LectorDeSpecs {

    private final TablaDeRendimiento tabla;
    private final TablaDeRendimiento tablaGaming;

    public CpuSpecsReader() {
        this(TablaDeRendimientoCsv.cpu());
    }

    public CpuSpecsReader(TablaDeRendimiento tabla) {
        this(tabla, TablaDeRendimientoCsv.cpuGaming());
    }

    public CpuSpecsReader(TablaDeRendimiento tabla, TablaDeRendimiento tablaGaming) {
        this.tabla = tabla;
        this.tablaGaming = tablaGaming;
    }

    @Override
    public String categoria() {
        return "CPU";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        String modelo = ClaveDeModelo.cpu(tokens);
        return TechSpecs.builder()
                .socket(cpuSocket(tokens))
                .gama(gama(tokens))
                .marcaChip(marcaChip(tokens))
                .generacion(generacion(tokens))
                .nivel(nivel(tokens))
                .modelo(modelo)
                .rendimiento(modelo.isEmpty() ? 0 : tabla.puntaje(modelo))
                .rendimientoGaming(modelo.isEmpty() ? 0 : tablaGaming.puntaje(modelo))
                .build();
    }

    private static String cpuSocket(Tokens tokens) {
        return SocketDeNombre.cpu(tokens.array(), tokens.padded());
    }

    /** Package-visible (not private): */
    static Gama gama(Tokens tokens) {
        for (String t : tokens.array()) {
            if (t.endsWith("x3d")) return Gama.ALTA; // el sufijo gana solo, sin importar la familia
        }

        String padded = tokens.padded();
        if (tokens.has("i9") || tokens.has("i7")
                || padded.contains(" ryzen 9 ") || padded.contains(" ryzen 7 ")
                || padded.contains(" ultra 9 ") || padded.contains(" ultra 7 ")) {
            return Gama.ALTA;
        }
        if (tokens.has("i5") || padded.contains(" ryzen 5 ") || padded.contains(" ultra 5 ")) {
            return Gama.MEDIA;
        }
        if (tokens.has("i3") || padded.contains(" ryzen 3 ") || padded.contains(" ultra 3 ")
                || tokens.has("athlon") || tokens.has("celeron") || tokens.has("pentium")) {
            return Gama.BAJA;
        }
        // Xeon es de servidor: no mapea a esta escala de escritorio, queda DESCONOCIDA salvo
        // evidencia medida que diga lo contrario.
        return Gama.DESCONOCIDA;
    }

    /** Package-visible (not private): */
    static String marcaChip(Tokens tokens) {
        if (tokens.has("intel") || tokens.has("i3") || tokens.has("i5") || tokens.has("i7") || tokens.has("i9")
                || tokens.has("ultra") || tokens.has("celeron") || tokens.has("pentium")) {
            return "INTEL";
        }
        if (tokens.has("amd") || tokens.has("ryzen") || tokens.has("athlon")) return "AMD";
        return "";
    }

    /**
     * Athlon/Celeron/Pentium tienen gama BAJA pero no juegan en esta escala: abstienen, y el eje
     * los manda al final.
     */
    /** Package-visible (not private): */
    static int nivel(Tokens tokens) {
        String padded = tokens.padded();
        if (tokens.has("i9") || padded.contains(" ryzen 9 ") || padded.contains(" ultra 9 ")) return 9;
        if (tokens.has("i7") || padded.contains(" ryzen 7 ") || padded.contains(" ultra 7 ")) return 7;
        if (tokens.has("i5") || padded.contains(" ryzen 5 ") || padded.contains(" ultra 5 ")) return 5;
        if (tokens.has("i3") || padded.contains(" ryzen 3 ") || padded.contains(" ultra 3 ")) return 3;
        return 0;
    }

    private static int generacion(Tokens tokens) {
        String padded = tokens.padded();

        Matcher ryzen = SocketDeNombre.RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) - '0';

        if (SocketDeNombre.CORE_ULTRA_MODEL.matcher(padded).find()) return 15;

        Matcher core = SocketDeNombre.CORE_MODEL.matcher(padded);
        if (core.find()) return Integer.parseInt(core.group(1));

        Matcher core1200 = SocketDeNombre.CORE_MODEL_1200.matcher(padded);
        if (core1200.find()) return Integer.parseInt(core1200.group(1));

        Matcher core1151 = SocketDeNombre.CORE_MODEL_1151.matcher(padded);
        if (core1151.find()) return Integer.parseInt(core1151.group(1));

        return 0;
    }
}
