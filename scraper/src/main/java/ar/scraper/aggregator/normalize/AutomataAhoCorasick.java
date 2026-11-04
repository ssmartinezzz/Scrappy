package ar.scraper.aggregator.normalize;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AutomataAhoCorasick {

    private static final int RAIZ = 0;
    private static final int SIN_TRANSICION = -1;
    private static final int CONJUNTO_VACIO = 0;

    private final AlfabetoDeClases alfabeto;
    private final int palabrasPorConjunto;
    private final int ancho;
    private final char[] transiciones;
    private final int[] salida;
    private final int[] propia;
    private final short[] profundidad;
    private final long[] conjuntos;

    AutomataAhoCorasick(String[][] grupos) {
        alfabeto = new AlfabetoDeClases(grupos);
        palabrasPorConjunto = (grupos.length + Long.SIZE - 1) / Long.SIZE;
        ancho = alfabeto.cantidad();
        Construccion construccion = new Construccion(grupos, alfabeto, palabrasPorConjunto);
        transiciones = construccion.transicionesCompactas();
        salida = construccion.referenciasDeSalida();
        propia = construccion.referenciasDePropias();
        profundidad = construccion.profundidad();
        conjuntos = construccion.conjuntosAplanados();
    }

    long[] contenidos(String texto) {
        long[] encontrados = new long[palabrasPorConjunto];
        int estado = RAIZ;
        acumular(encontrados, salida[estado]);
        for (int i = 0, n = texto.length(); i < n; i++) {
            estado = transiciones[estado * ancho + alfabeto.clase(texto.charAt(i))];
            int referencia = salida[estado];
            if (referencia != CONJUNTO_VACIO) acumular(encontrados, referencia);
        }
        return encontrados;
    }

    long[] prefijos(String texto) {
        long[] encontrados = new long[palabrasPorConjunto];
        int estado = RAIZ;
        acumular(encontrados, propia[estado]);
        for (int i = 0, n = texto.length(); i < n; i++) {
            estado = transiciones[estado * ancho + alfabeto.clase(texto.charAt(i))];
            if (profundidad[estado] != i + 1) break;
            int referencia = propia[estado];
            if (referencia != CONJUNTO_VACIO) acumular(encontrados, referencia);
        }
        return encontrados;
    }

    private void acumular(long[] encontrados, int referencia) {
        int base = referencia * palabrasPorConjunto;
        for (int i = 0; i < palabrasPorConjunto; i++) encontrados[i] |= conjuntos[base + i];
    }

    private static final class Construccion {

        private final int ancho;
        private final int palabras;
        private final int[] delta;
        private final long[][] propios;
        private final short[] profundidades;
        private final int[] fallo;
        private final long[][] salidas;
        private final List<long[]> pool = new ArrayList<>();
        private final Map<LongBuffer, Integer> referencias = new HashMap<>();
        private int estados = 1;

        Construccion(String[][] grupos, AlfabetoDeClases alfabeto, int palabras) {
            this.ancho = alfabeto.cantidad();
            this.palabras = palabras;
            int maximo = 1;
            for (String[] grupo : grupos) for (String palabra : grupo) maximo += palabra.length();
            delta = new int[maximo * ancho];
            Arrays.fill(delta, SIN_TRANSICION);
            propios = new long[maximo][];
            profundidades = new short[maximo];
            fallo = new int[maximo];
            salidas = new long[maximo][];
            pool.add(new long[palabras]);
            referencias.put(LongBuffer.wrap(pool.get(CONJUNTO_VACIO)), CONJUNTO_VACIO);
            for (int grupo = 0; grupo < grupos.length; grupo++) {
                for (String palabra : grupos[grupo]) insertar(palabra, grupo, alfabeto);
            }
            enlazarFallos();
        }

        private void insertar(String palabra, int grupo, AlfabetoDeClases alfabeto) {
            int estado = RAIZ;
            for (int i = 0; i < palabra.length(); i++) {
                int posicion = estado * ancho + alfabeto.clase(palabra.charAt(i));
                if (delta[posicion] == SIN_TRANSICION) {
                    delta[posicion] = estados;
                    profundidades[estados] = (short) (profundidades[estado] + 1);
                    estados++;
                }
                estado = delta[posicion];
            }
            if (propios[estado] == null) propios[estado] = new long[palabras];
            propios[estado][grupo >>> 6] |= 1L << grupo;
        }

        private void enlazarFallos() {
            int[] cola = new int[estados];
            int inicio = 0;
            int fin = 0;
            salidas[RAIZ] = propios[RAIZ];
            for (int clase = 0; clase < ancho; clase++) {
                int hijo = delta[clase];
                if (hijo == SIN_TRANSICION) {
                    delta[clase] = RAIZ;
                } else {
                    fallo[hijo] = RAIZ;
                    cola[fin++] = hijo;
                }
            }
            while (inicio < fin) {
                int estado = cola[inicio++];
                salidas[estado] = unir(propios[estado], salidas[fallo[estado]]);
                for (int clase = 0; clase < ancho; clase++) {
                    int hijo = delta[estado * ancho + clase];
                    int viaFallo = delta[fallo[estado] * ancho + clase];
                    if (hijo == SIN_TRANSICION) {
                        delta[estado * ancho + clase] = viaFallo;
                    } else {
                        fallo[hijo] = viaFallo;
                        cola[fin++] = hijo;
                    }
                }
            }
        }

        private long[] unir(long[] propio, long[] heredado) {
            if (propio == null) return heredado;
            if (heredado == null) return propio;
            long[] unido = new long[palabras];
            for (int i = 0; i < palabras; i++) unido[i] = propio[i] | heredado[i];
            return unido;
        }

        char[] transicionesCompactas() {
            char[] compactas = new char[estados * ancho];
            for (int i = 0; i < compactas.length; i++) {
                if (delta[i] > Character.MAX_VALUE) throw new IllegalStateException("too many states: " + estados);
                compactas[i] = (char) delta[i];
            }
            return compactas;
        }

        int[] referenciasDeSalida() {
            return referenciasDe(salidas);
        }

        int[] referenciasDePropias() {
            return referenciasDe(propios);
        }

        short[] profundidad() {
            return Arrays.copyOf(profundidades, estados);
        }

        long[] conjuntosAplanados() {
            long[] aplanados = new long[pool.size() * palabras];
            for (int i = 0; i < pool.size(); i++) System.arraycopy(pool.get(i), 0, aplanados, i * palabras, palabras);
            return aplanados;
        }

        private int[] referenciasDe(long[][] porEstado) {
            int[] resultado = new int[estados];
            for (int estado = 0; estado < estados; estado++) {
                long[] conjunto = porEstado[estado];
                resultado[estado] = conjunto == null ? CONJUNTO_VACIO : referenciaDe(conjunto);
            }
            return resultado;
        }

        private int referenciaDe(long[] conjunto) {
            return referencias.computeIfAbsent(LongBuffer.wrap(conjunto), clave -> {
                pool.add(conjunto);
                return pool.size() - 1;
            });
        }
    }
}
