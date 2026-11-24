INSERT INTO inventory (sku, available) VALUES
    ('CAFE-250G', 1), ('FILTRO-01', 10), ('CANECA-01', 8),
    ('MOEDOR-01', 4), ('BALANCA-01', 6), ('PRENSA-01', 5)
ON CONFLICT (sku) DO NOTHING;

INSERT INTO commerce_catalog (sku, name, description, category, price_cents, image_key) VALUES
    ('CAFE-250G', 'Café Serra Clara', 'Grãos de torra média com notas de chocolate, laranja e caramelo. Pacote de 250 g.', 'Cafés', 4890, 'cafe'),
    ('FILTRO-01', 'Filtros V60 01', 'Pacote com 40 filtros de papel para coados mais limpos e aromáticos.', 'Preparo', 2490, 'filtro'),
    ('CANECA-01', 'Caneca Terracota', 'Cerâmica esmaltada de 280 ml, com acabamento fosco e alça confortável.', 'Acessórios', 5900, 'caneca'),
    ('MOEDOR-01', 'Moedor Manual', 'Moagem ajustável e corpo em aço para preparar o café no seu ritmo.', 'Preparo', 17900, 'moedor'),
    ('BALANCA-01', 'Balança de Precisão', 'Acompanhe a receita com precisão de 0,1 g e cronômetro integrado.', 'Acessórios', 11900, 'balanca'),
    ('PRENSA-01', 'Prensa Francesa', 'Prensa de vidro de 350 ml para uma extração encorpada em poucos minutos.', 'Preparo', 10900, 'prensa')
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
    category = EXCLUDED.category, price_cents = EXCLUDED.price_cents, image_key = EXCLUDED.image_key;
