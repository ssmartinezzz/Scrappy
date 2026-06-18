export default function RouteFallback() {
  return (
    <div style={{
      display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center',
      gap:10, padding:'3rem', color:'var(--t4)', fontSize:'.8rem', height:'100%',
    }}>
      <div style={{
        width:28, height:28, borderRadius:'50%',
        border:'2px solid var(--bd)', borderTopColor:'var(--p)',
        animation:'spin 0.7s linear infinite',
      }}/>
      Cargando...
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
