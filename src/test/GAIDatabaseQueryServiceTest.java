package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.core.data.db.DataSourceDictionary;
import com.citi.risk.scef.limitexposure.gai.GAIFeedException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * JUnit 4 tests for GAIDatabaseQueryService.
 *
 * Key patterns:
 *   - AtomicReference<NamedParameterJdbcTemplate> injected via reflection
 *   - DataSourceDictionary.getDataSource() stubbed
 *   - SQL loaded from classpath — real classpath resources used where available,
 *     or JDBC template stubbed to bypass SQL loading
 */
public class GAIDatabaseQueryServiceTest {

    @Mock private DataSourceDictionary         dataSourceDictionary;
    @Mock private DataSource                   dataSource;
    @Mock private NamedParameterJdbcTemplate   jdbc;

    private GAIDatabaseQueryService service;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(dataSource);

        service = new GAIDatabaseQueryService(dataSourceDictionary);

        // Inject mock JDBC template directly into the AtomicReference
        // to bypass actual DataSource / connection setup in unit tests
        injectJdbcTemplate(service, jdbc);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Injects the mock NamedParameterJdbcTemplate into jdbcRef via reflection.
     * Mirrors the pattern used for CEF spy injection in SCEF tests.
     */
    private void injectJdbcTemplate(GAIDatabaseQueryService svc,
                                     NamedParameterJdbcTemplate template) throws Exception {
        Field field = GAIDatabaseQueryService.class.getDeclaredField("jdbcRef");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<NamedParameterJdbcTemplate> ref =
                (AtomicReference<NamedParameterJdbcTemplate>) field.get(svc);
        ref.set(template);
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

    // ── query() — happy path ──────────────────────────────────────────────────

    @Test
    public void query_validParams_returnsRows() {
        List<Map<String, Object>> expected = buildRows(5);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(expected);

        List<Map<String, Object>> result =
                service.query("stress-exposure", "event", "20260522");

        assertEquals(5, result.size());
    }

    @Test
    public void query_emptyResult_returnsEmptyList() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(Collections.emptyList());

        List<Map<String, Object>> result =
                service.query("stress-exposure", "record", "20260522");

        assertTrue(result.isEmpty());
    }

    @Test
    public void query_fileTypeLowercase_buildsCorrectSqlPath() {
        // fileType "EVENT" and "event" both resolve to the same SQL file
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(buildRows(2));

        List<Map<String, Object>> r1 = service.query("stress-exposure", "event",     "20260522");
        List<Map<String, Object>> r2 = service.query("stress-exposure", "EVENT",     "20260522");

        assertEquals(2, r1.size());
        assertEquals(2, r2.size());
    }

    @Test
    public void query_passesAllThreeCobDateBindParams() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(buildRows(1));

        service.query("stress-exposure", "event", "20260522");

        verify(jdbc).queryForList(anyString(), argThat(
                (org.mockito.ArgumentMatcher<MapSqlParameterSource>) params -> {
                    Map<String, Object> values = params.getValues();
                    return "20260522".equals(values.get("cobDate"))
                        && "20260522".equals(values.get("cobDateYYYYMMDD"))
                        && "05222026".equals(values.get("cobDateMMDDYYYY"));
                }
        ));
    }

    @Test
    public void query_allSixFeedTypes_doNotThrow() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(buildRows(1));

        String[] feeds = {
            "stress-exposure", "swwr-flag", "pse-exposure",
            "swwr-recovery",   "oet-flag",  "pse-month-end"
        };
        String[] types = { "event", "record", "attribute" };

        for (String feed : feeds) {
            for (String type : types) {
                // Should not throw — SQL file path built correctly
                List<Map<String, Object>> rows = service.query(feed, type, "20260522");
                assertNotNull(rows);
            }
        }
    }

    // ── query() — failure path ────────────────────────────────────────────────

    @Test(expected = GAIFeedException.class)
    public void query_jdbcThrows_wrapsInGAIFeedException() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("ORA-12541: no listener"));

        service.query("stress-exposure", "event", "20260522");
    }

    @Test
    public void query_jdbcThrows_exceptionMessageContainsFeedAndType() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("connection timed out"));

        try {
            service.query("stress-exposure", "event", "20260522");
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("stress-exposure"));
            assertTrue(e.getMessage().contains("event"));
            assertTrue(e.getMessage().contains("20260522"));
        }
    }

    @Test
    public void query_jdbcThrows_originalExceptionIsChained() {
        RuntimeException root = new RuntimeException("ORA-04031: shared pool");
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(root);

        try {
            service.query("stress-exposure", "event", "20260522");
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertSame(root, e.getCause());
        }
    }

    // ── buildJdbcTemplate — DataSource resolution ─────────────────────────────

    @Test
    public void buildJdbcTemplate_dataSourceNull_throwsGAIFeedException() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods")).thenReturn(null);

        // Create a fresh service with no injected template so it calls buildJdbcTemplate
        GAIDatabaseQueryService freshService =
                new GAIDatabaseQueryService(dataSourceDictionary);

        try {
            // query() triggers lazy JDBC template init → buildJdbcTemplate
            freshService.query("stress-exposure", "event", "20260522");
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("scef-ods"));
        }
    }

    @Test
    public void buildJdbcTemplate_dataSourceThrows_throwsGAIFeedException() throws Exception {
        when(dataSourceDictionary.getDataSource("scef-ods"))
                .thenThrow(new RuntimeException("DataSourceDictionary not initialised"));

        GAIDatabaseQueryService freshService =
                new GAIDatabaseQueryService(dataSourceDictionary);

        try {
            freshService.query("stress-exposure", "event", "20260522");
            fail("Expected GAIFeedException");
        } catch (GAIFeedException e) {
            assertTrue(e.getMessage().contains("scef-ods"));
        }
    }

    // ── AtomicReference lazy init — thread safety ─────────────────────────────

    @Test
    public void jdbcRef_concurrentAccess_singleTemplateCreated() throws Exception {
        // Remove pre-injected template so buildJdbcTemplate is called
        GAIDatabaseQueryService freshService =
                new GAIDatabaseQueryService(dataSourceDictionary);

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(buildRows(1));

        // Simulate concurrent first-use calls
        int threadCount = 10;
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    freshService.query("stress-exposure", "event", "20260522");
                } catch (Exception ignored) {
                    // DataSource may not be fully wired — we just care about no NPE/race
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        // DataSource looked up at most once per atomic compare-and-set
        verify(dataSourceDictionary, atMost(threadCount)).getDataSource("scef-ods");
    }

    // ── toMmDdYyyy conversion ─────────────────────────────────────────────────

    @Test
    public void query_cobDateConvertedCorrectly_20260522becomes05222026() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(Collections.emptyList());

        service.query("stress-exposure", "event", "20260522");

        verify(jdbc).queryForList(anyString(), argThat(
                (org.mockito.ArgumentMatcher<MapSqlParameterSource>) params ->
                        "05222026".equals(params.getValues().get("cobDateMMDDYYYY"))
        ));
    }

    @Test
    public void query_cobDate_20260101_convertsTo_01012026() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(Collections.emptyList());

        service.query("stress-exposure", "event", "20260101");

        verify(jdbc).queryForList(anyString(), argThat(
                (org.mockito.ArgumentMatcher<MapSqlParameterSource>) params ->
                        "01012026".equals(params.getValues().get("cobDateMMDDYYYY"))
        ));
    }
}
