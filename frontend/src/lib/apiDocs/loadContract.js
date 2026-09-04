// Fetches and prepares the OpenAPI contract for the interactive console.
// Goes through authedFetch, not swagger-ui's own `url` prop, so the spec
// fetch shares the app's one auth chokepoint (401 → refresh → retry) instead
// of opening a second, weaker path.
//
// apidocs-public-filtered-document: the route is PERMIT now, so this runs
// with no token at all on an anonymous visit. authedFetch handles that
// without a special case — getAccessToken() returns null, the Authorization
// header is simply not set, and the refresh-on-401 branch never runs because
// a PERMIT route does not answer 401. The error map below is kept for the
// failure modes that remain real: a missing resource, a broken backend.
import { parse } from 'yaml';
import { authedFetch } from '../authedFetch';
import { BASE } from '../../api';

const STATUS_MESSAGES = {
  401: 'Your session expired. Reload the page to sign in again.',
  403: 'The server refused to serve the API contract.',
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
 * Fetches the server-filtered contract via `GET /api/openapi.yaml`, parses
 * it, and
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
