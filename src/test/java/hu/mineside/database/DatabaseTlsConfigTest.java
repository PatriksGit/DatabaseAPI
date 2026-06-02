package hu.mineside.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import hu.mineside.database.DatabaseConfig.SslMode;

import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTlsConfigTest {

    private static DatabaseConfig base() {
        return DatabaseConfig.of("localhost", 3306, "db", "u", "p", 4, SslMode.DISABLED, false);
    }

    @Test void blastRadiusFlagsPinnedOff() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertEquals("false", p.getProperty("autoDeserialize"));
            assertEquals("false", p.getProperty("allowLoadLocalInfile"));
            assertEquals("false", p.getProperty("allowUrlInLocalInfile"));
            assertEquals("false", p.getProperty("allowMultiQueries"));
        }
    }

    @Test void poolHardeningDefaults() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            HikariDataSource h = (HikariDataSource) d.dataSource();
            assertEquals(60_000, h.getLeakDetectionThreshold());
            assertNull(h.getConnectionTestQuery(), "rely on JDBC4 isValid(), no SELECT 1");
        }
    }

    @Test void trustStorePropertiesSetWhenProvided() {
        DatabaseConfig cfg = base().withTrustStore("file:/ks.jks", "tspw", "PKCS12");
        try (Database d = new Database(cfg, LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertEquals("file:/ks.jks", p.getProperty("trustCertificateKeyStoreUrl"));
            assertEquals("tspw", p.getProperty("trustCertificateKeyStorePassword"));
            assertEquals("PKCS12", p.getProperty("trustCertificateKeyStoreType"));
        }
    }

    @Test void trustStorePropertiesAbsentByDefault() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertNull(p.getProperty("trustCertificateKeyStoreUrl"));
        }
    }

    @Test void poolNameDerivedFromDatabaseByDefault() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            assertEquals("MineSide-DB-db", ((HikariDataSource) d.dataSource()).getPoolName());
        }
    }

    @Test void poolNameOverrideUsedVerbatim() {
        try (Database d = new Database(base().withPoolName("MineAuth"), LoggerFactory.getLogger("t"))) {
            assertEquals("MineAuth", ((HikariDataSource) d.dataSource()).getPoolName());
        }
    }

    @Test void poolNameRejectsJmxUnsafeChars() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(base().withPoolName("a:b=c"), LoggerFactory.getLogger("t")));
    }

    @Test void rejectsRemoteTrustStoreUrl() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(base().withTrustStore("https://evil/ca.jks", "p", "JKS"), LoggerFactory.getLogger("t")));
        assertThrows(IllegalArgumentException.class,
            () -> new Database(base().withTrustStore("http://evil/ca.jks", "p", "JKS"), LoggerFactory.getLogger("t")));
    }
}
