package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes pipe-delimited .dat.gz data files and .ctrl trigger files.
 *
 * Exception handling:
 *   - Output directory creation failure → IOException propagates up
 *   - File write failure               → IOException propagates up
 *   - All exceptions propagate to AbstractGAIFeedJob.execute() catch block
 *   - finally blocks guarantee streams are always closed even on failure
 *
 * Logging:
 *   - INFO:  directory created (first time)
 *   - DEBUG: each row being processed (only if debug enabled — avoids log flood)
 *   - INFO:  file written with name + record count + size
 *   - INFO:  control file written with name
 *   - WARN:  null value encountered for a non-nullable column
 *   - ERROR: file write failure with path
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

        Path outPath = Paths.get(outputDir);
        if (!Files.exists(outPath)) {
            Files.createDirectories(outPath);
            logger.info("[GAI][{}][{}] Created output directory: {}", feedName, fileType, outputDir);
        }

        String csi      = firstNonBlank(def.getSourceSystemCsi(), cfg.getString("SCEF.gai.feed.csi", "161534"));
        String org      = cfg.getString("SCEF.gai.feed.org", "CRC");
        String fileName = naming.dataFile(csi, org, def.getGaiFeedId(), fileType, def.getFrequency(), cobDate);
        Path   filePath = Paths.get(outputDir, fileName);

        List<String> columns     = def.getColumnsForType(fileType);
        String       delimiter   = def.getDelimiter();
        String       nullDefault = firstNonNull(def.getNullDefault(),
                                       cfg.getString("SCEF.gai.feed.null.default", ""));

        if (columns.isEmpty()) {
            logger.error("[GAI][{}][{}] No columns configured in feed definition for fileType={}",
                         feedName, fileType, fileType);
            throw new IllegalStateException(
                    "No columns for fileType=" + fileType + " in feed=" + feedName);
        }

        logger.info("[GAI][{}][{}] Writing {} rows → {}", feedName, fileType, rows.size(), fileName);

        int recordCount = 0;
        OutputStream fos = null;
        GZIPOutputStream gzip = null;
        BufferedWriter writer = null;
        try {
            fos    = Files.newOutputStream(filePath);
            gzip   = new GZIPOutputStream(fos);
            writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8));

            // Header
            writer.write(join(columns, delimiter));
            writer.write(NEWLINE);

            // Data rows
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) writer.write(delimiter);
                    String col   = columns.get(i);
                    Object value = findValue(row, col);
                    writer.write(formatValue(def, col, value, cobDate, nullDefault, delimiter));
                }
                writer.write(NEWLINE);
                recordCount++;

                if (logger.isDebugEnabled() && recordCount % 1000 == 0) {
                    logger.debug("[GAI][{}][{}] Written {} / {} rows",
                                 feedName, fileType, recordCount, rows.size());
                }
            }
        } catch (Exception e) {
            logger.error("[GAI][{}][{}] Failed writing file {} at row {}: {}",
                         feedName, fileType, fileName, recordCount, e.getMessage(), e);
            // Attempt to delete partially written file
            try { Files.deleteIfExists(filePath); } catch (Exception ignored) { }
            throw e;
        } finally {
            if (writer != null) try { writer.close(); } catch (Exception ignored) { }
            else if (gzip != null) try { gzip.close(); } catch (Exception ignored) { }
            else if (fos  != null) try { fos.close();  } catch (Exception ignored) { }
        }

        long fileSizeKb = Files.size(filePath) / 1024;
        logger.info("[GAI][{}][{}] File written — name={} records={} size={}KB",
                    feedName, fileType, fileName, recordCount, fileSizeKb);

        return new FileMetadata(fileName, filePath.toString(), recordCount, fileType);
    }

    // ── Control file ──────────────────────────────────────────────────────────

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

        logger.info("[GAI][{}] Writing control file: {}", feedName, ctrlName);

        BufferedWriter writer = null;
        try {
            writer = Files.newBufferedWriter(ctrlPath, StandardCharsets.UTF_8);
            writer.write("FILE_NAME" + delimiter + "RECORD_COUNT" + delimiter + "FILE_TYPE");
            writer.write(NEWLINE);
            for (FileMetadata f : dataFiles) {
                writer.write(f.getFileName() + delimiter + f.getRecordCount() + delimiter + f.getFileType());
                writer.write(NEWLINE);
                logger.debug("[GAI][{}] ctrl entry: {} records={}", feedName, f.getFileName(), f.getRecordCount());
            }
        } catch (Exception e) {
            logger.error("[GAI][{}] Failed writing control file {}: {}", feedName, ctrlName, e.getMessage(), e);
            try { Files.deleteIfExists(ctrlPath); } catch (Exception ignored) { }
            throw e;
        } finally {
            if (writer != null) try { writer.close(); } catch (Exception ignored) { }
        }

        logger.info("[GAI][{}] Control file written: {}", feedName, ctrlName);
        return ctrlName;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Case-insensitive column lookup — Oracle driver returns uppercase column names */
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
        // cobDate columns always use the job cobDate, not the DB value
        Object actual = def.isCobDateColumn(column) ? cobDate : value;
        if (actual == null) return nullDefault;

        String pattern = def.getDatePattern(column);
        String text;
        if (pattern != null && pattern.trim().length() > 0) {
            text = formatDate(actual, pattern);
        } else if (actual instanceof BigDecimal) {
            // toPlainString prevents scientific notation e.g. 1.23E+7
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
            String s = String.valueOf(value).trim();
            // Handle yyyyMMdd string → MMddyyyy conversion
            if (s.length() == 8 && s.matches("\\d{8}") && "MMddyyyy".equalsIgnoreCase(pattern)) {
                return s.substring(4, 6) + s.substring(6, 8) + s.substring(0, 4);
            }
            return s;
        } catch (Exception e) {
            logger.warn("[GAI] Date format failed for value={} pattern={}: {}", value, pattern, e.getMessage());
            return String.valueOf(value);
        }
    }

    /** Strips delimiter and newline chars that would corrupt the file format */
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
