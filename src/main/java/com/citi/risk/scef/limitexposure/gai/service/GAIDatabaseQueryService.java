package com.citi.risk.scef.limitexposure.gai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.inject.Inject;
import javax.inject.Named;
import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Loads and executes the 18 GAI SQL files using SCEF's existing Oracle
 * DataSource (ADMCEF_APP user — same connection already used by SCEF).
 *
 * SQL files are loaded from classpath:
 *   gai/sql/{feedName}_{fileType}_query.sql
 * e.g.
 *   gai/sql/stress-exposure_event_query.sql
 *   gai/sql/swwr-flag_record_query.sql
 *
 * The DataSource is the same one Guice injects everywhere else in SCEF —
 * no new connection pool, no new credentials needed.
 */
public class GAIDatabaseQueryService {

    private static final Logger logger =
            LoggerFactory.getLogger(GAIDatabaseQueryService.class);

    private final JdbcTemplate jdbc;

    /**
     * @param dataSource injected by SCEF's Guice — the existing ADMCEF Oracle DS.
     *                   @Named("scef-ods") matches the binding in SCEF's
     *                   DataSource Guice module (same name used by other services).
     */
    @Inject
    public GAIDatabaseQueryService(@Named("scef-ods") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Runs the SQL file for a given feed + file type combination.
     *
     * @param feedName  e.g. "stress-exposure"
     * @param fileType  one of: "event", "record", "attribute"
     * @param cobDate   e.g. "20260522" — available as :cobDate bind param if needed
     * @return list of rows, each row a column-name → value map
     */
    public List<Map<String, Object>> query(String feedName,
                                           String fileType,
                                           String cobDate) {
        String sqlPath = String.format("gai/sql/%s_%s_query.sql",
                                       feedName, fileType);
        String sql = loadSql(sqlPath);

        logger.debug("[GAI] Executing SQL: {} for cobDate={}", sqlPath, cobDate);

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        long elapsed = System.currentTimeMillis() - start;

        logger.info("[GAI][{}][{}] {} rows in {}ms",
                    feedName, fileType, rows.size(), elapsed);
        return rows;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String loadSql(String classpathResource) {
        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException(
                    "GAI SQL file not found on classpath: " + classpathResource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to load GAI SQL: " + classpathResource, e);
        }
    }
}
