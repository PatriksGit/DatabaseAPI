package hu.mineside.database;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import hu.mineside.database.DatabaseConfig.SslMode;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseValidationTest {

    private static DatabaseConfig cfg(String host, int port, String db) {
        return DatabaseConfig.of(host, port, db, "u", "p", 4, SslMode.DISABLED, false);
    }

    @Test void rejectsBadHost() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> new Database(cfg("evil;host", 3306, "db"), LoggerFactory.getLogger("t")));
        assertTrue(ex.getMessage().toLowerCase().contains("host"));
    }

    @Test void rejectsBadDbName() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(cfg("localhost", 3306, "db?sslMode=DISABLED"), LoggerFactory.getLogger("t")));
    }

    @Test void rejectsBadPort() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(cfg("localhost", 70000, "db"), LoggerFactory.getLogger("t")));
    }

    @Test void acceptsIpv6Host() {
        // Valid identifiers must pass validation. We can't assert a successful
        // CONNECT (no DB), but validation runs before Hikari opens anything, and
        // Hikari is lazy — getConnection() is what connects, not the constructor.
        assertDoesNotThrow(() ->
            new Database(cfg("[::1]", 3306, "mydb"), LoggerFactory.getLogger("t")).close());
    }
}
