-- Se incluye con `\ir guarda.sql` al principio de todo .sql de esta carpeta que escribe o borra a lo
-- bestia. Corta el script entero si la base no es una descartable de perf (perf_*), también cuando
-- alguien lo corre con psql a mano contra otra base.
\set ON_ERROR_STOP on
DO $$
BEGIN
  IF current_database() NOT LIKE 'perf\_%' THEN
    RAISE EXCEPTION 'rechazado: % no es una base de perf (perf_*); estos scripts borran y siembran a lo bestia',
      current_database();
  END IF;
END
$$;
