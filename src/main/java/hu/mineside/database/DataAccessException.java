package hu.mineside.database;

import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

/**
 * Unchecked wrapper for a {@link SQLException} from a {@link Database} helper.
 * The message embeds the SQL plus parameter info so a single log line tells you
 * what failed and which query caused it. Parameter VALUES appear only when the
 * config has {@code debugParams=true} (otherwise just their types, to avoid
 * leaking secrets like password hashes / emails / IPs).
 */
public final class DataAccessException extends RuntimeException {

    private DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Wrap a non-SQL failure from inside a {@code tx} body (preserves the cause). */
    static DataAccessException fromBody(String message, Throwable cause) {
        return new DataAccessException(message, cause);
    }

    static DataAccessException wrap(String sql, List<?> params, boolean debugParams, SQLException cause) {
        StringBuilder sb = new StringBuilder("SQL failed: ").append(sql);
        if (params != null && !params.isEmpty()) {
            StringJoiner j = new StringJoiner(", ", " params=[", "]");
            for (Object p : params) j.add(describe(p, debugParams));
            sb.append(j);
        }
        if (cause != null) {
            sb.append(" (").append(cause.getClass().getSimpleName())
              .append(": ").append(cause.getMessage())
              .append(", SQLState=").append(cause.getSQLState())
              .append(", code=").append(cause.getErrorCode()).append(')');
        }
        return new DataAccessException(sb.toString(), cause);
    }

    private static String describe(Object p, boolean debugParams) {
        if (p == null) return "null";
        if (debugParams) return String.valueOf(p);
        // byte[] (UUID/BINARY) prints as e.g. byte[16]; everything else by simple type name
        if (p instanceof byte[] b) return "byte[" + b.length + "]";
        return p.getClass().getSimpleName();
    }
}
