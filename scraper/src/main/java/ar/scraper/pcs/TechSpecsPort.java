package ar.scraper.pcs;

import java.util.List;

/**
 * Own write path: filled after aggregation, like {@code ml_output}, never through
 * {@code sp_upsert_run}.
 */
public interface TechSpecsPort {

    /**
     * {@code wifi} on {@code producto_tech_specs} is only ever an affirmed true/false for a
     * Motherboard row (NULL otherwise, abstention or "not applicable" — TechSpecsRepository is what
     * tells the two apart, and it needs the category to do it).
     */
    record SpecsDeProducto(String url, String categoria, TechSpecs specs) {

        /** Defaults to {@code ""}, which never equals {@code "Motherboard"}. */
        public SpecsDeProducto(String url, TechSpecs specs) {
            this(url, "", specs);
        }
    }

    /** Idempotent: re-upserting the same url updates its row in place. */
    void upsertSpecs(List<SpecsDeProducto> specs);
}
