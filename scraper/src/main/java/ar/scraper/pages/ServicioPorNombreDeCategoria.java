package ar.scraper.pages;

import java.util.regex.Pattern;

public final class ServicioPorNombreDeCategoria implements CriterioDeServicio {

    private static final Pattern SERVICIO = Pattern.compile(
            "^\\s*(instalaci[oó]n(es)?|armados?|servicios?|colocaci[oó]n)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Override
    public boolean esServicio(String nombreCategoria) {
        return nombreCategoria != null && SERVICIO.matcher(nombreCategoria).find();
    }
}
