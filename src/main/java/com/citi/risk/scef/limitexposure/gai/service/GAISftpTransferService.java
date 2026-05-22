package com.citi.risk.scef.limitexposure.gai.service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SFTP transfer to GAI gateway using JSch — PEM private key auth only.
 *
 * Fixes applied vs original:
 *   - Configurable retries with exponential backoff (default 2 retries)
 *   - Each file uploaded as {name}.tmp then renamed to final name atomically
 *     so .ctrl can never arrive before the data files it references
 *   - PEM key file existence checked before attempting connection
 *   - All connection params validated before session creation
 *   - Configurable port, timeout, known_hosts, strict host key checking
 *
 * Transfer order enforced: EVENT → RECORD → ATTRIBUTE → .ctrl
 *
 * JSch dependency (add to scef/pom.xml if not present):
 *   <dependency>
 *     <groupId>com.github.mwiede</groupId>
 *     <artifactId>jsch</artifactId>
 *     <version>0.2.17</version>
 *   </dependency>
 */
public class GAISftpTransferService {

    private static final Logger logger = LoggerFactory.getLogger(GAISftpTransferService.class);

    private final Configuration cfg;

    @Inject
    public GAISftpTransferService(Configuration cfg) { this.cfg = cfg; }

    /**
     * Transfers all .dat.gz and .ctrl files in localDir to remotePath.
     * Retries on failure up to SCEF.gai.feed.sftp.retries times.
     */
    public void transfer(String localDir, String remotePath,
                          String host, String user, String pemKeyPath) throws Exception {

        int port      = cfg.getInt("SCEF.gai.feed.sftp.port", 22);
        int timeoutMs = cfg.getInt("SCEF.gai.feed.sftp.timeout.ms", 30000);
        int retries   = cfg.getInt("SCEF.gai.feed.sftp.retries", 2);

        Exception last = null;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            try {
                doTransfer(localDir, remotePath, host, user, pemKeyPath, port, timeoutMs);
                return;
            } catch (Exception e) {
                last = e;
                logger.warn("[GAI][SFTP] attempt {}/{} failed: {}", attempt, retries + 1, e.getMessage());
                if (attempt <= retries) {
                    Thread.sleep(2000L * attempt);
                }
            }
        }
        throw last;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void doTransfer(String localDir, String remotePath,
                             String host, String user, String pemKeyPath,
                             int port, int timeoutMs) throws Exception {

        // Validate all inputs before touching the network
        requireNonBlank(host,       "SCEF.gai.feed.sftp.host");
        requireNonBlank(user,       "SCEF.gai.feed.sftp.user");
        requireNonBlank(remotePath, "SCEF.gai.feed.sftp.remote.path");
        requireNonBlank(pemKeyPath, "SCEF.gai.feed.sftp.key.path");

        File keyFile = new File(pemKeyPath);
        if (!keyFile.exists()) {
            throw new IllegalStateException("PEM key file not found: " + pemKeyPath);
        }

        logger.info("[GAI][SFTP] Connecting to {}@{}:{}", user, host, port);

        JSch jsch = new JSch();
        jsch.addIdentity(pemKeyPath);

        String knownHosts = cfg.getString("SCEF.gai.feed.sftp.known.hosts", "");
        if (knownHosts != null && knownHosts.trim().length() > 0) {
            jsch.setKnownHosts(knownHosts);
        }

        Session session = null;
        ChannelSftp channel = null;
        try {
            session = jsch.getSession(user, host, port);
            boolean strict = cfg.getBoolean("SCEF.gai.feed.sftp.strict.host.key.checking", false);
            session.setConfig("StrictHostKeyChecking", strict ? "yes" : "no");
            session.setTimeout(timeoutMs);
            session.connect(timeoutMs);

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(timeoutMs);
            channel.cd(remotePath);

            List<File> files = orderedFiles(localDir);
            for (File f : files) {
                String tmpName   = f.getName() + ".tmp";
                String finalName = f.getName();
                logger.info("[GAI][SFTP] uploading {} ({} bytes)", finalName, f.length());
                // Upload as .tmp first — rename to final only when complete
                channel.put(f.getAbsolutePath(), tmpName);
                channel.rename(tmpName, finalName);
                logger.info("[GAI][SFTP] uploaded {}", finalName);
            }

            logger.info("[GAI][SFTP] transfer complete — {} files → {}:{}", files.size(), host, remotePath);

        } finally {
            if (channel != null) try { channel.disconnect(); } catch (Exception ignored) { }
            if (session != null) try { session.disconnect(); } catch (Exception ignored) { }
        }
    }

    /**
     * Returns files sorted in mandatory GAI transfer order:
     * EVENT → RECORD → ATTRIBUTE → .ctrl  (.ctrl triggers GAI processing — must be last)
     */
    private List<File> orderedFiles(String localDir) {
        File dir = new File(localDir);
        File[] all = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".dat.gz") || name.endsWith(".ctrl");
            }
        });
        if (all == null || all.length == 0) {
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
            throw new IllegalStateException("Required SFTP property is blank: " + propertyName);
        }
    }
}
