package com.citi.risk.scef.limitexposure.gai.service;

import org.apache.commons.configuration.Configuration;

import javax.inject.Inject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Generates file names following the GAI naming convention.
 *
 * Pattern:
 *   {CSI}_{ORG}_{FEED_ID}_N_{FILE_TYPE}_{FREQ}_F_SRC_{COB_DATE}_{TIMESTAMP}.dat.gz
 *
 * Example:
 *   161534_CRC_SCEF-STRESSEXP_N_EVENT_DLY_F_SRC_20260522_20260522143000.dat.gz
 *
 * Control file:
 *   161534_CRC_SCEF-STRESSEXP_N_CONTROL_DLY_F_SRC_20260522_20260522143000.ctrl
 *
 * csi and org are taken from FeedDefinition first, then fall back to
 * SCEF.gai.feed.csi / SCEF.gai.feed.org properties.
 */
public class GAIFeedFileNamingService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Configuration cfg;

    @Inject
    public GAIFeedFileNamingService(Configuration cfg) { this.cfg = cfg; }

    /**
     * Generates the filename for a data file.
     *
     * @param csi      source system CSI e.g. "161534" (from FeedDefinition or properties)
     * @param org      organisation code e.g. "CRC"
     * @param feedId   GAI feed ID e.g. "SCEF-STRESSEXP"
     * @param fileType "EVENT", "RECORD", or "ATTRIBUTE"
     * @param freq     "DLY" or "MNTH"
     * @param cobDate  "20260522"
     */
    public String dataFile(String csi, String org, String feedId,
                            String fileType, String freq, String cobDate) {
        return generate(csi, org, feedId, fileType, freq, cobDate, ".dat.gz");
    }

    /**
     * Generates the filename for the .ctrl trigger file.
     */
    public String ctrlFile(String csi, String org, String feedId,
                            String freq, String cobDate) {
        return generate(csi, org, feedId, "CONTROL", freq, cobDate, ".ctrl");
    }

    public String generate(String csi, String org, String feedId,
                            String fileType, String freq, String cobDate, String ext) {
        String resolvedCsi = firstNonBlank(csi, cfg.getString("SCEF.gai.feed.csi", "161534"));
        String resolvedOrg = firstNonBlank(org, cfg.getString("SCEF.gai.feed.org", "CRC"));
        String ts = LocalDateTime.now().format(TS_FMT);
        return String.format("%s_%s_%s_N_%s_%s_F_SRC_%s_%s%s",
                resolvedCsi, resolvedOrg, feedId, fileType, freq, cobDate, ts, ext);
    }

    private String firstNonBlank(String a, String b) {
        return (a == null || a.trim().length() == 0) ? b : a.trim();
    }
}
