package com.citi.risk.scef.limitexposure.gai.service;

import com.google.inject.Inject;
import com.citi.risk.core.ioc.impl.guice.CRFGuiceContext;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Executes GAI SQL files against ADMCEF ODS.
 *
 * Follows the same lazy-init pattern as AgedReportJob.execute():
 *   - CRFGuiceContext.getInjector() is called INSIDE query(), not at construction
 *   - This guarantees the Configuration is fully resolved with environment
 *     properties before the JDBC URL is read
 *   - No @Inject DataSource — avoids the ScefOdsDBModule binding issue
 *
 * The JdbcTemplate is initialised once on first query call and reused.
 */
public class GAIDatabaseQueryService {

    private static final Logger logger = LoggerFactory.getLogger(GAIDatabaseQueryService.class);

    // Lazily initialised on first query — volatile for thread safety
    private volatile NamedParameterJdbcTemplate jdbc;

    @Inject
    public GAIDatabaseQueryService() { }

    /**
     * Runs one SQL file for the given feed + file type.
     * @param feedName e.g. "stress-exposure"
     * @param fileType "event", "record", or "attribute"
     * @param cobDate  yyyyMMdd e.g. "20260522"
     */
    public List<Map<String, Object>> query(String feedName, String fileType, String cobDate) {
        String sqlPath = String.format("gai/sql/%s_%s_query.sql", feedName, fileType.toLowerCase());

        logger.debug("[GAI][{}][{}] Loading SQL: {}", feedName, fileType, sqlPath);
        String sql = loadSql(sqlPath, feedName, fileType);

        if (!sql.contains(":cobDate") && !sql.contains(":cobDateYYYYMMDD")) {
            logger.warn("[GAI][{}][{}] SQL has no cobDate bind parameter — may extract full history: {}",
                        feedName, fileType, sqlPath);
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("cobDate",          cobDate);
        params.addValue("cobDateYYYYMMDD",  cobDate);
        params.addValue("cobDateMMDDYYYY",  toMmDdYyyy(cobDate));

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        try {
            rows = getJdbc().queryForList(sql, params);
        } catch (Exception e) {
            logger.error("[GAI][{}][{}] Query execution failed. cobDate={} SQL={}",
                         feedName, fileType, cobDate, sqlPath);
            logger.error("[GAI][{}][{}] Exception: {}", feedName, fileType, e.getMessage(), e);
            throw e;
        }

        logger.info("[GAI][{}][{}] rows={} elapsedMs={} cobDate={}",
                    feedName, fileType, rows.size(), System.currentTimeMillis() - start, cobDate);
        return rows;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Lazy init — called on first query, after the application has fully started
     * and the environment-aware Configuration has resolved all property prefixes.
     * Same pattern as AgedReportJob which calls CRFGuiceContext inside execute().
     */
    private NamedParameterJdbcTemplate getJdbc() {
        if (jdbc == null) {
            synchronized (this) {
                if (jdbc == null) {
                    jdbc = createJdbcTemplate();
                }
            }
        }
        return jdbc;
    }

    private NamedParameterJdbcTemplate createJdbcTemplate() {
        // Get Configuration via CRFGuiceContext — same as AgedReportJob.execute()
        // At this point the app has started, so env-prefix resolution is complete
        Configuration cfg = CRFGuiceContext.getInjector().getInstance(Configuration.class);

        String driver   = cfg.getString("scef-ods.JDBC.driver",
                                        "oracle.jdbc.driver.OracleDriver");
        String url      = cfg.getString("scef-ods.JDBC.url", "");
        String username = cfg.getString("scef-ods.JDBC.username", "ADMCEF_APP");
        String password = cfg.getString("scef-ods.JDBC.password", "");

        logger.info("[GAI] Initialising JDBC template — driver={} url={} username={}",
                    driver, url, username);

        if (url == null || url.trim().length() == 0) {
            throw new IllegalStateException(
                    "[GAI] scef-ods.JDBC.url is not configured. " +
                    "Check core.properties for the current environment.");
        }

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(driver);
        ds.setUrl(url.trim());
        ds.setUsername(username);
        ds.setPassword(password);

        logger.info("[GAI] JDBC template initialised successfully");
        return new NamedParameterJdbcTemplate(ds);
    }

    private String loadSql(String classpathResource, String feedName, String fileType) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                logger.error("[GAI][{}][{}] SQL file not found on classpath: {}",
                             feedName, fileType, classpathResource);
                throw new IllegalStateException(
                        "GAI SQL file not found on classpath: " + classpathResource);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[GAI][{}][{}] Failed to read SQL file: {} — {}",
                         feedName, fileType, classpathResource, e.getMessage(), e);
            throw new RuntimeException("Failed to load GAI SQL: " + classpathResource, e);
        }
    }

    private String toMmDdYyyy(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) return yyyyMMdd;
        return yyyyMMdd.substring(4, 6) + yyyyMMdd.substring(6, 8) + yyyyMMdd.substring(0, 4);
    }
}
