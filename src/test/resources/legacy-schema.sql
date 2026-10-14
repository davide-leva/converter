-- Frozen schema of the application before Flyway: independent upgrade fixture.
CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL);
CREATE TABLE tokens (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE conversions (id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, format TEXT NOT NULL, size INTEGER NOT NULL, pdf_size INTEGER NOT NULL, created TEXT NOT NULL);
CREATE INDEX conversions_owner ON conversions(owner,created);
