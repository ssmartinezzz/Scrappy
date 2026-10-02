package ar.scraper.web;

import ar.scraper.api.ApiException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class Params {

    private Params() {}

    static Stream<String> tokens(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank());
    }

    static Set<String> setOrEmpty(String raw) {
        return StringUtils.isBlank(raw) ? Set.of() : tokens(raw).collect(Collectors.toSet());
    }

    static List<String> listOrEmpty(String raw) {
        return StringUtils.isBlank(raw) ? List.of() : tokens(raw).collect(Collectors.toList());
    }

    static String nombreObligatorio(Map<String, Object> body) {
        String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
        if (nombre.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "nombre es obligatorio");
        }
        return nombre;
    }
}
