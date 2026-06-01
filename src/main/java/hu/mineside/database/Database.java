package hu.mineside.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Hardened MySQL connection pool + easy query helpers. Platform-independent
 * (pure JDBC/HikariCP/slf4j). Construct once at startup, {@link #close()} on
 * shutdown. Helpers run on the CALLER's thread — the library never spawns threads.
 */
public final class Database implements AutoCloseable {

    // Host: hostname / IPv4 / [IPv6]. DB name: strict identifier. These guard the
    // URL-concatenated fields so a value like `db?sslMode=DISABLED` can't break out
    // of its segment and silently downgrade TLS.
    private static final Pattern SAFE_DB_HOST =
        Pattern.compile("[A-Za-z0-9._\\-]+|\\[[0-9A-Fa-f:]+\\]");
    private static final Pattern SAFE_DB_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_COLUMN_EXPR = Pattern.compile("[A-Za-z0-9_(),.'\\-\\s]+");

    // Typed as DataSource (not HikariDataSource) from the start so the test seam
    // (Task 9) can back it with H2. The production constructor assigns a Hikari pool.
    private final DataSource ds;
    private final boolean debugParams;
    private final Logger log;

    public Database(DatabaseConfig cfg, Logger log) {
        this.log = log;
        this.debugParams = cfg.debugParams();

        if (cfg.host() == null || !SAFE_DB_HOST.matcher(cfg.host()).matches()) {
            throw new IllegalArgumentException("Invalid database host '" + cfg.host()
                + "' — must be hostname / IPv4 / [IPv6], no URL/query characters.");
        }
        if (cfg.port() < 1 || cfg.port() > 65535) {
            throw new IllegalArgumentException("Invalid database port '" + cfg.port() + "'.");
        }
        if (cfg.database() == null || !SAFE_DB_NAME.matcher(cfg.database()).matches()) {
            throw new IllegalArgumentException("Invalid database name '" + cfg.database()
                + "' — must be [A-Za-z0-9_]+; no JDBC URL parameter chars allowed.");
        }

        boolean loopback = "localhost".equalsIgnoreCase(cfg.host())
            || "127.0.0.1".equals(cfg.host()) || "::1".equals(cfg.host()) || "[::1]".equals(cfg.host());
        switch (cfg.sslMode()) {
            case DISABLED -> {
                if (loopback) log.info("ssl-mode=DISABLED on loopback ({}) — TLS skipped (acceptable for localhost).", cfg.host());
                else log.warn("ssl-mode=DISABLED but host '{}' is not loopback — DB credentials and traffic travel UNENCRYPTED. "
                    + "Use VERIFY_CA (or VERIFY_IDENTITY) for any remote MySQL.", cfg.host());
            }
            case REQUIRED -> log.warn("ssl-mode=REQUIRED — TLS is on but the server certificate is NOT verified. "
                + "A MITM with a self-signed cert can intercept credentials. Prefer VERIFY_CA or VERIFY_IDENTITY.");
            case VERIFY_CA, VERIFY_IDENTITY -> {
                if (loopback) log.warn("ssl-mode={} on loopback ('{}') — the default MySQL self-signed cert will likely "
                    + "FAIL the CA-chain check. For local dev set ssl-mode=DISABLED; for production-on-localhost provision a CA-signed cert.",
                    cfg.sslMode(), cfg.host());
            }
        }

        String sslParam = switch (cfg.sslMode()) {
            case DISABLED -> "sslMode=DISABLED";
            case REQUIRED -> "sslMode=REQUIRED";
            case VERIFY_CA -> "sslMode=VERIFY_CA";
            case VERIFY_IDENTITY -> "sslMode=VERIFY_IDENTITY";
        };

        HikariConfig hc = new HikariConfig();
        // useAffectedRows=false (driver default, pinned): callers relying on the
        // MySQL "INSERT...ON DUPLICATE KEY UPDATE returns 1 for insert / 2 for
        // update" convention (e.g. MineAuth's brute-force counter) need this.
        hc.setJdbcUrl("jdbc:mysql://" + cfg.host() + ":" + cfg.port() + "/" + cfg.database()
            + "?" + sslParam + "&serverTimezone=UTC&useUnicode=true&characterEncoding=utf8&useAffectedRows=false");
        // Explicit driver class: Velocity/Paper classloader isolation breaks the
        // DriverManager ServiceLoader path; setting it makes Hikari load the driver
        // from the plugin's shaded classloader directly.
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setUsername(cfg.username());
        hc.setPassword(cfg.password());
        hc.setMaximumPoolSize(cfg.poolSize());
        hc.setPoolName("MineSide-DB");
        if (cfg.connectionTimeoutMs() > 0) hc.setConnectionTimeout(cfg.connectionTimeoutMs());
        if (cfg.idleTimeoutMs() > 0) hc.setIdleTimeout(cfg.idleTimeoutMs());
        if (cfg.maxLifetimeMs() > 0) hc.setMaxLifetime(cfg.maxLifetimeMs());
        // Lazy pool: don't open a connection in the constructor. Real connection
        // failures surface on first getConnection(), and unit tests can construct
        // a Database without a live DB.
        hc.setInitializationFailTimeout(-1);
        // MySQL drops idle connections after wait_timeout; keepalive avoids handing
        // out dead ones after a quiet period.
        hc.setKeepaliveTime(60_000);
        hc.setConnectionTestQuery("SELECT 1");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.ds = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException { return ds.getConnection(); }
    public DataSource dataSource() { return ds; }

    // HikariDataSource is Closeable; H2's JdbcDataSource (test seam) is not.
    @Override public void close() {
        if (ds instanceof AutoCloseable a) { try { a.close(); } catch (Exception ignored) { } }
    }

    /**
     * Add the column if missing. Swallows MySQL duplicate-column error (1060) so
     * re-running on an already-migrated table is a no-op. Identifiers validated
     * against a strict whitelist (DDL identifiers cannot be bound as parameters).
     */
    public void ensureColumn(Connection c, String table, String column, String definition) throws SQLException {
        requireIdent(table, "table");
        requireIdent(column, "column");
        requireColumnExpr(definition, "definition");
        try (var st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1060) return; // duplicate column — fine
            throw e;
        }
    }

    /** Convert a column to the given type (idempotent; MODIFY on a matching type is a no-op). */
    public void ensureColumnType(Connection c, String table, String column, String typeDefinition) throws SQLException {
        requireIdent(table, "table");
        requireIdent(column, "column");
        requireColumnExpr(typeDefinition, "type");
        try (var st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + typeDefinition);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1146) return; // table doesn't exist — defensive no-op
            throw e;
        }
    }

    /** Create the named index if absent. Swallows duplicate-key-name error (1061). */
    public void ensureIndex(Connection c, String table, String indexName, String columns) throws SQLException {
        requireIdent(table, "table");
        requireIdent(indexName, "index");
        requireColumnExpr(columns, "columns");
        try (var st = c.createStatement()) {
            st.execute("CREATE INDEX " + indexName + " ON " + table + " " + columns);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1061) return; // already exists — fine
            throw e;
        }
    }

    private static void requireIdent(String v, String what) {
        if (v == null || !SAFE_IDENT.matcher(v).matches())
            throw new IllegalArgumentException("Unsafe " + what + " identifier: " + v);
    }
    private static void requireColumnExpr(String v, String what) {
        if (v == null || !SAFE_COLUMN_EXPR.matcher(v).matches())
            throw new IllegalArgumentException("Unsafe " + what + " expression: " + v);
    }

    // ---- Query helpers (synchronous; run on the caller's thread) ----

    /** Run a SELECT, mapping every row. */
    public <T> List<T> query(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper) {
        List<Object> captured = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindAndCapture(ps, binder, captured);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw DataAccessException.wrap(sql, captured, debugParams, e);
        }
    }

    /** Run a SELECT, returning the first row if any. */
    public <T> Optional<T> queryFirst(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper) {
        List<Object> captured = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindAndCapture(ps, binder, captured);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw DataAccessException.wrap(sql, captured, debugParams, e);
        }
    }

    /** Run an INSERT/UPDATE/DELETE; returns affected rows. */
    public int update(String sql, Sql.Binder binder) {
        List<Object> captured = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindAndCapture(ps, binder, captured);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw DataAccessException.wrap(sql, captured, debugParams, e);
        }
    }

    // Param-less convenience overloads.
    public <T> List<T> query(String sql, Sql.RowMapper<T> mapper) { return query(sql, Sql.Binder.NONE, mapper); }
    public int update(String sql) { return update(sql, Sql.Binder.NONE); }

    /**
     * Bind params through a capturing proxy so a failure can report the actual
     * values/types. We wrap the real PreparedStatement only for the set* calls
     * the binder makes; this records (index,value) without changing behavior.
     */
    private void bindAndCapture(PreparedStatement ps, Sql.Binder binder, List<Object> captured) throws SQLException {
        binder.bind(new CapturingPreparedStatement(ps, captured));
    }

    /** Default batch chunk size — keeps SQL shapes stable and stays under driver limits. */
    private static final int BATCH_CHUNK = 100;

    /**
     * Batched INSERT/UPDATE over {@code items}, chunked at {@value #BATCH_CHUNK}.
     * Returns per-item affected-row counts (in iteration order). All chunks run in
     * ONE transaction (autocommit off, single commit) so a failure in a later chunk
     * cannot leave earlier chunks committed — the whole batch is all-or-nothing.
     */
    public <T> int[] batch(String sql, Iterable<T> items, Sql.BiBinder<T> binder) {
        List<T> all = new ArrayList<>();
        for (T it : items) all.add(it);
        if (all.isEmpty()) return new int[0];
        int[] result = new int[all.size()];
        int written = 0;
        Connection c = null;
        boolean prevAutoCommit = true;
        try {
            c = ds.getConnection();
            prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            for (int start = 0; start < all.size(); start += BATCH_CHUNK) {
                int end = Math.min(start + BATCH_CHUNK, all.size());
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = start; i < end; i++) {
                        binder.bind(ps, all.get(i));
                        ps.addBatch();
                    }
                    int[] counts = ps.executeBatch();
                    System.arraycopy(counts, 0, result, written, counts.length);
                    written += counts.length;
                }
            }
            c.commit();
            return result;
        } catch (SQLException e) {
            if (c != null) { try { c.rollback(); } catch (SQLException ignored) { } }
            throw DataAccessException.wrap(sql, null, debugParams, e);
        } finally {
            if (c != null) {
                try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) { }
                try { c.close(); } catch (SQLException ignored) { }
            }
        }
    }

    /**
     * Run {@code body} inside a transaction: autocommit off, commit on success,
     * rollback + rethrow on ANY failure, autocommit restored and connection closed
     * in finally. The body gets the live connection and must use it directly (plain
     * JDBC) for multi-statement atomic units.
     *
     * <p><b>Important:</b> the other helpers ({@code query}/{@code update}/...) open
     * their OWN connection and do NOT join this transaction — inside a {@code tx}
     * body always use the passed {@code Connection c}, never {@code this.update(...)}.
     */
    public <T> T tx(Sql.TxBody<T> body) {
        Connection c = null;
        boolean prevAutoCommit = true;
        try {
            c = ds.getConnection();
            prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            T result = body.run(c);
            c.commit();
            return result;
        } catch (Exception e) {
            // Catch ANY throwable from the body — our own query helpers throw the
            // unchecked DataAccessException, so a SQLException-only catch would skip
            // rollback and leak an open transaction back to the pool.
            if (c != null) { try { c.rollback(); } catch (SQLException ignored) { } }
            if (e instanceof DataAccessException dae) throw dae;
            if (e instanceof SQLException sqe) throw DataAccessException.wrap("<transaction>", null, debugParams, sqe);
            throw DataAccessException.fromBody("Transaction body failed", e);
        } finally {
            if (c != null) {
                try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) { }
                try { c.close(); } catch (SQLException ignored) { }
            }
        }
    }
}
