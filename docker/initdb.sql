-- One database and one role per service, so a service cannot reach another's tables even by accident.
-- Development credentials: the db container publishes no port, so nothing outside the compose network
-- can reach it. Change these before this goes anywhere real.
-- Runs once, when the volume is created: a service added later needs its lines run by hand or a `db reset`.
CREATE USER auth WITH PASSWORD 'auth';
CREATE DATABASE auth OWNER auth;

CREATE USER todo WITH PASSWORD 'todo';
CREATE DATABASE todo OWNER todo;

CREATE USER agent WITH PASSWORD 'agent';
CREATE DATABASE agent OWNER agent;
