CREATE TABLE Schedules(
  slot TSTZRANGE NOT NULL CHECK(
      date_part('minute', LOWER(slot)) IN (00, 30)
      AND
      date_part('minute', UPPER(slot)) IN (00, 30)),
    duration INT GENERATED ALWAYS AS (
      EXTRACT (epoch FROM UPPER(slot) - LOWER(slot))/60
  ) STORED CHECK(duration IN (30, 60, 90, 120)),
  EXCLUDE USING GIST(slot WITH &&)
);

CREATE TABLE Reservations (
    start_time TSTZRANGE,
    finish_time TSTZRANGE,
    CONSTRAINT no_screening_time_overlap EXCLUDE USING GIST (finish_time WITH =, start_time WITH &&)
);


