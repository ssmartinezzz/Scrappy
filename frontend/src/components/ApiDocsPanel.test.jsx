// swagger-ui-react is mocked: this proves our own wiring (spec, plugins,
// requestInterceptor), not the third-party component's runtime behaviour.
import { render, screen, waitFor } from '@testing-library/react';
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

describe('ApiDocsPanel', () => {
  it('renders SwaggerUI with the spec loadContract() resolved, plus a deny plugin', async () => {
    const spec = { openapi: '3.1.0', paths: {} };
    loadContract.mockResolvedValue(spec);

    render(<ApiDocsPanel/>);

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

    render(<ApiDocsPanel/>);
    await waitFor(() => expect(swaggerUIMock).toHaveBeenCalled());

    const { requestInterceptor } = swaggerUIMock.mock.calls.at(-1)[0];
    const req = requestInterceptor({ headers: {} });

    expect(req.headers.Authorization).toBe('Bearer a-real-token');
  });

  it('shows an error state instead of crashing when loadContract rejects', async () => {
    loadContract.mockRejectedValue(new Error('Only an ADMIN account can view the API contract.'));

    render(<ApiDocsPanel/>);

    expect(await screen.findByText(/Only an ADMIN account/)).toBeInTheDocument();
    expect(screen.queryByTestId('swagger-ui-stub')).not.toBeInTheDocument();
  });
});
