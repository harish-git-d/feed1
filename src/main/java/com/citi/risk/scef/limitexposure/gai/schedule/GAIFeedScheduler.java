package com.citi.risk.scef.limitexposure.gai.schedule;

import com.citi.risk.scef.limitexposure.gai.job.GAIOetFlagJob;
import com.citi.risk.scef.limitexposure.gai.job.GAIPseExposureJob;
import com.citi.risk.scef.limitexposure.gai.job.GAIPseMonthEndJob;
import com.citi.risk.scef.limitexposure.gai.job.GAIStressExposureJob;
import com.citi.risk.scef.limitexposure.gai.job.GAISwwrFlagJob;
import com.citi.risk.scef.limitexposure.gai.job.GAISwwrRecoveryJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz schedule configuration for all 6 GAI feed jobs.
 *
 * ── HOW TO REGISTER ──────────────────────────────────────────────────────────
 * Find SCEFSpringQuartzConfig.java in the SCEF codebase.
 * Add this class to its component scan or import it directly.
 *
 * Search IntelliJ for: SCEFSpringQuartzConfig
 * Then follow the pattern used for other scheduled jobs there.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Default schedules (configurable via core.properties):
 *
 *   Daily feeds (5):   0 0 3 * * ?   — 3:00 AM every day
 *     GAIFeedStressExposureJob
 *     GAIFeedSwwrFlagJob
 *     GAIFeedPseExposureJob
 *     GAIFeedSwwrRecoveryJob
 *     GAIFeedOetFlagJob
 *
 *   Monthly feed (1):  0 0 4 L * ?   — 4:00 AM last day of every month
 *     GAIFeedPseMonthEndJob
 *
 * Properties to override cron expressions:
 *   SCEF.gai.feed.cron.daily=0 0 3 * * ?
 *   SCEF.gai.feed.cron.monthly=0 0 4 L * ?
 */
@Configuration
public class GAIFeedScheduler {

    private static final Logger logger = LoggerFactory.getLogger(GAIFeedScheduler.class);

    // ── Default cron expressions ──────────────────────────────────────────────

    /** 3:00 AM every day — after COB, before business hours */
    private static final String DEFAULT_DAILY_CRON   = "0 0 3 * * ?";

    /** 4:00 AM on the last day of every month */
    private static final String DEFAULT_MONTHLY_CRON = "0 0 4 L * ?";

    // ── Daily feed jobs ───────────────────────────────────────────────────────

    @Bean
    public JobDetail gaiFeedStressExposureJobDetail() {
        return jobDetail(GAIStressExposureJob.class, "GAIFeedStressExposureJob");
    }

    @Bean
    public Trigger gaiFeedStressExposureTrigger() {
        return dailyTrigger("GAIFeedStressExposureTrigger",
                             gaiFeedStressExposureJobDetail());
    }

    @Bean
    public JobDetail gaiFeedSwwrFlagJobDetail() {
        return jobDetail(GAISwwrFlagJob.class, "GAIFeedSwwrFlagJob");
    }

    @Bean
    public Trigger gaiFeedSwwrFlagTrigger() {
        return dailyTrigger("GAIFeedSwwrFlagTrigger",
                             gaiFeedSwwrFlagJobDetail());
    }

    @Bean
    public JobDetail gaiFeedPseExposureJobDetail() {
        return jobDetail(GAIPseExposureJob.class, "GAIFeedPseExposureJob");
    }

    @Bean
    public Trigger gaiFeedPseExposureTrigger() {
        return dailyTrigger("GAIFeedPseExposureTrigger",
                             gaiFeedPseExposureJobDetail());
    }

    @Bean
    public JobDetail gaiFeedSwwrRecoveryJobDetail() {
        return jobDetail(GAISwwrRecoveryJob.class, "GAIFeedSwwrRecoveryJob");
    }

    @Bean
    public Trigger gaiFeedSwwrRecoveryTrigger() {
        return dailyTrigger("GAIFeedSwwrRecoveryTrigger",
                             gaiFeedSwwrRecoveryJobDetail());
    }

    @Bean
    public JobDetail gaiFeedOetFlagJobDetail() {
        return jobDetail(GAIOetFlagJob.class, "GAIFeedOetFlagJob");
    }

    @Bean
    public Trigger gaiFeedOetFlagTrigger() {
        return dailyTrigger("GAIFeedOetFlagTrigger",
                             gaiFeedOetFlagJobDetail());
    }

    // ── Monthly feed job ──────────────────────────────────────────────────────

    @Bean
    public JobDetail gaiFeedPseMonthEndJobDetail() {
        return jobDetail(GAIPseMonthEndJob.class, "GAIFeedPseMonthEndJob");
    }

    @Bean
    public Trigger gaiFeedPseMonthEndTrigger() {
        return monthlyTrigger("GAIFeedPseMonthEndTrigger",
                               gaiFeedPseMonthEndJobDetail());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private JobDetail jobDetail(Class<?> jobClass, String jobName) {
        logger.debug("[GAI][SCHEDULE] Registering job: {}", jobName);
        return JobBuilder.newJob((Class<? extends org.quartz.Job>) jobClass)
                .withIdentity(jobName, "GAIFeedGroup")
                .storeDurably()
                .build();
    }

    private Trigger dailyTrigger(String triggerName, JobDetail jobDetail) {
        // Read from property — override in core.properties if needed
        // *.*.*.SCEF.gai.feed.cron.daily=0 0 3 * * ?
        String cron = getCronFromConfig("SCEF.gai.feed.cron.daily", DEFAULT_DAILY_CRON);
        logger.info("[GAI][SCHEDULE] {} cron: {}", triggerName, cron);
        return TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(triggerName, "GAIFeedGroup")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                .build();
    }

    private Trigger monthlyTrigger(String triggerName, JobDetail jobDetail) {
        // Read from property — override in core.properties if needed
        // *.*.*.SCEF.gai.feed.cron.monthly=0 0 4 L * ?
        String cron = getCronFromConfig("SCEF.gai.feed.cron.monthly", DEFAULT_MONTHLY_CRON);
        logger.info("[GAI][SCHEDULE] {} cron: {}", triggerName, cron);
        return TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(triggerName, "GAIFeedGroup")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                .build();
    }

    private String getCronFromConfig(String key, String defaultValue) {
        try {
            com.citi.risk.core.ioc.impl.guice.CRFGuiceContext injector =
                    null; // CRFGuiceContext is static — no instance needed
            org.apache.commons.configuration.Configuration config =
                    com.citi.risk.core.ioc.impl.guice.CRFGuiceContext
                            .getInjector().getInstance(
                                    org.apache.commons.configuration.Configuration.class);
            String value = config.getString(key, "");
            if (value != null && value.trim().length() > 0) {
                return value.trim();
            }
        } catch (Exception e) {
            logger.warn("[GAI][SCHEDULE] Could not read {} from config — using default: {}",
                        key, defaultValue);
        }
        return defaultValue;
    }
}
