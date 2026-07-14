import { SEMANTIC } from '../lib/colors';
import { PHASE_LABELS } from '../lib/mlPhaseLabels';

function elapsed(startedAt) {
  if (!startedAt) return '';
  const secs = Math.floor((Date.now() - new Date(startedAt)) / 1000);
  if (secs < 60) return `${secs}s`;
  const m = Math.floor(secs / 60), s = secs % 60;
  return `${m}m ${s}s`;
}

// Full-screen blocking overlay for GPU fine-tuning. Mounted as a sibling of
// the main layout (in AppLayout) so it covers Topbar/Sidebar/primary-nav — same
// pattern as DetailPanel/CompareModal but with a much higher z-index since
// it must block ALL navigation while training runs.
export default function GpuTrainingOverlay({ training, onClose }) {
  if (!training) return null;

  const { running, phase, pct, msg, startedAt, error, success } = training;
  const isError   = !!error || phase === 'error' || phase === 'timeout';
  const phaseLabel = PHASE_LABELS[phase] ?? phase ?? '';
  const barColor = isError ? SEMANTIC.negative
    : phase === 'image' || phase === 'image_download' ? SEMANTIC.warn
    : 'var(--p)';

  return (
    <div style={{
      position:'fixed', inset:0, zIndex:9999,
      background:'rgba(8,8,12,0.86)',
      backdropFilter:'blur(3px)',
      display:'flex', alignItems:'center', justifyContent:'center',
      pointerEvents:'all',
    }}>
      <style>{`
        @keyframes gpu-pulse{0%,100%{opacity:1}50%{opacity:.45}}
        @keyframes gpu-shimmer{0%{background-position:200% center}100%{background-position:-200% center}}
        @keyframes gpu-glow{0%,100%{box-shadow:0 0 24px color-mix(in srgb, var(--p) 35%, transparent)}50%{box-shadow:0 0 44px color-mix(in srgb, var(--p) 55%, transparent)}}
      `}</style>

      <div style={{
        background:'var(--s2)', border:'1px solid var(--bd)',
        borderRadius:16, padding:'2rem 2.4rem',
        minWidth:340, maxWidth:440,
        display:'flex', flexDirection:'column', gap:16,
        animation: !isError && !success ? 'gpu-glow 2.2s ease-in-out infinite' : 'none',
      }}>
        {!isError && !success && (
          <>
            <div style={{ textAlign:'center' }}>
              <span style={{
                fontSize:'1.05rem', fontWeight:800, color:'var(--p)',
                animation:'gpu-pulse 1.5s ease-in-out infinite',
              }}>
                ⚡ Re-entrenando modelo con GPU...
              </span>
            </div>

            <div style={{
              background:'color-mix(in srgb, var(--p) 6%, transparent)',
              border:'1px solid color-mix(in srgb, var(--p) 20%, transparent)',
              borderRadius:10, padding:'.85rem 1rem',
              display:'flex', flexDirection:'column', gap:10,
            }}>
              <div style={{ display:'flex', justifyContent:'space-between', alignItems:'center' }}>
                <span style={{ fontSize:'.78rem', fontWeight:700, color: barColor }}>
                  {phaseLabel}
                </span>
                <span style={{ fontSize:'.68rem', color:'var(--t4)', fontVariantNumeric:'tabular-nums' }}>
                  {elapsed(startedAt)} · {pct ?? 0}%
                </span>
              </div>

              <div style={{ height:8, background:'var(--s3)', borderRadius:4, overflow:'hidden' }}>
                <div style={{
                  height:'100%', width:`${pct ?? 0}%`,
                  background:`linear-gradient(90deg, color-mix(in srgb, ${barColor} 60%, transparent), ${barColor})`,
                  borderRadius:4,
                  transition:'width .6s ease',
                  ...(pct > 0 && pct < 100 ? {
                    backgroundSize:'200% auto',
                    animation:'gpu-shimmer 2s linear infinite',
                  } : {}),
                }}/>
              </div>

              {msg && (
                <span style={{
                  fontSize:'.68rem', color:'var(--t4)',
                  whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
                }}>
                  {msg}
                </span>
              )}
            </div>

            <span style={{ fontSize:'.65rem', color:'var(--t4)', textAlign:'center' }}>
              No se puede navegar hasta que termine el entrenamiento.
            </span>
          </>
        )}

        {success && !isError && (
          <div style={{ textAlign:'center', display:'flex', flexDirection:'column', gap:6 }}>
            <span style={{ fontSize:'1.05rem', fontWeight:800, color: SEMANTIC.positive }}>
              ✓ Listo{msg ? ` — ${msg}` : ''}
            </span>
          </div>
        )}

        {isError && (
          <div style={{ textAlign:'center', display:'flex', flexDirection:'column', gap:14 }}>
            <span style={{ fontSize:'1rem', fontWeight:800, color: SEMANTIC.negative }}>
              ✕ Error en el entrenamiento
            </span>
            <span style={{ fontSize:'.74rem', color:'var(--t3)' }}>
              {error || msg || 'Ocurrió un error inesperado durante el entrenamiento.'}
            </span>
            <button onClick={onClose} style={{
              padding:'8px 16px', borderRadius:8,
              border:`1px solid ${SEMANTIC.negative}66`,
              background:`color-mix(in srgb, ${SEMANTIC.negative} 12%, transparent)`,
              color: SEMANTIC.negative,
              cursor:'pointer', fontSize:'.78rem', fontWeight:700,
            }}>
              Cerrar
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
