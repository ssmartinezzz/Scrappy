package ar.scraper.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * normalize-db-schema-fks-1nf, slice A.4 (V8).
 *
 * <p>The one place that knows how a {@code TIMESTAMPTZ} enters and leaves this
 * codebase. Before V8 every repository carried its own
 * {@code DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")} and bound the
 * result with {@code setString} — nine copies of the same decision, into
 * columns that were TEXT precisely because nobody had made it once.</p>
 *
 * <p><b>Write</b>: {@link #now()} returns an {@link OffsetDateTime} bound with
 * {@code setObject}. A formatted String into a {@code TIMESTAMPTZ} parameter
 * fails outright — pgjdbc types {@code setString} as {@code varchar} and
 * {@code timestamptz = character varying} has no assignment cast, the same way
 * {@code date < character varying} bit slice A.2.</p>
 *
 * <p><b>Read</b>: {@link #iso} renders UTC ISO-8601 to the second
 * ({@code 2026-08-11T20:15:00Z}). This IS a payload change, and a deliberate
 * one: pgjdbc's own {@code getString} on a {@code TIMESTAMPTZ} renders in the
 * JVM's timezone with a two-digit offset ({@code 2026-08-11 17:15:00.936768-03}),
 * which leaks the server's timezone into the API and is not valid ISO 8601 —
 * {@code new Date(...)} returns {@code Invalid Date} for it. Seconds precision
 * matches what these columns actually held; {@code OffsetDateTime.toString()}
 * was rejected because it DROPS zero seconds ({@code 2026-08-11T20:15Z}), so
 * the payload shape would change with the data.</p>
 */
final class Timestamps {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    /** El instante actual, listo para {@code ps.setObject(n, Timestamps.now())}. */
    static OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    /** ISO-8601 UTC al segundo, o {@code null} si la columna es NULL. */
    static String iso(ResultSet rs, String columna) throws SQLException {
        OffsetDateTime valor = rs.getObject(columna, OffsetDateTime.class);
        return valor != null ? ISO_UTC.format(valor) : null;
    }
}
