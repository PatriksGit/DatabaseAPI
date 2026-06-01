package hu.mineside.database;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import hu.mineside.database.DatabaseConfig.SslMode;

class DatabaseConfigTest {

    @Test void parseCanonicalNames() {
        assertEquals(SslMode.DISABLED, SslMode.parse("DISABLED"));
        assertEquals(SslMode.REQUIRED, SslMode.parse("required"));
        assertEquals(SslMode.VERIFY_CA, SslMode.parse("verify_ca"));
        assertEquals(SslMode.VERIFY_IDENTITY, SslMode.parse("VERIFY-IDENTITY")); // hyphen normalized
    }

    @Test void parseLegacyBooleans() {
        assertEquals(SslMode.DISABLED, SslMode.parse("false"));
        assertEquals(SslMode.DISABLED, SslMode.parse("OFF"));
        assertEquals(SslMode.VERIFY_CA, SslMode.parse("true"));
        assertEquals(SslMode.VERIFY_CA, SslMode.parse("yes"));
    }

    @Test void parseFailsClosedOnGarbage() {
        assertThrows(IllegalArgumentException.class, () -> SslMode.parse("vrify_ca"));
        assertThrows(IllegalArgumentException.class, () -> SslMode.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SslMode.parse(null));
    }

    @Test void defaultsConstructorClampsPoolAndKeepsZeros() {
        DatabaseConfig c = DatabaseConfig.of("h", 3306, "db", "u", "p", 0, SslMode.DISABLED, false);
        assertEquals(1, c.poolSize());          // clamped to >= 1
        assertEquals(0, c.connectionTimeoutMs()); // 0 = use Hikari default
        assertFalse(c.debugParams());
    }
}
