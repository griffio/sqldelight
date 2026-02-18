CREATE TABLE T (
  -- numeric
                 smallint smallint,
                 integer integer,
                 bigint bigint,
                 decimal decimal,
                 numeric numeric,
                 real real,
                 double_precision double precision,
                 smallserial smallserial,
                 serial serial,
                 bigserial bigserial,
  -- character / binary
                 character character,
                 character_varying character varying,
                 varchar varchar(100),
                 text text,
                 char char(1),
                 bytea bytea,
                 boolean boolean DEFAULT TRUE,

                 date date,
                 time time,
                 time_with_time_zone time WITH time zone,
                 timestamp timestamp,
                 timestamp_with_time_zone timestamp WITH time zone,
                 interval interval,
  -- uuid
                 uuid uuid,
  -- full-text search
                 tsvector tsvector,
                 tsquery tsquery,

  -- json / xml
                 json json,
                 jsonb jsonb,
                 xml xml,

  -- geometric
                 point point,
  -- ranges
                 tsrange tsrange,
                 tstzrange tstzrange
);
