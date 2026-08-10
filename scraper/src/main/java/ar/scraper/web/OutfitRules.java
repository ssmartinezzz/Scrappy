package ar.scraper.web;

import ar.scraper.model.Product;

/**
 * Pure eligibility and mapping rules shared by the outfit assembler and the
 * budget builder.
 *
 * <p>Extracted verbatim from {@link OutfitService} (backlog A3). Like
 * {@link FeedbackModels} and {@link ProductJson}, these live on their own
 * because MORE THAN ONE collaborator needs them: {@code armar} and
 * {@code armarPorCategorias} both filter by genero, both apply the style gate
 * and both build {@code SlotPick}s. All three are pure functions of their
 * arguments, which is why they are static.</p>
 */
final class OutfitRules {

    private OutfitRules() {}

    static boolean generoElegible(Product p, String generoSolicitado) {
        String g = p.genero() != null ? p.genero().trim() : "";
        if ("infantil".equalsIgnoreCase(g)) return false; // nunca en el armador, ni pidiendo unisex
        if (g.isEmpty()) return true;
        if ("unisex".equalsIgnoreCase(g)) return true;
        if (generoSolicitado == null || generoSolicitado.isBlank()) return true; // sin genero pedido: todo elegible
        if ("unisex".equalsIgnoreCase(generoSolicitado)) return true; // pedido unisex: todo elegible
        return g.equalsIgnoreCase(generoSolicitado);
    }

    static boolean pasaEstiloGate(Product p, String slot, String estilo) {
        boolean esRopaGateada = slot.startsWith(OutfitService.SLOT_TORSO)
                || OutfitService.SLOT_PIERNAS.equals(slot);
        if (!esRopaGateada) return true;
        if ("casual".equalsIgnoreCase(estilo)) return !p.gymrat();
        return p.gymrat(); // default / "gym"
    }

    static OutfitService.SlotPick toSlotPick(String slot, Product p) {
        String img = p.imagenUrl() != null ? p.imagenUrl() : "";
        if (img.startsWith("//")) img = "https:" + img;
        return new OutfitService.SlotPick(
                slot,
                p.sitio() != null ? p.sitio() : "",
                p.nombre() != null ? p.nombre() : "",
                p.precio(),
                p.url() != null ? p.url() : "",
                img,
                p.categoria() != null ? p.categoria() : "",
                p.marca() != null ? p.marca() : "");
    }
}
