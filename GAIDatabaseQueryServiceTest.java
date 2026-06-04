package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.exception.GAIFeedException;
import com.citi.risk.core.datasource.DataSourceDictionary;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GAIDatabaseQueryService.
 *
 * Key fix: argThat() with SqlParameterSource lambda causes
 * 'Cannot resolve method queryForList(String, T)' due to Mockito
 * generic inference ambiguity. Use ArgumentCaptor<SqlParameterSource>
 * instead to capture and assert bind params.
 */
public class GAIDatabaseQueryServiceTest {

    @Mock private DataSourceDictionary dataSourceDictionary;
    @Mock private DataSource           dataSource;
    @Mock private NamedParameterJdbcTemplate jdbcTemplate;

    private GAIDatabaseQueryService service;

    private static final String FEED_NAME    = "stress-exposure";
    private static final String COB_DATE     = "20260601";   // yyyyMMdd
    private static final String COB_MMDDYYYY = "06012026";   // MMddyyyy

    // ── Setup ─────────────────────────────────────────────────────────────────

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(dataSource);
        service = new GAIDatabaseQueryService(dataSourceDictionary);
    }

    // ── 1. SQL path resolution ────────────────────────────────────────────────

    @Test
    public void testSqlPath_event() throws Exception {
        List<Map<String, Object>> rows = stubAndQuery(FEED_NAME, "event", COB_DATE, sampleRows(2));
        assertEquals(2, rows.size());
    }

    @Test
    public void testSqlPath_record() throws Exception {
        List<Map<String, Object>> rows = stubAndQuery(FEED_NAME, "record", COB_DATE, sampleRows(3));
        assertEquals(3, rows.size());
    }

    @Test
    public void testSqlPath_attribute() throws Exception {
        List<Map<String, Object>> rows = stubAndQuery(FEED_NAME, "attribute", COB_DATE, sampleRows(5));
        assertEquals(5, rows.size());
    }

    @Test
    public void testSqlPath_fileTypeLowercased() throws Exception {
        // "EVENT" should resolve same as "event"
        List<Map<String, Object>> rows = stubAndQuery(FEED_NAME, "EVENT", COB_DATE, sampleRows(1));
        assertEquals(1, rows.size());
    }

    // ── 2. SQL not found → GAIFeedException ───────────────────────────────────

    @Test(expected = GAIFeedException.class)
    public void testLoadSql_throws_whenFeedNotFound() throws Exception {
        service.query("nonexistent-feed", "event", COB_DATE);
    }

    @Test(expected = GAIFeedException.class)
    public void testLoadSql_throws_whenFileTypeInvalid() throws Exception {
        service.query(FEED_NAME, "invalid-type", COB_DATE);
    }

    // ── 3. cobDate bind params — use ArgumentCaptor ───────────────────────────

    @Test
    public void testCobDateBindParams_cobDate_isYYYYMMDD() throws Exception {
        ArgumentCaptor<SqlParameterSource> captor =
                ArgumentCaptor.forClass(SqlParameterSource.class);

        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event", COB_DATE);

        verify(jdbcTemplate).queryForList(anyString(), captor.capture());
        SqlParameterSource params = captor.getValue();
        assertEquals("cobDate should be yyyyMMdd",
                COB_DATE, params.getValue("cobDate"));
    }

    @Test
    public void testCobDateBindParams_cobDateYYYYMMDD_isYYYYMMDD() throws Exception {
        ArgumentCaptor<SqlParameterSource> captor =
                ArgumentCaptor.forClass(SqlParameterSource.class);

        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event", COB_DATE);

        verify(jdbcTemplate).queryForList(anyString(), captor.capture());
        assertEquals("cobDateYYYYMMDD should be yyyyMMdd",
                COB_DATE, captor.getValue().getValue("cobDateYYYYMMDD"));
    }

    @Test
    public void testCobDateBindParams_cobDateMMDDYYYY_isMMDDYYYY() throws Exception {
        ArgumentCaptor<SqlParameterSource> captor =
                ArgumentCaptor.forClass(SqlParameterSource.class);

        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event", COB_DATE);

        verify(jdbcTemplate).queryForList(anyString(), captor.capture());
        assertEquals("cobDateMMDDYYYY should be MMddyyyy",
                COB_MMDDYYYY, captor.getValue().getValue("cobDateMMDDYYYY"));
    }

    @Test
    public void testCobDateBindParams_allThreePresent() throws Exception {
        ArgumentCaptor<SqlParameterSource> captor =
                ArgumentCaptor.forClass(SqlParameterSource.class);

        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event", COB_DATE);

        verify(jdbcTemplate).queryForList(anyString(), captor.capture());
        SqlParameterSource p = captor.getValue();
        assertNotNull("cobDate must be bound",         p.getValue("cobDate"));
        assertNotNull("cobDateYYYYMMDD must be bound", p.getValue("cobDateYYYYMMDD"));
        assertNotNull("cobDateMMDDYYYY must be bound", p.getValue("cobDateMMDDYYYY"));
    }

    // ── 4. toMmDdYyyy conversion ──────────────────────────────────────────────

    @Test
    public void testToMmDdYyyy_june01() throws Exception {
        // 20260601 → 06012026
        assertMmDdYyyy("20260601", "06012026");
    }

    @Test
    public void testToMmDdYyyy_january01() throws Exception {
        // 20260101 → 01012026
        assertMmDdYyyy("20260101", "01012026");
    }

    @Test
    public void testToMmDdYyyy_december31() throws Exception {
        // 20261231 → 12312026
        assertMmDdYyyy("20261231", "12312026");
    }

    @Test
    public void testToMmDdYyyy_wrongLength_returnsOriginal() throws Exception {
        // length != 8 → returns unchanged
        assertMmDdYyyy("20260", "20260");
    }

    // ── 5. DB query returns rows ───────────────────────────────────────────────

    @Test
    public void testQuery_returnsRows() throws Exception {
        List<Map<String, Object>> result =
                stubAndQuery(FEED_NAME, "event", COB_DATE, sampleRows(10));
        assertEquals(10, result.size());
        assertEquals("EVENT_ID_1", result.get(0).get("EVENT_ID"));
    }

    @Test
    public void testQuery_returnsEmptyList() throws Exception {
        List<Map<String, Object>> result =
                stubAndQuery(FEED_NAME, "event", COB_DATE, Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── 6. DB throws → wrapped in GAIFeedException ────────────────────────────

    @Test(expected = GAIFeedException.class)
    public void testQuery_wrapsJdbcException() throws Exception {
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new RuntimeException("ORA-01017: invalid username/password"));
        service.query(FEED_NAME, "event", COB_DATE);
    }

    @Test
    public void testQuery_exceptionMessage_containsContext() throws Exception {
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new RuntimeException("DB timeout"));
        try {
            service.query(FEED_NAME, "event", COB_DATE);
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains(FEED_NAME));
            assertTrue(e.getMessage().contains("event"));
            assertTrue(e.getMessage().contains(COB_DATE));
        }
    }

    // ── 7. DataSource null → GAIFeedException ─────────────────────────────────

    @Test(expected = GAIFeedException.class)
    public void testBuildJdbc_throws_whenDataSourceNull() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(null);
        service = new GAIDatabaseQueryService(dataSourceDictionary);
        service.query(FEED_NAME, "event", COB_DATE);
    }

    @Test
    public void testBuildJdbc_exceptionMessage_containsDatasourceName() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(null);
        service = new GAIDatabaseQueryService(dataSourceDictionary);
        try {
            service.query(FEED_NAME, "event", COB_DATE);
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("scef-ods"));
        }
    }

    // ── 8. Lazy JDBC init — AtomicReference ───────────────────────────────────

    @Test
    public void testLazyJdbcInit_dataSourceObtainedOnlyOnce() throws Exception {
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event",     COB_DATE);
        service.query(FEED_NAME, "record",    COB_DATE);
        service.query(FEED_NAME, "attribute", COB_DATE);

        verify(dataSourceDictionary, times(1)).getDataSource("scef-ods");
    }

    // ── 9. All 18 feed+fileType combinations ──────────────────────────────────

    @Test
    public void testAllFeedsAndFileTypes_resolveWithoutException() throws Exception {
        String[] feeds     = { "stress-exposure", "swwr-flag", "pse-exposure",
                               "swwr-recovery",   "oet-flag",  "pse-month-end" };
        String[] fileTypes = { "event", "record", "attribute" };

        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(Collections.emptyList());

        for (String feed : feeds) {
            for (String ft : fileTypes) {
                assertNotNull(service.query(feed, ft, COB_DATE));
            }
        }
        verify(jdbcTemplate, times(18))
                .queryForList(anyString(), any(SqlParameterSource.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> stubAndQuery(
            String feed, String fileType, String cobDate,
            List<Map<String, Object>> returnRows) throws Exception {
        injectJdbc();
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(returnRows);
        return service.query(feed, fileType, cobDate);
    }

    /**
     * Asserts cobDateMMDDYYYY bind param equals expected for the given cobDate.
     */
    private void assertMmDdYyyy(String cobDate, String expectedMMDDYYYY) throws Exception {
        ArgumentCaptor<SqlParameterSource> captor =
                ArgumentCaptor.forClass(SqlParameterSource.class);

        injectJdbc();
        reset(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(sampleRows(1));

        service.query(FEED_NAME, "event", cobDate);

        verify(jdbcTemplate).queryForList(anyString(), captor.capture());
        assertEquals(expectedMMDDYYYY,
                captor.getValue().getValue("cobDateMMDDYYYY"));
    }

    /** Injects mocked JdbcTemplate into the AtomicReference via reflection */
    private void injectJdbc() throws Exception {
        Field f = GAIDatabaseQueryService.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<NamedParameterJdbcTemplate> ref =
                (AtomicReference<NamedParameterJdbcTemplate>) f.get(service);
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
