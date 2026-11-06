-- pg-0: one database per service that owns data (database-per-service), plus the first
-- half of the order shards. Each service creates its own schema with Flyway; this script
-- only creates the empty databases.
CREATE DATABASE catalog;
CREATE DATABASE inventory;
CREATE DATABASE payment;
CREATE DATABASE orders;   -- order shards 0..7 (ShardingSphere data source ds_0)
