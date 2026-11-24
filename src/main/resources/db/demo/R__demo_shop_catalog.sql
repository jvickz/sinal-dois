-- Previous demo orders and reservations keep their original SKUs and balances.
UPDATE commerce_catalog SET active = false
WHERE sku IN ('CAFE-250G', 'FILTRO-01', 'CANECA-01', 'MOEDOR-01', 'BALANCA-01', 'PRENSA-01');

INSERT INTO inventory (sku, available) VALUES
    ('IPHONE-15-128', 1), ('PS5-SLIM', 10), ('MACBOOK-AIR-M3', 4),
    ('THINKPAD-T14', 7), ('SWITCH-OLED', 8), ('MAC-MINI-M4', 5)
ON CONFLICT (sku) DO NOTHING;

-- Illustrative prices in BRL cents for the demo storefront.
INSERT INTO commerce_catalog (sku, name, description, category, price_cents, image_key, brand, specs, display_order) VALUES
    ('IPHONE-15-128', 'iPhone 15',
     'A câmera principal de 48 MP captura detalhes em pouca luz. A porta USB-C serve para carregar o aparelho e transferir fotos.',
     'iPhones', 429900, 'iphone', 'Apple', ARRAY['128 GB', 'A16 Bionic', 'OLED de 6,1″'], 10),
    ('PS5-SLIM', 'PlayStation 5 Slim',
     'Edição com leitor de discos para sua coleção física. O controle DualSense acompanha o console.',
     'Videogames', 379900, 'ps5', 'Sony', ARRAY['SSD de 1 TB', 'Leitor Blu-ray 4K', 'Controle DualSense'], 20),
    ('MACBOOK-AIR-M3', 'MacBook Air M3',
     'O Air de 13 polegadas combina tela Liquid Retina, carregamento MagSafe e duas portas Thunderbolt para monitores e acessórios.',
     'Mac', 749900, 'macbook', 'Apple', ARRAY['Apple M3', '16 GB de memória', 'SSD de 256 GB'], 30),
    ('THINKPAD-T14', 'ThinkPad T14 Gen 4',
     'Notebook de 14 polegadas com teclado TrackPoint, porta de rede e HDMI para trabalhar com um monitor externo.',
     'Notebooks', 589900, 'thinkpad', 'Lenovo', ARRAY['Intel Core i5-1335U', '16 GB de memória', 'SSD de 512 GB'], 40),
    ('SWITCH-OLED', 'Nintendo Switch OLED',
     'Jogue na tela OLED com os Joy-Con acoplados ou encaixe o console na base para continuar a partida na TV.',
     'Videogames', 249900, 'switch', 'Nintendo', ARRAY['OLED de 7″', '64 GB', 'Base com porta LAN'], 50),
    ('MAC-MINI-M4', 'Mac mini M4',
     'Desktop compacto com HDMI para o monitor e três portas Thunderbolt 4 para armazenamento e periféricos.',
     'Mac', 549900, 'macmini', 'Apple', ARRAY['Apple M4', '16 GB de memória', 'SSD de 256 GB'], 60)
ON CONFLICT (sku) DO UPDATE SET
    name = EXCLUDED.name, description = EXCLUDED.description, category = EXCLUDED.category,
    price_cents = EXCLUDED.price_cents, image_key = EXCLUDED.image_key, brand = EXCLUDED.brand,
    specs = EXCLUDED.specs, display_order = EXCLUDED.display_order;
