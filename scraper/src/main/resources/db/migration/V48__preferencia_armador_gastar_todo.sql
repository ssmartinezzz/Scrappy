-- V48__preferencia_armador_gastar_todo.sql — modo "gastar todo" del armador de PCs
--
-- `gastar_todo` recuerda la elección Ahorrar / Gastar todo: con presupuesto, lo que
-- le sobró al armado sube la motherboard. Booleano NOT NULL DEFAULT false como
-- `ram_dual`/`wifi`: false es el comportamiento de siempre ("ahorrar"), así que las
-- filas existentes no cambian.

ALTER TABLE preferencia_armador
    ADD COLUMN gastar_todo boolean NOT NULL DEFAULT false;
