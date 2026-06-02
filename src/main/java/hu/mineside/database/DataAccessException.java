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
 *
 * <p>To keep the message a single, safe log line, the SQL and any debug values are
 * control-stripped (newlines/tabs/control chars → spaces) and debug values are
 * length-capped — this prevents log-injection via attacker-controlled parameters
 * and avoids dumping huge blobs.
 */
public final class DataAccessException extends RuntimeException {

    /** Max rendered length of a single bound value in debug mode. */
    private static final int MAX_DEBUG_VALUE_LEN = 64;

    private DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Wrap a non-SQL failure from inside a {@code tx}/{@code batch} body (preserves the cause). */
    static DataAccessException fromBody(String message, Throwable cause) {
        return new DataAccessException(stripControl(message), cause);
    }

    static DataAccessException wrap(String sql, List<?> params, boolean debugParams, SQLException cause) {
        StringBuilder sb = new StringBuilder("SQL failed: ").append(stripControl(sql));
        if (params != null && !params.isEmpty()) {
            StringJoiner j = new StringJoiner(", ", " params=[", "]");
            for (Object p : params) j.add(describe(p, debugParams));
            sb.append(j);
        }
        if (cause != null) {
            sb.append(" (").append(cause.getClass().getSimpleName())
              .append(": ").append(stripControl(cause.getMessage()))
              .append(", SQLState=").append(cause.getSQLState())
              .append(", code=").append(cause.getErrorCode()).append(')');
        }
        return new DataAccessException(sb.toString(), cause);
    }

    private static String describe(Object p, boolean debugParams) {
        if (p == null) return "null";
        // byte[] (UUID/BINARY) always prints as byte[N] (content-free) in BOTH modes — never
        // dump raw bytes nor the useless array identity hash from String.valueOf(byte[]).
        if (p instanceof byte[] b) return "byte[" + b.length + "]";
        if (debugParams) return truncate(stripControl(String.valueOf(p)));
        return p.getClass().getSimpleName();
    }

    /** Replace control characters (incl. newline/tab and DEL) with spaces so the message is one line. */
    private static String stripControl(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u0000-\\u001F\\u007F]", " ");
    }

    /** Cap a rendered value; if over the limit, keep the head and append the original length. */
    private static String truncate(String s) {
        if (s.length() <= MAX_DEBUG_VALUE_LEN) return s;
        return s.substring(0, MAX_DEBUG_VALUE_LEN) + "…(" + s.length() + ")";
    }
}
