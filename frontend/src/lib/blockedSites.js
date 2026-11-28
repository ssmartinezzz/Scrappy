// A store answering 403/429 refuses our egress IP; retrying minutes later fails the same way.
// The backend writes "bloqueado por la tienda (HTTP 403)" (SiteYieldGuard.MARCA_BLOQUEO), but the
// raw status is matched too so older runs and other error shapes classify the same.
const BLOQUEO = /\bHTTP (?:403|429)\b|bloqueado por la tienda/i;

export function esBloqueo(fallido) {
  return BLOQUEO.test(String(fallido?.error ?? ''));
}

/** Splits failed sites into those the store blocked and those worth retrying. */
export function clasificarFallidos(fallidos) {
  const lista = fallidos || [];
  return {
    bloqueados: lista.filter(esBloqueo),
    reintentables: lista.filter(f => !esBloqueo(f)),
  };
}
