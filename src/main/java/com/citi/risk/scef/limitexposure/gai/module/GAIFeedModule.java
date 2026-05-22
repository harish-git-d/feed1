package com.citi.risk.scef.limitexposure.gai.module;

import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import com.google.inject.AbstractModule;

/**
 * Guice module for GAI feed services.
 *
 * Register this module in SCEF's existing Guice bootstrap
 * (wherever other SCEF modules are installed — typically in
 *  the main Guice injector setup class, e.g. SCEFGuiceModule or similar).
 *
 * Add one line:
 *   install(new GAIFeedModule());
 */
public class GAIFeedModule extends AbstractModule {

    @Override
    protected void configure() {
        // All services are singletons — created once, reused across job runs
        bind(GAIDatabaseQueryService.class).asEagerSingleton();
        bind(GAIFeedDefinitionLoader.class).asEagerSingleton();
        bind(GAIFeedFileNamingService.class).asEagerSingleton();
        bind(GAIFileWriterService.class).asEagerSingleton();
        bind(GAISftpTransferService.class).asEagerSingleton();
    }
}
