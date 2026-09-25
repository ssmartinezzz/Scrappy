package ar.scraper.pcs;

/**
 * The build profile {@link PcBuilder#armar} assembles for — a different
 * axis from {@link Gama} (D3, odd/tasks/pc-builder-homelab.md). {@code
 * GAMING} is today's desktop/gamer build, byte-for-byte unchanged, and the
 * default everywhere a caller doesn't say otherwise. {@code HOMELAB} swaps
 * the storage-heavy slots in (D4) and, combined with {@code
 * tamanioGabinete=MINI}, collapses the whole tower into a single {@code
 * Mini PC} pick (D6).
 *
 * <p>Unlike {@link Gama}, there is no abstention sentinel here: a build
 * always has a concrete {@code Uso}, never "couldn't read one off a name" —
 * this is what the CALLER asked for, not something parsed off a product.</p>
 */
public enum Uso {
    GAMING, HOMELAB
}
