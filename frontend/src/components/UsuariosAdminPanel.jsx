// ABM de cuentas — la UI que `UsuarioAdminEndpoints` dice explícitamente que no
// traía ("Backend only, deliberately"). Toda la superficie /api/usuarios/** es
// ADMIN en ApiRoutePolicy.TABLE; el gate de ruta vive en App.jsx.
import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, UserPlus } from 'lucide-react';

import {
  cambiarRolUsuario,
  crearUsuario,
  desactivarUsuario,
  fetchUsuarios,
  reactivarUsuario,
} from '../api';

// El vocabulario lo siembra V1 (`INSERT INTO rol VALUES ('ADMIN'),('VIEWER')`)
// y el backend valida contra la tabla. Si alguna vez se agrega un rol, esta
// lista queda corta y el select deja de ofrecerlo — el backend lo aceptaría.
const ROLES = ['ADMIN', 'VIEWER'];

const MIN_PASSWORD = 8;   // UsuarioAdminEndpoints.MIN_PASSWORD

function iniciales(nombre) {
  return nombre.slice(0, 2).toUpperCase();
}

/** El rol que mostramos es el primero; hoy una cuenta tiene exactamente uno. */
function rolDe(u) {
  return u.roles?.[0] || '—';
}

function Avatar({ username }) {
  return (
    <div
      aria-hidden="true"
      className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-s3 font-semibold text-t2 text-xs"
    >
      {iniciales(username)}
    </div>
  );
}

function Badge({ tone, children }) {
  const tones = {
    admin:    'border-primary/40 bg-primary/10 text-primary',
    viewer:   'border-border bg-s3 text-t2',
    activa:   'border-success/40 bg-success/10 text-success',
    inactiva: 'border-border bg-s3 text-t4',
    servicio: 'border-warning/40 bg-warning/10 text-warning',
  };
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 font-semibold text-xs ${tones[tone]}`}
    >
      {children}
    </span>
  );
}

export default function UsuariosAdminPanel() {
  const [usuarios, setUsuarios] = useState(null);
  const [error, setError]       = useState('');
  const [aviso, setAviso]       = useState(null);   // { ok, texto }
  const [ocupado, setOcupado]   = useState('');     // username en vuelo, o 'nuevo'
  const [creando, setCreando]   = useState(false);
  const [form, setForm]         = useState({ username: '', password: '', email: '', role: 'VIEWER' });

  const cargar = useCallback(async () => {
    setError('');
    // `fetchUsuarios` da null tanto si el backend contestó mal como si no
    // contestó — para esta pantalla las dos cosas significan lo mismo.
    const lista = await fetchUsuarios().catch(() => null);
    if (!lista) { setError('No pude leer las cuentas.'); return; }
    setUsuarios(lista);
  }, []);

  useEffect(() => { cargar(); }, [cargar]);

  // Toda mutación termina igual: mostrar el mensaje del backend y recargar.
  // El 409 `ultimo_admin` se muestra tal cual viene — explica por qué se negó
  // y qué hacer antes de reintentar, y reescribirlo acá sería perder eso.
  async function ejecutar(clave, accion, exito) {
    setOcupado(clave);
    setAviso(null);
    try {
      const { ok, body } = await accion();
      setAviso({ ok, texto: body?.mensaje || (ok ? exito : 'La operación falló.') });
      if (ok) await cargar();
    } catch {
      setAviso({ ok: false, texto: 'No pude contactar al backend.' });
    } finally {
      setOcupado('');
    }
  }

  function onCrear(e) {
    e.preventDefault();
    return ejecutar('nuevo', () => crearUsuario(form), 'Cuenta creada.').then(() => {
      setForm({ username: '', password: '', email: '', role: 'VIEWER' });
    });
  }

  const formValido = form.username.trim() && form.password.length >= MIN_PASSWORD;

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-3 p-3">
      <header className="flex items-center justify-between gap-2">
        <div>
          <p className="text-eyebrow text-t3">Administración</p>
          <h1 className="font-display text-display-2 text-t1">Cuentas</h1>
        </div>
        <div className="flex items-center gap-1">
          <button
            className="inline-flex items-center gap-1 rounded-btn border border-border px-3 py-2 text-sm text-t2 hover:bg-s2"
            onClick={cargar}
            type="button"
          >
            <RefreshCw aria-hidden="true" size={15} /> Recargar
          </button>
          <button
            className="inline-flex items-center gap-1 rounded-btn bg-primary px-3 py-2 font-medium text-sm text-white hover:bg-primary2"
            onClick={() => setCreando(v => !v)}
            type="button"
          >
            <UserPlus aria-hidden="true" size={15} /> Nueva cuenta
          </button>
        </div>
      </header>

      {aviso && (
        <p
          className={`rounded-card border px-3 py-2 text-sm ${
            aviso.ok
              ? 'border-success/40 bg-success/10 text-success'
              : 'border-danger/40 bg-danger/10 text-danger'
          }`}
          role="status"
        >
          {aviso.texto}
        </p>
      )}

      {creando && (
        <form className="grid gap-2 rounded-card border border-border bg-s2 p-3 sm:grid-cols-2" onSubmit={onCrear}>
          <label className="flex flex-col gap-1 text-sm text-t2">
            Usuario
            <input
              className="rounded-btn border border-border bg-s1 px-2 py-2 text-t1"
              onChange={e => setForm({ ...form, username: e.target.value })}
              required
              value={form.username}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-t2">
            Contraseña <span className="text-t4 text-xs">(mínimo {MIN_PASSWORD})</span>
            <input
              className="rounded-btn border border-border bg-s1 px-2 py-2 text-t1"
              minLength={MIN_PASSWORD}
              onChange={e => setForm({ ...form, password: e.target.value })}
              required
              type="password"
              value={form.password}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-t2">
            Email <span className="text-t4 text-xs">(opcional)</span>
            <input
              className="rounded-btn border border-border bg-s1 px-2 py-2 text-t1"
              onChange={e => setForm({ ...form, email: e.target.value })}
              type="email"
              value={form.email}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-t2">
            Rol
            <select
              className="rounded-btn border border-border bg-s1 px-2 py-2 text-t1"
              onChange={e => setForm({ ...form, role: e.target.value })}
              value={form.role}
            >
              {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
            </select>
          </label>
          <button
            className="rounded-btn bg-primary px-3 py-2 font-medium text-sm text-white hover:bg-primary2 disabled:opacity-50 sm:col-span-2"
            disabled={!formValido || ocupado === 'nuevo'}
            type="submit"
          >
            {ocupado === 'nuevo' ? 'Creando…' : 'Crear cuenta'}
          </button>
        </form>
      )}

      {error && <p className="rounded-card border border-danger/40 bg-danger/10 px-3 py-2 text-danger text-sm">{error}</p>}

      {usuarios === null && !error && (
        <p className="rounded-card border border-border p-6 text-center text-t4">Cargando cuentas…</p>
      )}

      {usuarios?.length === 0 && (
        <p className="rounded-card border border-border p-6 text-center text-t4">No hay cuentas.</p>
      )}

      {usuarios?.length > 0 && (
        <div className="overflow-x-auto rounded-card border border-border bg-s2">
          <table className="w-full caption-bottom text-sm">
            <thead className="[&_tr]:border-border [&_tr]:border-b">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-t3">Cuenta</th>
                <th className="px-4 py-3 text-left font-medium text-t3">Rol</th>
                <th className="px-4 py-3 text-left font-medium text-t3">Estado</th>
                <th className="px-4 py-3 text-right font-medium text-t3">Acciones</th>
              </tr>
            </thead>
            <tbody className="[&_tr:last-child]:border-0">
              {usuarios.map(u => {
                const enVuelo = ocupado === u.username;
                return (
                  <tr className="border-border border-b" key={u.id}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Avatar username={u.username} />
                        <div className="flex flex-col">
                          <span className="font-medium text-t1">{u.username}</span>
                          <span className="text-t3 text-xs">{u.email || 'sin email'}</span>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Badge tone={rolDe(u) === 'ADMIN' ? 'admin' : 'viewer'}>{rolDe(u)}</Badge>
                        {u.esServicio && <Badge tone="servicio">servicio</Badge>}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={u.activo ? 'activa' : 'inactiva'}>{u.activo ? 'Activa' : 'Inactiva'}</Badge>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <label className="sr-only" htmlFor={`rol-${u.id}`}>Rol de {u.username}</label>
                        <select
                          className="rounded-btn border border-border bg-s1 px-2 py-1 text-t1 text-xs disabled:opacity-50"
                          disabled={enVuelo}
                          id={`rol-${u.id}`}
                          onChange={e => ejecutar(u.username,
                            () => cambiarRolUsuario(u.username, e.target.value), 'Rol actualizado.')}
                          value={rolDe(u)}
                        >
                          {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                        </select>
                        {u.activo ? (
                          <button
                            className="rounded-btn border border-danger/40 px-2 py-1 text-danger text-xs hover:bg-danger/10 disabled:opacity-50"
                            disabled={enVuelo}
                            onClick={() => ejecutar(u.username,
                              () => desactivarUsuario(u.username), 'Cuenta desactivada.')}
                            type="button"
                          >
                            Desactivar
                          </button>
                        ) : (
                          <button
                            className="rounded-btn border border-border px-2 py-1 text-t2 text-xs hover:bg-s3 disabled:opacity-50"
                            disabled={enVuelo}
                            onClick={() => ejecutar(u.username,
                              () => reactivarUsuario(u.username), 'Cuenta reactivada.')}
                            type="button"
                          >
                            Reactivar
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <p className="text-t4 text-xs">
        Desactivar no borra: la cuenta conserva su auditoría y sus datos personales, y su
        próximo request es rechazado. La última cuenta ADMIN activa no se puede desactivar
        ni degradar — el backend lo niega.
      </p>
    </div>
  );
}
