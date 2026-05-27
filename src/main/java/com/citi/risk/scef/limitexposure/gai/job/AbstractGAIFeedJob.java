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

            // ── cobDate format guard ──────────────────────────────────────────
            if (!cobDate.matches("\\d{8}")) {
                throw new IllegalArgumentException(
                        "cobDate must be yyyyMMdd format. Received: '" + cobDate + "'");
            }

            // Output directory — must be explicitly configured, no hardcoded fallback
            String outputDir = cfg.getString("SCEF.gai.feed.output.dir", "");
            if (outputDir == null || outputDir.trim().length() == 0) {
                throw new IllegalStateException(
                        "[GAI] SCEF.gai.feed.output.dir is not configured. " +
                        "Add it to core.properties for the current environment.");
            }
            outputDir = outputDir.trim();

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
        FeedDefinition def    = defLoader.load(feedName);
        String         fileDir = resolveOutputDir(outputDir, feedName, cobDate);

        // ── Step 2: Query DB ──────────────────────────────────────────────────
        logger.info("[GAI][{}] Step 2 — querying ADMCEF.SCEF_REQUEST cobDate={}", feedName, cobDate);
        List<Map<String, Object>> eventRows     = dbService.query(feedName, "event",     cobDate);
        List<Map<String, Object>> recordRows    = dbService.query(feedName, "record",    cobDate);
        List<Map<String, Object>> attributeRows = dbService.query(feedName, "attribute", cobDate);

        int ec = eventRows.size(), rc = recordRows.size(), ac = attributeRows.size();
        logger.info("[GAI][{}] Query complete — event={} record={} attribute={}", feedName, ec, rc, ac);

        // ── Alert: zero rows — warn but still generate empty files ─────────────
        if (ec == 0 && rc == 0 && ac == 0) {
            logger.warn("[GAI][{}] Zero rows for all file types cobDate={} — " +
                        "generating empty files and sending alert", feedName, cobDate);
            alertService.sendZeroRowsAlert(feedName, cobDate);
            // Continue — empty files will be generated and transferred
        }

        // ── Alert: row count mismatch — warn but still generate files ─────────
        else if (ec == 0 || rc == 0 || ac == 0) {
            logger.warn("[GAI][{}] Row mismatch cobDate={} event={} record={} attribute={} — " +
                        "generating files and sending alert", feedName, cobDate, ec, rc, ac);
            alertService.sendRowMismatchAlert(feedName, cobDate, ec, rc, ac);
            // Continue — partial files will be generated and transferred
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

        // ── Step 5: SFTP transfer — always from the latest folder ───────────────
        if (sftpEnabled) {
            // Resolve the latest populated folder for this feedName/cobDate
            // e.g. if 20260522/, 20260522-1/, 20260522-2/ exist → uses 20260522-2/
            String sftpDir = resolveLatestDir(outputDir, feedName, cobDate);
            logger.info("[GAI][{}] Step 5 — SFTP transfer from {} to {}",
                        feedName, sftpDir, cfg.getString("SCEF.gai.feed.sftp.host"));
            sftp.transfer(sftpDir,
                    cfg.getString("SCEF.gai.feed.sftp.remote.path"),
                    cfg.getString("SCEF.gai.feed.sftp.host"),
                    cfg.getString("SCEF.gai.feed.sftp.user"),
                    cfg.getString("SCEF.gai.feed.sftp.key.path"));
        } else {
            logger.info("[GAI][{}] Step 5 — SFTP disabled. Files staged at: {}", feedName, fileDir);
        }

        // ── Step 6: Clean up files older than 30 days ───────────────────────────
        logger.info("[GAI][{}] Step 6 — cleaning up files older than 30 days in {}",
                    feedName, outputDir + "/" + feedName);
        cleanupOldFiles(outputDir + "/" + feedName, cfg);

        logger.info("[GAI][{}] ===== JOB COMPLETE cobDate={} event={} record={} attribute={} =====",
                    feedName, cobDate, ec, rc, ac);
    }

    // ── Output directory resolution ─────────────────────────────────────────────

    /**
     * Resolves a unique output directory for this run.
     *
     * First run:   outputDir/feedName/cobDate/
     * Second run:  outputDir/feedName/cobDate-1/
     * Third run:   outputDir/feedName/cobDate-2/
     * etc.
     *
     * A directory is considered "used" if it already exists and contains files.
     * An empty directory is reused (allows retry after partial failure).
     */
    private String resolveOutputDir(String outputDir, String feedName, String cobDate) {
        // Base path: first run
        String base = outputDir + "/" + feedName + "/" + cobDate;
        java.io.File baseDir = new java.io.File(base);

        // Reuse if does not exist or is empty (clean retry scenario)
        if (!baseDir.exists() || isEmptyDir(baseDir)) {
            logger.info("[GAI][{}] Output directory: {}", feedName, base);
            return base;
        }

        // Directory exists and has files — find next available suffix
        for (int i = 1; i <= 999; i++) {
            String candidate = base + "-" + i;
            java.io.File candidateDir = new java.io.File(candidate);
            if (!candidateDir.exists() || isEmptyDir(candidateDir)) {
                logger.info("[GAI][{}] Re-run detected — output directory: {}", feedName, candidate);
                return candidate;
            }
        }

        // Fallback — should never happen in practice
        String fallback = base + "-" + System.currentTimeMillis();
        logger.warn("[GAI][{}] Could not find available suffix after 999 attempts — using: {}",
                    feedName, fallback);
        return fallback;
    }

    /**
     * Returns the latest existing folder for feedName/cobDate.
     * Checks base folder then -1, -2, -3 ... and returns the highest that exists.
     *
     * Examples:
     *   Only 20260522/        exists → returns 20260522/
     *   20260522/ and 20260522-1/ exist → returns 20260522-1/
     *   20260522/ through 20260522-3/ exist → returns 20260522-3/
     */
    private String resolveLatestDir(String outputDir, String feedName, String cobDate) {
        String base    = outputDir + "/" + feedName + "/" + cobDate;
        String latest  = base;

        // Walk forward until a folder doesn't exist
        for (int i = 1; i <= 999; i++) {
            String candidate = base + "-" + i;
            if (new java.io.File(candidate).exists()) {
                latest = candidate;
            } else {
                break;
            }
        }

        logger.debug("[GAI][{}] Latest output folder for cobDate={}: {}", feedName, cobDate, latest);
        return latest;
    }

    private boolean isEmptyDir(java.io.File dir) {
        if (!dir.isDirectory()) return true;
        String[] contents = dir.list();
        return contents == null || contents.length == 0;
    }

    // ── File cleanup ─────────────────────────────────────────────────────────

    /**
     * Deletes cobDate subfolders older than SCEF.gai.feed.cleanup.days (default 30).
     * Folder structure: {outputDir}/{feedName}/{cobDate}/
     * Only deletes folders whose name matches yyyyMMdd and is older than the threshold.
     * Cleanup failure never fails the job — logged as warning only.
     */
    private void cleanupOldFiles(String feedBaseDir, Configuration cfg) {
        int retentionDays = cfg.getInt("SCEF.gai.feed.cleanup.days", 30);
        java.io.File baseDir = new java.io.File(feedBaseDir);

        if (!baseDir.exists() || !baseDir.isDirectory()) {
            logger.debug("[GAI] Cleanup skipped — base dir does not exist: {}", feedBaseDir);
            return;
        }

        java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(retentionDays);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        int deleted = 0;
        int failed  = 0;

        java.io.File[] subDirs = baseDir.listFiles(java.io.File::isDirectory);
        if (subDirs == null) return;

        for (java.io.File dir : subDirs) {
            try {
                // Only process folders named yyyyMMdd
                java.time.LocalDate folderDate = java.time.LocalDate.parse(dir.getName(), fmt);
                if (folderDate.isBefore(cutoff)) {
                    if (deleteDirectory(dir)) {
                        logger.info("[GAI] Deleted old folder: {} (age > {} days)",
                                    dir.getAbsolutePath(), retentionDays);
                        deleted++;
                    } else {
                        logger.warn("[GAI] Could not delete folder: {}", dir.getAbsolutePath());
                        failed++;
                    }
                }
            } catch (java.time.format.DateTimeParseException ignored) {
                // Folder name is not a cobDate — skip silently
            } catch (Exception e) {
                // Cleanup failure must never fail the job
                logger.warn("[GAI] Cleanup error for folder {}: {}", dir.getName(), e.getMessage());
                failed++;
            }
        }

        logger.info("[GAI] Cleanup complete — deleted={} failed={} cutoff={} retentionDays={}",
                    deleted, failed, cutoff, retentionDays);
    }

    /** Recursively deletes a directory and all its contents. */
    private boolean deleteDirectory(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    if (!f.delete()) {
                        logger.warn("[GAI] Could not delete file: {}", f.getAbsolutePath());
                    }
                }
            }
        }
        return dir.delete();
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
