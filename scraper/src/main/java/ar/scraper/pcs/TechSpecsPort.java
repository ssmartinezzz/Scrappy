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

    record SpecsDeProducto(String url, TechSpecs specs) {}

    /** Idempotent: re-upserting the same url updates its row in place. */
    void upsertSpecs(List<SpecsDeProducto> specs);
}
