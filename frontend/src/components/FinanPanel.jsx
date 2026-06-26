import { useEffect, useState } from 'react';
import {
  fetchFinanciacionPresets, crearFinanciacionPreset, editarFinanciacionPreset,
  activarFinanciacionPreset, eliminarFinanciacionPreset,
} from '../api';
import { SEMANTIC } from '../lib/colors';

const DISCLAIMER = 'Valores asumidos por vos — no es una tasa oficial ni proviene de ningún banco/financiera.';

function isIlustrativo(label) {
  return typeof label === 'string' && label.toLowerCase().includes('ejemplo');
}

function PresetForm({ initial, onSubmit, onCancel, submitLabel }) {
  const [label, setLabel]           = useState(initial?.label ?? '');
  const [recargoPct, setRecargoPct] = useState(initial?.recargoPct ?? 0);
  const [cuotas, setCuotas]         = useState(initial?.cuotas ?? 1);
  const [error, setError]           = useState('');

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');
    const res = await onSubmit({ label, recargoPct: Number(recargoPct), cuotas: Number(cuotas) });
    if (res && res.ok === false) setError(res.mensaje || 'Error al guardar');
  }

  return (
    <form onSubmit={handleSubmit} className="finan-preset-form"
      style={{ border:'1px solid var(--bd)', borderRadius:8, background:'var(--s2)', marginTop:8 }}>
      <label style={{ fontSize:'.7rem', color:'var(--t3)' }}>
        Nombre
        <input value={label} onChange={e => setLabel(e.target.value)}
          placeholder="Ej: 12 cuotas sin interés"
          style={{ width:'100%', marginTop:3, padding:'5px 8px', borderRadius:6,
                    border:'1px solid var(--bd)', background:'var(--s1)', color:'var(--t1)' }} />
      </label>
      <div className="finan-field-row">
        <label style={{ fontSize:'.7rem', color:'var(--t3)', flex:1 }}>
          Recargo (%)
          <input type="number" step="0.1" min="0" value={recargoPct}
            onChange={e => setRecargoPct(e.target.value)}
            style={{ width:'100%', marginTop:3, padding:'5px 8px', borderRadius:6,
                      border:'1px solid var(--bd)', background:'var(--s1)', color:'var(--t1)' }} />
        </label>
        <label style={{ fontSize:'.7rem', color:'var(--t3)', flex:1 }}>
          Cuotas
          <input type="number" step="1" min="1" value={cuotas}
            onChange={e => setCuotas(e.target.value)}
            style={{ width:'100%', marginTop:3, padding:'5px 8px', borderRadius:6,
                      border:'1px solid var(--bd)', background:'var(--s1)', color:'var(--t1)' }} />
        </label>
      </div>
      <div style={{ fontSize:'.62rem', color:'var(--t4)', fontStyle:'italic' }}>
        ⚠️ {DISCLAIMER}
      </div>
      {error && <div style={{ fontSize:'.68rem', color: SEMANTIC.negative }}>{error}</div>}
      <div style={{ display:'flex', gap:6, marginTop:2, flexWrap:'wrap' }}>
        <button type="submit" style={{
          padding:'5px 12px', borderRadius:6, border:'none', cursor:'pointer',
          background:'var(--p)', color:'#fff', fontSize:'.7rem', fontWeight:700,
        }}>
          {submitLabel}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} style={{
            padding:'5px 12px', borderRadius:6, border:'1px solid var(--bd)', cursor:'pointer',
            background:'transparent', color:'var(--t4)', fontSize:'.7rem',
          }}>
            Cancelar
          </button>
        )}
      </div>
    </form>
  );
}

function PresetRow({ preset, onActivar, onEliminar, onGuardarEdicion }) {
  const [editando, setEditando] = useState(false);

  if (editando) {
    return (
      <PresetForm
        initial={preset}
        submitLabel="Guardar cambios"
        onCancel={() => setEditando(false)}
        onSubmit={async (vals) => {
          const res = await onGuardarEdicion(preset.id, vals);
          if (!res || res.ok !== false) setEditando(false);
          return res;
        }}
      />
    );
  }

  return (
    <div style={{
      display:'flex', justifyContent:'space-between', alignItems:'center',
      padding:'8px 10px', borderRadius:8, marginBottom:6,
      border: `1.5px solid ${preset.activo ? 'var(--p)' : 'var(--bd)'}`,
      background: preset.activo ? 'rgba(163,113,247,.08)' : 'var(--s2)',
    }}>
      <div style={{ flex:1 }}>
        <div style={{ fontSize:'.78rem', fontWeight:700, color:'var(--t1)', display:'flex', alignItems:'center', gap:6 }}>
          {preset.label}
          {isIlustrativo(preset.label) && (
            <span style={{
              fontSize:'.58rem', fontWeight:700, color: SEMANTIC.warn,
              border: `1px solid ${SEMANTIC.warn}`, borderRadius:10, padding:'1px 6px',
            }}>
              ejemplo — editá
            </span>
          )}
          {preset.activo && (
            <span style={{
              fontSize:'.58rem', fontWeight:700, color:'var(--p2)',
              border:'1px solid var(--p)', borderRadius:10, padding:'1px 6px',
            }}>
              activo
            </span>
          )}
        </div>
        <div style={{ fontSize:'.66rem', color:'var(--t4)', marginTop:2 }}>
          {preset.recargoPct}% recargo · {preset.cuotas} cuotas
          <span style={{ fontStyle:'italic', marginLeft:6 }}>(asumido por vos)</span>
        </div>
      </div>
      <div style={{ display:'flex', gap:4 }}>
        {!preset.activo && (
          <button onClick={() => onActivar(preset.id)} title="Activar" style={{
            padding:'4px 8px', borderRadius:6, border:'1px solid var(--p)', cursor:'pointer',
            background:'transparent', color:'var(--p2)', fontSize:'.66rem',
          }}>
            Activar
          </button>
        )}
        <button onClick={() => setEditando(true)} title="Editar" style={{
          padding:'4px 8px', borderRadius:6, border:'1px solid var(--bd)', cursor:'pointer',
          background:'transparent', color:'var(--t4)', fontSize:'.66rem',
        }}>
          ✎
        </button>
        <button onClick={() => onEliminar(preset.id)} title="Eliminar" style={{
          padding:'4px 8px', borderRadius:6, border:'1px solid var(--bd)', cursor:'pointer',
          background:'transparent', color:'var(--t4)', fontSize:'.66rem',
        }}>
          🗑
        </button>
      </div>
    </div>
  );
}

export default function FinanPanel() {
  const [presets, setPresets]   = useState([]);
  const [activo, setActivo]     = useState(null);
  const [loading, setLoading]   = useState(true);
  const [creando, setCreando]   = useState(false);
  const [error, setError]       = useState('');

  async function reload() {
    const data = await fetchFinanciacionPresets();
    if (data) {
      setPresets(data.presets || []);
      setActivo(data.activo || null);
    }
    setLoading(false);
  }

  useEffect(() => { reload(); }, []);

  async function handleActivar(id) {
    setError('');
    const res = await activarFinanciacionPreset(id);
    if (res && res.ok === false) setError(res.mensaje || 'Error al activar el preset');
    else reload();
  }

  async function handleEliminar(id) {
    setError('');
    const res = await eliminarFinanciacionPreset(id);
    if (res && res.ok === false) setError(res.mensaje || 'Error al eliminar el preset');
    else reload();
  }

  async function handleEditar(id, vals) {
    const res = await editarFinanciacionPreset(id, vals);
    if (!res || res.ok !== false) reload();
    return res;
  }

  async function handleCrear(vals) {
    const res = await crearFinanciacionPreset(vals);
    if (res && res.ok) { setCreando(false); reload(); }
    return res;
  }

  return (
    <div className="finan-panel">
      <h3 style={{ fontSize:'.9rem', color:'var(--t1)', marginBottom:4 }}>
        💳 Presets de financiación
      </h3>
      <p style={{ fontSize:'.7rem', color:'var(--t4)', marginBottom:10 }}>
        Configurá el recargo y la cantidad de cuotas para calcular si conviene
        pagar en cuotas o al contado. {DISCLAIMER}
      </p>

      {error && <div style={{ fontSize:'.68rem', color: SEMANTIC.negative, marginBottom:8 }}>{error}</div>}

      {loading && (
        <div style={{ fontSize:'.75rem', color:'var(--t4)' }}>Cargando presets...</div>
      )}

      {!loading && presets.length === 0 && (
        <div style={{ fontSize:'.75rem', color:'var(--t4)' }}>
          No hay presets configurados todavía.
        </div>
      )}

      {!loading && presets.map(p => (
        <PresetRow
          key={p.id}
          preset={p}
          onActivar={handleActivar}
          onEliminar={handleEliminar}
          onGuardarEdicion={handleEditar}
        />
      ))}

      {!creando ? (
        <button onClick={() => setCreando(true)} style={{
          marginTop:8, padding:'6px 14px', borderRadius:8, border:'1.5px dashed var(--bd)',
          cursor:'pointer', background:'transparent', color:'var(--t3)', fontSize:'.72rem', fontWeight:700,
        }}>
          + Nuevo preset
        </button>
      ) : (
        <PresetForm
          submitLabel="Crear preset"
          onCancel={() => setCreando(false)}
          onSubmit={handleCrear}
        />
      )}

      {activo && (
        <div style={{ marginTop:14, fontSize:'.64rem', color:'var(--t4)' }}>
          Preset activo: <strong style={{ color:'var(--t2)' }}>{activo.label}</strong>
          {isIlustrativo(activo.label) && (
            <span style={{ marginLeft:6, color: SEMANTIC.warn, fontWeight:700 }}>(ejemplo — editá)</span>
          )}
        </div>
      )}
    </div>
  );
}
