package com.citi.risk.scef.limitexposure.gai.service;

import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
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
 * Logging:
 *   - DEBUG: generated filename (avoids flooding INFO with every file name —
 *            GAIFileWriterService already logs at INFO level)
 *   - WARN:  csi or org resolved to fallback default
 */
public class GAIFeedFileNamingService {

    private static final Logger logger = LoggerFactory.getLogger(GAIFeedFileNamingService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Configuration cfg;

    @Inject
    public GAIFeedFileNamingService(Configuration cfg) { this.cfg = cfg; }

    public String dataFile(String csi, String org, String feedId,
                            String fileType, String freq, String cobDate) {
        return generate(csi, org, feedId, fileType, freq, cobDate, ".dat.gz");
    }

    public String ctrlFile(String csi, String org, String feedId,
                            String freq, String cobDate) {
        return generate(csi, org, feedId, "CONTROL", freq, cobDate, ".ctrl");
    }

    public String generate(String csi, String org, String feedId,
                            String fileType, String freq, String cobDate, String ext) {

        String resolvedCsi = firstNonBlank(csi, cfg.getString("SCEF.gai.feed.csi", "161534"));
        String resolvedOrg = firstNonBlank(org, cfg.getString("SCEF.gai.feed.org", "CRC"));

        if (csi == null || csi.trim().length() == 0) {
            logger.warn("[GAI][NAMING] csi not set in FeedDefinition — using default: {}", resolvedCsi);
        }
        if (org == null || org.trim().length() == 0) {
            logger.warn("[GAI][NAMING] org not set — using default: {}", resolvedOrg);
        }

        String ts   = LocalDateTime.now().format(TS_FMT);
        String name = String.format("%s_%s_%s_N_%s_%s_F_SRC_%s_%s%s",
                resolvedCsi, resolvedOrg, feedId, fileType, freq, cobDate, ts, ext);

        logger.debug("[GAI][NAMING] Generated filename: {}", name);
        return name;
    }

    private String firstNonBlank(String a, String b) {
        return (a == null || a.trim().length() == 0) ? b : a.trim();
    }
}
