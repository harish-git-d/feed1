package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes pipe-delimited (or configured delimiter) .dat.gz data files
 * and .ctrl trigger files for GAI feed delivery.
 *
 * Fixes applied vs original:
 *   - sanitize() strips pipes/newlines/tabs from field values
 *   - configurable nullDefault (from FeedDefinition or SCEF.gai.feed.null.default property)
 *   - date columns formatted via SimpleDateFormat patterns in FeedDefinition
 *   - cobDateColumns filled from the job cobDate rather than the DB value
 *   - BigDecimal rendered with toPlainString() (no scientific notation)
 *   - ctrl file uses FeedDefinition directly (no fragile string parsing)
 *   - output directory sub-foldered by cobDate to prevent run-over-run overwrite
 */
public class GAIFileWriterService {

    private static final Logger logger = LoggerFactory.getLogger(GAIFileWriterService.class);
    private static final String NEWLINE = "\n";

    private final Configuration cfg;

    @Inject
    public GAIFileWriterService(Configuration cfg) { this.cfg = cfg; }

    // ── Data file ─────────────────────────────────────────────────────────────

    public FileMetadata write(String feedName,
                               String fileType,
                               String cobDate,
                               List<Map<String, Object>> rows,
                               FeedDefinition def,
                               String outputDir,
                               GAIFeedFileNamingService naming) throws Exception {

        Files.createDirectories(Paths.get(outputDir));

        String csi       = firstNonBlank(def.getSourceSystemCsi(), cfg.getString("SCEF.gai.feed.csi", "161534"));
        String org       = cfg.getString("SCEF.gai.feed.org", "CRC");
        String fileName  = naming.dataFile(csi, org, def.getGaiFeedId(), fileType, def.getFrequency(), cobDate);
        Path   filePath  = Paths.get(outputDir, fileName);

        List<String> columns    = def.getColumnsForType(fileType);
        String       delimiter  = def.getDelimiter();
        String       nullDefault = firstNonNull(def.getNullDefault(),
                                       cfg.getString("SCEF.gai.feed.null.default", ""));

        int recordCount = 0;
        OutputStream fos = Files.newOutputStream(filePath);
        try {
            GZIPOutputStream gzip = new GZIPOutputStream(fos);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8));
            try {
                // Header row
                writer.write(join(columns, delimiter));
                writer.write(NEWLINE);

                // Data rows
                for (Map<String, Object> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) writer.write(delimiter);
                        String column = columns.get(i);
                        Object value  = findValue(row, column);
                        writer.write(formatValue(def, column, value, cobDate, nullDefault, delimiter));
                    }
                    writer.write(NEWLINE);
                    recordCount++;
                }
            } finally {
                writer.close();
            }
        } finally {
            fos.close();
        }

        logger.info("[GAI][{}][{}] wrote records={} file={}", feedName, fileType, recordCount, fileName);
        return new FileMetadata(fileName, filePath.toString(), recordCount, fileType);
    }

    // ── Control file ──────────────────────────────────────────────────────────

    /**
     * Writes the .ctrl trigger file.
     * Must be called AFTER all 3 data files are complete.
     * Format: FILE_NAME|RECORD_COUNT|FILE_TYPE (one row per data file)
     */
    public String writeControlFile(String feedName,
                                    String cobDate,
                                    String outputDir,
                                    FeedDefinition def,
                                    GAIFeedFileNamingService naming,
                                    List<FileMetadata> dataFiles) throws Exception {

        Files.createDirectories(Paths.get(outputDir));

        String csi       = firstNonBlank(def.getSourceSystemCsi(), cfg.getString("SCEF.gai.feed.csi", "161534"));
        String org       = cfg.getString("SCEF.gai.feed.org", "CRC");
        String ctrlName  = naming.ctrlFile(csi, org, def.getGaiFeedId(), def.getFrequency(), cobDate);
        Path   ctrlPath  = Paths.get(outputDir, ctrlName);
        String delimiter = def.getDelimiter();

        BufferedWriter writer = Files.newBufferedWriter(ctrlPath, StandardCharsets.UTF_8);
        try {
            writer.write("FILE_NAME" + delimiter + "RECORD_COUNT" + delimiter + "FILE_TYPE");
            writer.write(NEWLINE);
            for (FileMetadata f : dataFiles) {
                writer.write(f.getFileName() + delimiter + f.getRecordCount() + delimiter + f.getFileType());
                writer.write(NEWLINE);
            }
        } finally {
            writer.close();
        }

        logger.info("[GAI][{}] wrote control file={}", feedName, ctrlName);
        return ctrlName;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Case-insensitive column lookup (Oracle driver returns uppercase keys) */
    private Object findValue(Map<String, Object> row, String column) {
        if (row == null || column == null) return null;
        if (row.containsKey(column)) return row.get(column);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (column.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    private String formatValue(FeedDefinition def, String column, Object value,
                                String cobDate, String nullDefault, String delimiter) {
        // cobDate columns always use the job cobDate regardless of DB value
        Object actual = def.isCobDateColumn(column) ? cobDate : value;
        if (actual == null) return nullDefault;

        String pattern = def.getDatePattern(column);
        String text;
        if (pattern != null && pattern.trim().length() > 0) {
            text = formatDate(actual, pattern);
        } else if (actual instanceof BigDecimal) {
            text = ((BigDecimal) actual).toPlainString();
        } else {
            text = String.valueOf(actual);
        }
        return sanitize(text, delimiter);
    }

    private String formatDate(Object value, String pattern) {
        try {
            if (value instanceof Timestamp) return new SimpleDateFormat(pattern).format((Timestamp) value);
            if (value instanceof Date)      return new SimpleDateFormat(pattern).format((Date) value);
            // Handle yyyyMMdd string passed as cobDate
            String s = String.valueOf(value).trim();
            if (s.length() == 8 && s.matches("\\d{8}") && pattern.equalsIgnoreCase("MMddyyyy")) {
                return s.substring(4, 6) + s.substring(6, 8) + s.substring(0, 4);
            }
            return s;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** Strips delimiters, newlines, and tabs so field values never break the file format */
    private String sanitize(String value, String delimiter) {
        if (value == null) return "";
        String s = value.trim();
        s = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        if (delimiter != null && delimiter.length() > 0) s = s.replace(delimiter, " ");
        return s;
    }

    private String join(List<String> cols, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(cols.get(i));
        }
        return sb.toString();
    }

    private String firstNonBlank(String a, String b) {
        return (a == null || a.trim().length() == 0) ? b : a.trim();
    }

    private String firstNonNull(String a, String b) { return a == null ? b : a; }
}
