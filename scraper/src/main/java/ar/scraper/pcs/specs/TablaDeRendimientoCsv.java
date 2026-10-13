package ar.scraper.pcs.specs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code clave,puntaje,muestras} tables under {@code pcs/benchmarks/}. A missing resource abstains on
 * every key, so the ladder decides as if there were no benchmarks; a malformed one fails at load.
 */
public final class TablaDeRendimientoCsv implements TablaDeRendimiento {

    private static final String ENCABEZADO = "clave,puntaje,muestras";
    private static final Pattern CLAVE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final Map<String, Integer> puntajes;

    private TablaDeRendimientoCsv(Map<String, Integer> puntajes) {
        this.puntajes = Map.copyOf(puntajes);
    }

    public static TablaDeRendimiento cpu() {
        return Cpu.TABLA;
    }

    public static TablaDeRendimiento cpuGaming() {
        return CpuGaming.TABLA;
    }

    public static TablaDeRendimiento gpu() {
        return Gpu.TABLA;
    }

    @Override
    public int puntaje(String clave) {
        return clave == null ? 0 : puntajes.getOrDefault(clave, 0);
    }

    static TablaDeRendimiento desdeRecurso(String recurso) {
        InputStream in = TablaDeRendimientoCsv.class.getClassLoader().getResourceAsStream(recurso);
        if (in == null) return new TablaDeRendimientoCsv(Map.of());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return leer(reader, recurso);
        } catch (IOException e) {
            throw new UncheckedIOException(recurso, e);
        }
    }

    static TablaDeRendimiento leer(BufferedReader reader, String origen) {
        try {
            if (!ENCABEZADO.equals(reader.readLine())) throw invalido(origen, 1, "se esperaba '" + ENCABEZADO + "'");
            Map<String, Integer> puntajes = new HashMap<>();
            int numero = 1;
            for (String linea = reader.readLine(); linea != null; linea = reader.readLine()) {
                numero++;
                if (linea.isBlank()) continue;
                String[] campos = linea.split(",", -1);
                if (campos.length != 3) throw invalido(origen, numero, "se esperaban 3 campos");
                if (!CLAVE.matcher(campos[0]).matches()) throw invalido(origen, numero, "clave no canónica: " + campos[0]);
                int puntaje = entero(campos[1], origen, numero);
                if (puntaje <= 0) throw invalido(origen, numero, "puntaje debe ser > 0");
                if (entero(campos[2], origen, numero) < 0) throw invalido(origen, numero, "muestras debe ser >= 0");
                if (puntajes.put(campos[0], puntaje) != null) {
                    throw invalido(origen, numero, "clave duplicada: " + campos[0]);
                }
            }
            return new TablaDeRendimientoCsv(puntajes);
        } catch (IOException e) {
            throw new UncheckedIOException(origen, e);
        }
    }

    private static int entero(String campo, String origen, int numero) {
        try {
            return Integer.parseInt(campo);
        } catch (NumberFormatException e) {
            throw invalido(origen, numero, "no es un entero: " + campo);
        }
    }

    private static IllegalArgumentException invalido(String origen, int numero, String motivo) {
        return new IllegalArgumentException(origen + ":" + numero + ": " + motivo);
    }

    private static final class Cpu {
        static final TablaDeRendimiento TABLA = desdeRecurso("pcs/benchmarks/cpu.csv");
    }

    private static final class CpuGaming {
        static final TablaDeRendimiento TABLA = desdeRecurso("pcs/benchmarks/cpu_gaming.csv");
    }

    private static final class Gpu {
        static final TablaDeRendimiento TABLA = desdeRecurso("pcs/benchmarks/gpu.csv");
    }
}
