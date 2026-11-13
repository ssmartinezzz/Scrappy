package ar.scraper.promos;

import ar.scraper.model.promo.TipoMedio;
import ar.scraper.aggregator.text.AccentStripper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single owner of alias -> canonical medio de pago. Names are the seed of V101 (the test reads
 * the migration, so the two cannot drift). Unknown input resolves to empty: callers pick the generic
 * fallback, a bank is never guessed (CODE-5).
 */
public final class MediosDePago {

    private record Entrada(MedioPago medio, boolean nombreEnTextoLibre, String[] alias) {}

    /** Alias are matched after {@link #normalizar}, so case, accents and punctuation do not matter. */
    private static Entrada e(String nombre, TipoMedio tipo, boolean nombreEnTextoLibre, String... alias) {
        return new Entrada(new MedioPago(nombre, tipo), nombreEnTextoLibre, alias);
    }

    private static Entrada e(String nombre, TipoMedio tipo, String... alias) {
        return e(nombre, tipo, true, alias);
    }

    // nombreEnTextoLibre=false: the bare name is also a common word ("nacion", "ciudad"), so in running
    // text only the longer alias counts; resolver() on a field that holds just the name still works.
    private static final List<Entrada> TABLA = List.of(
            e("Santander", TipoMedio.BANCO, "banco santander", "santander rio", "banco santander rio"),
            e("Galicia", TipoMedio.BANCO, "banco galicia"),
            e("BBVA", TipoMedio.BANCO, "banco bbva", "bbva frances", "banco frances"),
            e("Macro", TipoMedio.BANCO, "banco macro"),
            e("Nación", TipoMedio.BANCO, false, "banco nacion", "banco de la nacion", "banco nacion argentina",
                    "banco de la nacion argentina", "bna"),
            e("Provincia", TipoMedio.BANCO, false, "banco provincia", "banco de la provincia", "bapro",
                    "banco provincia de buenos aires"),
            e("Ciudad", TipoMedio.BANCO, false, "banco ciudad", "banco ciudad de buenos aires"),
            e("ICBC", TipoMedio.BANCO, "banco icbc"),
            e("HSBC", TipoMedio.BANCO, "banco hsbc"),
            e("Patagonia", TipoMedio.BANCO, "banco patagonia"),
            e("Supervielle", TipoMedio.BANCO, "banco supervielle"),
            e("Credicoop", TipoMedio.BANCO, "banco credicoop"),
            e("Hipotecario", TipoMedio.BANCO, "banco hipotecario"),
            e("Comafi", TipoMedio.BANCO, "banco comafi"),
            e("Brubank", TipoMedio.BANCO),
            e("Naranja X", TipoMedio.BANCO, "naranjax", "naranja equis"),
            e("Mercado Pago", TipoMedio.BILLETERA, "mercadopago", "mercadopagopro", "mercadopagooff",
                    "mercado pago pro", "mercado pago off"),
            e("Modo", TipoMedio.BILLETERA, false, "modo payment", "modo pay", "con modo", "app modo", "billetera modo",
                    "desde modo", "via modo", "modo app"),
            e("Ualá", TipoMedio.BILLETERA, "uala bis"),
            e("Cuenta DNI", TipoMedio.BILLETERA),
            e("Personal Pay", TipoMedio.BILLETERA, "personalpay"),
            e("Visa", TipoMedio.TARJETA, "visa electron", "visa debito", "visa debit", "visa credito",
                    "tarjeta visa"),
            e("Mastercard", TipoMedio.TARJETA, "master card", "mastercard debit", "mastercard debito",
                    "debit mastercard", "mastercard credito", "tarjeta mastercard"),
            e("American Express", TipoMedio.TARJETA, "americanexpress", "amex"),
            e("Cabal", TipoMedio.TARJETA, "cabal debito", "tarjeta cabal"),
            e("Naranja", TipoMedio.TARJETA, false, "tarjeta naranja", "naranja credito"),
            e("Maestro", TipoMedio.TARJETA),
            e("Pago Nube", TipoMedio.PASARELA, "pagonube"),
            e("GOcuotas", TipoMedio.PASARELA, "go cuotas"),
            e("Mercado Crédito", TipoMedio.PASARELA, "mercadocredito"),
            e("Transferencia", TipoMedio.MEDIO, "transferencias", "transferencia bancaria", "wire transfer",
                    "bank transfer", "transfer"),
            e("Efectivo", TipoMedio.MEDIO, "pago en efectivo", "cash"),
            e("Tarjeta de crédito", TipoMedio.MEDIO, "tarjetas de credito", "tarjeta credito", "credit card"),
            e("Tarjeta de débito", TipoMedio.MEDIO, "tarjetas de debito", "tarjeta debito", "debit card"));

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    private static final Map<String, MedioPago> POR_ALIAS = new HashMap<>();
    private static final Map<String, MedioPago> EN_TEXTO = new HashMap<>();
    private static final Pattern BUSCADOR;

    static {
        for (Entrada en : TABLA) {
            registrar(POR_ALIAS, normalizar(en.medio().nombre()), en.medio());
            if (en.nombreEnTextoLibre()) registrar(EN_TEXTO, normalizar(en.medio().nombre()), en.medio());
            for (String a : en.alias()) {
                registrar(POR_ALIAS, a, en.medio());
                registrar(EN_TEXTO, a, en.medio());
            }
        }
        List<String> claves = new ArrayList<>(EN_TEXTO.keySet());
        claves.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        StringBuilder alt = new StringBuilder();
        for (String k : claves) {
            if (!alt.isEmpty()) alt.append('|');
            alt.append(Pattern.quote(k));
        }
        BUSCADOR = Pattern.compile("(?<![a-z0-9])(?:" + alt + ")(?![a-z0-9])");
    }

    private static void registrar(Map<String, MedioPago> mapa, String clave, MedioPago medio) {
        MedioPago previo = mapa.put(clave, medio);
        if (previo != null && !previo.equals(medio)) {
            throw new IllegalStateException("alias '" + clave + "' maps to both " + previo + " and " + medio);
        }
    }

    private MediosDePago() {}

    /** Lower-case, accent-free, every run of non-alphanumerics collapsed to one space. */
    static String normalizar(String s) {
        String base = AccentStripper.strip(s.toLowerCase(Locale.ROOT));
        return NO_ALFANUMERICO.matcher(base).replaceAll(" ").trim();
    }

    /** The medio a whole field names ("Visa Electron", "wire_transfer"), or empty if unknown. */
    public static Optional<MedioPago> resolver(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        return Optional.ofNullable(POR_ALIAS.get(normalizar(raw)));
    }

    /** "con modo de pago" is the Spanish noun, not the wallet: the bare word is only a medio after "con", "app"... */
    private static boolean esModoDePago(MedioPago medio, String texto, int fin) {
        return "Modo".equals(medio.nombre()) && texto.startsWith(" de ", fin);
    }

    /** Every distinct medio named in running text, in order of appearance; longest alias wins. */
    public static List<MedioPago> encontrarEn(String texto) {
        if (texto == null || texto.isBlank()) return List.of();
        String normalizado = normalizar(texto);
        Matcher m = BUSCADOR.matcher(normalizado);
        Set<MedioPago> out = new LinkedHashSet<>();
        while (m.find()) {
            MedioPago medio = EN_TEXTO.get(m.group());
            if (!esModoDePago(medio, normalizado, m.end())) out.add(medio);
        }
        return List.copyOf(out);
    }
}
