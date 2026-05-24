package com.citi.risk.scef.limitexposure.gai.service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SFTP transfer to GAI gateway using JSch — PEM private key auth only.
 *
 * Exception handling:
 *   - Missing/blank config params → IllegalStateException before any connection
 *   - PEM key file not found      → IllegalStateException before any connection
 *   - Connection failure          → JSchException, retried up to sftp.retries times
 *   - Upload failure              → SftpException, retried up to sftp.retries times
 *   - After all retries exhausted → last exception re-thrown to execute() catch block
 *   - Session/channel always closed in finally block even on failure
 *
 * Logging:
 *   - INFO:  connection attempt with host:port
 *   - INFO:  each file upload start + completion with size
 *   - INFO:  overall transfer complete with file count
 *   - WARN:  retry attempt with reason
 *   - ERROR: final failure after all retries exhausted
 *   - DEBUG: transfer order list before upload begins
 */
public class GAISftpTransferService {

    private static final Logger logger = LoggerFactory.getLogger(GAISftpTransferService.class);

    private final Configuration cfg;

    @Inject
    public GAISftpTransferService(Configuration cfg) { this.cfg = cfg; }

    /**
     * Transfers all .dat.gz + .ctrl files in localDir to remotePath.
     * Transfer order enforced: EVENT → RECORD → ATTRIBUTE → .ctrl
     */
    public void transfer(String localDir, String remotePath,
                          String host, String user, String pemKeyPath) throws Exception {

        // ── Validate all params before touching the network ───────────────────
        requireNonBlank(host,       "SCEF.gai.feed.sftp.host");
        requireNonBlank(user,       "SCEF.gai.feed.sftp.user");
        requireNonBlank(remotePath, "SCEF.gai.feed.sftp.remote.path");
        requireNonBlank(pemKeyPath, "SCEF.gai.feed.sftp.key.path");

        File keyFile = new File(pemKeyPath);
        if (!keyFile.exists()) {
            logger.error("[GAI][SFTP] PEM key file not found: {}", pemKeyPath);
            throw new IllegalStateException("PEM key file not found: " + pemKeyPath);
        }

        int port      = cfg.getInt("SCEF.gai.feed.sftp.port", 22);
        int timeoutMs = cfg.getInt("SCEF.gai.feed.sftp.timeout.ms", 30000);
        int retries   = cfg.getInt("SCEF.gai.feed.sftp.retries", 2);

        // ── Retry loop ────────────────────────────────────────────────────────
        Exception last = null;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            try {
                doTransfer(localDir, remotePath, host, user, pemKeyPath, port, timeoutMs);
                return;     // success — exit retry loop
            } catch (Exception e) {
                last = e;
                if (attempt <= retries) {
                    long backoffMs = 2000L * attempt;
                    logger.warn("[GAI][SFTP] Attempt {}/{} failed — retrying in {}ms. Reason: {}",
                                attempt, retries + 1, backoffMs, e.getMessage());
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    logger.error("[GAI][SFTP] All {} attempts failed. Last error: {}",
                                 retries + 1, e.getMessage(), e);
                }
            }
        }
        throw last;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void doTransfer(String localDir, String remotePath,
                             String host, String user, String pemKeyPath,
                             int port, int timeoutMs) throws Exception {

        logger.info("[GAI][SFTP] Connecting to {}@{}:{}", user, host, port);

        JSch jsch = new JSch();
        jsch.addIdentity(pemKeyPath);

        String knownHosts = cfg.getString("SCEF.gai.feed.sftp.known.hosts", "");
        if (knownHosts != null && knownHosts.trim().length() > 0) {
            jsch.setKnownHosts(knownHosts);
        }

        Session    session = null;
        ChannelSftp channel = null;
        try {
            session = jsch.getSession(user, host, port);
            boolean strict = cfg.getBoolean("SCEF.gai.feed.sftp.strict.host.key.checking", false);
            session.setConfig("StrictHostKeyChecking", strict ? "yes" : "no");
            session.setTimeout(timeoutMs);
            session.connect(timeoutMs);
            logger.info("[GAI][SFTP] Session established to {}@{}", user, host);

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(timeoutMs);
            channel.cd(remotePath);
            logger.info("[GAI][SFTP] Changed to remote directory: {}", remotePath);

            List<File> files = orderedFiles(localDir);
            logger.debug("[GAI][SFTP] Transfer order: {}",
                         files.stream().map(File::getName).reduce((a, b) -> a + ", " + b).orElse(""));

            for (File f : files) {
                String tmpName   = f.getName() + ".tmp";
                String finalName = f.getName();
                logger.info("[GAI][SFTP] Uploading {} ({} bytes)", finalName, f.length());

                // Upload as .tmp, rename to final — ctrl file only appears complete
                channel.put(f.getAbsolutePath(), tmpName);
                channel.rename(tmpName, finalName);
                logger.info("[GAI][SFTP] Uploaded and renamed → {}", finalName);
            }

            logger.info("[GAI][SFTP] Transfer complete — {} files → {}:{}",
                        files.size(), host, remotePath);

        } catch (Exception e) {
            logger.error("[GAI][SFTP] Transfer failed to {}:{} — {}", host, remotePath, e.getMessage(), e);
            throw e;
        } finally {
            if (channel != null) try { channel.disconnect(); } catch (Exception ignored) { }
            if (session != null) try { session.disconnect(); } catch (Exception ignored) { }
            logger.debug("[GAI][SFTP] Session and channel closed");
        }
    }

    /**
     * Returns files in mandatory GAI transfer order:
     *   EVENT → RECORD → ATTRIBUTE → .ctrl  (ctrl triggers GAI processing — must be last)
     */
    private List<File> orderedFiles(String localDir) {
        File dir = new File(localDir);
        File[] all = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".dat.gz") || name.endsWith(".ctrl");
            }
        });
        if (all == null || all.length == 0) {
            logger.error("[GAI][SFTP] No .dat.gz or .ctrl files found in: {}", localDir);
            throw new IllegalStateException("No GAI files found to transfer in: " + localDir);
        }
        List<File> files = new ArrayList<File>(Arrays.asList(all));
        files.sort(new Comparator<File>() {
            public int compare(File a, File b) {
                return Integer.compare(order(a.getName()), order(b.getName()));
            }
        });
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
            logger.error("[GAI][SFTP] Required property is blank: {}", propertyName);
            throw new IllegalStateException("Required SFTP property is blank: " + propertyName);
        }
    }
}
