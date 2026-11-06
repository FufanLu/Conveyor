-- Deterministic synthetic catalog: ${seed_count} products (100,000 by default, fewer in tests).
-- Brands are invented. SKUs are 'B0' + 8 hex digits from a multiplicative hash of the row
-- number; 2654435761 is odd, so i -> i * 2654435761 mod 2^32 is a bijection and SKUs are unique.
WITH
brands(brand, b) AS (
    SELECT * FROM unnest(ARRAY['Aldervale', 'Brightwick', 'Cobalt & Pine', 'Dunmore', 'Everline',
                               'Fernhollow', 'Gallant Goods', 'Harborlight', 'Ironbark', 'Juniper Lane',
                               'Kestrel', 'Lumen & Co'])
                  WITH ORDINALITY),
categories(category, nouns, c) AS (
    SELECT * FROM (VALUES
        ('Electronics',       ARRAY['Wireless Earbuds', 'Smart Speaker', 'USB-C Charger', 'Portable SSD', 'Webcam'], 1),
        ('Home & Kitchen',    ARRAY['Chef Knife', 'Cast Iron Skillet', 'Pour-Over Kettle', 'Air Fryer', 'Blender'], 2),
        ('Books',             ARRAY['Field Guide', 'Cookbook', 'Mystery Novel', 'Atlas', 'Sketchbook'], 3),
        ('Toys & Games',      ARRAY['Puzzle', 'Board Game', 'Building Set', 'Plush Fox', 'Kite'], 4),
        ('Sports & Outdoors', ARRAY['Trail Backpack', 'Yoga Mat', 'Water Bottle', 'Camping Lantern', 'Bike Light'], 5),
        ('Beauty',            ARRAY['Face Serum', 'Hair Dryer', 'Lip Balm', 'Shampoo Bar', 'Sunscreen'], 6),
        ('Grocery',           ARRAY['Coffee Beans', 'Green Tea', 'Olive Oil', 'Dark Chocolate', 'Granola'], 7),
        ('Pet Supplies',      ARRAY['Dog Leash', 'Cat Tree', 'Chew Toy', 'Pet Bed', 'Fish Food'], 8)
    ) AS t(category, nouns, c)),
adjectives(adjective, a) AS (
    SELECT * FROM unnest(ARRAY['Classic', 'Compact', 'Deluxe', 'Everyday', 'Pro', 'Ultra', 'Travel', 'Eco'])
                  WITH ORDINALITY),
rows AS (
    -- One multiplicative hash per attribute (all odd constants), so brand, category, product
    -- kind and price vary independently instead of all following the SKU hash.
    SELECT i,
           (i * 2654435761::bigint) % 4294967296 AS h,
           (i * 2246822519::bigint) % 4294967296 AS hb,
           (i * 3266489917::bigint) % 4294967296 AS hc,
           (i * 668265263::bigint)  % 4294967296 AS hn,
           (i * 374761393::bigint)  % 4294967296 AS ha,
           (i * 1103515245::bigint) % 4294967296 AS hp
    FROM generate_series(1, ${seed_count}) AS i)
INSERT INTO product (sku, title, brand, category, price_cents, rating)
SELECT 'B0' || lpad(upper(to_hex(r.h)), 8, '0'),
       b.brand || ' ' || a.adjective || ' ' || cat.nouns[1 + (r.hn / 7) % 5] || ' ' || (100 + r.i % 900)::text,
       b.brand,
       cat.category,
       199 + (r.hp / 13) % 19801,                          -- $1.99 .. $199.99
       round((1 + ((r.hp / 17) % 41) / 10.0)::numeric, 1)  -- 1.0 .. 5.0
FROM rows r
JOIN brands b       ON b.b = 1 + (r.hb / 11) % 12
JOIN categories cat ON cat.c = 1 + (r.hc / 3) % 8
JOIN adjectives a   ON a.a = 1 + (r.ha / 5) % 8;
