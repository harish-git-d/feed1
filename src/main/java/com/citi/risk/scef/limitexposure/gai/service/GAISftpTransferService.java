package com.citi.risk.scef.limitexposure.gai.service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SFTP transfer to GAI gateway using JSch (PEM private key auth only —
 * no passwords, matching the GAI gateway requirement).
 *
 * JSch is already a dependency in scef-war via existing SFTP usage.
 * If not present, add to scef/pom.xml:
 *   <dependency>
 *     <groupId>com.github.mwiede</groupId>
 *     <artifactId>jsch</artifactId>
 *     <version>0.2.17</version>
 *   </dependency>
 */
public class GAISftpTransferService {

    private static final Logger logger =
            LoggerFactory.getLogger(GAISftpTransferService.class);

    private static final int TIMEOUT_MS = 30_000;

    @Inject
    public GAISftpTransferService() {}

    /**
     * Transfers all files in localDir to remotePath on the SFTP gateway.
     * Transfer order: EVENT → RECORD → ATTRIBUTE → .ctrl
     * The .ctrl file must be last (it triggers GAI processing).
     *
     * @param localDir   directory containing the generated files
     * @param remotePath e.g. /gfolysftp/gfolyrsk/incoming/citirisk
     * @param host       e.g. gfolygwdev.wlb3.nam.nsroot.net
     * @param user       e.g. opiemdv
     * @param pemKeyPath path to PEM private key file
     */
    public void transfer(String localDir, String remotePath,
                          String host, String user,
                          String pemKeyPath) throws Exception {

        logger.info("[GAI][SFTP] Connecting to {}@{}", user, host);

        JSch jsch = new JSch();
        jsch.addIdentity(pemKeyPath);

        Session session = jsch.getSession(user, host, 22);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setTimeout(TIMEOUT_MS);
        session.connect();

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(TIMEOUT_MS);

        try {
            channel.cd(remotePath);

            // Get files in correct transfer order
            List<File> files = orderedFiles(localDir);

            for (File f : files) {
                logger.info("[GAI][SFTP] Uploading {} ({} bytes)",
                            f.getName(), f.length());
                channel.put(f.getAbsolutePath(), f.getName());
                logger.info("[GAI][SFTP] Uploaded {}", f.getName());
            }

            logger.info("[GAI][SFTP] All {} files transferred", files.size());

        } finally {
            channel.disconnect();
            session.disconnect();
        }
    }

    /**
     * Returns files in mandatory GAI transfer order:
     * EVENT → RECORD → ATTRIBUTE → .ctrl (trigger last)
     */
    private List<File> orderedFiles(String localDir) throws Exception {
        File dir = new File(localDir);
        File[] all = dir.listFiles(f -> f.isFile()
                && (f.getName().endsWith(".dat.gz")
                    || f.getName().endsWith(".ctrl")));

        if (all == null || all.length == 0) {
            throw new IllegalStateException(
                "No files to transfer in: " + localDir);
        }

        return Arrays.stream(all)
                .sorted((a, b) -> {
                    int orderA = transferOrder(a.getName());
                    int orderB = transferOrder(b.getName());
                    return Integer.compare(orderA, orderB);
                })
                .collect(Collectors.toList());
    }

    private int transferOrder(String fileName) {
        if (fileName.contains("_EVENT_"))     return 1;
        if (fileName.contains("_RECORD_"))    return 2;
        if (fileName.contains("_ATTRIBUTE_")) return 3;
        if (fileName.endsWith(".ctrl"))       return 4;
        return 5;
    }
}
