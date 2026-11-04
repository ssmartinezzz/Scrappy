package ar.scraper.aggregator.normalize;

import java.util.Arrays;
import java.util.TreeSet;

final class AlfabetoDeClases {

    private static final int LIMITE_ASCII = 128;
    private static final int MAXIMO_DE_CLASES = 256;

    private final byte[] clasesAscii = new byte[LIMITE_ASCII];
    private final char[] extras;
    private final byte[] clasesExtras;
    private final int cantidad;

    AlfabetoDeClases(String[][] grupos) {
        TreeSet<Character> usados = new TreeSet<>();
        for (String[] grupo : grupos) {
            for (String palabra : grupo) {
                for (int i = 0; i < palabra.length(); i++) usados.add(palabra.charAt(i));
            }
        }
        if (usados.size() >= MAXIMO_DE_CLASES) {
            throw new IllegalStateException("alphabet too large: " + usados.size());
        }
        extras = new char[(int) usados.stream().filter(c -> c >= LIMITE_ASCII).count()];
        clasesExtras = new byte[extras.length];
        int clase = 1;
        int siguienteExtra = 0;
        for (char c : usados) {
            if (c < LIMITE_ASCII) {
                clasesAscii[c] = (byte) clase;
            } else {
                extras[siguienteExtra] = c;
                clasesExtras[siguienteExtra++] = (byte) clase;
            }
            clase++;
        }
        cantidad = usados.size() + 1;
    }

    int cantidad() {
        return cantidad;
    }

    int clase(char c) {
        if (c < LIMITE_ASCII) return clasesAscii[c] & 0xFF;
        int posicion = Arrays.binarySearch(extras, c);
        return posicion < 0 ? 0 : clasesExtras[posicion] & 0xFF;
    }
}
