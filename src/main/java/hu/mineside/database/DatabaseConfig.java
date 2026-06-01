package hu.mineside.database;

import java.util.Locale;

/**
 * Immutable JDBC/pool configuration. Build via the canonical constructor or the
 * {@link #of} convenience (clamps poolSize, leaves timeouts at 0 = Hikari default,
 * leaves the truststore unset). Add a custom truststore with {@link #withTrustStore}.
 *
 * <p>{@code debugParams}: when true, {@link DataAccessException} messages include
 * actual bound parameter VALUES (handy for debugging, but may log secrets such as
 * password hashes / emails / IPs — values are control-stripped and length-capped).
 * Default false → only param types are logged.
 *
 * <p>Note: {@link #equals}/{@link #hashCode} are record-generated and therefore
 * include the password; {@link #toString()} is overridden to redact it.
 */
public record DatabaseConfig(
    String host, int port, String database,
    String username, String password,
    int poolSize,
    long connectionTimeoutMs, long idleTimeoutMs, long maxLifetimeMs,
    SslMode sslMode,
    boolean debugParams,
    String trustStoreUrl, String trustStorePassword, String trustStoreType
) {
    /** Convenience: timeouts default to 0 (use Hikari defaults), poolSize clamped >= 1, no truststore. */
    public static DatabaseConfig of(String host, int port, String database,
                                    String username, String password,
                                    int poolSize, SslMode sslMode, boolean debugParams) {
        return new DatabaseConfig(host, port, database, username, password,
            Math.max(1, poolSize), 0L, 0L, 0L, sslMode, debugParams, null, null, null);
    }

    public DatabaseConfig {
        poolSize = Math.max(1, poolSize);
    }

    /**
     * Returns a copy with a custom truststore so {@code VERIFY_CA}/{@code VERIFY_IDENTITY}
     * can validate a private-CA / self-hosted MySQL server certificate. {@code type} is e.g.
     * "JKS" or "PKCS12"; null/blank uses the driver default. {@code storePassword} is the
     * TRUSTSTORE password — the DB password is preserved from this config.
     */
    public DatabaseConfig withTrustStore(String url, String storePassword, String type) {
        return new DatabaseConfig(host, port, database, username, password, poolSize,
            connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, sslMode, debugParams,
            url, storePassword, type);
    }

    @Override public String toString() {
        return "DatabaseConfig[host=" + host + ", port=" + port + ", database=" + database
            + ", username=" + username + ", password=***, poolSize=" + poolSize
            + ", connectionTimeoutMs=" + connectionTimeoutMs + ", idleTimeoutMs=" + idleTimeoutMs
            + ", maxLifetimeMs=" + maxLifetimeMs + ", sslMode=" + sslMode
            + ", debugParams=" + debugParams
            + ", trustStoreUrl=" + trustStoreUrl
            + ", trustStorePassword=" + (trustStorePassword == null ? "null" : "***")
            + ", trustStoreType=" + trustStoreType + "]";
    }

    /**
     * TLS posture for the JDBC connection. Maps directly to Connector/J's
     * {@code sslMode} URL parameter.
     */
    public enum SslMode {
        /** No TLS. Acceptable for localhost-only deployments. */
        DISABLED,
        /** TLS encrypted, server certificate NOT verified. Vulnerable to MITM. */
        REQUIRED,
        /** TLS encrypted, CA chain verified. Recommended for remote DBs. */
        VERIFY_CA,
        /** TLS encrypted, CA chain + hostname verified. Strongest. */
        VERIFY_IDENTITY;

        /**
         * Parse a config string. Case-insensitive; '-' normalized to '_'; accepts
         * the legacy boolean {@code ssl: true/false} mapping. Fails closed: any
         * unrecognized input throws rather than silently downgrading TLS.
         *
         * @throws IllegalArgumentException if {@code raw} is null, blank, or unknown.
         */
        public static SslMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("ssl-mode is blank or missing");
            }
            String t = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (t) {
                case "DISABLED", "FALSE", "NO", "OFF" -> DISABLED;
                case "REQUIRED" -> REQUIRED;
                case "VERIFY_CA", "TRUE", "YES", "ON" -> VERIFY_CA;
                case "VERIFY_IDENTITY" -> VERIFY_IDENTITY;
                default -> throw new IllegalArgumentException(
                    "Unknown ssl-mode '" + raw + "'. Valid: DISABLED, REQUIRED, VERIFY_CA, "
                    + "VERIFY_IDENTITY (or legacy boolean: true/false/yes/no/on/off).");
            };
        }
    }
}
