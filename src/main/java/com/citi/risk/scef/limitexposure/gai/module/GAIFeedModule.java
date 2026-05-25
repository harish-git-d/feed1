package com.citi.risk.scef.limitexposure.gai.module;

import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIEmailAlertService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import com.google.inject.AbstractModule;

/**
 * Guice module for all GAI feed services.
 *
 * Add to SCEFModule.configure() after ScefMatrixDBModule (line 113):
 *   install(new GAIFeedModule());
 *
 * Dependencies already bound by SCEFModule (no extra bindings needed):
 *   @Named("emailSendServiceImpl")   CommonEmailSendService
 *   @Named("emailDetailServiceImpl") EmailDetailService
 *   EmailServiceProperties
 *   Configuration
 *   DataSourceDictionary
 */
public class GAIFeedModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(GAIDatabaseQueryService.class).asEagerSingleton();
        bind(GAIFeedDefinitionLoader.class).asEagerSingleton();
        bind(GAIFeedFileNamingService.class).asEagerSingleton();
        bind(GAIFileWriterService.class).asEagerSingleton();
        bind(GAISftpTransferService.class).asEagerSingleton();
        bind(GAIEmailAlertService.class).asEagerSingleton();
    }
}
