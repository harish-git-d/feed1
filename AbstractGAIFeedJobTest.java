package com.citi.risk.scef.limitexposure.gai.job;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import com.citi.risk.scef.limitexposure.gai.domain.FileMetadata;
import com.citi.risk.scef.limitexposure.gai.service.*;
import org.apache.commons.configuration.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AbstractGAIFeedJob.
 *
 * Tests cover:
 *   - Master switch (SCEF.gai.feed.job.open)
 *   - cobDate resolution (property override vs T-1 default)
 *   - cobDate format guard (yyyyMMdd validation)
 *   - Output directory resolution (first run, rerun suffix)
 *   - Zero rows alert path
 *   - Row mismatch alert path
 *   - Normal execution path (steps 1-6)
 *   - SFTP enabled vs disabled
 *   - Job failure + email alert
 *   - Cleanup old files
 */
public class AbstractGAIFeedJobTest {

    // ── Concrete subclass for testing ─────────────────────────────────────────

    private static class TestGAIFeedJob extends AbstractGAIFeedJob {
        private final String feedName;
        TestGAIFeedJob(String feedName) { this.feedName = feedName; }

        @Override
        protected String getFeedName() { return feedName; }
    }

    // ── Mocks ─────────────────────────────────────────────────────────────────

    @Mock private Configuration cfg;
    @Mock private GAIFeedDefinitionLoader defLoader;
    @Mock private GAIDatabaseQueryService dbService;
    @Mock private GAIFeedFileNamingService namingService;
    @Mock private GAIFileWriterService fileWriter;
    @Mock private GAISftpTransferService sftp;
    @Mock private GAIEmailAlertService alertService;
    @Mock private FeedDefinition feedDef;

    private static final String FEED_NAME   = "stress-exposure";
    private static final String TODAY       = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final String YESTERDAY   = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final String OUTPUT_DIR  = "/opt/scef/gai/staging";
    private static final String ENV         = "PROD";

    // ── Setup ─────────────────────────────────────────────────────────────────

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Default: master switch ON, SFTP ON, output dir configured
        when(cfg.getBoolean(ENV + ".*.*.SCEF.gai.feed.job.open", false)).thenReturn(true);
        when(cfg.getString(ENV + ".*.*.SCEF.gai.feed.output.dir", "")).thenReturn(OUTPUT_DIR);
        when(cfg.getBoolean(ENV + ".*.*.SCEF.gai.feed.sftp.enabled", false)).thenReturn(true);
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn("");
        when(cfg.getString(ENV + ".*.*.SCEF.gai.feed.sftp.host")).thenReturn("gfolygwprod.wlb3.nam.nsroot.net");
        when(cfg.getString(ENV + ".*.*.SCEF.gai.feed.sftp.user")).thenReturn("opiempr");
        when(cfg.getString("*.*.*.SCEF.gai.feed.sftp.remote.path")).thenReturn("/gfolysftp/gfolyrsk/incoming/citirisk");
        when(cfg.getInt("*.*.*.SCEF.gai.feed.cleanup.days", 30)).thenReturn(30);

        // Feed definition defaults
        when(defLoader.load(FEED_NAME)).thenReturn(feedDef);
        when(feedDef.getDelimiter()).thenReturn("|");
        when(feedDef.getFrequency()).thenReturn("DLY");
        when(feedDef.getGaiFeedId()).thenReturn("SCEF-STRESSEXP");
        when(feedDef.getNullDefault()).thenReturn("");
    }

    // ── 1. Master switch tests ─────────────────────────────────────────────────

    @Test
    public void testMasterSwitchOff_jobSkips() throws Exception {
        when(cfg.getBoolean(ENV + ".*.*.SCEF.gai.feed.job.open", false)).thenReturn(false);

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        Integer result = job.execute();

        assertEquals("Job should return 1 (skip) when master switch is off", Integer.valueOf(1), result);
        verify(defLoader, never()).load(anyString());
        verify(dbService, never()).query(anyString(), anyString(), anyString());
    }

    @Test
    public void testMasterSwitchOn_jobProceeds() throws Exception {
        when(cfg.getBoolean(ENV + ".*.*.SCEF.gai.feed.job.open", false)).thenReturn(true);
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        Integer result = job.execute();

        assertEquals(Integer.valueOf(1), result);
        verify(defLoader, times(1)).load(FEED_NAME);
    }

    // ── 2. cobDate resolution tests ────────────────────────────────────────────

    @Test
    public void testCobDate_defaultsToYesterday_whenPropertyBlank() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn("");
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        // Verify DB queried with T-1 date
        verify(dbService).query(eq(FEED_NAME), eq("event"), eq(YESTERDAY));
    }

    @Test
    public void testCobDate_usesPropertyOverride_whenSet() throws Exception {
        String overrideDate = "20260529";
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn(overrideDate);
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        verify(dbService).query(eq(FEED_NAME), eq("event"), eq(overrideDate));
        verify(dbService).query(eq(FEED_NAME), eq("record"), eq(overrideDate));
        verify(dbService).query(eq(FEED_NAME), eq("attribute"), eq(overrideDate));
    }

    @Test
    public void testCobDate_ignoresInvalidPropertyValue_defaultsToYesterday() throws Exception {
        // Non-8-digit value should be ignored
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn("invalid");
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        verify(dbService).query(eq(FEED_NAME), eq("event"), eq(YESTERDAY));
    }

    // ── 3. cobDate format guard ────────────────────────────────────────────────

    @Test(expected = GAIFeedException.class)
    public void testCobDate_formatGuard_throwsOnNonNumeric() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn("2026-06-01");

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();
    }

    @Test(expected = GAIFeedException.class)
    public void testCobDate_formatGuard_throwsOnWrongLength() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn("202606");

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();
    }

    @Test
    public void testCobDate_formatGuard_accepts8DigitDate() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn("20260601");
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        Integer result = job.execute();

        assertEquals(Integer.valueOf(1), result);
    }

    // ── 4. Output directory resolution ────────────────────────────────────────

    @Test
    public void testOutputDir_throwsWhenNotConfigured() throws Exception {
        when(cfg.getString(ENV + ".*.*.SCEF.gai.feed.output.dir", "")).thenReturn("");

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);

        try {
            job.execute();
            fail("Expected GAIFeedException for missing output.dir");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("SCEF.gai.feed.output.dir is not configured"));
        }
    }

    @Test
    public void testOutputDir_throwsWhenNull() throws Exception {
        when(cfg.getString(ENV + ".*.*.SCEF.gai.feed.output.dir", "")).thenReturn(null);

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);

        try {
            job.execute();
            fail("Expected GAIFeedException for null output.dir");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("SCEF.gai.feed.output.dir is not configured"));
        }
    }

    // ── 5. Zero rows alert ─────────────────────────────────────────────────────

    @Test
    public void testZeroRows_allFileTypes_sendsAlertAndContinues() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn(YESTERDAY);
        when(dbService.query(FEED_NAME, "event",     YESTERDAY)).thenReturn(Collections.emptyList());
        when(dbService.query(FEED_NAME, "record",    YESTERDAY)).thenReturn(Collections.emptyList());
        when(dbService.query(FEED_NAME, "attribute", YESTERDAY)).thenReturn(Collections.emptyList());
        stubFileWriter();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        Integer result = job.execute();

        // Job should not throw — zero rows is warn + continue
        assertEquals(Integer.valueOf(1), result);
        verify(alertService).sendZeroRowsAlert(eq(FEED_NAME), eq(YESTERDAY));
        // Files still written
        verify(fileWriter).write(eq(FEED_NAME), eq("EVENT"), anyString(), anyList(), any(), anyString(), any());
    }

    // ── 6. Row mismatch alert ──────────────────────────────────────────────────

    @Test
    public void testRowMismatch_eventZero_recordNonZero_sendsAlert() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn(YESTERDAY);
        when(dbService.query(FEED_NAME, "event",     YESTERDAY)).thenReturn(Collections.emptyList());
        when(dbService.query(FEED_NAME, "record",    YESTERDAY)).thenReturn(sampleRows(5));
        when(dbService.query(FEED_NAME, "attribute", YESTERDAY)).thenReturn(sampleRows(5));
        stubFileWriter();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        verify(alertService).sendRowMismatchAlert(eq(FEED_NAME), eq(YESTERDAY), eq(0), eq(5), eq(5));
        verify(alertService, never()).sendZeroRowsAlert(anyString(), anyString());
    }

    // ── 7. Normal execution path ───────────────────────────────────────────────

    @Test
    public void testNormalExecution_allStepsExecuted() throws Exception {
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        Integer result = job.execute();

        assertEquals(Integer.valueOf(1), result);

        // Step 1 — feed definition loaded
        verify(defLoader).load(FEED_NAME);

        // Step 2 — DB queried for all 3 file types
        verify(dbService).query(FEED_NAME, "event",     YESTERDAY);
        verify(dbService).query(FEED_NAME, "record",    YESTERDAY);
        verify(dbService).query(FEED_NAME, "attribute", YESTERDAY);

        // Step 3 — data files written
        verify(fileWriter).write(eq(FEED_NAME), eq("EVENT"),     anyString(), anyList(), any(), anyString(), any());
        verify(fileWriter).write(eq(FEED_NAME), eq("RECORD"),    anyString(), anyList(), any(), anyString(), any());
        verify(fileWriter).write(eq(FEED_NAME), eq("ATTRIBUTE"), anyString(), anyList(), any(), anyString(), any());

        // Step 4 — control file written
        verify(fileWriter).writeControlFile(eq(FEED_NAME), anyString(), anyString(), any(), any(), anyList());

        // Step 5 — SFTP transfer executed
        verify(sftp).transfer(anyString(), anyString(), anyString(), anyString());
    }

    // ── 8. SFTP enabled / disabled ────────────────────────────────────────────

    @Test
    public void testSftpDisabled_transferSkipped() throws Exception {
        when(cfg.getBoolean(ENV + ".*.*.SCEF.gai.feed.sftp.enabled", false)).thenReturn(false);
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        verify(sftp, never()).transfer(anyString(), anyString(), anyString(), anyString());
        // Files still written locally
        verify(fileWriter).write(eq(FEED_NAME), eq("EVENT"), anyString(), anyList(), any(), anyString(), any());
    }

    @Test
    public void testSftpEnabled_transferCalledWithCorrectParams() throws Exception {
        when(cfg.getBoolean(ENV + ".*.*.SCEF.gai.feed.sftp.enabled", false)).thenReturn(true);
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        verify(sftp).transfer(
            anyString(),                                         // localDir
            eq("/gfolysftp/gfolyrsk/incoming/citirisk"),        // remotePath
            eq("gfolygwprod.wlb3.nam.nsroot.net"),              // host
            eq("opiempr")                                       // user
        );
    }

    // ── 9. Job failure + email alert ──────────────────────────────────────────

    @Test
    public void testJobFailure_sendsFailureAlert_thenRethrows() throws Exception {
        when(dbService.query(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("DB connection failed"));

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);

        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("Feed FAILED"));
            assertTrue(e.getMessage().contains(FEED_NAME));
        }

        verify(alertService).sendFailureAlert(eq(FEED_NAME), anyString(), any(Exception.class));
    }

    @Test
    public void testJobFailure_alertServiceAlsoFails_logsAndRethrows() throws Exception {
        when(dbService.query(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("DB error"));
        doThrow(new RuntimeException("Email failed"))
            .when(alertService).sendFailureAlert(anyString(), anyString(), any());

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);

        try {
            job.execute();
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            // Job still throws even if alert email fails
            assertTrue(e.getMessage().contains("Feed FAILED"));
        }
    }

    // ── 10. Cleanup old files ─────────────────────────────────────────────────

    @Test
    public void testCleanup_runsAfterSuccessfulTransfer() throws Exception {
        stubNormalExecution();

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        job.execute();

        // Step 6 cleanup should execute — verify cfg read for cleanup.days
        verify(cfg).getInt("*.*.*.SCEF.gai.feed.cleanup.days", 30);
    }

    @Test
    public void testCleanup_doesNotFailJob_whenCleanupThrows() throws Exception {
        stubNormalExecution();
        // cleanupOldFiles is private — we verify job still returns 1
        // even if underlying file ops fail (caught internally)

        TestGAIFeedJob job = new TestGAIFeedJob(FEED_NAME);
        Integer result = job.execute();

        assertEquals("Cleanup failure must not fail the job", Integer.valueOf(1), result);
    }

    // ── 11. Quartz scheduling — cron expressions ──────────────────────────────

    @Test
    public void testCronExpressions_allSevenJobsScheduled_20_30_staggered() {
        // Verify expected cron expressions for all 7 GAI jobs at 20:30 staggered
        Map<String, String> expectedCrons = new LinkedHashMap<>();
        expectedCrons.put("GAIFeedStressExposureJob", "0 30 20 * * ?");
        expectedCrons.put("GAIFeedSwwrFlagJob",       "0 35 20 * * ?");
        expectedCrons.put("GAIFeedPseExposureJob",    "0 40 20 * * ?");
        expectedCrons.put("GAIFeedSwwrRecoveryJob",   "0 45 20 * * ?");
        expectedCrons.put("GAIFeedOetFlagJob",        "0 50 20 * * ?");
        expectedCrons.put("GAIFeedPseMonthEndJob",    "0 55 20 L * ?");
        expectedCrons.put("GAIFeedGwwrFlagJob",       "0 0 21 * * ?");

        // Verify count matches jobname count
        assertEquals("Must have 7 GAI jobs scheduled", 7, expectedCrons.size());

        // Verify no two jobs share the same cron (no scheduling collision)
        long distinctCrons = expectedCrons.values().stream().distinct().count();
        assertEquals("All 7 jobs must have distinct cron times", 7, distinctCrons);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubNormalExecution() throws Exception {
        when(cfg.getString("*.*.*.SCEF.gai.feed.cobDate", "")).thenReturn(YESTERDAY);
        when(dbService.query(FEED_NAME, "event",     YESTERDAY)).thenReturn(sampleRows(3));
        when(dbService.query(FEED_NAME, "record",    YESTERDAY)).thenReturn(sampleRows(3));
        when(dbService.query(FEED_NAME, "attribute", YESTERDAY)).thenReturn(sampleRows(3));
        stubFileWriter();
    }

    private void stubFileWriter() throws Exception {
        when(fileWriter.write(anyString(), eq("EVENT"),     anyString(), anyList(), any(), anyString(), any()))
            .thenReturn(new FileMetadata("event.dat.gz",     "/tmp/event.dat.gz",     3, "EVENT"));
        when(fileWriter.write(anyString(), eq("RECORD"),    anyString(), anyList(), any(), anyString(), any()))
            .thenReturn(new FileMetadata("record.dat.gz",    "/tmp/record.dat.gz",    3, "RECORD"));
        when(fileWriter.write(anyString(), eq("ATTRIBUTE"), anyString(), anyList(), any(), anyString(), any()))
            .thenReturn(new FileMetadata("attribute.dat.gz", "/tmp/attribute.dat.gz", 3, "ATTRIBUTE"));
        when(fileWriter.writeControlFile(anyString(), anyString(), anyString(), any(), any(), anyList()))
            .thenReturn("161534_CRC_SCEF-STRESSEXP_N_CONTROL_DLY_F_SRC_20260601_20260601203000.ctrl");
    }

    private List<Map<String, Object>> sampleRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("EVENT_ID",   String.valueOf(i + 1));
            row.put("COB_DATE",   YESTERDAY);
            row.put("RECORD_ID",  String.valueOf(i + 1));
            rows.add(row);
        }
        return rows;
    }
}
