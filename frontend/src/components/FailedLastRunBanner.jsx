import { cn } from '../lib/utils';

const MAX_ERROR = 80;

// scrape_run_site.error can be a whole Playwright stack trace; the banner names the cause only.
function resumenError(error) {
  const texto = String(error);
  const mensaje = /message='([^\n']+)/.exec(texto);
  const linea = (mensaje ? mensaje[1] : texto.split('\n')[0]).trim();
  return linea.length > MAX_ERROR ? `${linea.slice(0, MAX_ERROR - 1)}…` : linea;
}

function etiqueta(fallido) {
  return fallido.error ? `${fallido.sitio} (${resumenError(fallido.error)})` : fallido.sitio;
}

export default function FailedLastRunBanner({ run, busy = false, error = '', onRetry, onDismiss }) {
  if (!run) return null;

  const fallidos = run.fallidos || [];
  const porDeadline = fallidos.filter(f => f.porDeadline);

  return (
    <div
      role="alert"
      className="mb-3 flex flex-col gap-2 rounded-card border border-warning bg-[color-mix(in_srgb,var(--sem-warn)_10%,transparent)] px-4 py-3"
    >
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-[.82rem] font-bold text-t1">⚠ La última corrida dejó sitios sin completar</span>
        {porDeadline.length > 0 && (
          <span className="text-[.72rem] text-t4">{porDeadline.length} por deadline global</span>
        )}
      </div>

      <div className="text-[.75rem] leading-relaxed text-t3">
        {fallidos.map(etiqueta).join(', ')}
      </div>

      {error && <div className="text-[.72rem] text-danger">{error}</div>}

      <div className="flex flex-wrap gap-2">
        <button
          type="button" onClick={onRetry} disabled={busy}
          className={cn('btn-sm border-none bg-primary text-on-accent', busy && 'cursor-not-allowed opacity-60')}
        >
          {busy ? 'Reintentando…' : '↻ Reintentar estos sitios'}
        </button>
        <button
          type="button" onClick={onDismiss} disabled={busy}
          className={cn('btn-sm btn-ghost text-t4', busy && 'cursor-not-allowed opacity-60')}
        >
          Ocultar
        </button>
      </div>
    </div>
  );
}
