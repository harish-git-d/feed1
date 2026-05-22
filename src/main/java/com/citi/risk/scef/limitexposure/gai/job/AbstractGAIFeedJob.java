package com.citi.risk.scef.limitexposure.gai.job;

import com.citi.risk.core.ioc.impl.guice.CRFGuiceContext;
import com.citi.risk.core.spring.batch.interfaces.BatchParameterAware;
import com.citi.risk.core.spring.batch.interfaces.JobInterfaceDefaultImpl;
import com.citi.risk.core.spring.batch.interfaces.ManagedExecution;
import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Base class for all 6 GAI feed jobs.
 *
 * Follows the exact same pattern as AgedReportJob:
 *   - extends JobInterfaceDefaultImpl
 *   - implements Callable<Integer>, ManagedExecution, BatchParameterAware
 *   - uses CRFGuiceContext.getInjector() to resolve services
 *   - guarded by a master switch property
 *
 * Each subclass only needs to implement getFeedName().
 *
 * Job flow (all in one step, matches existing SCEF single-step jobs):
 *   1. Load feed definition YAML
 *   2. Query ADMCEF.SCEF_REQUEST (EVENT / RECORD / ATTRIBUTE)
 *   3. Write pipe-delimited .dat.gz files
 *   4. Generate .ctrl control file
 *   5. SFTP to GAI gateway (if enabled)
 */
public abstract class AbstractGAIFeedJob
        extends JobInterfaceDefaultImpl
        implements Callable<Integer>, ManagedExecution, BatchParameterAware {

    private static final Logger logger =
            LoggerFactory.getLogger(AbstractGAIFeedJob.class);

    private static final DateTimeFormatter COB_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private JobParameters jobParameters;

    /** Each subclass returns its feed name, e.g. "stress-exposure" */
    protected abstract String getFeedName();

    // ── BatchParameterAware ──────────────────────────────────────────────────

    @Override
    public void setJobParameters(JobParameters jobParameters) {
        this.jobParameters = jobParameters;
    }

    // ── Callable<Integer> bridge ─────────────────────────────────────────────

    @Override
    public Integer call() {
        return execute();
    }

    // ── ManagedExecution / main entry ────────────────────────────────────────

    @Override
    public Integer execute() {
        Configuration cfg = CRFGuiceContext.getInjector()
                                           .getInstance(Configuration.class);

        // Master switch — same guard as AgedReportJob
        boolean open = cfg.getBoolean("SCEF.gai.feed.job.open", false);
        if (!open) {
            logger.info("[GAI][{}] Skipped — SCEF.gai.feed.job.open=false",
                        getFeedName());
            return 1;
        }

        String feedName  = getFeedName();
        String cobDate   = resolveParam("cobDate",
                               LocalDate.now().format(COB_FMT));
        String outputDir = cfg.getString("SCEF.gai.feed.output.dir",
                               "/opt/scef/gai/staging");
        boolean sftpOn   = cfg.getBoolean("SCEF.gai.feed.sftp.enabled", false);

        logger.info("[GAI][{}] Starting — cobDate={} sftp={}",
                    feedName, cobDate, sftpOn);

        try {
            return doTask(cfg, feedName, cobDate, outputDir, sftpOn);
        } catch (Exception e) {
            logger.error("[GAI][{}] FAILED", feedName, e);
            return 0;
        }
    }

    // ── Core orchestration ───────────────────────────────────────────────────

    private int doTask(Configuration cfg,
                       String feedName, String cobDate,
                       String outputDir, boolean sftpOn) throws Exception {

        // Resolve services via Guice — same pattern as AgedReportJob.execute()
        GAIFeedDefinitionLoader  defLoader   = CRFGuiceContext.getInjector()
                .getInstance(GAIFeedDefinitionLoader.class);
        GAIDatabaseQueryService  dbService   = CRFGuiceContext.getInjector()
                .getInstance(GAIDatabaseQueryService.class);
        GAIFeedFileNamingService namingService = CRFGuiceContext.getInjector()
                .getInstance(GAIFeedFileNamingService.class);
        GAIFileWriterService     fileWriter  = CRFGuiceContext.getInjector()
                .getInstance(GAIFileWriterService.class);
        GAISftpTransferService   sftp        = CRFGuiceContext.getInjector()
                .getInstance(GAISftpTransferService.class);

        // Step 1 — Load feed definition
        FeedDefinition def = defLoader.load(feedName);
        logger.info("[GAI][{}] Definition loaded — columns={}",
                    feedName, def.getColumns().size());

        // Step 2 — Query DB for all 3 file types
        List<Map<String, Object>> eventRows     = dbService.query(feedName, "event",     cobDate);
        List<Map<String, Object>> recordRows    = dbService.query(feedName, "record",    cobDate);
        List<Map<String, Object>> attributeRows = dbService.query(feedName, "attribute", cobDate);

        logger.info("[GAI][{}] Query results — event={} record={} attribute={}",
                    feedName,
                    eventRows.size(), recordRows.size(), attributeRows.size());

        if (eventRows.isEmpty() && recordRows.isEmpty() && attributeRows.isEmpty()) {
            logger.warn("[GAI][{}] No data found for cobDate={}. " +
                        "Skipping file generation.", feedName, cobDate);
            return 1;
        }

        // Step 3 — Write EVENT / RECORD / ATTRIBUTE files
        String fileDir = outputDir + "/" + feedName;

        FileMetadata eventMeta = fileWriter.write(
                feedName, "EVENT", cobDate, eventRows, def, fileDir,
                namingService);
        FileMetadata recordMeta = fileWriter.write(
                feedName, "RECORD", cobDate, recordRows, def, fileDir,
                namingService);
        FileMetadata attributeMeta = fileWriter.write(
                feedName, "ATTRIBUTE", cobDate, attributeRows, def, fileDir,
                namingService);

        logger.info("[GAI][{}] Files written — event={} record={} attribute={}",
                    feedName,
                    eventMeta.getFileName(),
                    recordMeta.getFileName(),
                    attributeMeta.getFileName());

        // Step 4 — Generate .ctrl control file
        String ctrlFile = fileWriter.writeControlFile(
                feedName, cobDate, fileDir, namingService,
                List.of(eventMeta, recordMeta, attributeMeta));

        logger.info("[GAI][{}] Control file written — {}", feedName, ctrlFile);

        // Step 5 — SFTP transfer (if enabled)
        if (sftpOn) {
            String keyPath    = cfg.getString("SCEF.gai.feed.sftp.key.path", "");
            String remotePath = cfg.getString("SCEF.gai.feed.sftp.remote.path",
                                    "/gfolysftp/gfolyrsk/incoming/citirisk");
            String sftpHost   = cfg.getString("SCEF.gai.feed.sftp.host", "");
            String sftpUser   = cfg.getString("SCEF.gai.feed.sftp.user", "");

            sftp.transfer(fileDir, remotePath, sftpHost, sftpUser, keyPath);
            logger.info("[GAI][{}] SFTP transfer complete → {}:{}",
                        feedName, sftpHost, remotePath);
        } else {
            logger.info("[GAI][{}] SFTP skipped (disabled)", feedName);
        }

        logger.info("[GAI][{}] Completed successfully", feedName);
        return 1;
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private String resolveParam(String key, String defaultValue) {
        if (jobParameters != null) {
            String v = jobParameters.getString(key);
            if (v != null && !v.isBlank()) return v;
        }
        return defaultValue;
    }
}
