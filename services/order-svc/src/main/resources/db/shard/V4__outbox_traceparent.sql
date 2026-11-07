-- The trace an outbox row was written in, as a W3C traceparent ("00-<trace>-<span>-<flags>").
-- Debezium's outbox router copies it into a Kafka header of the same name, and consumers'
-- Kafka observations continue the trace from there: one trace from the customer's POST through
-- the WAL to the saga. NULL when the write happened outside any trace.
ALTER TABLE outbox ADD COLUMN traceparent text;
