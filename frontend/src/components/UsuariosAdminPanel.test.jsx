import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({
  fetchUsuarios: vi.fn(),
  crearUsuario: vi.fn(),
  cambiarRolUsuario: vi.fn(),
  desactivarUsuario: vi.fn(),
  reactivarUsuario: vi.fn(),
}));

import { fetchUsuarios } from '../api';
import UsuariosAdminPanel from './UsuariosAdminPanel';

// Forma real de GET /api/usuarios (UsuarioAdminEndpoints.listar): `roles` es un
// array y `activo`/`esServicio` son booleanos, no strings.
const CUENTAS = [
  { id: '1', username: 'admin', email: 'admin@x.com', activo: true, esServicio: false, roles: ['ADMIN'] },
  { id: '2', username: 'cli', email: null, activo: true, esServicio: true, roles: ['ADMIN'] },
  { id: '3', username: 'lucia', email: 'lucia@x.com', activo: true, esServicio: false, roles: ['VIEWER'] },
  { id: '4', username: 'exempleado', email: 'ex@x.com', activo: false, esServicio: false, roles: ['VIEWER'] },
];

beforeEach(() => {
  vi.clearAllMocks();
  fetchUsuarios.mockResolvedValue(CUENTAS);
});

async function renderPanel() {
  render(<UsuariosAdminPanel />);
  await screen.findByText('admin');
}

describe('UsuariosAdminPanel — las inactivas se piden, no se muestran solas', () => {
  it('esconde las cuentas inactivas en la carga inicial', async () => {
    await renderPanel();

    expect(screen.getByText('lucia')).toBeInTheDocument();
    expect(screen.queryByText('exempleado')).not.toBeInTheDocument();
  });

  it('las muestra al tildar "Mostrar inactivas", y las vuelve a esconder al destildar', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.click(screen.getByLabelText(/Mostrar inactivas/));
    expect(await screen.findByText('exempleado')).toBeInTheDocument();

    await user.click(screen.getByLabelText(/Mostrar inactivas/));
    await waitFor(() => expect(screen.queryByText('exempleado')).not.toBeInTheDocument());
  });

  // Sin esto, un filtro que esconde cuentas sin decirlo es indistinguible de
  // un backend que no las devolvió.
  it('dice cuántas está escondiendo', async () => {
    await renderPanel();
    expect(screen.getByText('(1)')).toBeInTheDocument();
  });
});

describe('UsuariosAdminPanel — buscador', () => {
  it('filtra por username', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.type(screen.getByLabelText('Buscar por usuario o email'), 'luc');

    expect(await screen.findByText('lucia')).toBeInTheDocument();
    expect(screen.queryByText('admin')).not.toBeInTheDocument();
  });

  it('filtra por email, no sólo por username', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.type(screen.getByLabelText('Buscar por usuario o email'), 'lucia@x.com');

    expect(await screen.findByText('lucia')).toBeInTheDocument();
    expect(screen.queryByText('admin')).not.toBeInTheDocument();
  });

  // `email` viene null para las cuentas de servicio, y un `.toLowerCase()`
  // sobre null tira y se lleva la tabla entera puesta.
  it('no explota con una cuenta que no tiene email', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.type(screen.getByLabelText('Buscar por usuario o email'), 'cli');

    expect(await screen.findByText('cli')).toBeInTheDocument();
  });

  it('avisa cuando la búsqueda no encontró nada, sin confundirlo con lista vacía', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.type(screen.getByLabelText('Buscar por usuario o email'), 'nadie');

    expect(await screen.findByText(/Ninguna cuenta coincide/)).toBeInTheDocument();
  });
});
