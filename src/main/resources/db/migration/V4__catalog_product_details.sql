ALTER TABLE commerce_catalog
    ADD COLUMN active boolean NOT NULL DEFAULT true,
    ADD COLUMN brand varchar(80) NOT NULL DEFAULT '',
    ADD COLUMN specs text[] NOT NULL DEFAULT '{}',
    ADD COLUMN display_order integer NOT NULL DEFAULT 1000;
