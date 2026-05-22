package com.citi.risk.scef.limitexposure.gai.service;

import org.apache.commons.configuration.Configuration;

import javax.inject.Inject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Generates file names following the GAI naming convention:
 *
 * Pattern:
 *   {CSI}_{ORG}_{FEED_ID}_N_{FILE_TYPE}_{FREQ}_F_SRC_{COB_DATE}_{TIMESTAMP}.dat.gz
 *
 * Example:
 *   161534_CRC_SCEF-STRESSEXP_N_EVENT_DLY_F_SRC_20260522_20260522143000.dat.gz
 *
 * Control file (.ctrl):
 *   161534_CRC_SCEF-STRESSEXP_N_CONTROL_DLY_F_SRC_20260522_20260522143000.ctrl
 */
public class GAIFeedFileNamingService {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Configuration cfg;

    @Inject
    public GAIFeedFileNamingService(Configuration configuration) {
        this.cfg = configuration;
    }

    /**
     * @param feedId   GAI feed ID e.g. "SCEF-STRESSEXP"
     * @param fileType "EVENT", "RECORD", "ATTRIBUTE", "CONTROL"
     * @param freq     "DLY" or "MNTH"
     * @param cobDate  "20260522"
     * @param ext      ".dat.gz" or ".ctrl"
     */
    public String generate(String feedId, String fileType,
                            String freq, String cobDate, String ext) {
        String csi = cfg.getString("SCEF.gai.feed.csi", "161534");
        String org = cfg.getString("SCEF.gai.feed.org", "CRC");
        String ts  = LocalDateTime.now().format(TS_FMT);

        return String.format("%s_%s_%s_N_%s_%s_F_SRC_%s_%s%s",
                             csi, org, feedId, fileType,
                             freq, cobDate, ts, ext);
    }

    /** Convenience for data files */
    public String dataFile(String feedId, String fileType,
                            String freq, String cobDate) {
        return generate(feedId, fileType, freq, cobDate, ".dat.gz");
    }

    /** Convenience for control file */
    public String ctrlFile(String feedId, String freq, String cobDate) {
        return generate(feedId, "CONTROL", freq, cobDate, ".ctrl");
    }
}
