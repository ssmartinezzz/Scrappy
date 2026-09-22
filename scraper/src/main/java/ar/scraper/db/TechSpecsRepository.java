package ar.scraper.db;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TechSpecsPort;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;

/**
 * Persistence for {@code producto_tech_specs} (V35): the specs {@code
 * TechSpecsIndexer} parses off each tech product's name, one row per url.
 * Every {@code *_id} is resolved from its lookup by name in the same
 * statement — same molde as {@code PreferenciaArmadorRepository}'s
 * {@code (SELECT id FROM gama WHERE nombre = ?)} — rather than caching ids
 * in Java, so the six lookups stay the single source of the vocabulary.
 */
@Repository
class TechSpecsRepository implements TechSpecsPort {

    private static final Logger LOG = LoggerFactory.getLogger(TechSpecsRepository.class);

    private static final String SQL = """
            INSERT INTO producto_tech_specs (
                url, socket_id, ddr_id, form_factor_id, tipo_memoria_id,
                certificacion_id, gama_id, tipo_almacenamiento_id,
                watts, capacidad_gb, velocidad_mhz,
                marca_chip_id, chipset_tier_id, tipo_cooler_id, generacion, modulos, wifi,
                actualizado_at
            ) VALUES (
                ?,
                (SELECT id FROM socket WHERE nombre = ?),
                (SELECT id FROM ddr WHERE nombre = ?),
                (SELECT id FROM form_factor WHERE nombre = ?),
                (SELECT id FROM tipo_memoria WHERE nombre = ?),
                (SELECT id FROM certificacion WHERE nombre = ?),
                (SELECT id FROM gama WHERE nombre = ?),
                (SELECT id FROM tipo_almacenamiento WHERE nombre = ?),
                ?, ?, ?,
                (SELECT id FROM marca_chip WHERE nombre = ?),
                (SELECT id FROM chipset_tier WHERE nombre = ?),
                (SELECT id FROM tipo_cooler WHERE nombre = ?),
                ?, ?, ?,
                now()
            )
            ON CONFLICT (url) DO UPDATE SET
                socket_id              = EXCLUDED.socket_id,
                ddr_id                 = EXCLUDED.ddr_id,
                form_factor_id         = EXCLUDED.form_factor_id,
                tipo_memoria_id        = EXCLUDED.tipo_memoria_id,
                certificacion_id       = EXCLUDED.certificacion_id,
                gama_id                = EXCLUDED.gama_id,
                tipo_almacenamiento_id = EXCLUDED.tipo_almacenamiento_id,
                watts                  = EXCLUDED.watts,
                capacidad_gb           = EXCLUDED.capacidad_gb,
                velocidad_mhz          = EXCLUDED.velocidad_mhz,
                marca_chip_id          = EXCLUDED.marca_chip_id,
                chipset_tier_id        = EXCLUDED.chipset_tier_id,
                tipo_cooler_id         = EXCLUDED.tipo_cooler_id,
                generacion             = EXCLUDED.generacion,
                modulos                = EXCLUDED.modulos,
                wifi                   = EXCLUDED.wifi,
                actualizado_at         = now()
            """;

    /** {@code tierChipset}'s int scale (T3b/T4c, pc-builder-deep-taxonomy) — 0 is abstention, never a row. */
    private static String nombreDeTierChipset(int tierChipset) {
        return switch (tierChipset) {
            case 1 -> "X_Z";
            case 2 -> "B";
            case 3 -> "A_H";
            default -> null;
        };
    }

    private final DataSource dataSource;

    TechSpecsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void upsertSpecs(List<SpecsDeProducto> specs) {
        if (specs == null || specs.isEmpty()) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL)) {
            for (SpecsDeProducto s : specs) {
                bind(ps, s.url(), s.categoria(), s.specs());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando {} fila(s) de tech specs: {}", specs.size(), e.getMessage());
        }
    }

    private void bind(PreparedStatement ps, String url, String categoria, TechSpecs specs) throws java.sql.SQLException {
        ps.setString(1, url);
        setNullableString(ps, 2, blank(specs.socket()));
        setNullableString(ps, 3, blank(specs.ddr()));
        setNullableString(ps, 4, blank(specs.formFactor()));
        setNullableString(ps, 5, blank(specs.tipoMemoria()));
        setNullableString(ps, 6, specs.certificacion() == Certificacion.NINGUNA ? null : specs.certificacion().name());
        setNullableString(ps, 7, specs.gama() == Gama.DESCONOCIDA ? null : GamaMapeo.nombreDeGama(specs.gama()));
        setNullableString(ps, 8, specs.tipoAlmacenamiento() == TipoAlmacenamiento.DESCONOCIDO
                ? null : specs.tipoAlmacenamiento().name());
        setNullableInt(ps, 9, specs.watts());
        setNullableInt(ps, 10, specs.capacidadGb());
        setNullableInt(ps, 11, specs.velocidadMhz());
        setNullableString(ps, 12, blank(specs.marcaChip()));
        setNullableString(ps, 13, nombreDeTierChipset(specs.tierChipset()));
        setNullableString(ps, 14, specs.tipoCooler() == TipoCooler.DESCONOCIDO ? null : specs.tipoCooler().name());
        setNullableInt(ps, 15, specs.generacion());
        setNullableInt(ps, 16, specs.modulos());
        // wifi (D2's exception): only a Motherboard row gets an affirmed true/false —
        // every other reader's false is a default, not an assertion (T5b).
        if ("Motherboard".equals(categoria)) {
            ps.setBoolean(17, specs.wifi());
        } else {
            ps.setNull(17, Types.BOOLEAN);
        }
    }

    private static String blank(String valor) {
        return (valor == null || valor.isBlank()) ? null : valor;
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** {@code 0} is TechSpecs's own abstention centinela — never written as a value. */
    private static void setNullableInt(PreparedStatement ps, int index, int value) throws java.sql.SQLException {
        if (value == 0) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
