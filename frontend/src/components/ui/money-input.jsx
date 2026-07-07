// Money input — composes ui/input.jsx (does NOT edit it) with `$`/`ARS`
// adornments and live, caret-safe `es-AR` thousands formatting.
//
// The canonical `value` the parent stores/reads is ALWAYS a digits-only
// string (e.g. "150000" or ""), so `Number(value)` downstream keeps working
// unchanged. The formatted "150.000" display is derived every render — this
// component holds no local display state as source of truth.
import * as React from 'react';
import { cn } from '@/lib/utils';
import { Input } from './input';

function formatDigits(digits) {
  return digits ? Number(digits).toLocaleString('es-AR') : '';
}

const MoneyInput = React.forwardRef(({
  value,
  onChange,
  id,
  placeholder,
  className,
  ...props
}, ref) => {
  const inputRef = React.useRef(null);
  const caretRef = React.useRef(null);

  React.useImperativeHandle(ref, () => inputRef.current);

  const display = formatDigits(value);

  function handleChange(e) {
    const el = e.target;
    const caret = el.selectionStart ?? el.value.length;
    const digitsBefore = el.value.slice(0, caret).replace(/\D/g, '').length;
    const digits = el.value.replace(/\D/g, '');
    caretRef.current = digitsBefore;
    onChange?.(digits);
  }

  React.useLayoutEffect(() => {
    const el = inputRef.current;
    if (!el || caretRef.current == null) return;
    const digitsBefore = caretRef.current;
    let seen = 0;
    let pos = 0;
    for (; pos < display.length; pos++) {
      if (/\d/.test(display[pos])) seen++;
      if (seen >= digitsBefore) {
        pos++;
        break;
      }
    }
    el.setSelectionRange(pos, pos);
  }, [value, display]);

  return (
    <div className="relative">
      <span
        aria-hidden="true"
        className="pointer-events-none absolute left-[12px] top-1/2 -translate-y-1/2 text-t3"
      >
        $
      </span>
      <Input
        ref={inputRef}
        id={id}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        placeholder={placeholder}
        value={display}
        onChange={handleChange}
        className={cn('min-h-[44px] pl-[26px] pr-[48px] text-right tabular-nums', className)}
        {...props}
      />
      <span
        aria-hidden="true"
        className="pointer-events-none absolute right-[12px] top-1/2 -translate-y-1/2 text-[.8rem] font-medium tracking-[.04em] text-t4"
      >
        ARS
      </span>
    </div>
  );
});
MoneyInput.displayName = 'MoneyInput';

export { MoneyInput };
