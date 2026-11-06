-- M4: the fulfillment saga records why an order ended where it did, and when it will arrive.
DO $$
BEGIN
    FOR i IN ${first_shard}..${last_shard} LOOP
        EXECUTE format('ALTER TABLE orders_%1$s ADD COLUMN status_reason text, ADD COLUMN promised_delivery timestamptz',
                       lpad(i::text, 2, '0'));
    END LOOP;
END
$$;
