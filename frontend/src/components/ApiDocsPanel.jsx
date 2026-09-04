// swagger-ui-admin-gated — ADMIN-only console at /api-docs. Lazy-loaded in
// AppLayout.jsx, exported as ApiDocsPanelRoute (same pattern as
// CronjobsRoute/UsuariosAdminRoute). Only one of four role-aware layers here
// enforces anything: the ApiRoutePolicy ADMIN row on GET /api/openapi.yaml.
// Nav, route guard and the try-it-out deny-list are all cosmetic.
import { useEffect, useState } from 'react';
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
    return <div className="api-docs-panel api-docs-panel--loading">Cargando el contrato de la API…</div>;
  }

  if (state.status === 'error') {
    return (
      <div className="api-docs-panel api-docs-panel--error">
        No se pudo cargar el contrato: {state.error?.message || 'error desconocido'}
      </div>
    );
  }

  return (
    <div className="api-docs-panel">
      <SwaggerUI
        spec={state.spec}
        plugins={PLUGINS}
        requestInterceptor={requestInterceptor}
      />
    </div>
  );
}
