import { useState } from 'react';
import { fetchSuplementosBuilder, fmt } from '../api';
import { MultiSelectTags } from './ui/multi-select-tags';
import { MoneyInput } from './ui/money-input';

const TIPOS_DISPONIBLES = [
  { tipo: 'Proteína en Polvo', grupo: 'Proteína' },
  { tipo: 'Barra Proteica',    grupo: 'Proteína' },
  { tipo: 'Pancake / Waffle',  grupo: 'Proteína' },
  { tipo: 'Snack Proteico',    grupo: 'Proteína' },
  { tipo: 'Vitamina C',        grupo: 'Vitaminas' },
  { tipo: 'Multivitamínico',   grupo: 'Vitaminas' },
  { tipo: 'Vitamina D',        grupo: 'Vitaminas' },
  { tipo: 'Omega 3',           grupo: 'Vitaminas' },
  { tipo: 'Complejo B',        grupo: 'Vitaminas' },
  { tipo: 'Zinc',              grupo: 'Vitaminas' },
  { tipo: 'Mayonesa',          grupo: 'Aderezos' },
  { tipo: 'Ketchup / Salsa',  grupo: 'Aderezos' },
  { tipo: 'Mostaza',          grupo: 'Aderezos' },
  { tipo: 'Maple / Sirope',   grupo: 'Aderezos' },
  { tipo: 'Creatina',         grupo: null },
  { tipo: 'Magnesio',         grupo: null },
  { tipo: 'Quemador',         grupo: null },
];
const DEFAULT_TIPOS = new Set(['Proteína en Polvo', 'Creatina', 'Magnesio']);

// Grouped tag data for MultiSelectTags, derived from TIPOS_DISPONIBLES —
// preserves order and maps the `grupo: null` bucket to "Otros".
const GROUPS = ['Proteína', 'Vitaminas', 'Aderezos', null].map(grupo => ({
  label: grupo ?? 'Otros',
  tags: TIPOS_DISPONIBLES.filter(t => t.grupo === grupo).map(t => t.tipo),
}));

export default function SuplementosPanel() {
  const [tipos, setTipos] = useState(DEFAULT_TIPOS);
  const [presupuesto, setPresupuesto] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [picks, setPicks] = useState(null);
  const [sinStock, setSinStock] = useState([]);

  function toggleTipo(tipo) {
    setTipos(prev => {
      const next = new Set(prev);
      if (next.has(tipo)) next.delete(tipo);
      else next.add(tipo);
      return next;
    });
  }

  async function generar() {
    if (tipos.size === 0) {
      setError('Seleccioná al menos un tipo de suplemento.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await fetchSuplementosBuilder({
        tipos: Array.from(tipos),
        presupuesto: presupuesto ? Number(presupuesto) : 0,
      });
      setPicks(data?.picks ?? []);
      setSinStock(data?.sinStock ?? []);
    } catch {
      setError('Error al conectar con el servidor.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ height: '100%', overflowY: 'auto' }}>
    <div style={{ padding: '24px 20px', maxWidth: 920, margin: '0 auto' }}>
      <p style={{
        color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.12em',
        fontSize: '.72rem', fontWeight: 700, margin: '0 0 6px',
      }}>
        Armador
      </p>
      <h1 style={{
        color: 'var(--t1)', fontSize: '1.9rem', fontWeight: 800,
        margin: '0 0 24px', lineHeight: 1.15,
      }}>
        Stack de suplementos
      </h1>

      {/* Type selector */}
      <div style={{
        background: 'var(--s1)', borderRadius: 14, padding: '20px 24px', marginBottom: 16,
      }}>
        <p style={{ color: 'var(--t2)', fontWeight: 600, fontSize: '.85rem', margin: '0 0 14px' }}>
          ¿Qué suplementos necesitás?
        </p>
        <MultiSelectTags groups={GROUPS} selected={tipos} onToggle={toggleTipo} />
      </div>

      {/* Budget + generate */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 24 }}>
        <div style={{ flex: 1, minWidth: 200 }}>
          <label htmlFor="presupuesto" style={{ display: 'block', color: 'var(--t3)', fontSize: '.8rem', fontWeight: 600, marginBottom: 6 }}>
            Presupuesto total (opcional)
          </label>
          <MoneyInput
            id="presupuesto"
            value={presupuesto}
            onChange={setPresupuesto}
            placeholder="Ej: 50.000"
          />
        </div>
        <button
          onClick={generar}
          disabled={loading || tipos.size === 0}
          style={{
            padding: '10px 28px', borderRadius: 8, border: 'none',
            background: loading || tipos.size === 0 ? 'var(--s3)' : 'var(--p)',
            color: loading || tipos.size === 0 ? 'var(--t3)' : '#fff',
            fontWeight: 700, fontSize: '.9rem',
            cursor: loading || tipos.size === 0 ? 'not-allowed' : 'pointer',
            transition: 'background .15s', whiteSpace: 'nowrap',
          }}
        >
          {loading ? 'Buscando...' : 'Generar'}
        </button>
      </div>

      {/* Error */}
      {error && (
        <div style={{
          background: 'var(--s1)', border: '1px solid var(--y)', borderRadius: 10,
          padding: '14px 18px', color: 'var(--y)', marginBottom: 20, fontSize: '.88rem',
        }}>
          {error}
        </div>
      )}

      {/* Empty state — nothing found at all */}
      {picks !== null && picks.length === 0 && sinStock.length === 0 && (
        <div style={{ textAlign: 'center', padding: '48px 20px', color: 'var(--t3)', fontSize: '.95rem' }}>
          No se encontraron suplementos para los tipos seleccionados. Probá corriendo un scraping primero.
        </div>
      )}

      {/* Results */}
      {picks !== null && (picks.length > 0 || sinStock.length > 0) && (
        <>
          {picks.length > 0 && (
            <div style={{
              display: 'flex', justifyContent: 'flex-end', alignItems: 'baseline',
              gap: 8, marginBottom: 16,
            }}>
              <span style={{ color: 'var(--t3)', fontSize: '.8rem', fontWeight: 600 }}>Total stack</span>
              <span style={{ color: 'var(--p)', fontSize: '1.3rem', fontWeight: 800 }}>
                ${fmt(picks.reduce((acc, p) => acc + p.precio, 0))}
              </span>
            </div>
          )}

          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
            gap: 16,
            marginBottom: 24,
          }}>
            {picks.map((pick, i) => (
              <a
                key={i}
                href={pick.url}
                target="_blank"
                rel="noopener noreferrer"
                style={{ textDecoration: 'none', display: 'block' }}
              >
                <div style={{
                  background: 'var(--s1)', borderRadius: 14,
                  overflow: 'hidden', border: '1.5px solid var(--s2)',
                  transition: 'box-shadow .15s',
                  height: '100%',
                }}>
                  <div style={{ position: 'relative', background: 'var(--s2)', height: 160, overflow: 'hidden' }}>
                    {pick.img ? (
                      <img
                        src={pick.img}
                        alt={pick.nombre}
                        loading="lazy"
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        onError={e => { e.currentTarget.style.display = 'none'; }}
                      />
                    ) : (
                      <div style={{
                        width: '100%', height: '100%',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: 'var(--t4)', fontSize: '2.4rem',
                      }}>
                        💊
                      </div>
                    )}
                    <span style={{
                      position: 'absolute', top: 8, left: 8,
                      background: 'var(--p)', color: '#fff',
                      fontSize: '.68rem', fontWeight: 700, padding: '3px 9px',
                      borderRadius: 999, textTransform: 'uppercase', letterSpacing: '.07em',
                    }}>
                      {pick.tipo}
                    </span>
                  </div>
                  <div style={{ padding: '14px 16px 18px' }}>
                    <p style={{
                      color: 'var(--t1)', fontWeight: 700, fontSize: '.88rem',
                      margin: '0 0 4px', lineHeight: 1.35,
                      display: '-webkit-box', WebkitLineClamp: 2,
                      WebkitBoxOrient: 'vertical', overflow: 'hidden',
                    }}>
                      {pick.nombre}
                    </p>
                    <p style={{ color: 'var(--t3)', fontSize: '.76rem', margin: '0 0 10px' }}>
                      {pick.marca && pick.marca !== pick.sitio ? `${pick.marca} · ` : ''}
                      {pick.sitio}
                    </p>
                    <p style={{ color: 'var(--p)', fontWeight: 800, fontSize: '1.05rem', margin: 0 }}>
                      ${fmt(pick.precio)}
                    </p>
                  </div>
                </div>
              </a>
            ))}

            {/* Sin stock cards */}
            {sinStock.map(tipo => (
              <div key={tipo} style={{
                background: 'var(--s1)', borderRadius: 14,
                border: '1.5px dashed var(--s3)', opacity: 0.6,
                display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center',
                minHeight: 200, padding: 20, gap: 8,
              }}>
                <span style={{ fontSize: '1.6rem' }}>🔍</span>
                <span style={{
                  background: 'var(--s3)', color: 'var(--t3)',
                  fontSize: '.68rem', fontWeight: 700, padding: '3px 9px',
                  borderRadius: 999, textTransform: 'uppercase', letterSpacing: '.07em',
                }}>
                  {tipo}
                </span>
                <span style={{ color: 'var(--t4)', fontSize: '.78rem', textAlign: 'center' }}>
                  Sin stock en tu catálogo
                </span>
              </div>
            ))}
          </div>

          {/* Regenerate */}
          <div style={{ textAlign: 'center' }}>
            <button
              onClick={generar}
              disabled={loading}
              style={{
                padding: '10px 26px', borderRadius: 8,
                border: '1.5px solid var(--p)', background: 'transparent',
                color: 'var(--p)', fontWeight: 600, fontSize: '.88rem',
                cursor: loading ? 'not-allowed' : 'pointer',
              }}
            >
              {loading ? 'Buscando...' : 'Regenerar'}
            </button>
          </div>
        </>
      )}
    </div>
    </div>
  );
}
