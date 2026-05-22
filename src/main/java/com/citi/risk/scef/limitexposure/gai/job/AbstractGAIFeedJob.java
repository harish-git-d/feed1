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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Base class for all 6 GAI feed jobs.
 *
 * Follows the same pattern as AgedReportJob:
 *   - extends JobInterfaceDefaultImpl
 *   - implements Callable<Integer>, ManagedExecution, BatchParameterAware
 *   - services resolved via CRFGuiceContext.getInjector()
 *   - guarded by SCEF.gai.feed.job.open master switch
 *
 * Each subclass only needs to implement getFeedName().
 *
 * Job flow (single step, matches existing SCEF jobs):
 *   1. Load feed definition YAML
 *   2. Query ADMCEF.SCEF_REQUEST — EVENT / RECORD / ATTRIBUTE
 *   3. Write pipe-delimited .dat.gz output files
 *   4. Write .ctrl control file
 *   5. SFTP to GAI gateway (if SCEF.gai.feed.sftp.enabled=true)
 *
 * Batchly runtime parameters accepted:
 *   cobDate  — yyyyMMdd, defaults to today
 */
public abstract class AbstractGAIFeedJob extends JobInterfaceDefaultImpl
        implements Callable<Integer>, ManagedExecution, BatchParameterAware {

    private static final Logger logger = LoggerFactory.getLogger(AbstractGAIFeedJob.class);
    private static final DateTimeFormatter COB_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private JobParameters jobParameters;

    /** Each subclass returns its feed name, e.g. "stress-exposure" */
    protected abstract String getFeedName();

    // ── BatchParameterAware ───────────────────────────────────────────────────

    @Override
    public void setJobParameters(JobParameters jobParameters) {
        this.jobParameters = jobParameters;
    }

    // ── Callable<Integer> bridge ──────────────────────────────────────────────

    @Override
    public Integer call() { return execute(); }

    // ── ManagedExecution entry point ──────────────────────────────────────────

    @Override
    public Integer execute() {
        String feedName = getFeedName();
        try {
            Configuration cfg = CRFGuiceContext.getInjector().getInstance(Configuration.class);

            // Master switch — same pattern as AgedReportJob
            if (!cfg.getBoolean("SCEF.gai.feed.job.open", false)) {
                logger.info("[GAI][{}] skipped — SCEF.gai.feed.job.open=false", feedName);
                return 1;
            }

            String cobDate = resolveParam("cobDate", LocalDate.now().format(COB_FMT));

            // Validate cobDate format early — fail fast with a clear message
            if (!cobDate.matches("\\d{8}")) {
                throw new IllegalArgumentException(
                        "cobDate must be yyyyMMdd format. Value=" + cobDate);
            }

            String  outputDir   = cfg.getString("SCEF.gai.feed.output.dir", "/opt/scef/gai/staging");
            boolean sftpEnabled = cfg.getBoolean("SCEF.gai.feed.sftp.enabled", false);

            logger.info("[GAI][{}] starting cobDate={} sftp={}", feedName, cobDate, sftpEnabled);
            runFeed(cfg, feedName, cobDate, outputDir, sftpEnabled);
            return 1;   // SCEF convention: 1 = success

        } catch (Exception e) {
            logger.error("[GAI][{}] failed", feedName, e);
            return 0;   // SCEF convention: 0 = failure
        }
    }

    // ── Core orchestration ────────────────────────────────────────────────────

    private void runFeed(Configuration cfg, String feedName, String cobDate,
                          String outputDir, boolean sftpEnabled) throws Exception {

        // Resolve services via Guice — same pattern as AgedReportJob.execute()
        GAIFeedDefinitionLoader  defLoader    = CRFGuiceContext.getInjector().getInstance(GAIFeedDefinitionLoader.class);
        GAIDatabaseQueryService  dbService    = CRFGuiceContext.getInjector().getInstance(GAIDatabaseQueryService.class);
        GAIFeedFileNamingService namingService = CRFGuiceContext.getInjector().getInstance(GAIFeedFileNamingService.class);
        GAIFileWriterService     fileWriter   = CRFGuiceContext.getInjector().getInstance(GAIFileWriterService.class);
        GAISftpTransferService   sftp         = CRFGuiceContext.getInjector().getInstance(GAISftpTransferService.class);

        // Step 1 — Load feed definition YAML
        FeedDefinition def = defLoader.load(feedName);

        // Step 2 — Query DB: cobDate sub-folder prevents run-over-run overwrite
        String fileDir = outputDir + "/" + feedName + "/" + cobDate;

        List<Map<String, Object>> eventRows     = dbService.query(feedName, "event",     cobDate);
        List<Map<String, Object>> recordRows    = dbService.query(feedName, "record",    cobDate);
        List<Map<String, Object>> attributeRows = dbService.query(feedName, "attribute", cobDate);

        logger.info("[GAI][{}] query results event={} record={} attribute={}",
                    feedName, eventRows.size(), recordRows.size(), attributeRows.size());

        if (eventRows.isEmpty() && recordRows.isEmpty() && attributeRows.isEmpty()) {
            logger.warn("[GAI][{}] no rows for cobDate={} — files not generated", feedName, cobDate);
            return;
        }

        // Step 3 — Write EVENT / RECORD / ATTRIBUTE .dat.gz files
        FileMetadata eventFile  = fileWriter.write(feedName, "EVENT",     cobDate, eventRows,     def, fileDir, namingService);
        FileMetadata recordFile = fileWriter.write(feedName, "RECORD",    cobDate, recordRows,    def, fileDir, namingService);
        FileMetadata attrFile   = fileWriter.write(feedName, "ATTRIBUTE", cobDate, attributeRows, def, fileDir, namingService);

        // Step 4 — Write .ctrl trigger file (must be after all data files)
        fileWriter.writeControlFile(feedName, cobDate, fileDir, def, namingService,
                Arrays.asList(eventFile, recordFile, attrFile));

        // Step 5 — SFTP transfer: EVENT → RECORD → ATTRIBUTE → .ctrl
        if (sftpEnabled) {
            sftp.transfer(fileDir,
                    cfg.getString("SCEF.gai.feed.sftp.remote.path"),
                    cfg.getString("SCEF.gai.feed.sftp.host"),
                    cfg.getString("SCEF.gai.feed.sftp.user"),
                    cfg.getString("SCEF.gai.feed.sftp.key.path"));
        } else {
            logger.info("[GAI][{}] SFTP disabled — files staged at {}", feedName, fileDir);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String resolveParam(String key, String defaultValue) {
        if (jobParameters != null) {
            String v = jobParameters.getString(key);
            if (v != null && v.trim().length() > 0) return v.trim();
        }
        return defaultValue;
    }
}
