-- pg-1: the second half of the order shards. A separate physical instance, so sharding
-- spreads both storage and write load.
CREATE DATABASE orders;   -- order shards 8..15 (ShardingSphere data source ds_1)
