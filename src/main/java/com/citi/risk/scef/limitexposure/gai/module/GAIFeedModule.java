package com.citi.risk.scef.limitexposure.gai.module;

import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIEmailAlertService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Guice module for all GAI feed services.
 *
 * Add to SCEFModule.configure() after line 113 (ScefMatrixDBModule):
 *   install(new GAIFeedModule());
 *
 * DataSource:
 *   ScefOdsDBModule does NOT expose its DataSource as a Guice binding —
 *   it uses it internally for MyBatis only.
 *   GAIFeedModule creates its own NamedParameterJdbcTemplate using the same
 *   SCEF ODS JDBC properties from Configuration (same DB, same credentials).
 */
public class GAIFeedModule extends AbstractModule {

    private static final Logger logger = LoggerFactory.getLogger(GAIFeedModule.class);

    @Override
    protected void configure() {
        bind(GAIDatabaseQueryService.class).asEagerSingleton();
        bind(GAIFeedDefinitionLoader.class).asEagerSingleton();
        bind(GAIFeedFileNamingService.class).asEagerSingleton();
        bind(GAIFileWriterService.class).asEagerSingleton();
        bind(GAISftpTransferService.class).asEagerSingleton();
        bind(GAIEmailAlertService.class).asEagerSingleton();
    }

    /**
     * Provides a NamedParameterJdbcTemplate backed by the same ODS datasource
     * that ScefOdsDBModule uses — reads from the same JDBC properties in
     * core.properties (LOCAL/SIT/UAT/PROD prefixed).
     *
     * Note: In UAT/PROD the password comes from CyberArk. If scef-ods.JDBC.password
     * is blank in core.properties (CyberArk environments), the DataSource will still
     * be created — CyberArk replaces the password at runtime via the SCEF property
     * framework before this provider is called.
     */
    @Provides
    @Singleton
    public NamedParameterJdbcTemplate provideGaiJdbcTemplate(Configuration cfg) {
        String driver   = cfg.getString("scef-ods.JDBC.driver",
                                        "oracle.jdbc.driver.OracleDriver");
        String url      = cfg.getString("scef-ods.JDBC.url", "");
        String username = cfg.getString("scef-ods.JDBC.username", "ADMCEF_APP");
        String password = cfg.getString("scef-ods.JDBC.password", "");

        logger.info("[GAI] Creating GAI JDBC template — url={} username={}", url, username);

        if (url == null || url.trim().length() == 0) {
            throw new IllegalStateException(
                    "[GAI] scef-ods.JDBC.url is not configured. " +
                    "Check core.properties for the current environment.");
        }

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(driver);
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);

        return new NamedParameterJdbcTemplate(ds);
    }
}
