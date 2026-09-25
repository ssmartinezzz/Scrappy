package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Mother-only: vetoes a board whose platform (socket) has no CPU candidate
 * in the pool matching the requested gama/marcaCpu (pc-builder-homelab
 * T13). {@code PcBuilder} computes the qualifying set once per armado,
 * before any slot is picked — {@link ContextoDeArmado#socketsConCpuElegible()}
 * — since only it knows the CPU pool; this rule just reads it, same shape as
 * {@link ReglaSocketCooler} reading {@code contexto.motherSpecs()}.
 *
 * <p>Measured against the dev DB (T8b): with gama BAJA requested, the mother
 * ranking alone picked {@code MSI A620M-E PRO DDR5 AM5} first — its chipset
 * tier is closest to the BAJA target — but the low-tier CPUs in the catalog
 * are all AM4/LGA1700, so {@code cpu} came back {@code sinCompatible} at
 * every budget. An armado that never had a chance to succeed.</p>
 *
 * <p>Abstention: an empty set (nothing qualified, or no gama was requested)
 * never vetoes — same fallback as every non-{@code ReglaGama} rule. A mother
 * whose own socket didn't parse is left alone too, for the same reason
 * {@link ReglaSocket} does: with only one side legible there's nothing to
 * assert.</p>
 */
public class ReglaPlataformaConCpu implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        var elegibles = contexto.socketsConCpuElegible();
        if (elegibles.isEmpty() || candidato.socket().isEmpty()) return true;
        return elegibles.contains(candidato.socket());
    }

    @Override
    public String motivo() {
        return "ningún CPU del pool alcanza esta plataforma para la gama pedida";
    }
}
