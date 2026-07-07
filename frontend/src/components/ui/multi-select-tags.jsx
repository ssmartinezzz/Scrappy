// Grouped, animated tag-picker — controlled presentational primitive.
// Renders a "Seleccionados" row (shared-element chips) above N group
// sections, each showing only its unselected tags. A tag mounts in
// EXACTLY ONE place at a time (its group OR the selected row), so the
// single-instance `layoutId` invariant holds and framer-motion animates
// the chip flying between the two locations.
//
// Fully controlled: no internal selection state. Consumer owns the
// `selected` Set and reacts to `onToggle`.
import * as React from 'react';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';

const LAYOUT_TRANSITION = { type: 'spring', stiffness: 500, damping: 34 };
const ENTER_TRANSITION = { duration: 0.18 };
const EXIT_TRANSITION = { duration: 0.12 };
const REDUCED_TRANSITION = { duration: 0 };

function Chip({ tag, isSelected, onToggle, reduceMotion }) {
  return (
    <motion.button
      type="button"
      layout={!reduceMotion}
      layoutId={reduceMotion ? undefined : tag}
      initial={reduceMotion ? false : { opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={reduceMotion ? undefined : { opacity: 0, scale: 0.9 }}
      transition={
        reduceMotion
          ? REDUCED_TRANSITION
          : { layout: LAYOUT_TRANSITION, default: isSelected ? ENTER_TRANSITION : EXIT_TRANSITION }
      }
      aria-pressed={isSelected}
      aria-label={isSelected ? `Quitar ${tag}` : `Agregar ${tag}`}
      onClick={() => onToggle(tag)}
      className={cn(
        'inline-flex min-h-[44px] shrink-0 cursor-pointer items-center gap-[6px] rounded-btn px-[14px] py-[8px] text-[.9rem]',
        '[touch-action:manipulation] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
        isSelected
          ? 'border border-transparent bg-primary text-white'
          : 'border border-bd2 bg-s2 text-t2 hover:border-primary'
      )}
    >
      {tag}
      {isSelected && <X size={14} aria-hidden="true" />}
    </motion.button>
  );
}

const MultiSelectTags = React.forwardRef(({
  groups,
  selected,
  onToggle,
  selectedLabel = 'Seleccionados',
  id,
  className,
  ...props
}, ref) => {
  const reduceMotion = useReducedMotion();
  const selectedTags = groups.flatMap(g => g.tags.filter(tag => selected.has(tag)));

  return (
    <div ref={ref} id={id} className={cn('flex flex-col gap-[16px]', className)} {...props}>
      <div>
        <p className="mb-[8px] text-[.72rem] font-bold uppercase tracking-[.1em] text-t4">
          {selectedLabel}
        </p>
        <motion.div
          layout={!reduceMotion}
          className="flex flex-nowrap gap-[8px] overflow-x-auto [-webkit-overflow-scrolling:touch]"
        >
          <AnimatePresence initial={false}>
            {selectedTags.length === 0 ? (
              <motion.p
                key="empty-hint"
                initial={reduceMotion ? false : { opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={reduceMotion ? undefined : { opacity: 0 }}
                transition={reduceMotion ? REDUCED_TRANSITION : ENTER_TRANSITION}
                className="text-[.85rem] text-t4"
              >
                Todavía no seleccionaste ninguno.
              </motion.p>
            ) : (
              selectedTags.map(tag => (
                <Chip
                  key={tag}
                  tag={tag}
                  isSelected
                  onToggle={onToggle}
                  reduceMotion={reduceMotion}
                />
              ))
            )}
          </AnimatePresence>
        </motion.div>
      </div>

      {groups.map(group => {
        const availableTags = group.tags.filter(tag => !selected.has(tag));
        return (
          <div key={group.label}>
            <p className="mb-[8px] text-[.72rem] font-bold uppercase tracking-[.1em] text-t4">
              {group.label}
            </p>
            <motion.div layout={!reduceMotion} className="flex flex-wrap gap-[8px]">
              <AnimatePresence initial={false}>
                {availableTags.map(tag => (
                  <Chip
                    key={tag}
                    tag={tag}
                    isSelected={false}
                    onToggle={onToggle}
                    reduceMotion={reduceMotion}
                  />
                ))}
              </AnimatePresence>
            </motion.div>
          </div>
        );
      })}
    </div>
  );
});
MultiSelectTags.displayName = 'MultiSelectTags';

export { MultiSelectTags };
