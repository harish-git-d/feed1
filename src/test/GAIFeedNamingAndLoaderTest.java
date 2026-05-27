package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import org.apache.commons.configuration.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * JUnit 4 tests for GAIFeedFileNamingService and GAIFeedDefinitionLoader.
 */
public class GAIFeedNamingAndLoaderTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  GAIFeedFileNamingService
    // ══════════════════════════════════════════════════════════════════════════

    @Mock private Configuration cfg;

    private GAIFeedFileNamingService namingService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(cfg.getString("SCEF.gai.feed.csi", "161534")).thenReturn("161534");
        when(cfg.getString("SCEF.gai.feed.org", "CRC"))   .thenReturn("CRC");
        namingService = new GAIFeedFileNamingService(cfg);
    }

    // ── dataFile naming ───────────────────────────────────────────────────────

    @Test
    public void dataFile_eventType_producesCorrectPattern() {
        String name = namingService.dataFile(
                "161534", "CRC", "SCEF-STRESSEXP", "EVENT", "DLY", "20260522");

        assertTrue("Should start with CSI", name.startsWith("161534_"));
        assertTrue("Should contain ORG",    name.contains("_CRC_"));
        assertTrue("Should contain feedId", name.contains("_SCEF-STRESSEXP_"));
        assertTrue("Should contain N",      name.contains("_N_"));
        assertTrue("Should contain EVENT",  name.contains("_EVENT_"));
        assertTrue("Should contain DLY",    name.contains("_DLY_"));
        assertTrue("Should contain F_SRC",  name.contains("_F_SRC_"));
        assertTrue("Should contain cobDate",name.contains("_20260522_"));
        assertTrue("Should end with .dat.gz", name.endsWith(".dat.gz"));
    }

    @Test
    public void dataFile_recordType_containsRECORD() {
        String name = namingService.dataFile(
                "161534", "CRC", "SCEF-STRESSEXP", "RECORD", "DLY", "20260522");
        assertTrue(name.contains("_RECORD_"));
    }

    @Test
    public void dataFile_attributeType_containsATTRIBUTE() {
        String name = namingService.dataFile(
                "161534", "CRC", "SCEF-STRESSEXP", "ATTRIBUTE", "DLY", "20260522");
        assertTrue(name.contains("_ATTRIBUTE_"));
    }

    @Test
    public void dataFile_monthlyFeed_containsMNTH() {
        String name = namingService.dataFile(
                "161534", "CRC", "SCEF-PSEME", "EVENT", "MNTH", "20260531");
        assertTrue(name.contains("_MNTH_"));
    }

    @Test
    public void ctrlFile_producesCorrectPattern() {
        String name = namingService.ctrlFile(
                "161534", "CRC", "SCEF-STRESSEXP", "DLY", "20260522");

        assertTrue("ctrl file should start with CSI", name.startsWith("161534_"));
        assertTrue("ctrl file should contain CONTROL", name.contains("_CONTROL_"));
        assertTrue("ctrl file should end with .ctrl",  name.endsWith(".ctrl"));
    }

    @Test
    public void dataFile_nullCsi_fallsBackToDefault() {
        String name = namingService.dataFile(
                null, "CRC", "SCEF-STRESSEXP", "EVENT", "DLY", "20260522");
        assertTrue("Should fall back to default CSI 161534", name.startsWith("161534_"));
    }

    @Test
    public void dataFile_blankOrg_fallsBackToDefault() {
        String name = namingService.dataFile(
                "161534", "", "SCEF-STRESSEXP", "EVENT", "DLY", "20260522");
        assertTrue("Should fall back to default ORG CRC", name.contains("_CRC_"));
    }

    @Test
    public void dataFile_timestampIncludedInName() {
        String name = namingService.dataFile(
                "161534", "CRC", "SCEF-STRESSEXP", "EVENT", "DLY", "20260522");
        // Timestamp is 14 digits: yyyyMMddHHmmss — after cobDate
        String afterCobDate = name.substring(name.lastIndexOf("_20260522_") + 10);
        String timestamp = afterCobDate.replace(".dat.gz", "");
        assertTrue("Timestamp should be 14 digits", timestamp.matches("\\d{14}"));
    }

    @Test
    public void ctrlFile_timestampIncludedInName() {
        String name = namingService.ctrlFile(
                "161534", "CRC", "SCEF-STRESSEXP", "DLY", "20260522");
        String afterCobDate = name.substring(name.lastIndexOf("_20260522_") + 10);
        String timestamp = afterCobDate.replace(".ctrl", "");
        assertTrue("Timestamp should be 14 digits", timestamp.matches("\\d{14}"));
    }

    @Test
    public void dataFile_allSixFeedIds_producesValidNames() {
        String[] feedIds = {
            "SCEF-STRESSEXP", "SCEF-SWWR", "SCEF-PSE",
            "SCEF-SWWRRCY",   "SCEF-OET",  "SCEF-PSEME"
        };
        for (String feedId : feedIds) {
            String name = namingService.dataFile("161534", "CRC", feedId, "EVENT", "DLY", "20260522");
            assertNotNull(name);
            assertTrue("Name must contain feedId", name.contains(feedId));
            assertTrue("Name must end in .dat.gz", name.endsWith(".dat.gz"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GAIFeedDefinitionLoader
    // ══════════════════════════════════════════════════════════════════════════

    private GAIFeedDefinitionLoader loader;

    @Before
    public void setUpLoader() {
        loader = new GAIFeedDefinitionLoader();
    }

    @Test
    public void load_stressExposure_loadsSuccessfully() {
        // Requires classpath:gai/feed-definitions/stress-exposure.yml
        FeedDefinition def = loader.load("stress-exposure");

        assertNotNull(def);
        assertEquals("stress-exposure", def.getFeedName());
        assertEquals("STRESSEXP", def.getModuleName());
        assertFalse("stress-exposure is not monthly", def.isMonthly());
        assertEquals("DLY", def.getFrequency());
        assertEquals("|", def.getDelimiter());
    }

    @Test
    public void load_stressExposure_hasAllThreeFieldLists() {
        FeedDefinition def = loader.load("stress-exposure");

        assertFalse("eventFields should not be empty",     def.getColumnsForType("EVENT").isEmpty());
        assertFalse("recordFields should not be empty",    def.getColumnsForType("RECORD").isEmpty());
        assertFalse("attributeFields should not be empty", def.getColumnsForType("ATTRIBUTE").isEmpty());
    }

    @Test
    public void load_pseMonthEnd_isMonthly() {
        FeedDefinition def = loader.load("pse-month-end");
        assertTrue("pse-month-end should be monthly", def.isMonthly());
        assertEquals("MNTH", def.getFrequency());
    }

    @Test
    public void load_allSixFeeds_loadWithoutException() {
        String[] feeds = {
            "stress-exposure", "swwr-flag",  "pse-exposure",
            "swwr-recovery",   "oet-flag",   "pse-month-end"
        };
        for (String feed : feeds) {
            FeedDefinition def = loader.load(feed);
            assertNotNull("FeedDefinition should not be null for: " + feed, def);
            assertEquals(feed, def.getFeedName());
        }
    }

    @Test
    public void load_calledTwice_returnsCachedInstance() {
        FeedDefinition first  = loader.load("stress-exposure");
        FeedDefinition second = loader.load("stress-exposure");
        assertSame("Should return cached instance on second call", first, second);
    }

    @Test
    public void load_unknownFeed_throwsException() {
        try {
            loader.load("nonexistent-feed");
            fail("Expected exception for unknown feed");
        } catch (Exception e) {
            assertNotNull(e);
        }
    }

    @Test
    public void load_stressExposure_gaiFeedIdDerivedFromModuleName() {
        FeedDefinition def = loader.load("stress-exposure");
        assertEquals("SCEF-STRESSEXP", def.getGaiFeedId());
    }

    @Test
    public void load_pseExposure_perspectiveNameIsExposureMonitoring() {
        FeedDefinition def = loader.load("pse-exposure");
        assertEquals("Exposure Monitoring", def.getPerspectiveName());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FeedDefinition — unit tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    public void feedDefinition_getColumnsForType_caseInsensitive() {
        FeedDefinition def = new FeedDefinition();
        def.setEventFields(java.util.Arrays.asList("EVENT_ID", "COB_DATE"));
        def.setRecordFields(java.util.Arrays.asList("RECORD_ID"));
        def.setAttributeFields(java.util.Arrays.asList("ATTR_NAME"));

        assertEquals(2, def.getColumnsForType("event").size());
        assertEquals(2, def.getColumnsForType("EVENT").size());
        assertEquals(2, def.getColumnsForType("Event").size());
        assertEquals(1, def.getColumnsForType("record").size());
        assertEquals(1, def.getColumnsForType("attribute").size());
    }

    @Test
    public void feedDefinition_getColumnsForType_unknownType_returnsEmpty() {
        FeedDefinition def = new FeedDefinition();
        def.setEventFields(java.util.Arrays.asList("EVENT_ID"));
        assertTrue(def.getColumnsForType("UNKNOWN").isEmpty());
        assertTrue(def.getColumnsForType(null).isEmpty());
    }

    @Test
    public void feedDefinition_isCobDateColumn_caseInsensitive() {
        FeedDefinition def = new FeedDefinition();
        def.setCobDateColumns(java.util.Arrays.asList("COB_DATE"));

        assertTrue(def.isCobDateColumn("COB_DATE"));
        assertTrue(def.isCobDateColumn("cob_date"));
        assertFalse(def.isCobDateColumn("EVENT_ID"));
        assertFalse(def.isCobDateColumn(null));
    }

    @Test
    public void feedDefinition_getDatePattern_caseInsensitive() {
        FeedDefinition def = new FeedDefinition();
        java.util.Map<String, String> dateColumns = new java.util.HashMap<>();
        dateColumns.put("EFFECTIVE_DATE", "MMddyyyy");
        def.setDateColumns(dateColumns);

        assertEquals("MMddyyyy", def.getDatePattern("EFFECTIVE_DATE"));
        assertEquals("MMddyyyy", def.getDatePattern("effective_date"));
        assertNull(def.getDatePattern("UNKNOWN_COL"));
    }

    @Test
    public void feedDefinition_isMonthly_autoDetectedFromFeedName() {
        FeedDefinition def = new FeedDefinition();
        def.setFeedName("pse-month-end");
        // monthly not explicitly set — auto-detected from feedName
        assertTrue(def.isMonthly());
        assertEquals("MNTH", def.getFrequency());
    }

    @Test
    public void feedDefinition_isMonthly_autoDetectedFromModuleName() {
        FeedDefinition def = new FeedDefinition();
        def.setFeedName("some-feed");
        def.setModuleName("PSEME");
        assertTrue(def.isMonthly());
    }

    @Test
    public void feedDefinition_isMonthly_explicitFalseTakesPrecedence() {
        FeedDefinition def = new FeedDefinition();
        def.setFeedName("pse-month-end"); // would auto-detect as monthly
        def.setMonthly(false);            // explicit override
        assertFalse(def.isMonthly());
        assertEquals("DLY", def.getFrequency());
    }

    @Test
    public void feedDefinition_gaiFeedId_autoDerivesFromModuleName() {
        FeedDefinition def = new FeedDefinition();
        def.setModuleName("STRESSEXP");
        assertEquals("SCEF-STRESSEXP", def.getGaiFeedId());
    }

    @Test
    public void feedDefinition_gaiFeedId_explicitValueTakesPrecedence() {
        FeedDefinition def = new FeedDefinition();
        def.setModuleName("STRESSEXP");
        def.setGaiFeedId("OVERRIDE-ID");
        assertEquals("OVERRIDE-ID", def.getGaiFeedId());
    }

    @Test
    public void feedDefinition_delimiter_defaultsToPipe() {
        FeedDefinition def = new FeedDefinition();
        // delimiter not set
        assertEquals("|", def.getDelimiter());
    }
}
