package hu.mineside.database;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import hu.mineside.database.DatabaseConfig.SslMode;
import static org.junit.jupiter.api.Assertions.*;

class SchemaGuardTest {

    private Database db() {
        return new Database(
            DatabaseConfig.of("localhost", 3306, "db", "u", "p", 1, SslMode.DISABLED, false),
            LoggerFactory.getLogger("t"));
    }

    @Test void ensureColumnRejectsUnsafeTable() {
        try (Database d = db()) {
            assertThrows(IllegalArgumentException.class,
                () -> d.ensureColumn(null, "players; DROP TABLE x", "c", "INT"));
        }
    }

    @Test void ensureIndexRejectsUnsafeColumns() {
        try (Database d = db()) {
            assertThrows(IllegalArgumentException.class,
                () -> d.ensureIndex(null, "players", "idx", "(c); DROP TABLE x"));
        }
    }
}
