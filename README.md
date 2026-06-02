# DatabaseAPI

Hardened HikariCP/MySQL layer for MineSide plugins: a pooled `Database` with TLS
validation + idempotent schema helpers, and five easy synchronous query helpers.
Platform-independent (pure JDBC/HikariCP/slf4j) — works on Paper and Velocity.

## Install (JitPack)

```xml
<repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>

<dependency>
    <groupId>com.github.PatriksGit</groupId>
    <artifactId>DatabaseAPI</artifactId>
    <version>v1.0.0</version>
</dependency>
```

The consuming plugin shades + relocates HikariCP / mysql-connector / protobuf and
excludes slf4j (provided by the platform), exactly like the other MineSide plugins.

## Use

```java
DatabaseConfig cfg = DatabaseConfig.of(host, 3306, "mydb", user, pass, 8,
    DatabaseConfig.SslMode.parse(sslModeString), /*debugParams*/ false);
Database db = new Database(cfg, slf4jLogger);

// schema (once, at startup)
try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
    st.execute("CREATE TABLE IF NOT EXISTS players (...)");
    db.ensureColumn(c, "players", "best_streak", "INT UNSIGNED NOT NULL DEFAULT 0");
    db.ensureIndex(c, "players", "idx_pt", "(playtime_seconds)");
}

// queries (run these on YOUR async thread — the library never spawns threads)
Optional<String> name = db.queryFirst(
    "SELECT name FROM players WHERE uuid = ?",
    ps -> ps.setBytes(1, toBytes(uuid)),
    rs -> rs.getString("name"));

int affected = db.update("UPDATE players SET pt = pt + ? WHERE uuid = ?",
    ps -> { ps.setLong(1, delta); ps.setBytes(2, toBytes(uuid)); });

db.batch("INSERT INTO players (uuid, pt) VALUES (?, ?) "
       + "ON DUPLICATE KEY UPDATE pt = pt + VALUES(pt)",
    deltas.entrySet(),
    (ps, e) -> { ps.setBytes(1, toBytes(e.getKey())); ps.setLong(2, e.getValue()); });

db.tx(c -> { /* multi-statement atomic unit */ return null; });

db.close(); // shutdown
```

Errors throw an unchecked `DataAccessException` whose message embeds the SQL (and
param types; values only when `debugParams=true` — control-stripped and length-capped),
with the original `SQLException` as cause. Catch it once at your async boundary.

> **PII in logs:** `DataAccessException.getMessage()` is sanitized, but the attached **cause**
> is not — a MySQL `SQLException` can inline row values (e.g. `Duplicate entry 'a@b.com' ...`).
> Log `ex.getMessage()`, not the throwable, to keep emails/IPs out of your logs.

Inside a `tx`/`batch` body use the **passed `Connection`** for every statement — calling another
`Database` helper (`query`/`update`/...) there throws, because it would open a separate pooled
connection that doesn't join the transaction (and can self-deadlock at small pool sizes).

## TLS against a private CA

`VERIFY_CA` / `VERIFY_IDENTITY` validate the server certificate against the JVM default
trust store. For a self-hosted MySQL with a private CA, supply a truststore:

```java
DatabaseConfig cfg = DatabaseConfig.of(host, 3306, db, user, pass, 8,
        DatabaseConfig.SslMode.VERIFY_IDENTITY, false)
    .withTrustStore("file:/etc/mysql/truststore.jks", trustStorePassword, "JKS")
    .withPoolName("MyPlugin"); // optional; defaults to "MineSide-DB-<database>"
```

The truststore path and password are passed as driver dataSource properties (never in the
JDBC URL string); a remote `http(s)` truststore URL is rejected (MITM). The library also pins
`autoDeserialize`, `allowLoadLocalInfile`, `allowUrlInLocalInfile`, and `allowMultiQueries` to
`false`. On a multi-plugin server, set `withPoolName(...)` so each pool is distinguishable in logs.

> **Logging:** do not enable `com.zaxxer.hikari` DEBUG logging in production. HikariCP prints its
> pool configuration there, and while it masks the DB password, it does **not** mask the
> truststore password (`trustCertificateKeyStorePassword`) — it would appear in cleartext. The
> library never logs credentials itself, and `DatabaseConfig.toString()` redacts both passwords.
