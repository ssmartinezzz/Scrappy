import * as React from 'react';
import { ResponsiveContainer, Tooltip } from 'recharts';
import { cn } from '@/lib/utils';

/**
 * Envoltorio responsive para los gráficos de Recharts.
 *
 * Es un port del `ChartContainer` de shadcn, con tres adaptaciones que no son
 * opcionales en este proyecto:
 *
 * 1. **Tokens.** El original usa la paleta de shadcn (`muted-foreground`,
 *    `background`, `border`, `popover`). Acá el `tailwind.config.js` define
 *    `t1..t4`, `s1..s3`, `border`, `primary`. Pegado tal cual, cada una de esas
 *    clases resuelve a nada y el gráfico sale sin estilo **sin ningún error**:
 *    Tailwind no avisa por una clase que no existe.
 * 2. **Tailwind 3, no 4.** `outline-hidden` y `bg-(--var)` son sintaxis de v4.
 *    Acá van `outline-none` y `bg-[var(--var)]`.
 * 3. **Sin `ChartStyle`/`ChartLegend`/`ChartTooltipContent`.** El original los
 *    trae para configurar series por CSS var y renderizar leyendas. Esta vista
 *    tiene UNA serie, su propio tooltip y sus propios tokens, así que serían
 *    ~200 líneas sin un solo caller. En un codebase sin tipos que las sostenga,
 *    código genérico que nadie ejercita se pudre callado. Si mañana entra un
 *    gráfico multi-serie, se portan entonces.
 */
export function ChartContainer({ className, children, ...props }) {
  return (
    <div
      data-slot="chart"
      className={cn(
        'flex justify-center text-xs',
        "[&_.recharts-cartesian-axis-tick_text]:fill-[var(--t4)]",
        "[&_.recharts-cartesian-grid_line[stroke='#ccc']]:stroke-[var(--bd)]",
        '[&_.recharts-curve.recharts-tooltip-cursor]:stroke-[var(--bd)]',
        "[&_.recharts-dot[stroke='#fff']]:stroke-transparent",
        '[&_.recharts-layer]:outline-none',
        '[&_.recharts-sector]:outline-none',
        '[&_.recharts-surface]:outline-none',
        className,
      )}
      {...props}
    >
      <ResponsiveContainer>{children}</ResponsiveContainer>
    </div>
  );
}

/** Re-export, para que la vista importe el gráfico entero de un solo lugar. */
export const ChartTooltip = Tooltip;
