import { Link } from 'react-router-dom';

export default function NotFound() {
  return (
    <div style={{
      display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center',
      gap:10, padding:'3rem', height:'100%', textAlign:'center',
    }}>
      <h1 style={{ fontSize:'1.4rem', fontWeight:800, color:'var(--t1)', margin:0 }}>
        404 — Página no encontrada
      </h1>
      <p style={{ fontSize:'.85rem', color:'var(--t4)', margin:0 }}>
        La ruta que buscás no existe o fue movida.
      </p>
      <Link to="/catalogo" style={{ color:'var(--p2)', fontWeight:700, fontSize:'.85rem' }}>
        Volver al catálogo
      </Link>
    </div>
  );
}
