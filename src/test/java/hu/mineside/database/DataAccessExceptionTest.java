package hu.mineside.database;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataAccessExceptionTest {

    private static final String SQL = "UPDATE players SET pw=? WHERE name=?";
    private final SQLException cause = new SQLException("dup", "23000", 1062);

    @Test void alwaysIncludesSqlAndCause() {
        DataAccessException e = DataAccessException.wrap(SQL, List.of("hash", "Steve"), false, cause);
        assertTrue(e.getMessage().contains(SQL), "SQL must be in the message");
        assertSame(cause, e.getCause());
    }

    @Test void typesOnlyWhenDebugOff() {
        DataAccessException e = DataAccessException.wrap(SQL, List.of("hash", "Steve"), false, cause);
        assertTrue(e.getMessage().contains("String"), "param TYPES present");
        assertFalse(e.getMessage().contains("hash"), "param VALUES must NOT leak when debug off");
        assertFalse(e.getMessage().contains("Steve"));
    }

    @Test void valuesWhenDebugOn() {
        DataAccessException e = DataAccessException.wrap(SQL, List.of("hash", "Steve"), true, cause);
        assertTrue(e.getMessage().contains("hash"), "param VALUES present when debug on");
        assertTrue(e.getMessage().contains("Steve"));
    }

    @Test void handlesNullParamList() {
        DataAccessException e = DataAccessException.wrap(SQL, null, false, cause);
        assertTrue(e.getMessage().contains(SQL));
    }

    @Test void nullValueRendersAsNullType() {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(null);
        DataAccessException e = DataAccessException.wrap(SQL, params, false, cause);
        assertTrue(e.getMessage().contains("null"));
    }

    @Test void debugValueTruncatedAndLengthMarked() {
        String big = "x".repeat(200);
        DataAccessException e = DataAccessException.wrap(SQL, java.util.List.of(big), true, cause);
        assertFalse(e.getMessage().contains(big), "full long value must not appear");
        assertTrue(e.getMessage().contains("(200)"), "original length marker present");
    }

    @Test void debugValueControlCharsStripped() {
        DataAccessException e = DataAccessException.wrap(SQL, java.util.List.of("a\nb\tc"), true, cause);
        String m = e.getMessage();
        assertFalse(m.contains("\n"), "newline stripped from value");
        assertFalse(m.contains("\t"), "tab stripped from value");
    }

    @Test void sqlControlCharsStripped() {
        String multi = "SELECT *\nFROM players\tWHERE x=?";
        DataAccessException e = DataAccessException.wrap(multi, null, false, cause);
        String m = e.getMessage();
        assertFalse(m.contains("\n"), "newline stripped from SQL");
        assertFalse(m.contains("\t"), "tab stripped from SQL");
        assertTrue(m.contains("SELECT *"), "SQL still readable");
    }
}
