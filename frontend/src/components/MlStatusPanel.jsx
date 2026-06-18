import { useEffect, useState, useCallback } from 'react';
import { fetchMlEstado, startMlTraining, aplicarModeloML, fetchMlResultado } from '../api';

// ─── Toast helper ─────────────────────────────────────────────────────────────
function showToast(msg, type = 'success') {
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  el.textContent = msg;
  document.body.appendChild(el);
  const remove = () => {
    el.style.opacity = '0';
    el.style.transition = 'opacity .3s';
    setTimeout(() => el.remove(), 300);
  };
  setTimeout(remove, 4700);
}

const PHASE_LABELS = {
  starting:       'Iniciando...',
  text:           'Clasificador de texto',
  image_download: 'Descargando imágenes',
  image:          'EfficientNet-B3',
  idle:           '',
  timeout:        'Timeout',
  error:          'Error',
};

function elapsed(startedAt) {
  if (!startedAt) return '';
  const secs = Math.floor((Date.now() - new Date(startedAt)) / 1000);
  if (secs < 60) return `${secs}s`;
  const m = Math.floor(secs / 60), s = secs % 60;
  return `${m}m ${s}s`;
}

export default function MlStatusPanel() {
  const [estado,   setEstado]   = useState(null);
  const [running,  setRunning]  = useState(false);
  const [applying, setApplying] = useState(false);
  const [tick,     setTick]     = useState(0);

  const reload = () => fetchMlEstado().then(e => {
    setEstado(e);
    if (e?.training?.running) setRunning(true);
  });

  useEffect(() => { reload(); }, []);

  // Poll every 2s while training — fetchMlEstado gives us phase/pct/msg
  // Also poll /api/ml/resultado to detect completion and show toast (ADR-6)
  useEffect(() => {
    if (!running) return;
    const iv = setInterval(async () => {
      const [e, res] = await Promise.all([
        fetchMlEstado().catch(() => null),
        fetchMlResultado().catch(() => null),
      ]);
      if (e) { setEstado(e); setTick(t => t + 1); }
      if (!e?.training?.running) {
        setRunning(false);
        clearInterval(iv);
        // Toast notification on training done (ADR-6)
        if (res?.done) {
          if (res.phase === 'error' || e?.training?.phase === 'error') {
            showToast('Entrenamiento ML falló: ' + (res.msg || e?.training?.msg || ''), 'error');
          } else {
            const accMatch = (res.msg || '').match(/(\d+\.?\d*)\s*%/);
            const acc = accMatch ? ' — ' + accMatch[1] + '% accuracy' : '';
            showToast('Modelo ML actualizado' + acc);
          }
        }
      }
    }, 2000);
    return () => clearInterval(iv);
  }, [running]);

  const meta     = estado?.textMeta;
  const hasText  = estado?.hasTextModel;
  const hasImage = estado?.hasImageModel;
  const ts       = estado?.training;

  const handleTrain = async (withImages) => {
    setRunning(true);
    await startMlTraining(withImages, 8);
    reload();
  };

  const handleApply = async () => {
    setApplying(true);
    await aplicarModeloML();
    setTimeout(() => { setApplying(false); reload(); }, 3000);
  };

  return (
    <div style={{
      background:'var(--s2)', border:'1px solid var(--bd)',
      borderRadius:12, padding:'1rem 1.1rem',
      display:'flex', flexDirection:'column', gap:12,
    }}>
      <style>{`
        @keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
        @keyframes shimmer{0%{background-position:200% center}100%{background-position:-200% center}}
      `}</style>

      {/* Header */}
      <div style={{ display:'flex', justifyContent:'space-between', alignItems:'center' }}>
        <span style={{ fontSize:'.82rem', fontWeight:800, color:'var(--t1)' }}>
          Modelo ML
        </span>
        {running && (
          <span style={{
            fontSize:'.65rem', color:'#c084fc', fontWeight:600,
            animation:'pulse 1.5s ease-in-out infinite',
          }}>
            Entrenando...
          </span>
        )}
      </div>

      {/* Training progress — shown while running */}
      {running && ts && (
        <TrainingProgress ts={ts} tick={tick} />
      )}

      {/* Model rows — hide while training to save space */}
      {!running && (
        <>
          <Row
            active={hasText}
            title={hasText ? 'Clasificador de texto' : 'Sin modelo de texto'}
            color={hasText ? '#3fb950' : '#666'}
          >
            {meta ? (
              <span style={{ fontSize:'.67rem', color:'var(--t4)', lineHeight:1.7 }}>
                <strong style={{ color:'var(--t2)' }}>{(meta.accuracy*100).toFixed(1)}%</strong> accuracy
                &nbsp;·&nbsp;<strong style={{ color:'var(--t2)' }}>{meta.num_classes}</strong> categorías
                &nbsp;·&nbsp;{meta.num_train?.toLocaleString('es-AR')} ejemplos
                <br/>
                <span style={{ fontSize:'.6rem' }}>
                  {meta.trained_at ? new Date(meta.trained_at).toLocaleString('es-AR') : '—'}
                </span>
              </span>
            ) : (
              <span style={{ fontSize:'.67rem', color:'var(--t4)' }}>No entrenado aún</span>
            )}
          </Row>

          <Row
            active={hasImage}
            title={hasImage ? 'Clasificador de imágenes (EfficientNet-B3)' : 'Sin modelo de imagen'}
            color={hasImage ? '#f0a500' : '#666'}
          >
            <span style={{ fontSize:'.67rem', color:'var(--t4)' }}>
              {hasImage
                ? 'Refina categorías por foto · RTX 3080'
                : 'Requiere PyTorch + GPU'}
            </span>
          </Row>
        </>
      )}

      {/* Actions */}
      {!running && (
        <div style={{ display:'flex', gap:8, flexWrap:'wrap' }}>
          <Btn color='#c084fc' bg='rgba(163,113,247,.12)' onClick={() => handleTrain(true)}>
            Re-entrenar (texto + GPU)
          </Btn>
          <Btn color='var(--t3)' bg='var(--s3)' onClick={() => handleTrain(false)}>
            Solo texto
          </Btn>
          {hasText && (
            <Btn color='#3fb950' bg='rgba(63,185,80,.1)'
              onClick={handleApply} disabled={applying}>
              {applying ? 'Aplicando...' : 'Aplicar a datos'}
            </Btn>
          )}
        </div>
      )}
    </div>
  );
}

function TrainingProgress({ ts, tick }) {
  const phaseLabel = PHASE_LABELS[ts.phase] ?? ts.phase;
  const pct        = ts.pct ?? 0;
  const isError    = ts.phase === 'error' || ts.phase === 'timeout';

  const barColor = isError ? '#f85149'
    : ts.phase === 'image' || ts.phase === 'image_download' ? '#f0a500'
    : '#c084fc';

  return (
    <div style={{
      background:'rgba(163,113,247,.06)',
      border:'1px solid rgba(163,113,247,.2)',
      borderRadius:10, padding:'.75rem .9rem',
      display:'flex', flexDirection:'column', gap:8,
    }}>
      {/* Phase + elapsed */}
      <div style={{ display:'flex', justifyContent:'space-between', alignItems:'center' }}>
        <span style={{ fontSize:'.72rem', fontWeight:700, color: barColor }}>
          {phaseLabel}
        </span>
        <span style={{ fontSize:'.63rem', color:'var(--t4)', fontVariantNumeric:'tabular-nums' }}>
          {elapsed(ts.startedAt)} · {pct}%
        </span>
      </div>

      {/* Progress bar */}
      <div style={{
        height:6, background:'var(--s3)', borderRadius:3, overflow:'hidden',
      }}>
        <div style={{
          height:'100%', width:`${pct}%`,
          background: isError ? '#f85149'
            : `linear-gradient(90deg, ${barColor}99, ${barColor})`,
          borderRadius:3,
          transition:'width .6s ease',
          ...(pct > 0 && pct < 100 && !isError ? {
            backgroundSize:'200% auto',
            animation:'shimmer 2s linear infinite',
          } : {}),
        }}/>
      </div>

      {/* Message */}
      {ts.msg && (
        <span style={{
          fontSize:'.63rem', color:'var(--t4)',
          whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
        }}>
          {ts.msg}
        </span>
      )}
    </div>
  );
}

function Row({ active, title, color, children }) {
  return (
    <div style={{ display:'flex', gap:10, alignItems:'flex-start' }}>
      <div style={{
        width:8, height:8, borderRadius:'50%', flexShrink:0, marginTop:4,
        background: active ? color : '#444',
        boxShadow: active ? `0 0 6px ${color}88` : 'none',
      }}/>
      <div style={{ flex:1 }}>
        <div style={{ fontSize:'.74rem', fontWeight:700, color: active ? color : 'var(--t4)' }}>
          {title}
        </div>
        {children}
      </div>
    </div>
  );
}

function Btn({ color, bg, onClick, disabled, children }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{
      padding:'5px 11px', borderRadius:8,
      border:`1px solid ${color}66`,
      background: bg, color,
      cursor: disabled ? 'not-allowed' : 'pointer',
      fontSize:'.71rem', fontWeight:600,
      opacity: disabled ? .6 : 1,
      transition:'opacity .15s',
    }}>
      {children}
    </button>
  );
}
