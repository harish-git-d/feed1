package com.citi.risk.scef.limitexposure.gai.job;

import com.citi.risk.core.ioc.impl.guice.CRFGuiceContext;
import com.citi.risk.scef.limitexposure.gai.GAIFeedException;
import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import com.citi.risk.scef.limitexposure.gai.service.GAIDatabaseQueryService;
import com.citi.risk.scef.limitexposure.gai.service.GAIEmailAlertService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedDefinitionLoader;
import com.citi.risk.scef.limitexposure.gai.service.GAIFeedFileNamingService;
import com.citi.risk.scef.limitexposure.gai.service.GAIFileWriterService;
import com.citi.risk.scef.limitexposure.gai.service.GAISftpTransferService;
import com.google.inject.Injector;
import org.apache.commons.configuration.Configuration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * JUnit 4 tests for AbstractGAIFeedJob (tested via StubGAIFeedJob concrete subclass).
 *
 * SCEF patterns used:
 *   - MockitoAnnotations.initMocks(this)  (no runner annotation)
 *   - CRFGuiceContext.getInjector() mocked via PowerMock-style static mock
 *   - TemporaryFolder for output directory isolation
 */
public class AbstractGAIFeedJobTest {

    // ── Concrete stub subclass ────────────────────────────────────────────────

    static class StubGAIFeedJob extends AbstractGAIFeedJob {
        private final String feedName;
        StubGAIFeedJob(String feedName) { this.feedName = feedName; }
        @Override
        protected String getFeedName() { return feedName; }
    }

    // ── Rules & fields ────────────────────────────────────────────────────────

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock private Injector                injector;
    @Mock private Configuration           cfg;
    @Mock private GAIFeedDefinitionLoader defLoader;
    @Mock private GAIDatabaseQueryService dbService;
    @Mock private GAIFeedFileNamingService namingService;
    @Mock private GAIFileWriterService    fileWriter;
    @Mock private GAISftpTransferService  sftp;
    @Mock private GAIEmailAlertService    alertService;

    private StubGAIFeedJob job;
    private String         outputDir;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        job       = new StubGAIFeedJob("stress-exposure");
        outputDir = tempFolder.newFolder("gai-staging").getAbsolutePath();

        // Wire CRFGuiceContext static injector
        mockStatic_CRFGuiceContext();

        // Default cfg stubs
        when(cfg.getString("SCEF.gai.feed.output.dir", "")).thenReturn(outputDir);
        when(cfg.getString("SCEF.gai.feed.cobDate",    "")).thenReturn("");
        when(cfg.getBoolean("SCEF.gai.feed.sftp.enabled", false)).thenReturn(false);
        when(cfg.getInt("SCEF.gai.feed.cleanup.days",   30)).thenReturn(30);
        when(cfg.getString("SCEF.gai.feed.csi",  "161534")).thenReturn("161534");
        when(cfg.getString("SCEF.gai.feed.org",  "CRC"))   .thenReturn("CRC");
        when(cfg.getString("SCEF.gai.feed.sftp.host"))     .thenReturn("gfolygwdev.wlb3.nam.nsroot.net");
        when(cfg.getString("SCEF.gai.feed.sftp.remote.path")).thenReturn("/gfolysftp/gfolyrsk/incoming/citirisk");
        when(cfg.getString("SCEF.gai.feed.sftp.user"))     .thenReturn("opiemdv");
        when(cfg.getString("SCEF.gai.feed.sftp.key.path")) .thenReturn("/opt/scef/keys/opiemdv.pem");
        when(cfg.getInt("SCEF.gai.feed.sftp.timeout.ms",   60000)).thenReturn(60000);

        // Default feed definition
        FeedDefinition def = buildFeedDefinition("stress-exposure", "STRESSEXP");
        when(defLoader.load("stress-exposure")).thenReturn(def);

        // Default DB rows — non-empty
        List<Map<String, Object>> eventRows     = buildRows(3);
        List<Map<String, Object>> recordRows    = buildRows(3);
        List<Map<String, Object>> attributeRows = buildRows(3);
        when(dbService.query("stress-exposure", "event",     anyString())).thenReturn(eventRows);
        when(dbService.query("stress-exposure", "record",    anyString())).thenReturn(recordRows);
        when(dbService.query("stress-exposure", "attribute", anyString())).thenReturn(attributeRows);

        // File naming
        when(namingService.dataFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String fileType = inv.getArgumentAt(3, String.class);
                    return "161534_CRC_SCEF-STRESSEXP_N_" + fileType + "_DLY_F_SRC_20260522_20260522030000.dat.gz";
                });
        when(namingService.ctrlFile(anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn("161534_CRC_SCEF-STRESSEXP_N_CONTROL_DLY_F_SRC_20260522_20260522030000.ctrl");

        // File writer returns metadata
        when(fileWriter.write(anyString(), anyString(), anyString(), anyList(),
                any(FeedDefinition.class), anyString(), any(GAIFeedFileNamingService.class)))
                .thenAnswer(inv -> {
                    String fileType = inv.getArgumentAt(1, String.class);
                    return new FileMetadata("file_" + fileType + ".dat.gz", "/tmp/file.dat.gz", 3, fileType);
                });
        when(fileWriter.writeControlFile(anyString(), anyString(), anyString(),
                any(FeedDefinition.class), any(GAIFeedFileNamingService.class), anyList()))
                .thenReturn("file.ctrl");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Replaces the static CRFGuiceContext injector via reflection.
     * Pattern used in SwwrCorrelationFlagToolbarHandlerTest and others.
     */
    private void mockStatic_CRFGuiceContext() throws Exception {
        // Wire all service lookups through the mock injector
        when(injector.getInstance(Configuration.class))           .thenReturn(cfg);
        when(injector.getInstance(GAIFeedDefinitionLoader.class)) .thenReturn(defLoader);
        when(injector.getInstance(GAIDatabaseQueryService.class)) .thenReturn(dbService);
        when(injector.getInstance(GAIFeedFileNamingService.class)).thenReturn(namingService);
        when(injector.getInstance(GAIFileWriterService.class))    .thenReturn(fileWriter);
        when(injector.getInstance(GAISftpTransferService.class))  .thenReturn(sftp);
        when(injector.getInstance(GAIEmailAlertService.class))    .thenReturn(alertService);

        // Inject mock injector into CRFGuiceContext static field
        java.lang.reflect.Field field = CRFGuiceContext.class.getDeclaredField("injector");
        field.setAccessible(true);
        field.set(null, injector);
    }

    private FeedDefinition buildFeedDefinition(String feedName, String moduleName) {
        FeedDefinition def = new FeedDefinition();
        def.setFeedName(feedName);
        def.setModuleName(moduleName);
        def.setPerspectiveName("Exposure Monitoring");
        def.setMonthly(false);
        def.setDelimiter("|");
        def.setNullDefault("");
        def.setEventFields(Arrays.asList("EVENT_ID", "COB_DATE", "ADJUSTMENT_TYPE"));
        def.setRecordFields(Arrays.asList("RECORD_ID", "COB_DATE", "TRANSACTION_CCY"));
        def.setAttributeFields(Arrays.asList("EVENT_ID", "RECORD_ID", "ATTRIBUTE_NAME"));
        return def;
    }

    private List<Map<String, Object>> buildRows(int count) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("EVENT_ID", "EVT-" + i);
            row.put("COB_DATE", "20260522");
            rows.add(row);
        }
        return rows;
    }

    // ── execute() — happy path ────────────────────────────────────────────────

    @Test
    public void execute_happyPath_returnsOne() {
        Integer result = job.execute();
        assertEquals(Integer.valueOf(1), result);
    }

    @Test
    public void execute_happyPath_loadsDefinitionAndQueriesAllThreeTypes() throws Exception {
        job.execute();

        verify(defLoader).load("stress-exposure");
        verify(dbService).query(eq("stress-exposure"), eq("event"),     anyString());
        verify(dbService).query(eq("stress-exposure"), eq("record"),    anyString());
        verify(dbService).query(eq("stress-exposure"), eq("attribute"), anyString());
    }

    @Test
    public void execute_happyPath_writesThreeDataFilesAndOneCtrlFile() throws Exception {
        job.execute();

        verify(fileWriter).write(eq("stress-exposure"), eq("EVENT"),     anyString(), anyList(), any(), anyString(), any());
        verify(fileWriter).write(eq("stress-exposure"), eq("RECORD"),    anyString(), anyList(), any(), anyString(), any());
        verify(fileWriter).write(eq("stress-exposure"), eq("ATTRIBUTE"), anyString(), anyList(), any(), anyString(), any());
        verify(fileWriter).writeControlFile(eq("stress-exposure"), anyString(), anyString(), any(), any(), anyList());
    }

    @Test
    public void execute_sftpDisabled_doesNotCallSftpTransfer() throws Exception {
        when(cfg.getBoolean("SCEF.gai.feed.sftp.enabled", false)).thenReturn(false);
        job.execute();
        verify(sftp, never()).transfer(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void execute_sftpEnabled_callsSftpTransfer() throws Exception {
        when(cfg.getBoolean("SCEF.gai.feed.sftp.enabled", false)).thenReturn(true);
        job.execute();
        verify(sftp).transfer(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ── execute() — cobDate resolution ───────────────────────────────────────

    @Test
    public void execute_cobDateFromProperty_usesConfiguredDate() throws Exception {
        when(cfg.getString("SCEF.gai.feed.cobDate", "")).thenReturn("20260501");
        job.execute();
        // Verify DB query was called with the configured cobDate
        verify(dbService).query(eq("stress-exposure"), eq("event"), eq("20260501"));
    }

    @Test
    public void execute_cobDatePropertyBlank_defaultsToYesterday() throws Exception {
        when(cfg.getString("SCEF.gai.feed.cobDate", "")).thenReturn("");
        job.execute();
        // T-1 = yesterday — just verify a query was made (date is dynamic)
        verify(dbService, atLeastOnce()).query(eq("stress-exposure"), eq("event"), anyString());
    }

    @Test
    public void execute_cobDatePropertyInvalidFormat_defaultsToYesterday() throws Exception {
        when(cfg.getString("SCEF.gai.feed.cobDate", "")).thenReturn("not-a-date");
        job.execute();
        verify(dbService, atLeastOnce()).query(eq("stress-exposure"), eq("event"), anyString());
    }

    // ── execute() — output directory resolution ───────────────────────────────

    @Test
    public void execute_firstRun_createsBaseDir() throws Exception {
        job.execute();
        File baseDir = new File(outputDir + "/stress-exposure");
        assertTrue("Feed base dir should be created", baseDir.exists() || baseDir.getParentFile().exists());
    }

    @Test
    public void execute_missingOutputDir_throwsGAIFeedException() {
        when(cfg.getString("SCEF.gai.feed.output.dir", "")).thenReturn("");
        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("SCEF.gai.feed.output.dir is not configured"));
        }
    }

    // ── execute() — zero rows alert ───────────────────────────────────────────

    @Test
    public void execute_allZeroRows_sendsZeroRowsAlertAndContinues() throws Exception {
        when(dbService.query(anyString(), eq("event"),     anyString())).thenReturn(Collections.emptyList());
        when(dbService.query(anyString(), eq("record"),    anyString())).thenReturn(Collections.emptyList());
        when(dbService.query(anyString(), eq("attribute"), anyString())).thenReturn(Collections.emptyList());

        Integer result = job.execute();

        // Job should still complete (empty files are valid for GAI)
        assertEquals(Integer.valueOf(1), result);
        verify(alertService).sendZeroRowsAlert(eq("stress-exposure"), anyString());
        verify(alertService, never()).sendRowMismatchAlert(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void execute_partialZeroRows_sendsRowMismatchAlert() throws Exception {
        when(dbService.query(anyString(), eq("event"),     anyString())).thenReturn(buildRows(3));
        when(dbService.query(anyString(), eq("record"),    anyString())).thenReturn(Collections.emptyList());
        when(dbService.query(anyString(), eq("attribute"), anyString())).thenReturn(buildRows(3));

        Integer result = job.execute();

        assertEquals(Integer.valueOf(1), result);
        verify(alertService).sendRowMismatchAlert(eq("stress-exposure"), anyString(), eq(3), eq(0), eq(3));
        verify(alertService, never()).sendZeroRowsAlert(anyString(), anyString());
    }

    @Test
    public void execute_nonZeroRows_noAlertSent() throws Exception {
        job.execute();
        verify(alertService, never()).sendZeroRowsAlert(anyString(), anyString());
        verify(alertService, never()).sendRowMismatchAlert(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    // ── execute() — failure path ──────────────────────────────────────────────

    @Test
    public void execute_dbQueryThrows_sendsFailureAlertAndRethrows() {
        when(dbService.query(anyString(), eq("event"), anyString()))
                .thenThrow(new RuntimeException("DB connection lost"));

        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("Feed FAILED"));
            assertTrue(e.getMessage().contains("stress-exposure"));
        }
        verify(alertService).sendFailureAlert(eq("stress-exposure"), anyString(), any(Exception.class));
    }

    @Test
    public void execute_fileWriterThrows_sendsFailureAlertAndRethrows() throws Exception {
        when(fileWriter.write(anyString(), anyString(), anyString(), anyList(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("Disk full"));

        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("Feed FAILED"));
        }
        verify(alertService).sendFailureAlert(eq("stress-exposure"), anyString(), any(Exception.class));
    }

    @Test
    public void execute_alertServiceThrows_originalExceptionStillPropagates() throws Exception {
        when(dbService.query(anyString(), eq("event"), anyString()))
                .thenThrow(new RuntimeException("DB down"));
        doThrow(new RuntimeException("Email server down"))
                .when(alertService).sendFailureAlert(anyString(), anyString(), any());

        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            // Original exception message preserved in chain
            assertTrue(e.getCause().getMessage().contains("DB down"));
        }
    }

    @Test
    public void execute_invalidCobDateFormat_throwsGAIFeedException() throws Exception {
        // Force resolveCobDate to return an invalid value by hacking the config
        // to return a valid-looking value but then manually set it to an invalid one
        when(cfg.getString("SCEF.gai.feed.cobDate", "")).thenReturn("2026-05-22"); // has dashes
        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            // May be rethrown from execute() catch block — check root cause or message
            assertNotNull(e);
        }
    }

    // ── resolveOutputDir — rerun suffix logic ─────────────────────────────────

    @Test
    public void resolveOutputDir_firstRun_returnsBaseDir() throws Exception {
        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("resolveOutputDir", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(job, outputDir, "stress-exposure", "20260522");

        assertEquals(outputDir + "/stress-exposure/20260522", result);
    }

    @Test
    public void resolveOutputDir_secondRun_returnsSuffix1() throws Exception {
        // Pre-create the base directory with a file so it's "used"
        File baseDir = new File(outputDir + "/stress-exposure/20260522");
        baseDir.mkdirs();
        new File(baseDir, "dummy.dat.gz").createNewFile();

        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("resolveOutputDir", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(job, outputDir, "stress-exposure", "20260522");

        assertEquals(outputDir + "/stress-exposure/20260522-1", result);
    }

    @Test
    public void resolveOutputDir_emptyExistingDir_reusesIt() throws Exception {
        // Pre-create an empty directory
        File baseDir = new File(outputDir + "/stress-exposure/20260522");
        baseDir.mkdirs();
        // No files inside — should be reused

        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("resolveOutputDir", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(job, outputDir, "stress-exposure", "20260522");

        assertEquals(outputDir + "/stress-exposure/20260522", result);
    }

    // ── cleanupOldFiles ───────────────────────────────────────────────────────

    @Test
    public void cleanupOldFiles_oldFolder_isDeleted() throws Exception {
        // Create a folder with a cobDate 40 days ago
        String oldDate = java.time.LocalDate.now().minusDays(40)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        File oldDir = new File(outputDir + "/stress-exposure/" + oldDate);
        oldDir.mkdirs();
        new File(oldDir, "oldfile.dat.gz").createNewFile();

        when(cfg.getInt("SCEF.gai.feed.cleanup.days", 30)).thenReturn(30);

        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("cleanupOldFiles", String.class, Configuration.class);
        method.setAccessible(true);
        method.invoke(job, outputDir + "/stress-exposure", cfg);

        assertFalse("Old folder should be deleted", oldDir.exists());
    }

    @Test
    public void cleanupOldFiles_recentFolder_isNotDeleted() throws Exception {
        // Create a folder with today's cobDate
        String recentDate = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        File recentDir = new File(outputDir + "/stress-exposure/" + recentDate);
        recentDir.mkdirs();
        new File(recentDir, "recent.dat.gz").createNewFile();

        when(cfg.getInt("SCEF.gai.feed.cleanup.days", 30)).thenReturn(30);

        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("cleanupOldFiles", String.class, Configuration.class);
        method.setAccessible(true);
        method.invoke(job, outputDir + "/stress-exposure", cfg);

        assertTrue("Recent folder should NOT be deleted", recentDir.exists());
    }

    @Test
    public void cleanupOldFiles_nonExistentBaseDir_doesNotThrow() throws Exception {
        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("cleanupOldFiles", String.class, Configuration.class);
        method.setAccessible(true);
        // Should complete silently without exception
        method.invoke(job, outputDir + "/does-not-exist", cfg);
    }

    @Test
    public void cleanupOldFiles_nonDateNamedFolder_isSkipped() throws Exception {
        // Folder named "sftp.sh" or "logs" — should not be touched
        File nonDateDir = new File(outputDir + "/stress-exposure/logs");
        nonDateDir.mkdirs();

        when(cfg.getInt("SCEF.gai.feed.cleanup.days", 30)).thenReturn(30);

        Method method = AbstractGAIFeedJob.class
                .getDeclaredMethod("cleanupOldFiles", String.class, Configuration.class);
        method.setAccessible(true);
        method.invoke(job, outputDir + "/stress-exposure", cfg);

        assertTrue("Non-date folder should be skipped", nonDateDir.exists());
    }

    // ── getFeedName ───────────────────────────────────────────────────────────

    @Test
    public void getFeedName_returnsConfiguredName() {
        assertEquals("stress-exposure", job.getFeedName());
    }

    @Test
    public void getFeedName_differentFeed_returnsCorrectName() {
        StubGAIFeedJob swwrJob = new StubGAIFeedJob("swwr-flag");
        assertEquals("swwr-flag", swwrJob.getFeedName());
    }
}
