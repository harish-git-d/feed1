package com.citi.risk.scef.limitexposure.gai.job;

import com.citi.risk.core.ioc.impl.guice.CRFGuiceContext;
import com.citi.risk.core.spring.batch.interfaces.BatchParameterAware;
import com.citi.risk.core.spring.batch.interfaces.JobInterfaceDefaultImpl;
import com.citi.risk.core.spring.batch.interfaces.ManagedExecution;
import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIEmailAlertService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Base class for all 6 GAI feed jobs.
 *
 * Aligned to JobInterfaceDefaultImpl (image 3-4):
 *   - call() is already implemented in parent — do NOT override it
 *   - execute() is abstract in parent (line 64) — override with @Override
 *   - BatchParameterAware uses setBatchParameter(BatchParameter), not setJobParameters
 *
 * cobDate resolution (in priority order):
 *   1. SCEF.gai.feed.cobDate property (set for scheduled/manual reruns)
 *   2. Yesterday (T-1) — default for daily scheduled runs
 *
 * To run for a specific date, set in core.properties before launching:
 *   LOCAL.*.*.SCEF.gai.feed.cobDate=20260521
 * Clear it after the run to revert to T-1 default.
 */
public abstract class AbstractGAIFeedJob extends JobInterfaceDefaultImpl
        implements ManagedExecution, BatchParameterAware {

    private static final Logger logger = LoggerFactory.getLogger(AbstractGAIFeedJob.class);
    private static final DateTimeFormatter COB_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    protected abstract String getFeedName();

    // ── ManagedExecution — abstract in JobInterfaceDefaultImpl (line 64) ─────

    @Override
    public Integer execute() {
        String feedName = getFeedName();
        String cobDate  = resolveCobDate();

        try {
            Configuration cfg = CRFGuiceContext.getInjector().getInstance(Configuration.class);

            // ── Master switch ─────────────────────────────────────────────────
            if (!cfg.getBoolean("SCEF.gai.feed.job.open", false)) {
                logger.info("[GAI][{}] skipped — SCEF.gai.feed.job.open=false", feedName);
                return 1;
            }

            // ── cobDate format guard ──────────────────────────────────────────
            if (!cobDate.matches("\\d{8}")) {
                throw new IllegalArgumentException(
                        "cobDate must be yyyyMMdd format. Received: '" + cobDate + "'");
            }

            String  outputDir   = cfg.getString("SCEF.gai.feed.output.dir", "/opt/scef/gai/staging");
            boolean sftpEnabled = cfg.getBoolean("SCEF.gai.feed.sftp.enabled", false);

            logger.info("[GAI][{}] ===== JOB START cobDate={} sftp={} outputDir={} =====",
                        feedName, cobDate, sftpEnabled, outputDir);

            runFeed(cfg, feedName, cobDate, outputDir, sftpEnabled);
            return 1;

        } catch (Exception e) {
            logger.error("[GAI][{}] ===== JOB FAILED cobDate={} =====", feedName, cobDate);
            logger.error("[GAI][{}] Exception type   : {}", feedName, e.getClass().getName());
            logger.error("[GAI][{}] Exception message: {}", feedName, e.getMessage());
            logger.error("[GAI][{}] Full stack trace :", feedName, e);

            // Send failure alert (guarded — never suppresses original exception)
            try {
                GAIEmailAlertService alertService =
                        CRFGuiceContext.getInjector().getInstance(GAIEmailAlertService.class);
                alertService.sendFailureAlert(feedName, cobDate, e);
            } catch (Exception alertEx) {
                logger.error("[GAI][{}] Could not send failure email alert: {}",
                             feedName, alertEx.getMessage());
            }

            throw new RuntimeException(
                    "[GAI] Feed FAILED: " + feedName + " cobDate=" + cobDate, e);
        }
    }

    // ── Core orchestration ────────────────────────────────────────────────────

    private void runFeed(Configuration cfg, String feedName, String cobDate,
                          String outputDir, boolean sftpEnabled) throws Exception {

        GAIFeedDefinitionLoader  defLoader    = CRFGuiceContext.getInjector().getInstance(GAIFeedDefinitionLoader.class);
        GAIDatabaseQueryService  dbService    = CRFGuiceContext.getInjector().getInstance(GAIDatabaseQueryService.class);
        GAIFeedFileNamingService namingService = CRFGuiceContext.getInjector().getInstance(GAIFeedFileNamingService.class);
        GAIFileWriterService     fileWriter   = CRFGuiceContext.getInjector().getInstance(GAIFileWriterService.class);
        GAISftpTransferService   sftp         = CRFGuiceContext.getInjector().getInstance(GAISftpTransferService.class);
        GAIEmailAlertService     alertService = CRFGuiceContext.getInjector().getInstance(GAIEmailAlertService.class);

        // ── Step 1: Load feed definition ──────────────────────────────────────
        logger.info("[GAI][{}] Step 1 — loading feed definition", feedName);
        FeedDefinition def     = defLoader.load(feedName);
        String         fileDir = outputDir + "/" + feedName + "/" + cobDate;

        // ── Step 2: Query DB ──────────────────────────────────────────────────
        logger.info("[GAI][{}] Step 2 — querying ADMCEF.SCEF_REQUEST cobDate={}", feedName, cobDate);
        List<Map<String, Object>> eventRows     = dbService.query(feedName, "event",     cobDate);
        List<Map<String, Object>> recordRows    = dbService.query(feedName, "record",    cobDate);
        List<Map<String, Object>> attributeRows = dbService.query(feedName, "attribute", cobDate);

        int ec = eventRows.size(), rc = recordRows.size(), ac = attributeRows.size();
        logger.info("[GAI][{}] Query complete — event={} record={} attribute={}", feedName, ec, rc, ac);

        // ── Validation: zero rows ─────────────────────────────────────────────
        if (ec == 0 && rc == 0 && ac == 0) {
            logger.warn("[GAI][{}] Zero rows for all file types cobDate={} — no files generated",
                        feedName, cobDate);
            alertService.sendZeroRowsAlert(feedName, cobDate);
            throw new IllegalStateException(
                    "[GAI][" + feedName + "] Zero rows for cobDate=" + cobDate);
        }

        // ── Validation: row count mismatch ────────────────────────────────────
        if (ec == 0 || rc == 0 || ac == 0) {
            logger.warn("[GAI][{}] Row mismatch cobDate={} event={} record={} attribute={} — no files generated",
                        feedName, cobDate, ec, rc, ac);
            alertService.sendRowMismatchAlert(feedName, cobDate, ec, rc, ac);
            throw new IllegalStateException(String.format(
                    "[GAI][%s] Row mismatch cobDate=%s event=%d record=%d attribute=%d",
                    feedName, cobDate, ec, rc, ac));
        }

        // ── Step 3: Write data files ──────────────────────────────────────────
        logger.info("[GAI][{}] Step 3 — writing data files to {}", feedName, fileDir);
        FileMetadata eventFile  = fileWriter.write(feedName, "EVENT",     cobDate, eventRows,     def, fileDir, namingService);
        FileMetadata recordFile = fileWriter.write(feedName, "RECORD",    cobDate, recordRows,    def, fileDir, namingService);
        FileMetadata attrFile   = fileWriter.write(feedName, "ATTRIBUTE", cobDate, attributeRows, def, fileDir, namingService);

        // ── Step 4: Write control file ────────────────────────────────────────
        logger.info("[GAI][{}] Step 4 — writing control file", feedName);
        fileWriter.writeControlFile(feedName, cobDate, fileDir, def, namingService,
                Arrays.asList(eventFile, recordFile, attrFile));

        // ── Step 5: SFTP transfer ─────────────────────────────────────────────
        if (sftpEnabled) {
            logger.info("[GAI][{}] Step 5 — SFTP transfer from {} to {}",
                        feedName, fileDir, cfg.getString("SCEF.gai.feed.sftp.host"));
            sftp.transfer(fileDir,
                    cfg.getString("SCEF.gai.feed.sftp.remote.path"),
                    cfg.getString("SCEF.gai.feed.sftp.host"),
                    cfg.getString("SCEF.gai.feed.sftp.user"),
                    cfg.getString("SCEF.gai.feed.sftp.key.path"));
        } else {
            logger.info("[GAI][{}] Step 5 — SFTP disabled. Files staged at: {}", feedName, fileDir);
        }

        logger.info("[GAI][{}] ===== JOB COMPLETE cobDate={} event={} record={} attribute={} =====",
                    feedName, cobDate, ec, rc, ac);
    }

    // ── cobDate resolution ────────────────────────────────────────────────────

    private String resolveCobDate() {
        try {
            Configuration cfg = CRFGuiceContext.getInjector().getInstance(Configuration.class);
            String configured = cfg.getString("SCEF.gai.feed.cobDate", "");
            if (configured != null && configured.trim().length() > 0
                    && configured.trim().matches("\\d{8}")) {
                logger.info("[GAI] cobDate from property: {}", configured.trim());
                return configured.trim();
            }
        } catch (Exception ignored) { }

        // Default: yesterday (T-1) — standard COB convention
        String yesterday = LocalDate.now().minusDays(1).format(COB_FMT);
        logger.info("[GAI] cobDate defaulting to yesterday (T-1): {}", yesterday);
        return yesterday;
    }
}
