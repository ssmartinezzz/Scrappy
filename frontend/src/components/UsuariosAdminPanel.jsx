// ABM de cuentas — la UI que `UsuarioAdminEndpoints` dice explícitamente que no
// traía ("Backend only, deliberately"). Toda la superficie /api/usuarios/** es
// ADMIN en ApiRoutePolicy.TABLE; el gate de ruta vive en App.jsx.
//
// Tabla shadcn + TanStack, el mismo patrón que CronjobsPage: los primitivos ya
// están portados a JSX en ./ui, así que no hay nada que instalar.
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  flexRender, getCoreRowModel, getPaginationRowModel, getSortedRowModel, useReactTable,
} from '@tanstack/react-table';
import { ChevronDown, ChevronUp, RefreshCw, Search, UserPlus, X } from 'lucide-react';

import {
  cambiarRolUsuario, crearUsuario, desactivarUsuario, fetchUsuarios, reactivarUsuario,
} from '../api';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Checkbox } from './ui/checkbox';
import { Input } from './ui/input';
import { Label } from './ui/label';
import {
  Pagination, PaginationContent, PaginationEllipsis, PaginationItem,
} from './ui/pagination';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from './ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from './ui/table';
import { usePagination } from './hooks/use-pagination';

// El vocabulario lo siembra V1 (`INSERT INTO rol VALUES ('ADMIN'),('VIEWER')`)
// y el backend valida contra la tabla. Si alguna vez se agrega un rol, esta
// lista queda corta y el select deja de ofrecerlo — el backend lo aceptaría.
const ROLES = ['ADMIN', 'VIEWER'];

const MIN_PASSWORD = 8;   // UsuarioAdminEndpoints.MIN_PASSWORD

/** El rol que mostramos es el primero; hoy una cuenta tiene exactamente uno. */
function rolDe(u) {
  return u.roles?.[0] || '—';
}

function Avatar({ username }) {
  return (
    <div
      aria-hidden="true"
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-s3 font-semibold text-t2 text-xs"
    >
      {username.slice(0, 2).toUpperCase()}
    </div>
  );
}

export default function UsuariosAdminPanel() {
  const [usuarios, setUsuarios] = useState(null);
  const [error, setError]       = useState('');
  const [aviso, setAviso]       = useState(null);   // { ok, texto }
  const [ocupado, setOcupado]   = useState('');     // username en vuelo, o 'nuevo'
  const [creando, setCreando]   = useState(false);
  const [form, setForm]         = useState({ username: '', password: '', email: '', role: 'VIEWER' });

  const [busqueda, setBusqueda]         = useState('');
  const [verInactivas, setVerInactivas] = useState(false);
  const [sorting, setSorting]           = useState([{ id: 'username', desc: false }]);
  const [pagination, setPagination]     = useState({ pageIndex: 0, pageSize: 10 });

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
  const ejecutar = useCallback(async (clave, accion, exito) => {
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
  }, [cargar]);

  // Las inactivas quedan FUERA salvo que se pidan. Desactivar no borra, así
  // que la lista sólo crece: mostrarlas siempre hace que la pantalla se llene
  // con cuentas que ya no operan y entierre las que sí.
  const filtradas = useMemo(() => {
    if (!usuarios) return [];
    const q = busqueda.trim().toLowerCase();
    return usuarios.filter(u => {
      if (!verInactivas && !u.activo) return false;
      if (!q) return true;
      return u.username.toLowerCase().includes(q)
          || (u.email || '').toLowerCase().includes(q);
    });
  }, [usuarios, busqueda, verInactivas]);

  const inactivasOcultas = useMemo(
    () => (usuarios && !verInactivas ? usuarios.filter(u => !u.activo).length : 0),
    [usuarios, verInactivas],
  );

  const columns = useMemo(() => [
    {
      id: 'username',
      accessorKey: 'username',
      header: 'Cuenta',
      cell: ({ row }) => {
        const u = row.original;
        return (
          <div className="flex items-center gap-2">
            <Avatar username={u.username} />
            <div className="flex min-w-0 flex-col">
              <span className="truncate font-medium text-t1">{u.username}</span>
              <span className="truncate text-t3 text-xs">{u.email || 'sin email'}</span>
            </div>
          </div>
        );
      },
    },
    {
      id: 'rol',
      accessorFn: rolDe,
      header: 'Rol',
      cell: ({ row }) => (
        <div className="flex flex-wrap items-center gap-1">
          <Badge variant={rolDe(row.original) === 'ADMIN' ? 'default' : 'secondary'}>
            {rolDe(row.original)}
          </Badge>
          {row.original.esServicio && <Badge variant="warning">servicio</Badge>}
        </div>
      ),
    },
    {
      id: 'estado',
      accessorFn: u => (u.activo ? 'Activa' : 'Inactiva'),
      header: 'Estado',
      cell: ({ row }) => (
        <Badge variant={row.original.activo ? 'success' : 'outline'}>
          {row.original.activo ? 'Activa' : 'Inactiva'}
        </Badge>
      ),
    },
    {
      id: 'acciones',
      header: () => <span className="sr-only">Acciones</span>,
      enableSorting: false,
      cell: ({ row }) => {
        const u = row.original;
        const enVuelo = ocupado === u.username;
        return (
          <div className="flex flex-wrap items-center justify-end gap-1">
            <Label className="sr-only" htmlFor={`rol-${u.id}`}>Rol de {u.username}</Label>
            <Select
              disabled={enVuelo}
              onValueChange={v => ejecutar(u.username,
                () => cambiarRolUsuario(u.username, v), 'Rol actualizado.')}
              value={rolDe(u)}
            >
              <SelectTrigger className="h-7 w-fit whitespace-nowrap text-xs" id={`rol-${u.id}`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ROLES.map(r => <SelectItem key={r} value={r}>{r}</SelectItem>)}
              </SelectContent>
            </Select>
            {u.activo ? (
              <Button
                className="text-danger hover:border-danger hover:text-danger"
                disabled={enVuelo}
                onClick={() => ejecutar(u.username,
                  () => desactivarUsuario(u.username), 'Cuenta desactivada.')}
                size="sm"
                variant="ghost"
              >
                Desactivar
              </Button>
            ) : (
              <Button
                disabled={enVuelo}
                onClick={() => ejecutar(u.username,
                  () => reactivarUsuario(u.username), 'Cuenta reactivada.')}
                size="sm"
                variant="ghost"
              >
                Reactivar
              </Button>
            )}
          </div>
        );
      },
    },
  ], [ejecutar, ocupado]);

  const table = useReactTable({
    columns,
    data: filtradas,
    state: { sorting, pagination },
    onSortingChange: setSorting,
    onPaginationChange: setPagination,
    enableSortingRemoval: false,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  const { pages, showLeftEllipsis, showRightEllipsis } = usePagination({
    currentPage: table.getState().pagination.pageIndex + 1,
    totalPages: table.getPageCount(),
    paginationItemsToDisplay: 5,
  });

  function onCrear(e) {
    e.preventDefault();
    return ejecutar('nuevo', () => crearUsuario(form), 'Cuenta creada.').then(() => {
      setForm({ username: '', password: '', email: '', role: 'VIEWER' });
    });
  }

  const formValido = form.username.trim() && form.password.length >= MIN_PASSWORD;

  return (
    <div className="mx-auto w-full max-w-6xl px-2 py-3">
      {/* En mobile los dos botones se van a una fila propia y se reparten el
          ancho. Antes iban en la misma fila que el título con `gap-1`, así que
          en pantallas angostas se apretaban contra el borde. */}
      <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="text-eyebrow text-t3">Administración</p>
          <h1 className="font-display text-display-2 text-t1">Cuentas</h1>
        </div>
        <div className="flex w-full gap-1 sm:w-auto">
          <Button className="flex-1 sm:flex-none" onClick={cargar} variant="ghost">
            <RefreshCw aria-hidden="true" className="mr-1 h-4 w-4" strokeWidth={2} /> Recargar
          </Button>
          <Button className="flex-1 sm:flex-none" onClick={() => setCreando(v => !v)}>
            <UserPlus aria-hidden="true" className="mr-1 h-4 w-4" strokeWidth={2.5} /> Nueva cuenta
          </Button>
        </div>
      </header>

      {aviso && (
        <p
          className={`mb-3 rounded-card border px-3 py-2 text-sm ${
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
        <form className="mb-3 grid gap-2 rounded-card border border-border bg-s2 p-3 sm:grid-cols-2" onSubmit={onCrear}>
          <div className="flex flex-col gap-1">
            <Label htmlFor="nuevo-username">Usuario</Label>
            <Input
              id="nuevo-username"
              onChange={e => setForm({ ...form, username: e.target.value })}
              required
              value={form.username}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="nuevo-password">
              Contraseña <span className="text-t4 text-xs">(mínimo {MIN_PASSWORD})</span>
            </Label>
            <Input
              id="nuevo-password"
              minLength={MIN_PASSWORD}
              onChange={e => setForm({ ...form, password: e.target.value })}
              required
              type="password"
              value={form.password}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="nuevo-email">Email <span className="text-t4 text-xs">(opcional)</span></Label>
            <Input
              id="nuevo-email"
              onChange={e => setForm({ ...form, email: e.target.value })}
              type="email"
              value={form.email}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="nuevo-rol">Rol</Label>
            <Select onValueChange={v => setForm({ ...form, role: v })} value={form.role}>
              <SelectTrigger id="nuevo-rol"><SelectValue /></SelectTrigger>
              <SelectContent>
                {ROLES.map(r => <SelectItem key={r} value={r}>{r}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <Button className="sm:col-span-2" disabled={!formValido || ocupado === 'nuevo'} type="submit">
            {ocupado === 'nuevo' ? 'Creando…' : 'Crear cuenta'}
          </Button>
        </form>
      )}

      {/* Buscador + filtro de estado */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 sm:max-w-xs">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-t4"
            strokeWidth={2}
          />
          <Input
            aria-label="Buscar por usuario o email"
            className="px-9"
            onChange={e => setBusqueda(e.target.value)}
            placeholder="Buscar usuario o email…"
            value={busqueda}
          />
          {busqueda && (
            <button
              aria-label="Limpiar búsqueda"
              className="absolute right-0 top-0 flex h-9 w-9 items-center justify-center text-t4 hover:text-t1"
              onClick={() => setBusqueda('')}
              type="button"
            >
              <X aria-hidden="true" className="h-4 w-4" strokeWidth={2} />
            </button>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Checkbox
            checked={verInactivas}
            id="ver-inactivas"
            onCheckedChange={v => setVerInactivas(v === true)}
          />
          <Label className="whitespace-nowrap font-normal" htmlFor="ver-inactivas">
            Mostrar inactivas
            {inactivasOcultas > 0 && <span className="ml-1 text-t4">({inactivasOcultas})</span>}
          </Label>
        </div>
      </div>

      {error && (
        <p className="rounded-card border border-danger/40 bg-danger/10 px-3 py-2 text-danger text-sm">{error}</p>
      )}

      {usuarios === null && !error && (
        <p className="rounded-card border border-border p-6 text-center text-t4">Cargando cuentas…</p>
      )}

      {usuarios !== null && (
        <>
          <div className="overflow-x-auto rounded-card border border-border bg-s2">
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map(hg => (
                  <TableRow key={hg.id}>
                    {hg.headers.map(header => (
                      <TableHead key={header.id}>
                        {header.isPlaceholder ? null : header.column.getCanSort() ? (
                          <button
                            className="flex cursor-pointer select-none items-center gap-1"
                            onClick={header.column.getToggleSortingHandler()}
                            type="button"
                          >
                            {flexRender(header.column.columnDef.header, header.getContext())}
                            {{
                              asc:  <ChevronUp aria-hidden="true" className="h-4 w-4 shrink-0 opacity-60" />,
                              desc: <ChevronDown aria-hidden="true" className="h-4 w-4 shrink-0 opacity-60" />,
                            }[header.column.getIsSorted()] ?? null}
                          </button>
                        ) : (
                          flexRender(header.column.columnDef.header, header.getContext())
                        )}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.length > 0 ? (
                  table.getRowModel().rows.map(row => (
                    <TableRow key={row.id}>
                      {row.getVisibleCells().map(cell => (
                        <TableCell key={cell.id}>
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                ) : (
                  <TableRow>
                    <TableCell className="h-24 text-center text-t4" colSpan={columns.length}>
                      {busqueda
                        ? 'Ninguna cuenta coincide con la búsqueda.'
                        : 'No hay cuentas activas.'}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>

          {/* Paginación — sólo cuando se la gana, igual que CronjobsPage. */}
          {table.getPageCount() > 1 && (
            <div className="mt-3 flex items-center justify-between gap-3 max-sm:flex-col">
              <p aria-live="polite" className="flex-1 whitespace-nowrap text-sm text-t4">
                Página <span className="text-t1">{table.getState().pagination.pageIndex + 1}</span> de{' '}
                <span className="text-t1">{table.getPageCount()}</span>
              </p>

              <div className="grow">
                <Pagination>
                  <PaginationContent>
                    <PaginationItem>
                      <Button
                        aria-label="Página anterior"
                        className="disabled:opacity-50"
                        disabled={!table.getCanPreviousPage()}
                        onClick={() => table.previousPage()}
                        size="icon"
                        variant="outline"
                      >
                        <ChevronUp aria-hidden="true" className="h-4 w-4 -rotate-90" strokeWidth={2} />
                      </Button>
                    </PaginationItem>
                    {showLeftEllipsis && <PaginationItem><PaginationEllipsis /></PaginationItem>}
                    {pages.map(page => {
                      const isActive = page === table.getState().pagination.pageIndex + 1;
                      return (
                        <PaginationItem key={page}>
                          <Button
                            aria-current={isActive ? 'page' : undefined}
                            onClick={() => table.setPageIndex(page - 1)}
                            size="icon"
                            variant={isActive ? 'outline' : 'ghost'}
                          >
                            {page}
                          </Button>
                        </PaginationItem>
                      );
                    })}
                    {showRightEllipsis && <PaginationItem><PaginationEllipsis /></PaginationItem>}
                    <PaginationItem>
                      <Button
                        aria-label="Página siguiente"
                        className="disabled:opacity-50"
                        disabled={!table.getCanNextPage()}
                        onClick={() => table.nextPage()}
                        size="icon"
                        variant="outline"
                      >
                        <ChevronDown aria-hidden="true" className="h-4 w-4 -rotate-90" strokeWidth={2} />
                      </Button>
                    </PaginationItem>
                  </PaginationContent>
                </Pagination>
              </div>

              <div className="flex flex-1 justify-end">
                <Select
                  onValueChange={v => table.setPageSize(Number(v))}
                  value={String(table.getState().pagination.pageSize)}
                >
                  <SelectTrigger aria-label="Filas por página" className="w-fit whitespace-nowrap">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {[10, 25, 50].map(sz => <SelectItem key={sz} value={String(sz)}>{sz} / página</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </div>
          )}
        </>
      )}

      <p className="mt-3 text-t4 text-xs">
        Desactivar no borra: la cuenta conserva su auditoría y sus datos personales, y su
        próximo request es rechazado. La última cuenta ADMIN activa no se puede desactivar
        ni degradar — el backend lo niega.
      </p>
    </div>
  );
}
