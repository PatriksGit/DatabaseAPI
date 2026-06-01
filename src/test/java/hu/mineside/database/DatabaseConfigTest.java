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

    @Test void toStringRedactsSecrets() {
        DatabaseConfig c = DatabaseConfig.of("h", 3306, "db", "admin", "s3cr3t", 4, SslMode.DISABLED, false)
            .withTrustStore("file:/ks.jks", "tspw", "JKS");
        String s = c.toString();
        assertFalse(s.contains("s3cr3t"), "DB password must not appear");
        assertFalse(s.contains("tspw"), "truststore password must not appear");
        assertTrue(s.contains("password=***"), "DB password redacted");
        assertTrue(s.contains("trustStorePassword=***"), "truststore password redacted");
        assertTrue(s.contains("admin"), "username stays visible for debugging");
        assertTrue(s.contains("db"));
    }

    @Test void withTrustStoreSetsFieldsAndPreservesRest() {
        DatabaseConfig base = DatabaseConfig.of("h", 3306, "db", "u", "dbpw", 4, SslMode.VERIFY_CA, true);
        DatabaseConfig c = base.withTrustStore("file:/ks.jks", "tspw", "PKCS12");
        assertEquals("file:/ks.jks", c.trustStoreUrl());
        assertEquals("tspw", c.trustStorePassword());
        assertEquals("PKCS12", c.trustStoreType());
        assertEquals("dbpw", c.password(), "DB password preserved (not overwritten by store pw)");
        assertEquals(SslMode.VERIFY_CA, c.sslMode());
        assertTrue(c.debugParams());
        assertEquals(4, c.poolSize());
    }

    @Test void ofLeavesTrustStoreUnset() {
        DatabaseConfig c = DatabaseConfig.of("h", 3306, "db", "u", "p", 4, SslMode.DISABLED, false);
        assertNull(c.trustStoreUrl());
        assertNull(c.trustStorePassword());
        assertNull(c.trustStoreType());
    }
}
