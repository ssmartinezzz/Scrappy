import { useState } from 'react';
import { fetchSuplementosBuilder, fmt } from '../api';

const TIPOS_DISPONIBLES = ['Proteína', 'Creatina', 'Magnesio', 'Vitaminas', 'Quemador'];
const DEFAULT_TIPOS = new Set(['Proteína', 'Creatina', 'Magnesio']);

export default function SuplementosPanel() {
  const [tipos, setTipos] = useState(DEFAULT_TIPOS);
  const [presupuesto, setPresupuesto] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [resultado, setResultado] = useState(null);

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
      setResultado(data ?? []);
    } catch {
      setError('Error al conectar con el servidor.');
    } finally {
      setLoading(false);
    }
  }

  return (
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
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
          {TIPOS_DISPONIBLES.map(tipo => {
            const checked = tipos.has(tipo);
            return (
              <label key={tipo} style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer', userSelect: 'none' }}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleTipo(tipo)}
                  style={{ accentColor: 'var(--p)', width: 16, height: 16, cursor: 'pointer' }}
                />
                <span style={{
                  color: checked ? 'var(--p)' : 'var(--t2)',
                  fontWeight: checked ? 700 : 400,
                  fontSize: '.9rem',
                  transition: 'color .15s',
                }}>
                  {tipo}
                </span>
              </label>
            );
          })}
        </div>
      </div>

      {/* Budget + generate */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 24 }}>
        <div style={{ flex: 1, minWidth: 200 }}>
          <label style={{ display: 'block', color: 'var(--t3)', fontSize: '.8rem', fontWeight: 600, marginBottom: 6 }}>
            Presupuesto total (opcional)
          </label>
          <input
            type="number"
            min="0"
            placeholder="Presupuesto total opcional"
            value={presupuesto}
            onChange={e => setPresupuesto(e.target.value)}
            style={{
              width: '100%', padding: '10px 14px', borderRadius: 8, fontSize: '.9rem',
              border: '1.5px solid var(--s3)', background: 'var(--s1)', color: 'var(--t1)',
              outline: 'none', boxSizing: 'border-box',
            }}
            onFocus={e => { e.target.style.borderColor = 'var(--p)'; }}
            onBlur={e => { e.target.style.borderColor = 'var(--s3)'; }}
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

      {/* Empty state */}
      {resultado !== null && resultado.length === 0 && (
        <div style={{ textAlign: 'center', padding: '48px 20px', color: 'var(--t3)', fontSize: '.95rem' }}>
          No se encontraron suplementos para los tipos seleccionados. Probá corriendo un scraping primero.
        </div>
      )}

      {/* Results grid */}
      {resultado !== null && resultado.length > 0 && (
        <>
          <div style={{
            display: 'flex', justifyContent: 'flex-end', alignItems: 'baseline',
            gap: 8, marginBottom: 16,
          }}>
            <span style={{ color: 'var(--t3)', fontSize: '.8rem', fontWeight: 600 }}>Total stack</span>
            <span style={{ color: 'var(--p)', fontSize: '1.3rem', fontWeight: 800 }}>
              ${fmt(resultado.reduce((acc, p) => acc + p.precio, 0))}
            </span>
          </div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
            gap: 16,
            marginBottom: 24,
          }}>
            {resultado.map((pick, i) => (
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
                  {/* Image */}
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

                  {/* Info */}
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
  );
}
