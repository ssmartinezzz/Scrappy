-- LEGO sets of a toy store whose curated leaf maps to Juguete (V78: 'Lego', 'Sets de Ladrillos y Bloques') were never
-- split out: the leaf outranked the name classifier and skipped the LEGO split V96 applied once. Same rule as V96 step 6,
-- re-run over the rows scraped since. Whole word ("Legolas" is not a LEGO); Funko stays a Juguete; books keep Libro
-- (only Juguete rows are touched); blocked rows are never touched. Idempotent.
UPDATE productos
   SET categoria = 'LEGO', rubro = 'juguetes', sub_categoria = ''
 WHERE categoria = 'Juguete'
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' lego[ :]'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ ' funko';
