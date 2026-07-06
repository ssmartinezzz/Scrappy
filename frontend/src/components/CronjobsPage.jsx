// Admin panel for scraper cron jobs. Routed page for /cronjobs — owns its own
// fetch in local state (no lifted state to AppLayout's catalog reducer, same
// as CategoryPicksPage). The list is the shadcn Table + TanStack Table pattern
// the user supplied (sortable headers, numeric pagination, page-size select),
// ported to JSX + project tokens. Create/edit opens CronJobCard (the animated
// coach-card-style detail card) in a modal overlay.
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  flexRender, getCoreRowModel, getPaginationRowModel, getSortedRowModel, useReactTable,
} from '@tanstack/react-table';
import {
  Clock, Plus, Pencil, Play, Trash2, ChevronUp, ChevronDown,
  CheckCircle2, XCircle, Loader2, SkipForward, CircleAlert,
} from 'lucide-react';
import { listCronJobs, updateCronJob, deleteCronJob, runCronNow } from '../api';
import { cn } from '@/lib/utils';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from './ui/table';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from './ui/select';
import {
  Pagination, PaginationContent, PaginationItem, PaginationEllipsis,
} from './ui/pagination';
import { usePagination } from './hooks/use-pagination';
import CronJobCard from './cron/CronJobCard';

function fmtFecha(iso) {
  return iso ? iso.replace('T', ' ') : '—';
}

// Accessible on/off switch with a comfortable hit area.
function EnabledSwitch({ checked, onChange, label }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={onChange}
      className="relative inline-flex h-6 w-10 shrink-0 cursor-pointer items-center rounded-full border border-border transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-bg"
      style={{ backgroundColor: checked ? 'var(--p)' : 'var(--s3)' }}
    >
      <span
        className="inline-block h-4 w-4 rounded-full bg-white shadow transition-transform"
        style={{ transform: checked ? 'translateX(18px)' : 'translateX(3px)' }}
      />
    </button>
  );
}

// enabled + nextRunAt -> a schedule-status badge (data available in the list).
function estadoBadge(job) {
  if (!job.enabled) return <Badge variant="secondary">Pausado</Badge>;
  if (job.nextRunAt) return <Badge variant="default">Programado</Badge>;
  return <Badge variant="warning">Sin próxima</Badge>;
}

export default function CronjobsPage() {
  const [jobs, setJobs]           = useState([]);
  const [loading, setLoading]     = useState(true);
  const [editingJob, setEditingJob] = useState(null); // null=closed | {}=new | job=edit
  const [runMsg, setRunMsg]       = useState(null);    // { ok, mensaje }
  const [runningId, setRunningId] = useState(null);
  const [sorting, setSorting]     = useState([{ id: 'name', desc: false }]);
  const [pagination, setPagination] = useState({ pageIndex: 0, pageSize: 10 });

  const load = useCallback(async () => {
    setLoading(true);
    const data = await listCronJobs();
    setJobs(data?.jobs || []);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleToggle = useCallback(async (job) => {
    await updateCronJob(job.id, { ...job, enabled: !job.enabled });
    load();
  }, [load]);

  const handleDelete = useCallback(async (job) => {
    if (!window.confirm(`¿Eliminar el job "${job.name}"? No se puede deshacer.`)) return;
    await deleteCronJob(job.id);
    load();
  }, [load]);

  const handleRunNow = useCallback(async (job) => {
    setRunningId(job.id);
    const res = await runCronNow(job.id);
    setRunMsg({ ok: !!res?.ok, mensaje: res?.mensaje || (res?.ok ? 'Ejecución iniciada' : 'No se pudo iniciar') });
    setRunningId(null);
    setTimeout(() => setRunMsg(null), 5000);
    load();
  }, [load]);

  const columns = useMemo(() => [
    {
      header: 'Nombre',
      accessorKey: 'name',
      cell: ({ row }) => <div className="font-medium text-t1">{row.getValue('name')}</div>,
    },
    {
      header: 'Cron',
      accessorKey: 'cronExpr',
      enableSorting: false,
      cell: ({ row }) => <code className="font-mono text-xs text-t3">{row.getValue('cronExpr')}</code>,
    },
    {
      header: 'Estado',
      id: 'estado',
      enableSorting: false,
      cell: ({ row }) => estadoBadge(row.original),
    },
    {
      header: 'Próxima',
      accessorKey: 'nextRunAt',
      cell: ({ row }) => <span className="whitespace-nowrap tabular-nums text-t2">{fmtFecha(row.getValue('nextRunAt'))}</span>,
    },
    {
      header: 'Última',
      accessorKey: 'lastRunAt',
      cell: ({ row }) => <span className="whitespace-nowrap tabular-nums text-t3">{fmtFecha(row.getValue('lastRunAt'))}</span>,
    },
    {
      header: 'Activo',
      id: 'activo',
      enableSorting: false,
      cell: ({ row }) => (
        <EnabledSwitch
          checked={row.original.enabled}
          onChange={() => handleToggle(row.original)}
          label={row.original.enabled ? 'Deshabilitar job' : 'Habilitar job'}
        />
      ),
    },
    {
      header: () => <span className="sr-only">Acciones</span>,
      id: 'acciones',
      enableSorting: false,
      cell: ({ row }) => {
        const job = row.original;
        return (
          <div className="flex items-center justify-end gap-1">
            <Button variant="outline" size="sm" onClick={() => setEditingJob(job)}>
              <Pencil aria-hidden="true" className="mr-1 h-3.5 w-3.5" strokeWidth={2} /> Editar
            </Button>
            <Button variant="ghost" size="sm" disabled={runningId === job.id} onClick={() => handleRunNow(job)}>
              <Play aria-hidden="true" className="mr-1 h-3.5 w-3.5" strokeWidth={2} /> Ejecutar
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Eliminar ${job.name}`}
              className="h-7 w-7 text-t4 hover:border-danger hover:text-danger"
              onClick={() => handleDelete(job)}
            >
              <Trash2 aria-hidden="true" className="h-4 w-4" strokeWidth={1.75} />
            </Button>
          </div>
        );
      },
    },
  ], [handleToggle, handleRunNow, handleDelete, runningId]);

  const table = useReactTable({
    data: jobs,
    columns,
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

  return (
    <div className="mx-auto w-full max-w-6xl px-2 py-3">
      {/* Header */}
      <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h1 className="flex items-center gap-2 font-display text-display-2 text-t1">
          <Clock aria-hidden="true" className="h-7 w-7 text-primary" strokeWidth={2} />
          Cron Jobs
        </h1>
        <Button onClick={() => setEditingJob({})}>
          <Plus aria-hidden="true" className="mr-1 h-4 w-4" strokeWidth={2.5} /> Nuevo job
        </Button>
      </header>

      {/* run-now feedback */}
      {runMsg && (
        <div
          role="status"
          aria-live="polite"
          className={cn('mb-3 flex items-center gap-2 rounded-btn border px-3 py-2 text-sm',
            runMsg.ok ? 'border-success text-success' : 'border-danger text-danger')}
          style={{ backgroundColor: runMsg.ok ? 'color-mix(in srgb, var(--g) 12%, transparent)' : 'color-mix(in srgb, var(--r) 12%, transparent)' }}
        >
          {runMsg.ok
            ? <CheckCircle2 aria-hidden="true" className="h-4 w-4 shrink-0" strokeWidth={2} />
            : <CircleAlert aria-hidden="true" className="h-4 w-4 shrink-0" strokeWidth={2} />}
          <span>{runMsg.mensaje}</span>
        </div>
      )}

      {/* Body */}
      {loading ? (
        <div className="rounded-card border border-border p-6 text-center text-t4">
          <Loader2 aria-hidden="true" className="mx-auto h-6 w-6 animate-spin" strokeWidth={2} />
        </div>
      ) : jobs.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-card border border-border bg-s2 p-6 text-center">
          <Clock aria-hidden="true" className="h-10 w-10 text-t4" strokeWidth={1.5} />
          <div>
            <p className="font-semibold text-t2">Todavía no hay cron jobs</p>
            <p className="mt-1 text-sm text-t4">Programá un scraping automático para que corra solo.</p>
          </div>
          <Button onClick={() => setEditingJob({})}>
            <Plus aria-hidden="true" className="mr-1 h-4 w-4" strokeWidth={2.5} /> Crear el primero
          </Button>
        </div>
      ) : (
        <>
          <div className="overflow-hidden rounded-card border border-border bg-s2">
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map(hg => (
                  <TableRow key={hg.id} className="bg-s3 hover:bg-s3">
                    {hg.headers.map(header => (
                      <TableHead key={header.id} className="h-11 whitespace-nowrap text-xs uppercase tracking-wide">
                        {header.isPlaceholder ? null : header.column.getCanSort() ? (
                          <button
                            type="button"
                            className="flex h-full cursor-pointer select-none items-center gap-1"
                            onClick={header.column.getToggleSortingHandler()}
                          >
                            {flexRender(header.column.columnDef.header, header.getContext())}
                            {{
                              asc: <ChevronUp className="h-3.5 w-3.5 opacity-60" strokeWidth={2} aria-hidden="true" />,
                              desc: <ChevronDown className="h-3.5 w-3.5 opacity-60" strokeWidth={2} aria-hidden="true" />,
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
                {table.getRowModel().rows.map(row => (
                  <TableRow key={row.id}>
                    {row.getVisibleCells().map(cell => (
                      <TableCell key={cell.id} className="text-t2">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          {/* Pagination — only when it earns its place */}
          {table.getPageCount() > 1 && (
            <div className="mt-3 flex items-center justify-between gap-3 max-sm:flex-col">
              <p className="flex-1 whitespace-nowrap text-sm text-t4" aria-live="polite">
                Página <span className="text-t1">{table.getState().pagination.pageIndex + 1}</span> de{' '}
                <span className="text-t1">{table.getPageCount()}</span>
              </p>

              <div className="grow">
                <Pagination>
                  <PaginationContent>
                    <PaginationItem>
                      <Button size="icon" variant="outline" className="disabled:opacity-50"
                        onClick={() => table.previousPage()} disabled={!table.getCanPreviousPage()} aria-label="Página anterior">
                        <ChevronUp className="h-4 w-4 -rotate-90" strokeWidth={2} aria-hidden="true" />
                      </Button>
                    </PaginationItem>
                    {showLeftEllipsis && <PaginationItem><PaginationEllipsis /></PaginationItem>}
                    {pages.map(page => {
                      const isActive = page === table.getState().pagination.pageIndex + 1;
                      return (
                        <PaginationItem key={page}>
                          <Button size="icon" variant={isActive ? 'outline' : 'ghost'}
                            onClick={() => table.setPageIndex(page - 1)} aria-current={isActive ? 'page' : undefined}>
                            {page}
                          </Button>
                        </PaginationItem>
                      );
                    })}
                    {showRightEllipsis && <PaginationItem><PaginationEllipsis /></PaginationItem>}
                    <PaginationItem>
                      <Button size="icon" variant="outline" className="disabled:opacity-50"
                        onClick={() => table.nextPage()} disabled={!table.getCanNextPage()} aria-label="Página siguiente">
                        <ChevronDown className="h-4 w-4 -rotate-90" strokeWidth={2} aria-hidden="true" />
                      </Button>
                    </PaginationItem>
                  </PaginationContent>
                </Pagination>
              </div>

              <div className="flex flex-1 justify-end">
                <Select value={String(table.getState().pagination.pageSize)} onValueChange={v => table.setPageSize(Number(v))}>
                  <SelectTrigger className="w-fit whitespace-nowrap" aria-label="Filas por página">
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

      {/* Create / edit dialog */}
      {editingJob !== null && (
        <CronJobCard
          job={editingJob}
          onClose={() => setEditingJob(null)}
          onSaved={() => { setEditingJob(null); load(); }}
        />
      )}
    </div>
  );
}
