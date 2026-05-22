package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes pipe-delimited .dat.gz files and .ctrl control files
 * for GAI feed delivery.
 */
public class GAIFileWriterService {

    private static final Logger logger =
            LoggerFactory.getLogger(GAIFileWriterService.class);

    private static final String PIPE = "|";
    private static final String NEWLINE = "\n";

    @Inject
    public GAIFileWriterService() {}

    /**
     * Writes one data file (EVENT, RECORD, or ATTRIBUTE).
     *
     * @return FileMetadata with fileName and recordCount
     */
    public FileMetadata write(String feedName,
                               String fileType,
                               String cobDate,
                               List<Map<String, Object>> rows,
                               FeedDefinition def,
                               String outputDir,
                               GAIFeedFileNamingService naming) throws IOException {

        Files.createDirectories(Paths.get(outputDir));

        String freq     = def.isMonthly() ? "MNTH" : "DLY";
        String feedId   = def.getGaiFeedId();   // e.g. "SCEF-STRESSEXP"
        String fileName = naming.dataFile(feedId, fileType, freq, cobDate);
        Path   filePath = Paths.get(outputDir, fileName);

        List<String> columns = def.getColumnsForType(fileType);

        int recordCount = 0;
        try (OutputStream fos = Files.newOutputStream(filePath);
             GZIPOutputStream gzip = new GZIPOutputStream(fos);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {

            // Header row
            writer.write(String.join(PIPE, columns));
            writer.write(NEWLINE);

            // Data rows
            for (Map<String, Object> row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) sb.append(PIPE);
                    Object val = row.get(columns.get(i));
                    sb.append(val != null ? val.toString().trim() : "NA");
                }
                writer.write(sb.toString());
                writer.write(NEWLINE);
                recordCount++;
            }
        }

        logger.info("[GAI][{}][{}] Wrote {} records → {}",
                    feedName, fileType, recordCount, fileName);

        return new FileMetadata(fileName, filePath.toString(),
                                recordCount, fileType);
    }

    /**
     * Writes the .ctrl trigger file that signals GAI to pick up this batch.
     * Must be written LAST — after all 3 data files.
     *
     * Control file format (pipe-delimited, one row per data file):
     *   FILE_NAME|RECORD_COUNT|FILE_TYPE
     */
    public String writeControlFile(String feedName,
                                    String cobDate,
                                    String outputDir,
                                    GAIFeedFileNamingService naming,
                                    List<FileMetadata> dataFiles) throws IOException {

        Files.createDirectories(Paths.get(outputDir));

        // Infer freq from first file name
        boolean monthly = dataFiles.stream()
                .anyMatch(f -> f.getFileName().contains("_MNTH_"));
        String freq   = monthly ? "MNTH" : "DLY";
        String feedId = naming.generate("", "", "", "", "")  // use first file's feedId
                              .split("_")[2];                  // extract from data file

        // Derive feedId from first data file name
        // e.g. 161534_CRC_SCEF-STRESSEXP_N_EVENT_... → SCEF-STRESSEXP
        String feedIdFromFile = dataFiles.get(0).getFileName().split("_")[2];
        String ctrlName = naming.ctrlFile(feedIdFromFile, freq, cobDate);
        Path   ctrlPath = Paths.get(outputDir, ctrlName);

        try (BufferedWriter writer = Files.newBufferedWriter(
                ctrlPath, StandardCharsets.UTF_8)) {

            writer.write("FILE_NAME|RECORD_COUNT|FILE_TYPE");
            writer.write(NEWLINE);

            for (FileMetadata f : dataFiles) {
                writer.write(f.getFileName());
                writer.write(PIPE);
                writer.write(String.valueOf(f.getRecordCount()));
                writer.write(PIPE);
                writer.write(f.getFileType());
                writer.write(NEWLINE);
            }
        }

        logger.info("[GAI][{}] Control file written → {}", feedName, ctrlName);
        return ctrlName;
    }
}
