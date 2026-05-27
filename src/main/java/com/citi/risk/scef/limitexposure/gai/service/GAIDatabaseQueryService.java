package com.citi.risk.scef.limitexposure.gai.service;

import com.google.inject.Inject;
import com.citi.risk.core.data.db.DataSourceDictionary;
import com.citi.risk.scef.limitexposure.gai.GAIFeedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes GAI SQL files using SCEF's DataSourceDictionary — the same
 * pattern used by AutoOdsCefProcessUpgradeServiceImpl.
 *
 * DataSourceDictionary holds all named datasources registered by SCEF's
 * DB modules. The scef-ods datasource is keyed by "scef-ods" (the
 * mybatis prefix from ScefOdsDBModule.getMybatisPrefix()).
 *
 * No changes needed to ScefOdsDBModule — DataSourceDictionary is already
 * exposed publicly by CoreModule/DBModule infrastructure.
 *
 * SonarQube fixes applied (v37):
 *   S3077  — volatile non-primitive replaced with AtomicReference (Bug fix)
 *   S2139  — catch blocks either log OR rethrow, not both
 *   S112   — GAIFeedException replaces generic RuntimeException throws
 */
public class GAIDatabaseQueryService {

    private static final Logger logger =
            LoggerFactory.getLogger(GAIDatabaseQueryService.class);

    private static final String ODS_DATASOURCE_NAME = "scef-ods";

    private final DataSourceDictionary dataSourceDictionary;

    /**
     * S3077: volatile on a non-primitive reference is not thread-safe.
     * AtomicReference with compareAndSet provides correct lazy init
     * without needing a synchronized block.
     */
    private final AtomicReference<NamedParameterJdbcTemplate> jdbcRef =
            new AtomicReference<>();

    @Inject
    public GAIDatabaseQueryService(DataSourceDictionary dataSourceDictionary) {
        this.dataSourceDictionary = dataSourceDictionary;
    }

    /**
     * Runs one SQL file for the given feed + file type.
     * @param feedName e.g. "stress-exposure"
     * @param fileType "event", "record", or "attribute"
     * @param cobDate  yyyyMMdd e.g. "20260522"
     */
    public List<Map<String, Object>> query(String feedName,
                                            String fileType,
                                            String cobDate) {
        String sqlPath = String.format("gai/sql/%s_%s_query.sql",
                                        feedName, fileType.toLowerCase());

        logger.debug("[GAI][{}][{}] Loading SQL: {}", feedName, fileType, sqlPath);
        String sql = loadSql(sqlPath, feedName, fileType);

        if (!sql.contains(":cobDate") && !sql.contains(":cobDateYYYYMMDD")) {
            logger.warn("[GAI][{}][{}] SQL has no cobDate bind - may extract full history: {}",
                        feedName, fileType, sqlPath);
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("cobDate",         cobDate);
        params.addValue("cobDateYYYYMMDD", cobDate);
        params.addValue("cobDateMMDDYYYY", toMmDdYyyy(cobDate));

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        try {
            rows = getJdbc().queryForList(sql, params);
        } catch (Exception e) {
            // S2139: do not both log AND rethrow — rethrow with context; caller logs
            throw new GAIFeedException(
                    "[GAI][" + feedName + "][" + fileType + "] Query failed cobDate="
                    + cobDate + " sql=" + sqlPath, e);
        }

        logger.info("[GAI][{}][{}] rows={} elapsedMs={} cobDate={}",
                    feedName, fileType, rows.size(),
                    System.currentTimeMillis() - start, cobDate);
        return rows;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * S3077 fix: AtomicReference-based lazy init.
     * compareAndSet guarantees only one template is created even under concurrent access.
     */
    private NamedParameterJdbcTemplate getJdbc() {
        NamedParameterJdbcTemplate existing = jdbcRef.get();
        if (existing != null) {
            return existing;
        }
        NamedParameterJdbcTemplate created = buildJdbcTemplate();
        // If another thread set it first, discard ours and use theirs — both are equivalent
        jdbcRef.compareAndSet(null, created);
        return jdbcRef.get();
    }

    private NamedParameterJdbcTemplate buildJdbcTemplate() {
        // S2139: only throw — do not also log; caller's catch block handles logging
        // S112:  use GAIFeedException instead of RuntimeException
        DataSource ds = dataSourceDictionary.getDataSource(ODS_DATASOURCE_NAME);
        if (ds == null) {
            throw new GAIFeedException(
                    "[GAI] Could not obtain DataSource for '" +
                    ODS_DATASOURCE_NAME + "' from DataSourceDictionary");
        }
        logger.info("[GAI] JDBC template initialised via DataSourceDictionary key={}",
                    ODS_DATASOURCE_NAME);
        return new NamedParameterJdbcTemplate(ds);
    }

    private String loadSql(String resource, String feedName, String fileType) {
        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream(resource)) {
            if (is == null) {
                logger.error("[GAI][{}][{}] SQL not found: {}", feedName, fileType, resource);
                throw new GAIFeedException(
                        "GAI SQL not found on classpath: " + resource);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);

        } catch (GAIFeedException e) {
            // Re-throw our own exception directly — don't wrap it
            throw e;
        } catch (Exception e) {
            // S2139: only throw — do not also log
            // S112:  use GAIFeedException instead of RuntimeException
            throw new GAIFeedException("Failed to load GAI SQL: " + resource, e);
        }
    }

    private String toMmDdYyyy(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) return yyyyMMdd;
        return yyyyMMdd.substring(4, 6) +
               yyyyMMdd.substring(6, 8) +
               yyyyMMdd.substring(0, 4);
    }
}
