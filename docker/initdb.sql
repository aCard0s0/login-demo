-- One database and one role per service, so a service cannot reach the other's tables even by accident.
-- Development credentials: the db container publishes no port, so nothing outside the compose network
-- can reach it. Change these before this goes anywhere real.
CREATE USER auth WITH PASSWORD 'auth';
CREATE DATABASE auth OWNER auth;

CREATE USER todo WITH PASSWORD 'todo';
CREATE DATABASE todo OWNER todo;
