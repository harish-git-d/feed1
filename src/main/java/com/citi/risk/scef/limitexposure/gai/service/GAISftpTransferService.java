package com.citi.risk.scef.limitexposure.gai.service;

import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SFTP transfer using sftp.sh bundled inside scef-war.
 *
 * sftp.sh is packaged at:
 *   scef-war/src/main/resources/gai/sftp.sh
 *
 * At runtime it is extracted once to:
 *   {SCEF.gai.feed.output.dir}/sftp.sh
 * and made executable before use.
 *
 * Script signature (confirmed from server):
 *   sftp.sh <SRC_FILE> <DEST_PATH> <USER> <REMOTE_HOST>
 *
 * Transfer order: EVENT → RECORD → ATTRIBUTE → .ctrl
 * The .ctrl file must arrive last — it triggers GAI processing.
 */
public class GAISftpTransferService {

    private static final Logger logger = LoggerFactory.getLogger(GAISftpTransferService.class);

    private static final String SCRIPT_RESOURCE = "gai/sftp.sh";

    private final Configuration cfg;

    /** Cached path of the extracted script — extracted once per JVM lifetime */
    private volatile String extractedScriptPath;

    @Inject
    public GAISftpTransferService(Configuration cfg) {
        this.cfg = cfg;
    }

    /**
     * Transfers all .dat.gz and .ctrl files in localDir via sftp.sh.
     */
    public void transfer(String localDir, String remotePath,
                          String host, String user, String pemKeyPath) throws Exception {

        requireNonBlank(host,       "SCEF.gai.feed.sftp.host");
        requireNonBlank(user,       "SCEF.gai.feed.sftp.user");
        requireNonBlank(remotePath, "SCEF.gai.feed.sftp.remote.path");

        String scriptPath = getOrExtractScript(localDir);
        int timeoutMs     = cfg.getInt("SCEF.gai.feed.sftp.timeout.ms", 60000);

        List<File> files  = orderedFiles(localDir);
        logger.info("[GAI][SFTP] Transferring {} files to {}:{}", files.size(), host, remotePath);

        for (File f : files) {
            transferOneFile(f, remotePath, user, host, scriptPath, timeoutMs);
        }

        logger.info("[GAI][SFTP] All {} files transferred to {}:{}", files.size(), host, remotePath);
    }

    // ── Script extraction ─────────────────────────────────────────────────────

    /**
     * Returns the path to sftp.sh, extracting from the WAR classpath if needed.
     *
     * Re-extracts if:
     *   - Never extracted yet (first run)
     *   - File was deleted (temp cleanup, server restart, manual deletion)
     *
     * Thread-safe via double-checked locking.
     */
    private String getOrExtractScript(String localDir) throws Exception {
        // Fast path — already extracted and file still exists
        if (extractedScriptPath != null && new File(extractedScriptPath).exists()) {
            logger.debug("[GAI][SFTP] Using cached sftp.sh: {}", extractedScriptPath);
            return extractedScriptPath;
        }
        // Slow path — extract (or re-extract if file was deleted)
        synchronized (this) {
            if (extractedScriptPath != null && new File(extractedScriptPath).exists()) {
                return extractedScriptPath;
            }
            if (extractedScriptPath != null) {
                // File was deleted after previous extraction — re-extracting
                logger.warn("[GAI][SFTP] sftp.sh was deleted from cache path: {} " +
                            "— re-extracting from WAR classpath", extractedScriptPath);
            }
            extractedScriptPath = extractScript(localDir);
        }
        return extractedScriptPath;
    }

    private String extractScript(String localDir) throws Exception {
        // Extract to SCEF.gai.feed.output.dir — the root staging directory
        // e.g. SCEF.gai.feed.output.dir=/opt/scef/gai/staging → sftp.sh goes there
        // localDir is outputDir/feedName/cobDate — too deep, use cfg directly
        String outputBase = cfg.getString("SCEF.gai.feed.output.dir", "").trim();
        if (outputBase.length() == 0) {
            // Fallback: go up two levels from localDir (feedName/cobDate)
            Path p = Paths.get(localDir);
            outputBase = (p.getParent() != null && p.getParent().getParent() != null)
                    ? p.getParent().getParent().toString()
                    : localDir;
            logger.warn("[GAI][SFTP] SCEF.gai.feed.output.dir not set — " +
                        "extracting sftp.sh to fallback path: {}", outputBase);
        }
        Files.createDirectories(Paths.get(outputBase));

        String destPath = outputBase + File.separator + "sftp.sh";
        File destFile   = new File(destPath);

        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream(SCRIPT_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "[GAI][SFTP] sftp.sh not found on classpath: " + SCRIPT_RESOURCE +
                        ". Ensure gai/sftp.sh is in scef-war/src/main/resources/gai/");
            }
            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            }
        }

        // chmod +x — required for /bin/sh execution
        try {
            Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(Paths.get(destPath), perms);
        } catch (UnsupportedOperationException e) {
            // Windows (LOCAL dev) — Posix permissions not supported, skip
            logger.debug("[GAI][SFTP] Posix chmod skipped (Windows)");
        }

        logger.info("[GAI][SFTP] sftp.sh extracted to: {}", destPath);
        return destPath;
    }

    // ── Transfer ──────────────────────────────────────────────────────────────

    private void transferOneFile(File srcFile, String remotePath,
                                  String user, String host,
                                  String scriptPath, int timeoutMs) throws Exception {

        // sftp.sh <SRC_FILE> <DEST_PATH> <USER> <REMOTE_HOST>
        String[] cmd = { "/bin/sh", scriptPath,
                          srcFile.getAbsolutePath(), remotePath, user, host };

        logger.info("[GAI][SFTP] Sending {} ({} bytes)", srcFile.getName(), srcFile.length());
        logger.debug("[GAI][SFTP] cmd: /bin/sh {} {} {} {} {}",
                     scriptPath, srcFile.getAbsolutePath(), remotePath, user, host);

        Process proc = Runtime.getRuntime().exec(cmd);

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[GAI][SFTP][out] {}", line);
                output.append(line).append("\n");
            }
        }

        // Poll with timeout — same as AgedReportJob.deleteFileUnused()
        long start = System.currentTimeMillis();
        while (proc.isAlive()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                proc.destroy();
                throw new IllegalStateException(
                        "[GAI][SFTP] Timeout after " + timeoutMs + "ms for: " + srcFile.getName());
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
        }

        int exitCode = proc.exitValue();
        if (exitCode == 0) {
            logger.info("[GAI][SFTP] Sent: {}", srcFile.getName());
        } else {
            logger.error("[GAI][SFTP] Failed (exit={}) for: {}\nOutput:\n{}",
                         exitCode, srcFile.getName(), output);
            throw new RuntimeException(
                    "[GAI][SFTP] Transfer failed (exit=" + exitCode + ") for: " + srcFile.getName());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<File> orderedFiles(String localDir) {
        File dir  = new File(localDir);
        File[] all = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".dat.gz") || name.endsWith(".ctrl");
            }
        });
        if (all == null || all.length == 0) {
            throw new IllegalStateException(
                    "[GAI][SFTP] No .dat.gz or .ctrl files in: " + localDir);
        }
        List<File> files = new ArrayList<File>(Arrays.asList(all));
        files.sort(new Comparator<File>() {
            public int compare(File a, File b) {
                return Integer.compare(order(a.getName()), order(b.getName()));
            }
        });
        logger.debug("[GAI][SFTP] Order: {}",
                     files.stream().map(File::getName)
                          .reduce((a, b) -> a + " → " + b).orElse(""));
        return files;
    }

    private int order(String name) {
        if (name.contains("_EVENT_"))     return 1;
        if (name.contains("_RECORD_"))    return 2;
        if (name.contains("_ATTRIBUTE_")) return 3;
        if (name.endsWith(".ctrl"))       return 4;
        return 5;
    }

    private void requireNonBlank(String value, String propertyName) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalStateException(
                    "[GAI][SFTP] Required property is blank: " + propertyName);
        }
    }
}
