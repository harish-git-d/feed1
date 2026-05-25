package com.citi.risk.scef.limitexposure.gai.service;

import com.google.inject.Inject;
import com.citi.risk.core.data.db.DataSourceDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
 */
public class GAIDatabaseQueryService {

    private static final Logger logger =
            LoggerFactory.getLogger(GAIDatabaseQueryService.class);

    private static final String ODS_DATASOURCE_NAME = "scef-ods";

    private final DataSourceDictionary dataSourceDictionary;

    // Lazily initialised — DataSourceDictionary is ready at injection time
    // but we build the template on first use to follow SCEF lazy pattern
    private volatile NamedParameterJdbcTemplate jdbc;

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
            logger.warn("[GAI][{}][{}] SQL has no cobDate bind — may extract full history: {}",
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
            logger.error("[GAI][{}][{}] Query failed cobDate={} sql={}",
                         feedName, fileType, cobDate, sqlPath);
            logger.error("[GAI][{}][{}] Exception:", feedName, fileType, e);
            throw e;
        }

        logger.info("[GAI][{}][{}] rows={} elapsedMs={} cobDate={}",
                    feedName, fileType, rows.size(),
                    System.currentTimeMillis() - start, cobDate);
        return rows;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private NamedParameterJdbcTemplate getJdbc() {
        if (jdbc == null) {
            synchronized (this) {
                if (jdbc == null) {
                    jdbc = buildJdbcTemplate();
                }
            }
        }
        return jdbc;
    }

    private NamedParameterJdbcTemplate buildJdbcTemplate() {
        try {
            // Same pattern as AutoOdsCefProcessUpgradeServiceImpl:
            //   dataSourceDictionary.getTransactionManager(name)
            //                       .getDataSource()
            DataSource ds = dataSourceDictionary
                    .getTransactionManager(ODS_DATASOURCE_NAME)
                    .getDataSource();

            if (ds == null) {
                throw new IllegalStateException(
                        "[GAI] Could not obtain DataSource for '" +
                        ODS_DATASOURCE_NAME + "' from DataSourceDictionary");
            }

            logger.info("[GAI] JDBC template initialised via DataSourceDictionary key={}",
                        ODS_DATASOURCE_NAME);
            return new NamedParameterJdbcTemplate(ds);

        } catch (Exception e) {
            logger.error("[GAI] Failed to build JDBC template from DataSourceDictionary: {}",
                         e.getMessage(), e);
            throw new RuntimeException(
                    "[GAI] DataSource initialisation failed for key=" +
                    ODS_DATASOURCE_NAME, e);
        }
    }

    private String loadSql(String resource, String feedName, String fileType) {
        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream(resource)) {
            if (is == null) {
                logger.error("[GAI][{}][{}] SQL not found: {}", feedName, fileType, resource);
                throw new IllegalStateException(
                        "GAI SQL not found on classpath: " + resource);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[GAI][{}][{}] Failed reading SQL {}: {}",
                         feedName, fileType, resource, e.getMessage(), e);
            throw new RuntimeException("Failed to load GAI SQL: " + resource, e);
        }
    }

    private String toMmDdYyyy(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) return yyyyMMdd;
        return yyyyMMdd.substring(4, 6) +
               yyyyMMdd.substring(6, 8) +
               yyyyMMdd.substring(0, 4);
    }
}
