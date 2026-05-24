package com.citi.risk.scef.limitexposure.gai.service;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Executes GAI SQL files using a NamedParameterJdbcTemplate provided by
 * GAIFeedModule — backed by the same ADMCEF ODS datasource as ScefOdsDBModule.
 *
 * NamedParameterJdbcTemplate is injected directly (provided by GAIFeedModule),
 * avoiding the need for a @Named DataSource binding which ScefOdsDBModule
 * does not expose.
 */
public class GAIDatabaseQueryService {

    private static final Logger logger = LoggerFactory.getLogger(GAIDatabaseQueryService.class);

    private final NamedParameterJdbcTemplate jdbc;

    @Inject
    public GAIDatabaseQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
            rows = jdbc.queryForList(sql, params);
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
