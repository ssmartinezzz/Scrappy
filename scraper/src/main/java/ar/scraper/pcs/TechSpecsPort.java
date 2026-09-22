package ar.scraper.pcs;

import java.util.List;

/**
 * Persistence for the tech specs read off each {@code rubro=tecnologia}
 * product's name, one row per url ({@code producto_tech_specs}, V35). Own
 * write path (D11): filled after aggregation, like {@code ml_output}, never
 * through {@code sp_upsert_run}. {@code pcs/} never names {@code db/} —
 * implemented by a package-private {@code @Repository} there, same molde as
 * the other ports in this area.
 */
public interface TechSpecsPort {

    /**
     * {@code categoria} was added in pc-builder-deep-taxonomy T5b: {@code
     * wifi} on {@code producto_tech_specs} is only ever an affirmed
     * true/false for a Motherboard row (NULL otherwise, abstention or "not
     * applicable" — TechSpecsRepository is what tells the two apart, and it
     * needs the category to do it).
     */
    record SpecsDeProducto(String url, String categoria, TechSpecs specs) {

        /**
         * Pre-T5b shape (2 args, the record's canonical constructor before
         * {@code categoria} was added): kept so every existing caller/test
         * keeps compiling untouched — refactor contract, CODE-2. Defaults to
         * {@code ""}, which never equals {@code "Motherboard"}.
         */
        public SpecsDeProducto(String url, TechSpecs specs) {
            this(url, "", specs);
        }
    }

    /** Idempotent: re-upserting the same url updates its row in place. */
    void upsertSpecs(List<SpecsDeProducto> specs);
}
