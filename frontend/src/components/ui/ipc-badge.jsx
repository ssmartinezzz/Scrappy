// Lives inside `topbar-meta`, hidden below `md`: the non-wrapping first row
// of the topbar clips the user menu on phones (see CLAUDE.md gotcha).
import * as React from 'react';
import { cn } from '@/lib/utils';

const SIZES = {
  sm:      { box: 30, stroke: 3,   ipcFont: 7,  valueFont: 11, usdFont: 9  },
  default: { box: 48, stroke: 4.5, ipcFont: 10, valueFont: 14, usdFont: 11 },
};

const AMBER  = '#D08A1E';
const GREY   = 'var(--t4)';
const ACCENT = 'var(--p2)';

const RING_COLOR = { observado: ACCENT, extrapolado: AMBER, sin_datos: GREY };

const CONFIANZA_LABEL = {
  observado:   'dato observado',
  extrapolado: 'dato estimado',
  sin_datos:   'sin datos',
};

function formatPercent(value) {
  if (value == null || Number.isNaN(value)) return '—';
  return value.toLocaleString('es-AR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

function formatUsd(value) {
  if (value == null || Number.isNaN(value)) return '—';
  return value.toLocaleString('es-AR', { maximumFractionDigits: 0 });
}

function IpcBadge({ ipc, usd, size = 'sm', className, ...props }) {
  const ipcSinDatos = !ipc || ipc.confianza === 'sin_datos' || ipc.variacionMensual == null;
  const usdSinDatos = !usd || usd.confianza === 'sin_datos' || usd.ultimoValor == null;
  if (ipcSinDatos && usdSinDatos) return null;

  const dims = SIZES[size] || SIZES.sm;
  const radius = (dims.box - dims.stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const center = dims.box / 2;

  const confianza = ipcSinDatos ? 'sin_datos' : ipc.confianza;
  const ringColor = RING_COLOR[confianza] || GREY;
  const pct = ipcSinDatos ? 0 : Math.min(Math.max(ipc.variacionMensual, 0), 10) / 10;
  const dashOffset = circumference * (1 - pct);

  const ipcValueStr = ipcSinDatos ? '—' : `${formatPercent(ipc.variacionMensual)} %`;
  const usdValueStr = usdSinDatos ? '—' : formatUsd(usd.ultimoValor);
  const ariaLabel =
    `IPC ${ipcValueStr} mensual, dólar oficial $${usdValueStr}, ${CONFIANZA_LABEL[confianza]}`;

  return (
    <div
      role="img"
      aria-label={ariaLabel}
      className={cn('inline-flex items-center gap-[6px]', className)}
      {...props}
    >
      <span className="relative inline-flex shrink-0" style={{ width: dims.box, height: dims.box }}>
        <svg width={dims.box} height={dims.box} viewBox={`0 0 ${dims.box} ${dims.box}`}>
          <circle
            cx={center} cy={center} r={radius} fill="none"
            stroke="var(--bd)" strokeWidth={dims.stroke}
          />
          <circle
            cx={center} cy={center} r={radius} fill="none"
            stroke={ringColor} strokeWidth={dims.stroke} strokeLinecap="round"
            strokeDasharray={circumference} strokeDashoffset={dashOffset}
            transform={`rotate(-90 ${center} ${center})`}
          />
        </svg>
        <span
          className="absolute inset-0 flex items-center justify-center font-bold text-t2"
          style={{ fontSize: dims.ipcFont }}
        >
          IPC
        </span>
        <span
          data-testid="ipc-badge-dot"
          aria-hidden="true"
          className="absolute -right-px -top-px rounded-full"
          style={{ width: 6, height: 6, background: ringColor }}
        />
      </span>
      <span className="flex flex-col leading-tight">
        <strong className="text-t1" style={{ fontSize: dims.valueFont }}>
          {ipcValueStr}
        </strong>
        <span className="text-t4" style={{ fontSize: dims.usdFont }}>
          USD ${usdValueStr}
        </span>
      </span>
    </div>
  );
}

export { IpcBadge };
