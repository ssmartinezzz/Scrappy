// swagger-ui-admin-gated — fetches and prepares the OpenAPI contract for the
// interactive console. Goes through authedFetch, not swagger-ui's own `url`
// prop, so the spec fetch shares the app's one auth chokepoint (401 →
// refresh → retry) instead of opening a second, weaker path.
import { parse } from 'yaml';
import { authedFetch } from '../authedFetch';
import { BASE } from '../../api';

const STATUS_MESSAGES = {
  401: 'Your session expired. Reload the page to sign in again.',
  403: 'Only an ADMIN account can view the API contract.',
  404: 'The API contract was not found on the server.',
  500: 'The server could not serve the API contract. Check the backend logs.',
};

export class LoadContractError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'LoadContractError';
    this.status = status;
  }
}

function messageFor(status) {
  return STATUS_MESSAGES[status]
    || `The API contract request failed with an unexpected status (${status}).`;
}

/**
 * Fetches `docs/openapi.yaml` via `GET /api/openapi.yaml`, parses it, and
 * overwrites `servers` to the base the rest of the app already talks to —
 * never the YAML's hardcoded `http://localhost:3000`, which would misfire
 * under `start lan`.
 */
export async function loadContract() {
  const response = await authedFetch(`${BASE}/api/openapi.yaml`);
  if (!response.ok) {
    throw new LoadContractError(response.status, messageFor(response.status));
  }

  const text = await response.text();
  const spec = parse(text);

  const origin = BASE || (typeof window !== 'undefined' ? window.location.origin : '');
  spec.servers = [{ url: origin }];

  return spec;
}
