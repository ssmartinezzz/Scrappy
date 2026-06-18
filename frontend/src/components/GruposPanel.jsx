import { useEffect, useState, useCallback } from 'react';
import { fetchGrupos } from '../api';
import GroupCard from './GroupCard';

export default function GruposPanel({ onOpenDetail }) {
  const [data,     setData]     = useState(null);
  const [loading,  setLoading]  = useState(false);
  const [busq,     setBusq]     = useState('');
  const [page,     setPage]     = useState(0);
  const [minSit,   setMinSit]   = useState(2);
  const size = 18;

  const cargar = useCallback(async (p = 0) => {
    setLoading(true);
    const res = await fetchGrupos({ q: busq, minSitios: minSit, page: p, size });
    setData(res);
    setLoading(false);
  }, [busq, minSit]);

  useEffect(() => { setPage(0); cargar(0); }, [busq, minSit]);

  const grupos    = data?.grupos || [];
  const total     = data?.total  || 0;
  const totalPages = Math.ceil(total / size);

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Header */}
      <div style={{
        padding:'.65rem 1.25rem', background:'var(--s1)',
        borderBottom:'1px solid var(--bd)', display:'flex', gap:10, alignItems:'center',
        position:'sticky', top:104, zIndex:150,
      }}>
        <input
          type="text" placeholder="Buscar producto..."
          value={busq} onChange={e => setBusq(e.target.value)}
          style={{
            flex:1, padding:'8px 12px', borderRadius:8,
            border:'1.5px solid var(--bd2)', background:'var(--s2)',
            color:'var(--t1)', font:'.85rem var(--font)', outline:'none',
          }}
        />
        <select value={minSit} onChange={e => setMinSit(+e.target.value)}
          style={{
            padding:'8px 10px', borderRadius:8, border:'1.5px solid var(--bd2)',
            background:'var(--s2)', color:'var(--t3)', font:'.78rem var(--font)',
          }}>
          <option value={2}>2+ sitios</option>
          <option value={3}>3+ sitios</option>
          <option value={1}>Todos</option>
        </select>
        <span style={{ fontSize:'.72rem', color:'var(--t4)', whiteSpace:'nowrap' }}>
          {loading ? 'Buscando...' : `${total} grupos`}
        </span>
      </div>

      {/* Grid */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {!loading && grupos.length === 0 && (
          <div style={{
            display:'flex', flexDirection:'column', alignItems:'center',
            justifyContent:'center', gap:12, padding:'4rem', color:'var(--t4)',
          }}>
            <div style={{ fontSize:'2rem' }}>⚖</div>
            <div style={{ fontSize:'.9rem' }}>
              No se encontraron productos en 2+ sitios distintos.
            </div>
            <div style={{ fontSize:'.75rem' }}>
              Necesitás scrapear al menos 2 sitios con productos similares.
            </div>
          </div>
        )}

        <div style={{
          display:'grid', gap:12,
          gridTemplateColumns:'repeat(auto-fill, minmax(280px, 1fr))',
        }}>
          {grupos.map((g, i) => (
            <GroupCard key={g.nombre + i} grupo={g} onOpenDetail={onOpenDetail} />
          ))}
        </div>

        {/* Paginación */}
        {totalPages > 1 && (
          <div style={{ display:'flex', justifyContent:'center', gap:6, padding:'1rem 0' }}>
            <button className="btn-sm btn-ghost" disabled={page <= 0}
              onClick={() => { setPage(page-1); cargar(page-1); }}>← Anterior</button>
            <span style={{ padding:'5px 12px', fontSize:'.78rem', color:'var(--t3)' }}>
              {page+1} / {totalPages}
            </span>
            <button className="btn-sm btn-ghost" disabled={page >= totalPages-1}
              onClick={() => { setPage(page+1); cargar(page+1); }}>Siguiente →</button>
          </div>
        )}
      </div>
    </div>
  );
}
