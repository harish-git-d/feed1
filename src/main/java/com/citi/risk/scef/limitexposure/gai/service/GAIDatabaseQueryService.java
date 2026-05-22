package com.citi.risk.scef.limitexposure.gai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.inject.Inject;
import javax.inject.Named;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Executes the 18 GAI SQL files against SCEF's existing Oracle DataSource.
 *
 * Uses NamedParameterJdbcTemplate so SQL files can optionally filter by COB date:
 *   WHERE TO_CHAR(r.COB_DATE, 'YYYYMMDD') = :cobDateYYYYMMDD
 *   WHERE r.COB_DATE = TO_DATE(:cobDateMMDDYYYY, 'MMDDYYYY')
 *
 * SQL loaded from classpath: gai/sql/{feedName}_{fileType}_query.sql
 * DataSource: @Named("scef-ods") — reuses SCEF's existing Oracle connection pool.
 */
public class GAIDatabaseQueryService {

    private static final Logger logger = LoggerFactory.getLogger(GAIDatabaseQueryService.class);

    private final NamedParameterJdbcTemplate jdbc;

    @Inject
    public GAIDatabaseQueryService(@Named("scef-ods") DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Runs the SQL for one feed + file type combination.
     *
     * @param feedName e.g. "stress-exposure"
     * @param fileType "event", "record", or "attribute"
     * @param cobDate  yyyyMMdd e.g. "20260522"
     */
    public List<Map<String, Object>> query(String feedName, String fileType, String cobDate) {
        String sqlPath = String.format("gai/sql/%s_%s_query.sql", feedName, fileType.toLowerCase());
        String sql = loadSql(sqlPath);

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("cobDate",          cobDate);
        params.addValue("cobDateYYYYMMDD",  cobDate);
        params.addValue("cobDateMMDDYYYY",  toMmDdYyyy(cobDate));

        if (!sql.contains(":cobDate")) {
            logger.warn("[GAI][{}][{}] SQL has no :cobDate bind parameter — verify it does not " +
                        "extract full history: {}", feedName, fileType, sqlPath);
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        logger.info("[GAI][{}][{}] rows={} elapsedMs={}", feedName, fileType,
                    rows.size(), System.currentTimeMillis() - start);
        return rows;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String loadSql(String classpathResource) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException("GAI SQL not found on classpath: " + classpathResource);
            }
            // Java 8 compatible — no readAllBytes()
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load GAI SQL: " + classpathResource, e);
        }
    }

    /** Converts yyyyMMdd → MMddyyyy for Oracle TO_DATE patterns */
    private String toMmDdYyyy(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) return yyyyMMdd;
        return yyyyMMdd.substring(4, 6) + yyyyMMdd.substring(6, 8) + yyyyMMdd.substring(0, 4);
    }
}
