package ar.scraper.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A formatted String into a {@code TIMESTAMPTZ} parameter fails outright — pgjdbc types
 * {@code setString} as {@code varchar} and {@code timestamptz = character varying} has no
 * assignment cast, the same way {@code date < character varying} bit slice A.2.
 */
final class Timestamps {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    /** ISO-8601 UTC al segundo, o {@code null} si la columna es NULL. */
    static String iso(ResultSet rs, String columna) throws SQLException {
        OffsetDateTime valor = rs.getObject(columna, OffsetDateTime.class);
        return valor != null ? ISO_UTC.format(valor) : null;
    }
}
