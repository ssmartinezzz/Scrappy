import { useEffect, useState } from 'react';
import { fetchTendencias, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';

// ─── KPI Card ─────────────────────────────────────────────────────────────────
const KpiCard = ({ icon, value, label, color = 'var(--t1)', sub }) => (
  <div style={{
    background:'var(--s2)', border:'1px solid var(--bd)', borderRadius:10,
    padding:'.75rem 1rem', flex:1, minWidth:120,
  }}>
    <div style={{ fontSize:'1.2rem', marginBottom:4 }}>{icon}</div>
    <div style={{ fontSize:'1.1rem', fontWeight:800, color, letterSpacing:'-.02em' }}>{value}</div>
    <div style={{ fontSize:'.68rem', color:'var(--t4)', marginTop:2 }}>{label}</div>
    {sub && <div style={{ fontSize:'.65rem', color:'var(--t4)', marginTop:1 }}>{sub}</div>}
  </div>
);

// ─── Insight automático ───────────────────────────────────────────────────────
const Insights = ({ cats, badges, total }) => {
  if (!cats?.length) return null;
  const cheapest  = cats.reduce((a,b) => a.med < b.med ? a : b, cats[0]);
  const priciest  = cats.reduce((a,b) => a.med > b.med ? a : b, cats[0]);
  const mostProd  = cats.reduce((a,b) => a.n   > b.n   ? a : b, cats[0]);
  const highCV    = cats.filter(c => c.cv > 70).slice(0,3);
  const ofertaCount = badges?.verified_deal || 0;
  const histLow   = badges?.all_time_low || 0;
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
      <div className="detail-section-title">💡 Insights de mercado</div>
      {[
        { icon:'💸', text: `La categoría más accesible es <strong>${cheapest.categoria}</strong> con mediana $${fmt(cheapest.med)}` },
        { icon:'💎', text: `La más cara es <strong>${priciest.categoria}</strong> con mediana $${fmt(priciest.med)}` },
        { icon:'📦', text: `<strong>${mostProd.categoria}</strong> tiene la mayor oferta: ${mostProd.n} productos` },
        highCV.length > 0 && { icon:'⚠️', text: `Alta variabilidad en: <strong>${highCV.map(c=>c.categoria).join(', ')}</strong> — conviene comparar bien` },
        ofertaCount > 0 && { icon:'✅', text: `Hay <strong>${ofertaCount}</strong> ofertas reales detectadas estadísticamente (${(ofertaCount/total*100).toFixed(1)}% del catálogo)` },
        histLow > 0 && { icon:'🏆', text: `<strong>${histLow}</strong> productos están en su mínimo histórico de precio` },
      ].filter(Boolean).map((ins, i) => (
        <div key={i} style={{
          display:'flex', gap:10, alignItems:'flex-start',
          background:'var(--s2)', borderRadius:8, padding:'.5rem .75rem',
          border:'1px solid var(--bd)', fontSize:'.75rem', color:'var(--t3)',
        }}>
          <span style={{ flexShrink:0 }}>{ins.icon}</span>
          <span dangerouslySetInnerHTML={{ __html: ins.text }}/>
        </div>
      ))}
    </div>
  );
};

// ─── TrendsPanel ("Mercado" tab) ────────────────────────────────────────────
// Curated down to KPIs + text insights only (spec "Curated Mercado Tab") —
// the bar chart, bubble chart, "¿Qué aprendió la IA?" panel and Clusters tab
// were removed; opportunities (badges, top deals, drill-down) moved to the
// separate OportunidadesPanel at /analisis/oportunidades.
export default function TrendsPanel() {
  const [res, setRes] = useState(null);

  useEffect(() => { fetchTendencias().then(setRes); }, []);

  if (res === null) return (
    <div className="trends-panel">
      <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        Cargando…
      </div>
    </div>
  );

  if (res.state === 'failed') return (
    <div className="trends-panel">
      <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        El análisis ML no pudo generarse. Revisá los logs del servidor (scraper.log) y
        volvé a ejecutar un scraping.
      </div>
    </div>
  );

  if (res.state === 'error') return (
    <div className="trends-panel">
      <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        No se pudo conectar con el servidor. Verificá tu conexión y volvé a intentar.
      </div>
    </div>
  );

  if (res.state === 'empty' || !res.data) return (
    <div className="trends-panel">
      <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        Ejecutá un scraping para ver el análisis de mercado.
      </div>
    </div>
  );

  const data = res.data;

  const catDist  = data.distribucionCategorias || {};
  const badges   = data.badgeCounts || {};
  const total    = data.totalProductos || 1;

  const chartData = Object.entries(catDist).map(([cat, st]) => ({
    categoria: cat, med: st.median || 0, n: st.n || 0,
    cv: st.cv || 0, q1: st.q1, q3: st.q3,
  })).filter(d => d.n >= 3 && d.med > 0)
    .sort((a,b) => b.med - a.med);

  const totalProds = total;
  const globalMed  = chartData.length
    ? Math.round(chartData.reduce((sum,d) => sum + d.med * d.n, 0) /
                 chartData.reduce((sum,d) => sum + d.n, 0))
    : 0;

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem',
                    display:'flex', flexDirection:'column', gap:'1.5rem' }}>

        {/* KPIs */}
        <div style={{ display:'flex', gap:8, flexWrap:'wrap' }}>
          <KpiCard icon="📦" value={fmt(totalProds)} label="Productos scrapeados" color="var(--p2)"/>
          <KpiCard icon="💰" value={`$${fmt(globalMed)}`} label="Precio mediano global" color={SEMANTIC.positive}/>
          <KpiCard icon="✅" value={badges.verified_deal || 0}
                   label="Ofertas reales" sub={`${((badges.verified_deal||0)/total*100).toFixed(1)}% del catálogo`}
                   color={SEMANTIC.oferta}/>
          <KpiCard icon="🏆" value={badges.all_time_low || 0}
                   label="Mínimos históricos" color={SEMANTIC.warn}/>
        </div>

        {/* Insights */}
        <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
          <Insights cats={chartData} badges={badges} total={total}/>
        </div>
      </div>
    </div>
  );
}
