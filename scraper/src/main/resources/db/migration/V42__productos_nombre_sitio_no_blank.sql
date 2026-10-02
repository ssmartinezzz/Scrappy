-- V42__productos_nombre_sitio_no_blank.sql — backend-dedup-patterns, Phase C. Full rationale
-- in docs/DATABASE.md; this header is just the pointer.
--
-- A product row needs a visible nombre and sitio: Product's constructor rejects a blank one, and
-- ProductRowMapper builds a Product from every row it reads. V1 already forbids a missing value;
-- these two checks also forbid an empty or whitespace-only one. Dev data had no violating rows
-- (33,455 checked), so both ship valid.

ALTER TABLE productos
    ADD CONSTRAINT chk_productos_nombre_not_blank CHECK (nombre ~ '\S');

ALTER TABLE productos
    ADD CONSTRAINT chk_productos_sitio_not_blank CHECK (sitio ~ '\S');
