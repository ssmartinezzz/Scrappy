import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({
  fetchUsuarios: vi.fn(),
  crearUsuario: vi.fn(),
  cambiarRolUsuario: vi.fn(),
  desactivarUsuario: vi.fn(),
  reactivarUsuario: vi.fn(),
  cambiarEmailUsuario: vi.fn(),
  enviarCorreoDePrueba: vi.fn(),
  fetchEstadoSmtp: vi.fn(),
}));

import {
  cambiarEmailUsuario, desactivarUsuario, enviarCorreoDePrueba, fetchEstadoSmtp, fetchUsuarios,
} from '../api';
import UsuariosAdminPanel from './UsuariosAdminPanel';

// Forma real de GET /api/usuarios (UsuarioAdminEndpoints.listar): `roles` es un
// array y `activo`/`esServicio` son booleanos, no strings.
const CUENTAS = [
  { id: '1', username: 'admin', email: 'admin@x.com', activo: true, esServicio: false, roles: ['ADMIN'] },
  { id: '2', username: 'cli', email: null, activo: true, esServicio: true, roles: ['ADMIN'] },
  { id: '3', username: 'lucia', email: 'lucia@x.com', activo: true, esServicio: false, roles: ['VIEWER'] },
  { id: '4', username: 'exempleado', email: 'ex@x.com', activo: false, esServicio: false, roles: ['VIEWER'] },
];

const SMTP = {
  configurado: true, host: 'smtp.x.com', puerto: 587, remitente: 'a**@x.com',
  starttls: true, usuarioConfigurado: true, problema: null,
};

beforeEach(() => {
  vi.clearAllMocks();
  fetchUsuarios.mockResolvedValue(CUENTAS);
  fetchEstadoSmtp.mockResolvedValue(SMTP);
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

describe('UsuariosAdminPanel — email de una cuenta', () => {
  it('edita el email de una cuenta y recarga la lista', async () => {
    const user = userEvent.setup();
    cambiarEmailUsuario.mockResolvedValue({ ok: true, body: { mensaje: 'Email actualizado.' } });
    await renderPanel();

    await user.click(screen.getByRole('button', { name: 'Editar email de lucia' }));
    const campo = screen.getByLabelText('Email de lucia');
    await user.clear(campo);
    await user.type(campo, 'nuevo@x.com');
    await user.click(screen.getByRole('button', { name: 'Guardar email' }));

    expect(cambiarEmailUsuario).toHaveBeenCalledExactlyOnceWith('lucia', 'nuevo@x.com');
    await waitFor(() => expect(fetchUsuarios).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Email actualizado.')).toBeInTheDocument();
  });

  it('un campo vacío quita el email (null)', async () => {
    const user = userEvent.setup();
    cambiarEmailUsuario.mockResolvedValue({ ok: true, body: {} });
    await renderPanel();

    await user.click(screen.getByRole('button', { name: 'Editar email de lucia' }));
    await user.clear(screen.getByLabelText('Email de lucia'));
    await user.click(screen.getByRole('button', { name: 'Guardar email' }));

    expect(cambiarEmailUsuario).toHaveBeenCalledExactlyOnceWith('lucia', null);
  });

  it('muestra el motivo del backend cuando lo rechaza', async () => {
    const user = userEvent.setup();
    cambiarEmailUsuario.mockResolvedValue({ ok: false, body: { mensaje: 'Esa dirección no está disponible.' } });
    await renderPanel();

    await user.click(screen.getByRole('button', { name: 'Editar email de lucia' }));
    await user.type(screen.getByLabelText('Email de lucia'), 'x');
    await user.click(screen.getByRole('button', { name: 'Guardar email' }));

    expect(await screen.findByText('Esa dirección no está disponible.')).toBeInTheDocument();
  });

  it('no ofrece editar el email de una cuenta de servicio', async () => {
    await renderPanel();

    expect(screen.queryByRole('button', { name: 'Editar email de cli' })).not.toBeInTheDocument();
  });
});

describe('UsuariosAdminPanel — tabla', () => {
  it('muestra las cinco columnas con su título, el email en la suya', async () => {
    await renderPanel();

    for (const titulo of ['Cuenta', 'Email', 'Rol', 'Estado', 'Acciones']) {
      expect(screen.getByRole('columnheader', { name: titulo })).toBeInTheDocument();
    }
    expect(screen.getByText('lucia@x.com')).toBeInTheDocument();
  });

  it('ordena por email', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.click(screen.getByRole('button', { name: 'Email' }));

    const filas = screen.getAllByRole('row').slice(1).map(f => f.textContent);
    expect(filas[0]).toContain('cli');          // sin email: el vacío ordena primero
    expect(filas[1]).toContain('admin@x.com');
  });
});

describe('UsuariosAdminPanel — desactivar pide confirmación', () => {
  it('abre el aviso con la explicación y no desactiva hasta confirmar', async () => {
    const user = userEvent.setup();
    desactivarUsuario.mockResolvedValue({ ok: true, body: {} });
    await renderPanel();

    await user.click(screen.getAllByRole('button', { name: 'Desactivar' })[0]);

    const aviso = await screen.findByRole('dialog');
    expect(aviso).toHaveTextContent(/Desactivar no borra/);
    expect(aviso).toHaveTextContent(/última cuenta ADMIN/);
    expect(desactivarUsuario).not.toHaveBeenCalled();

    await user.click(within(aviso).getByRole('button', { name: 'Desactivar' }));

    expect(desactivarUsuario).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('cancelar no llama al backend', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.click(screen.getAllByRole('button', { name: 'Desactivar' })[0]);
    await user.click(await screen.findByRole('button', { name: 'Cancelar' }));

    expect(desactivarUsuario).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});

describe('UsuariosAdminPanel — correo saliente', () => {
  it('queda plegado y no lee el SMTP hasta que se abre', async () => {
    await renderPanel();

    expect(fetchEstadoSmtp).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Enviar prueba' })).not.toBeInTheDocument();
  });

  it('muestra el estado del SMTP en sólo lectura, sin ningún campo de contraseña', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.click(screen.getByRole('button', { name: /correo saliente/i }));

    expect(await screen.findByText('smtp.x.com')).toBeInTheDocument();
    expect(screen.getByText('587')).toBeInTheDocument();
    expect(screen.getByText('Configurado')).toBeInTheDocument();
    expect(screen.queryByLabelText(/contraseña/i)).not.toBeInTheDocument();
  });

  it('manda el mail de prueba al destino pedido y muestra el resultado enmascarado', async () => {
    const user = userEvent.setup();
    enviarCorreoDePrueba.mockResolvedValue({ ok: true, body: { mensaje: 'Mail de prueba enviado a o**@x.com.' } });
    await renderPanel();

    await user.click(screen.getByRole('button', { name: /correo saliente/i }));
    await user.type(await screen.findByLabelText(/Enviar prueba a/), 'otro@x.com');
    await user.click(screen.getByRole('button', { name: 'Enviar prueba' }));

    expect(enviarCorreoDePrueba).toHaveBeenCalledExactlyOnceWith('otro@x.com');
    expect(await screen.findByText('Mail de prueba enviado a o**@x.com.')).toBeInTheDocument();
  });

  it('con el destino vacío deja que el backend elija el suyo', async () => {
    const user = userEvent.setup();
    enviarCorreoDePrueba.mockResolvedValue({ ok: true, body: { mensaje: 'ok' } });
    await renderPanel();

    await user.click(screen.getByRole('button', { name: /correo saliente/i }));
    await user.click(await screen.findByRole('button', { name: 'Enviar prueba' }));

    expect(enviarCorreoDePrueba).toHaveBeenCalledExactlyOnceWith(undefined);
  });

  it('muestra el motivo y la pista cuando el SMTP falla', async () => {
    const user = userEvent.setup();
    enviarCorreoDePrueba.mockResolvedValue({
      ok: false, body: { mensaje: 'No se pudo conectar a smtp.x.com:587.', pista: 'Revisá el puerto.' },
    });
    await renderPanel();

    await user.click(screen.getByRole('button', { name: /correo saliente/i }));
    await user.click(await screen.findByRole('button', { name: 'Enviar prueba' }));

    expect(await screen.findByText(/no se pudo conectar a smtp.x.com:587/i)).toBeInTheDocument();
    expect(screen.getByText('Revisá el puerto.')).toBeInTheDocument();
  });

  it('avisa cuando no pudo leer la configuración, sin romper el envío', async () => {
    const user = userEvent.setup();
    fetchEstadoSmtp.mockResolvedValue(null);
    await renderPanel();

    await user.click(screen.getByRole('button', { name: /correo saliente/i }));

    expect(await screen.findByText(/No pude leer la configuración/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enviar prueba' })).toBeEnabled();
  });
});
