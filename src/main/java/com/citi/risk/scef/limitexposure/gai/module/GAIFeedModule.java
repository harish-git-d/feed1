package com.citi.risk.scef.limitexposure.gai.module;

import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import com.google.inject.AbstractModule;

/**
 * Guice module for all GAI feed services.
 *
 * Register in SCEF's existing Guice bootstrap — find the class in
 * com.citi.risk.scef.limitexposure.config.module that calls install(new ...)
 * and add one line:
 *
 *   install(new GAIFeedModule());
 */
public class GAIFeedModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(GAIDatabaseQueryService.class).asEagerSingleton();
        bind(GAIFeedDefinitionLoader.class).asEagerSingleton();
        bind(GAIFeedFileNamingService.class).asEagerSingleton();
        bind(GAIFileWriterService.class).asEagerSingleton();
        bind(GAISftpTransferService.class).asEagerSingleton();
    }
}
