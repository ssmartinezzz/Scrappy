package ar.scraper.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TalleOrder {

    private TalleOrder() {}

    public static Map<String, Long> sortTalles(Map<String, Long> talles) {
        List<String> orden = List.of("XS","S","M","L","XL","XXL","XXXL","3XL","4XL");
        List<String> conocidos = new ArrayList<>(), numericos = new ArrayList<>(), resto = new ArrayList<>();
        for (String t : talles.keySet()) {
            String u = t.toUpperCase();
            if (orden.contains(u)) conocidos.add(t);
            else if (t.matches("\\d+(\\.\\d+)?")) numericos.add(t);
            else resto.add(t);
        }
        conocidos.sort(Comparator.comparingInt(t -> { int i = orden.indexOf(t.toUpperCase()); return i >= 0 ? i : 999; }));
        numericos.sort(Comparator.comparingDouble(Double::parseDouble));
        resto.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, Long> result = new LinkedHashMap<>();
        for (String t : conocidos) result.put(t, talles.get(t));
        for (String t : numericos) result.put(t, talles.get(t));
        for (String t : resto)     result.put(t, talles.get(t));
        return result;
    }
}
