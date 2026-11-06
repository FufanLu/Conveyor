-- Deterministic stock for the catalog's ${seed_count} SKUs (same SKU formula as catalog-svc).
-- Every SKU is stocked in 2-4 of the 6 FCs with 0-199 units; rows 1-10 are "deal" SKUs with
-- 1,000 units in every FC, for the hot-SKU scenarios.
WITH rows AS (
    SELECT i,
           (i * 2654435761::bigint) % 4294967296 AS h,
           (i * 2246822519::bigint) % 4294967296 AS hs
    FROM generate_series(1, ${seed_count}) AS i),
fcs AS (
    SELECT fc_id, fallback_rank AS n FROM fulfillment_center)
INSERT INTO stock (sku, fc_id, on_hand)
SELECT 'B0' || lpad(upper(to_hex(r.h)), 8, '0'),
       f.fc_id,
       CASE WHEN r.i <= 10 THEN 1000 ELSE ((r.hs / 7 + f.n * 131) % 200)::int END
FROM rows r
JOIN fcs f ON r.i <= 10
           OR ((r.hs >> (f.n * 3)) & 7) < 3   -- each FC carries a given SKU with probability 3/8
                                               -- (2.25 of 6 FCs on average)
           OR f.n = 1 + (r.hs % 6);            -- and every SKU is in at least one FC
