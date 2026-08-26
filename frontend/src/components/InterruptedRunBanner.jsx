// scrape-run-persistence-and-resume, slice 6. Presentational only: what a dead
// process left open, and the two things an ADMIN can do about it.
import { formatFechaHora } from '../lib/fechas';
import { cn } from '../lib/utils';

export default function InterruptedRunBanner({ run, busy = false, error = '', onRetomar, onDismiss }) {
  if (!run) return null;

  const salteados = run.salteados || [];
  const pendientes = run.pendientes || [];
  const atendidos = run.atendidos || [];

  return (
    <div
      role="alert"
      className="mb-3 flex flex-col gap-2 rounded-card border border-warning bg-[color-mix(in_srgb,var(--sem-warn)_10%,transparent)] px-4 py-3"
    >
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-[.82rem] font-bold text-t1">
          ⚠ Quedó una corrida sin terminar
        </span>
        <span className="text-[.72rem] text-t4">
          empezó el {formatFechaHora(run.startedAt)}
        </span>
      </div>

      <div className="text-[.75rem] leading-relaxed text-t3">
        {run.soloFaltaLaPasadaFinal ? (
          // Every site finished and the crash landed in the trailing pass.
          // Saying "N sitios pendientes" here would misprice the offer: the
          // resume re-scrapes nothing at all.
          <>Todos los sitios terminaron — sólo falta la pasada final de ML y agregación. Retomar no vuelve a scrapear ningún sitio.</>
        ) : (
          <>{atendidos.length} sitio(s) atendido(s) · {pendientes.length} pendiente(s): {pendientes.join(', ')}</>
        )}
        {salteados.length > 0 && (
          <>
            <br/>
            {salteados.length} salieron del registro y no se van a retomar: {salteados.join(', ')}
          </>
        )}
      </div>

      {error && <div className="text-[.72rem] text-danger">{error}</div>}

      <div className="flex flex-wrap gap-2">
        <button
          type="button" onClick={onRetomar} disabled={busy}
          className={cn('btn-sm border-none bg-primary text-white', busy && 'cursor-not-allowed opacity-60')}
        >
          {busy ? 'Retomando…' : '▶ Retomar la corrida'}
        </button>
        {/* "Ocultar", never "Descartar": there is no discard endpoint —
            `interrumpida` is cleared by resuming and nothing else. This hides
            the notice for now; a reload brings it back, because the run really
            is still interrupted. */}
        <button
          type="button" onClick={onDismiss} disabled={busy}
          className={cn('btn-sm btn-ghost text-t4', busy && 'cursor-not-allowed opacity-60')}
        >
          Ocultar por ahora
        </button>
      </div>
    </div>
  );
}
