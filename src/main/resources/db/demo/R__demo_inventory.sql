INSERT INTO inventory (sku, available)
VALUES ('CAFE-250G', 1), ('FILTRO-01', 10)
ON CONFLICT (sku) DO NOTHING;
