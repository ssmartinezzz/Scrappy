import { FINANCIACION_CONFIG } from '../financiacionConfig';

// Compact financing-signal badge — sourced from the precompute embedded in
// /api/data (senalFinanciacion). Structurally independent from SenalBadge/
// BuySignal: separate component, separate config, never merged into the same
// badge or value (spec: "Visually Independent Financing Badge").
//
// Renders even in the sin_preset_activo state (distinct from the buy-signal's
// SenalBadge, which hides on sin_datos) — sin_preset_activo is actionable
// ("configure a preset"), not just "no data yet".
export default function FinanBadge({ finan, compact }) {
  if (!finan || !finan.senal) return null;
  const cfg = FINANCIACION_CONFIG[finan.senal];
  if (!cfg) return null;

  const title = finan.presetLabel
    ? `${cfg.label} · preset: ${finan.presetLabel} (asumido por vos, no es una tasa oficial)`
    : cfg.label;

  return (
    <span
      className={compact ? 'badge-compact' : 'badge-financiacion'}
      style={{ background: cfg.bg, borderColor: cfg.border, color: 'var(--t1)' }}
      title={title}
    >
      {compact ? cfg.icon : <>{cfg.icon} {cfg.label}</>}
    </span>
  );
}
