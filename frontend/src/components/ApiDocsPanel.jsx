// Public console at /apidocs. It is a STANDALONE full-viewport page:
// lazy-loaded and routed in App.jsx as a sibling of /splash and /login,
// deliberately OUTSIDE the AppLayout route tree, so swagger-ui owns the whole
// screen with no sidebar, topbar or app container fighting its stylesheet.
//
// apidocs-public-filtered-document: there is no role gate here any more, and
// no nav entry anywhere in the app — the page is reached by typing the URL.
// What keeps the administrative surface out of it is OpenApiDocumentController,
// which strips every `x-access: ADMIN` operation before serialising. Filtering
// on this side would be theatre: the full document would still cross the wire
// and sit in the Network tab. The try-it-out deny-list below stays cosmetic.
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import SwaggerUI from 'swagger-ui-react';
import 'swagger-ui-react/swagger-ui.css';
import { getAccessToken } from '../lib/authSession';
import { loadContract } from '../lib/apiDocs/loadContract';
import { denyTryItOutPlugin } from '../lib/apiDocs/denyTryItOutPlugin';

function requestInterceptor(req) {
  const token = getAccessToken();
  if (token) {
    req.headers = { ...req.headers, Authorization: `Bearer ${token}` };
  }
  return req;
}

const PLUGINS = [denyTryItOutPlugin];

// The page takes the viewport rather than a slot in a layout, and scrolls
// itself. swagger-ui.css lays the document out from here on.
const PAGE_STYLE = {
  position: 'fixed', inset: 0, overflow: 'auto', background: '#fff', zIndex: 40,
};

// The only chrome on this page: one way back into the app, so a reader is not
// stranded on a full-screen document. Pinned top-right, clear of swagger-ui's
// own header block, which starts at the top-left. An anonymous visitor who
// clicks it lands on /login, which is correct — the app behind it is gated
// even though this page is not.
const BACK_STYLE = {
  position: 'fixed', top: 12, right: 16, zIndex: 41,
  padding: '6px 12px', borderRadius: 6, border: '1px solid #d9dde3',
  background: '#fff', color: '#3b4151', fontSize: 13, textDecoration: 'none',
};

function ApiDocsPage({ className, children }) {
  return (
    <div className={className} style={PAGE_STYLE}>
      <Link to="/catalogo" style={BACK_STYLE}>← Volver</Link>
      {children}
    </div>
  );
}

export default function ApiDocsPanel() {
  const [state, setState] = useState({ status: 'loading', spec: null, error: null });

  useEffect(() => {
    let cancelled = false;
    loadContract()
      .then(spec => {
        if (!cancelled) setState({ status: 'ready', spec, error: null });
      })
      .catch(err => {
        if (!cancelled) setState({ status: 'error', spec: null, error: err });
      });
    return () => { cancelled = true; };
  }, []);

  if (state.status === 'loading') {
    return (
      <ApiDocsPage className="api-docs-panel api-docs-panel--loading">
        Cargando el contrato de la API…
      </ApiDocsPage>
    );
  }

  if (state.status === 'error') {
    return (
      <ApiDocsPage className="api-docs-panel api-docs-panel--error">
        No se pudo cargar el contrato: {state.error?.message || 'error desconocido'}
      </ApiDocsPage>
    );
  }

  return (
    <ApiDocsPage className="api-docs-panel">
      <SwaggerUI
        spec={state.spec}
        plugins={PLUGINS}
        requestInterceptor={requestInterceptor}
      />
    </ApiDocsPage>
  );
}
