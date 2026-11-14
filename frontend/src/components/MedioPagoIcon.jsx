// Monogramas PROPIOS de cada medio de pago, dibujados a mano (forma, color de
// marca e iniciales): no son los logos oficiales ni se descarga nada. Las claves
// son los nombres canónicos del seed `medio_pago` (V101) tal cual — con la misma
// ortografía y tildes. El color de cada uno es un token CSS (`--medio-*` en
// styles.css). Un medio sin monograma cae al glifo genérico de tarjeta: nunca se
// inventa una marca (CODE-5).

const TXT = { fontFamily: 'Segoe UI, system-ui, sans-serif', fontWeight: 800, textAnchor: 'middle' };

// Cuadrado redondeado de color de marca + contenido blanco.
function Chapa({ token, children }) {
  return (
    <>
      <rect x="0.5" y="0.5" width="23" height="23" rx="6" style={{ fill: `var(${token})` }} />
      {children}
    </>
  );
}

function Letras({ t, size = 11, y = 16, fill = '#fff' }) {
  return <text x="12" y={y} fontSize={size} style={{ ...TXT, fill }}>{t}</text>;
}

const MONOGRAMAS = {
  'Mercado Pago': () => (
    <>
      <ellipse cx="12" cy="12" rx="11.5" ry="8.5" style={{ fill: 'var(--medio-mercado-pago)' }} />
      <Letras t="mp" size={10} y={15} />
    </>
  ),
  Transferencia: () => (
    <Chapa token="--medio-transferencia">
      <path d="M5 9h12m0 0-3-3m3 3-3 3M19 15H7m0 0 3-3m-3 3 3 3"
            fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </Chapa>
  ),
  Santander: () => (
    <Chapa token="--medio-santander">
      {/* llama: gota con el vértice arriba */}
      <path d="M12 3.5c1.2 3 4.6 5.2 4.6 9.2a4.6 4.6 0 0 1-9.2 0c0-1.7.7-2.9 1.6-3.9.2 1.2.8 1.9 1.5 2.2C10.1 8.4 10.7 5.6 12 3.5Z"
            fill="#fff" />
    </Chapa>
  ),
  Galicia: () => <Chapa token="--medio-galicia"><Letras t="G" size={14} y={17.5} /></Chapa>,
  Macro: () => <Chapa token="--medio-macro"><Letras t="M" size={14} y={17.5} /></Chapa>,
  BBVA: () => <Chapa token="--medio-bbva"><Letras t="BBVA" size={7.2} y={14.5} /></Chapa>,
  Nación: () => <Chapa token="--medio-nacion"><Letras t="BN" size={11} y={16} /></Chapa>,
  Provincia: () => <Chapa token="--medio-provincia"><Letras t="BP" size={11} y={16} /></Chapa>,
  'Naranja X': () => <Chapa token="--medio-naranja-x"><Letras t="nx" size={12} y={16} /></Chapa>,
  Modo: () => (
    <Chapa token="--medio-modo">
      <Letras t="modo" size={7.6} y={14.5} fill="var(--medio-modo-acento)" />
    </Chapa>
  ),
  Ualá: () => (
    <>
      <circle cx="12" cy="12" r="11.5" style={{ fill: 'var(--medio-uala)' }} />
      <Letras t="ua" size={11} y={15.5} />
    </>
  ),
  'Cuenta DNI': () => <Chapa token="--medio-cuenta-dni"><Letras t="DNI" size={8.6} y={14.8} /></Chapa>,
  Visa: () => (
    <>
      <rect x="0.5" y="0.5" width="23" height="23" rx="6" style={{ fill: '#fff', stroke: 'var(--bd)' }} />
      <text x="12" y="15.2" fontSize="8.6" fontStyle="italic" style={{ ...TXT, fill: 'var(--medio-visa)' }}>VISA</text>
    </>
  ),
  Mastercard: () => (
    <>
      <rect x="0.5" y="0.5" width="23" height="23" rx="6" style={{ fill: '#fff', stroke: 'var(--bd)' }} />
      <circle cx="9.2" cy="12" r="5.6" style={{ fill: 'var(--medio-mastercard-rojo)' }} />
      <circle cx="14.8" cy="12" r="5.6" style={{ fill: 'var(--medio-mastercard-naranja)', fillOpacity: 0.85 }} />
    </>
  ),
  'American Express': () => <Chapa token="--medio-amex"><Letras t="AMEX" size={6.8} y={14.4} /></Chapa>,
  Cabal: () => <Chapa token="--medio-cabal"><Letras t="CB" size={11} y={16} /></Chapa>,
  'Pago Nube': () => (
    <Chapa token="--medio-pago-nube">
      <path d="M7.5 17.5a3.4 3.4 0 0 1-.4-6.8A5 5 0 0 1 16.8 9.6a3.9 3.9 0 0 1 .2 7.9Z" fill="#fff" />
    </Chapa>
  ),
};

function Generico() {
  return (
    <g fill="none" stroke="var(--t3)" strokeWidth="1.5" strokeLinecap="round">
      <rect x="1.5" y="5" width="21" height="14" rx="3" style={{ fill: 'var(--s2)' }} />
      <path d="M1.5 9.5h21M5 15h5" />
    </g>
  );
}

export default function MedioPagoIcon({ medio, size = 18 }) {
  const Dibujo = Object.hasOwn(MONOGRAMAS, medio) ? MONOGRAMAS[medio] : null;
  return (
    <svg
      className="medio-icon"
      role="img"
      aria-label={medio}
      data-generico={Dibujo ? 'false' : 'true'}
      width={size}
      height={size}
      viewBox="0 0 24 24"
    >
      <title>{medio}</title>
      {Dibujo ? <Dibujo /> : <Generico />}
    </svg>
  );
}
