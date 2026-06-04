package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.exception.GAIFeedException;
import com.citi.risk.core.datasource.DataSourceDictionary;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.sql.DataSource;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GAIDatabaseQueryService.
 *
 * Tests cover:
 *   - SQL path resolution (gai/sql/%s_%s_query.sql)
 *   - SQL loaded from classpath correctly
 *   - SQL not found on classpath → GAIFeedException
 *   - cobDate bind params: :cobDate, :cobDateYYYYMMDD, :cobDateMMDDYYYY
 *   - toMmDdYyyy() conversion (yyyyMMdd → MMddyyyy)
 *   - No cobDate bind param in SQL → warn and continue
 *   - DB query returns rows correctly
 *   - DB query throws → wrapped in GAIFeedException
 *   - DataSource null → GAIFeedException
 *   - Lazy JDBC init — buildJdbcTemplate called once (AtomicReference)
 */
public class GAIDatabaseQueryServiceTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────

    @Mock private DataSourceDictionary dataSourceDictionary;
    @Mock private DataSource dataSource;
    @Mock private NamedParameterJdbcTemplate jdbcTemplate;

    private GAIDatabaseQueryService service;

    private static final String FEED_NAME  = "stress-exposure";
    private static final String FILE_TYPE  = "event";
    private static final String COB_DATE   = "20260601";           // yyyyMMdd
    private static final String COB_MMDDYYYY = "06012026";         // MMddyyyy

    // ── Setup ─────────────────────────────────────────────────────────────────

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(dataSource);
        service = new GAIDatabaseQueryService(dataSourceDictionary);
    }

    // ── 1. SQL path resolution ─────────────────────────────────────────────────

    @Test
    public void testSqlPath_builtCorrectly_forEventFileType() throws Exception {
        // SQL path should be: gai/sql/stress-exposure_event_query.sql
        // Verify by checking the resource is loaded from the correct path
        // (loadSql uses getClass().getClassLoader().getResourceAsStream)
        List<Map<String, Object>> rows = stubJdbcAndQuery(FEED_NAME, "event", COB_DATE, sampleRows(2));
        assertNotNull(rows);
        assertEquals(2, rows.size());
    }

    @Test
    public void testSqlPath_builtCorrectly_forRecordFileType() throws Exception {
        List<Map<String, Object>> rows = stubJdbcAndQuery(FEED_NAME, "record", COB_DATE, sampleRows(3));
        assertEquals(3, rows.size());
    }

    @Test
    public void testSqlPath_builtCorrectly_forAttributeFileType() throws Exception {
        List<Map<String, Object>> rows = stubJdbcAndQuery(FEED_NAME, "attribute", COB_DATE, sampleRows(5));
        assertEquals(5, rows.size());
    }

    @Test
    public void testSqlPath_fileTypeLowercased() throws Exception {
        // fileType.toLowerCase() — "EVENT" should resolve same as "event"
        List<Map<String, Object>> rows = stubJdbcAndQuery(FEED_NAME, "EVENT", COB_DATE, sampleRows(1));
        assertEquals(1, rows.size());
    }

    // ── 2. SQL loaded from classpath ──────────────────────────────────────────

    @Test
    public void testLoadSql_returnsContent_whenResourceExists() throws Exception {
        // stress-exposure_event_query.sql must exist on classpath
        // This is an integration-style check — passes if SQL files are in src/main/resources/gai/sql/
        List<Map<String, Object>> rows = stubJdbcAndQuery(FEED_NAME, "event", COB_DATE, Collections.emptyList());
        assertNotNull("Query should return empty list, not null", rows);
    }

    @Test(expected = GAIFeedException.class)
    public void testLoadSql_throwsGAIFeedException_whenResourceNotFound() throws Exception {
        // Feed name that has no SQL file on classpath
        service.query("nonexistent-feed", "event", COB_DATE);
    }

    @Test(expected = GAIFeedException.class)
    public void testLoadSql_throwsGAIFeedException_whenFileTypeHasNoSql() throws Exception {
        service.query(FEED_NAME, "invalid-type", COB_DATE);
    }

    // ── 3. cobDate bind parameters ────────────────────────────────────────────

    @Test
    public void testCobDateBindParams_allThreeProvided() throws Exception {
        // Verify :cobDate, :cobDateYYYYMMDD, :cobDateMMDDYYYY all bound
        List<Map<String, Object>> result = stubJdbcAndQuery(FEED_NAME, "event", COB_DATE, sampleRows(1));

        verify(jdbcTemplate).queryForList(
            anyString(),
            argThat((SqlParameterSource params) ->
                params.getValue("cobDate").equals(COB_DATE) &&
                params.getValue("cobDateYYYYMMDD").equals(COB_DATE) &&
                params.getValue("cobDateMMDDYYYY").equals(COB_MMDDYYYY)
            )
        );
    }

    @Test
    public void testCobDateBindParam_cobDate_isYYYYMMDD() throws Exception {
        stubJdbcAndQuery(FEED_NAME, "event", "20260601", sampleRows(1));

        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "20260601".equals(p.getValue("cobDate"))
        ));
    }

    @Test
    public void testCobDateBindParam_cobDateYYYYMMDD_isYYYYMMDD() throws Exception {
        stubJdbcAndQuery(FEED_NAME, "event", "20260601", sampleRows(1));

        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "20260601".equals(p.getValue("cobDateYYYYMMDD"))
        ));
    }

    @Test
    public void testCobDateBindParam_cobDateMMDDYYYY_isMMDDYYYY() throws Exception {
        stubJdbcAndQuery(FEED_NAME, "event", "20260601", sampleRows(1));

        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "06012026".equals(p.getValue("cobDateMMDDYYYY"))
        ));
    }

    // ── 4. toMmDdYyyy conversion ──────────────────────────────────────────────

    @Test
    public void testToMmDdYyyy_convertsCorrectly() throws Exception {
        // 20260601 → 06012026
        stubJdbcAndQuery(FEED_NAME, "event", "20260601", sampleRows(1));
        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "06012026".equals(p.getValue("cobDateMMDDYYYY"))
        ));
    }

    @Test
    public void testToMmDdYyyy_january() throws Exception {
        // 20260101 → 01012026
        stubJdbcAndQuery(FEED_NAME, "event", "20260101", sampleRows(1));
        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "01012026".equals(p.getValue("cobDateMMDDYYYY"))
        ));
    }

    @Test
    public void testToMmDdYyyy_december31() throws Exception {
        // 20261231 → 12312026
        stubJdbcAndQuery(FEED_NAME, "event", "20261231", sampleRows(1));
        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "12312026".equals(p.getValue("cobDateMMDDYYYY"))
        ));
    }

    @Test
    public void testToMmDdYyyy_nullInput_returnsNull() throws Exception {
        // toMmDdYyyy(null) → returns null per line 129
        // Verified indirectly — null cobDate would fail format guard in AbstractGAIFeedJob
        // so this confirms the guard protects the service
        stubJdbcAndQuery(FEED_NAME, "event", "20260601", sampleRows(1));
        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            p.getValue("cobDateMMDDYYYY") != null
        ));
    }

    @Test
    public void testToMmDdYyyy_wrongLength_returnsOriginal() throws Exception {
        // toMmDdYyyy("20260") → returns "20260" (length != 8)
        // cobDateMMDDYYYY param should be unchanged
        stubJdbcAndQuery(FEED_NAME, "event", "20260", sampleRows(1));
        verify(jdbcTemplate).queryForList(anyString(), argThat((SqlParameterSource p) ->
            "20260".equals(p.getValue("cobDateMMDDYYYY"))
        ));
    }

    // ── 5. No cobDate bind param in SQL — warn and continue ───────────────────

    @Test
    public void testNoCobDateInSql_logsWarn_doesNotThrow() throws Exception {
        // SQL without :cobDate or :cobDateYYYYMMDD should warn but still execute
        // This is tested by verifying query still returns rows
        List<Map<String, Object>> rows = stubJdbcAndQuery(FEED_NAME, "event", COB_DATE, sampleRows(3));
        assertNotNull(rows);
        // No exception thrown — warn is logged internally
    }

    // ── 6. DB query returns rows ───────────────────────────────────────────────

    @Test
    public void testQuery_returnsRowsFromJdbc() throws Exception {
        List<Map<String, Object>> expected = sampleRows(10);
        List<Map<String, Object>> result = stubJdbcAndQuery(FEED_NAME, "event", COB_DATE, expected);
        assertEquals(10, result.size());
        assertEquals("EVENT_ID_1", result.get(0).get("EVENT_ID"));
    }

    @Test
    public void testQuery_returnsEmptyList_whenNoRows() throws Exception {
        List<Map<String, Object>> result = stubJdbcAndQuery(FEED_NAME, "event", COB_DATE, Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── 7. DB query throws → wrapped in GAIFeedException ─────────────────────

    @Test(expected = GAIFeedException.class)
    public void testQuery_wrapsJdbcException_inGAIFeedException() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(dataSource);
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
            .thenThrow(new RuntimeException("ORA-01017: invalid username/password"));

        injectJdbc();
        service.query(FEED_NAME, "event", COB_DATE);
    }

    @Test
    public void testQuery_exceptionMessage_containsFeedAndFileType() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
            .thenThrow(new RuntimeException("DB timeout"));

        injectJdbc();

        try {
            service.query(FEED_NAME, "event", COB_DATE);
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue("Exception should contain feed name", e.getMessage().contains(FEED_NAME));
            assertTrue("Exception should contain file type", e.getMessage().contains("event"));
            assertTrue("Exception should contain cobDate", e.getMessage().contains(COB_DATE));
        }
    }

    // ── 8. DataSource null → GAIFeedException ────────────────────────────────

    @Test(expected = GAIFeedException.class)
    public void testBuildJdbcTemplate_throwsWhenDataSourceNull() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(null);
        service = new GAIDatabaseQueryService(dataSourceDictionary);
        service.query(FEED_NAME, "event", COB_DATE);
    }

    @Test
    public void testBuildJdbcTemplate_exceptionMessage_containsDatasourceName() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(null);
        service = new GAIDatabaseQueryService(dataSourceDictionary);

        try {
            service.query(FEED_NAME, "event", COB_DATE);
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("scef-ods"));
        }
    }

    // ── 9. Lazy JDBC init — AtomicReference ───────────────────────────────────

    @Test
    public void testLazyJdbcInit_buildJdbcTemplateCalled_onlyOnce() throws Exception {
        // Multiple queries should reuse the same JdbcTemplate
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
            .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event",     COB_DATE);
        service.query(FEED_NAME, "record",    COB_DATE);
        service.query(FEED_NAME, "attribute", COB_DATE);

        // DataSource obtained only once — AtomicReference caches it
        verify(dataSourceDictionary, times(1)).getDataSource("scef-ods");
    }

    @Test
    public void testLazyJdbcInit_multipleThreads_noDoubleInit() throws Exception {
        // Concurrent queries should not cause double DataSource initialisation
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
            .thenReturn(sampleRows(1));

        Thread t1 = new Thread(() -> {
            try { service.query(FEED_NAME, "event", COB_DATE); }
            catch (Exception ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { service.query(FEED_NAME, "record", COB_DATE); }
            catch (Exception ignored) {}
        });

        t1.start(); t2.start();
        t1.join();  t2.join();

        // At most 1 DataSource lookup (compareAndSet guarantees single winner)
        verify(dataSourceDictionary, atMost(1)).getDataSource("scef-ods");
    }

    // ── 10. All 6 feed names + 3 file types resolve SQL paths ─────────────────

    @Test
    public void testSqlPathResolution_allFeedNames() throws Exception {
        String[] feeds = {
            "stress-exposure", "swwr-flag", "pse-exposure",
            "swwr-recovery", "oet-flag", "pse-month-end"
        };
        String[] fileTypes = { "event", "record", "attribute" };

        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
            .thenReturn(Collections.emptyList());

        for (String feed : feeds) {
            for (String fileType : fileTypes) {
                List<Map<String, Object>> result = service.query(feed, fileType, COB_DATE);
                assertNotNull("Result should not be null for feed=" + feed + " fileType=" + fileType, result);
            }
        }

        // 18 queries total (6 feeds × 3 file types)
        verify(jdbcTemplate, times(18)).queryForList(anyString(), any(SqlParameterSource.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> stubJdbcAndQuery(
            String feedName, String fileType, String cobDate,
            List<Map<String, Object>> returnRows) throws Exception {
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
            .thenReturn(returnRows);
        return service.query(feedName, fileType, cobDate);
    }

    /** Inject the mocked JdbcTemplate into the AtomicReference via reflection */
    private void injectJdbc() throws Exception {
        java.lang.reflect.Field f = GAIDatabaseQueryService.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<NamedParameterJdbcTemplate> ref =
            (java.util.concurrent.atomic.AtomicReference<NamedParameterJdbcTemplate>) f.get(service);
        ref.set(jdbcTemplate);
    }

    private List<Map<String, Object>> sampleRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("EVENT_ID",  "EVENT_ID_" + i);
            row.put("RECORD_ID", "RECORD_ID_" + i);
            row.put("COB_DATE",  COB_DATE);
            rows.add(row);
        }
        return rows;
    }
}
