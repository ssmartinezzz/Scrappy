// swagger-ui-react is mocked: this proves our own wiring (spec, plugins,
// requestInterceptor), not the third-party component's runtime behaviour.
// The panel is a standalone page now, so it carries its own "volver" Link and
// needs a router around it — it is no longer rendered inside AppLayout's.
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

const swaggerUIMock = vi.fn(() => <div data-testid="swagger-ui-stub"/>);
vi.mock('swagger-ui-react', () => ({ default: (props) => swaggerUIMock(props) }));
vi.mock('swagger-ui-react/swagger-ui.css', () => ({}));

vi.mock('../lib/apiDocs/loadContract', () => ({
  loadContract: vi.fn(),
}));
vi.mock('../lib/authSession', () => ({
  getAccessToken: vi.fn(),
}));

import ApiDocsPanel from './ApiDocsPanel';
import { loadContract } from '../lib/apiDocs/loadContract';
import { getAccessToken } from '../lib/authSession';

afterEach(() => {
  vi.clearAllMocks();
});

function renderPanel() {
  return render(
    <MemoryRouter>
      <ApiDocsPanel/>
    </MemoryRouter>,
  );
}

describe('ApiDocsPanel', () => {
  it('renders SwaggerUI with the spec loadContract() resolved, plus a deny plugin', async () => {
    const spec = { openapi: '3.1.0', paths: {} };
    loadContract.mockResolvedValue(spec);

    renderPanel();

    await waitFor(() => expect(screen.getByTestId('swagger-ui-stub')).toBeInTheDocument());

    expect(swaggerUIMock).toHaveBeenCalledWith(expect.objectContaining({
      spec,
      plugins: expect.arrayContaining([expect.any(Function)]),
      requestInterceptor: expect.any(Function),
    }));
  });

  it("requestInterceptor attaches the current access token as a bearer header", async () => {
    loadContract.mockResolvedValue({ openapi: '3.1.0', paths: {} });
    getAccessToken.mockReturnValue('a-real-token');

    renderPanel();
    await waitFor(() => expect(swaggerUIMock).toHaveBeenCalled());

    const { requestInterceptor } = swaggerUIMock.mock.calls.at(-1)[0];
    const req = requestInterceptor({ headers: {} });

    expect(req.headers.Authorization).toBe('Bearer a-real-token');
  });

  it('shows an error state instead of crashing when loadContract rejects', async () => {
    loadContract.mockRejectedValue(new Error('Only an ADMIN account can view the API contract.'));

    renderPanel();

    expect(await screen.findByText(/Only an ADMIN account/)).toBeInTheDocument();
    expect(screen.queryByTestId('swagger-ui-stub')).not.toBeInTheDocument();
  });

  // A full-viewport page outside AppLayout has no nav of its own: without this
  // one link an ADMIN who opens the console is stranded on it.
  it('offers a single link back into the app, in every state', async () => {
    loadContract.mockResolvedValue({ openapi: '3.1.0', paths: {} });

    renderPanel();

    await waitFor(() => expect(screen.getByTestId('swagger-ui-stub')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /volver/i })).toHaveAttribute('href', '/catalogo');
  });
});
