# Database Center

## SQLite

SQLite is embedded in Android and is immediately usable. The app supports database creation, schema inspection, table browsing, row insert/update/delete, raw SQL, SQL/CSV export, SQL import, binary backup and integrity-checked restore.

## MariaDB

The MariaDB runtime pack must expose at least:

- `mariadbd` or `mysqld`;
- `mariadb-install-db` or `mysql_install_db`;
- `mariadb` or `mysql` client;
- `mariadb-dump` or `mysqldump`.

The app initializes an isolated data directory, binds to `127.0.0.1`, creates a project database and users, runs SQL through the client, provides table/column/row browsing with PRIMARY KEY based insert/update/delete, and creates/restores SQL dumps.

## PostgreSQL

The PostgreSQL runtime pack must expose:

- `postgres`;
- `initdb`;
- `psql`;
- `pg_dump`.

The app initializes an isolated cluster, binds to `127.0.0.1`, creates the role/database, executes SQL, provides table/column/row browsing with PRIMARY KEY based insert/update/delete, and creates/restores dumps.

## AI access

AI database access is split into `db_read`, `db_write` and `destructive`. Destructive SQL additionally requires explicit destructive permission. Before destructive SQL, the agent creates a database backup when the selected engine supports it.
