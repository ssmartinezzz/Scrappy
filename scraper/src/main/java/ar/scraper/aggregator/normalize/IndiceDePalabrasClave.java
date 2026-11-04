package ar.scraper.aggregator.normalize;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class IndiceDePalabrasClave {

    private static final int SIN_ID = -1;
    private static final Map<String[], Integer> IDS_POR_ARRAY = new IdentityHashMap<>();
    private static final AutomataAhoCorasick AUTOMATA;

    static {
        List<Field> campos = Arrays.stream(GarmentTaxonomy.class.getFields())
            .filter(IndiceDePalabrasClave::esArrayDePalabrasClave)
            .sorted(Comparator.comparing(Field::getName))
            .toList();
        String[][] grupos = new String[campos.size()][];
        int cantidad = 0;
        for (Field campo : campos) {
            String[] palabras = valorDe(campo);
            if (palabras != null && !IDS_POR_ARRAY.containsKey(palabras)) {
                IDS_POR_ARRAY.put(palabras, cantidad);
                grupos[cantidad++] = palabras;
            }
        }
        AUTOMATA = new AutomataAhoCorasick(Arrays.copyOf(grupos, cantidad));
    }

    private IndiceDePalabrasClave() {}

    static int idDe(String[] palabras) {
        Integer id = IDS_POR_ARRAY.get(palabras);
        return id == null ? SIN_ID : id;
    }

    static long[] arraysContenidosEn(String texto) {
        return AUTOMATA.contenidos(texto);
    }

    static long[] arraysQueEmpiezanEl(String texto) {
        return AUTOMATA.prefijos(texto);
    }

    static boolean marcado(long[] arrays, int id) {
        return (arrays[id >>> 6] & (1L << id)) != 0;
    }

    private static boolean esArrayDePalabrasClave(Field campo) {
        int modificadores = campo.getModifiers();
        return Modifier.isStatic(modificadores) && Modifier.isFinal(modificadores)
            && campo.getType() == String[].class;
    }

    private static String[] valorDe(Field campo) {
        try {
            return (String[]) campo.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + campo, e);
        }
    }
}
