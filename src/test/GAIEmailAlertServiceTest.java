package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.domain.EmailDetails;
import com.citi.risk.scef.limitexposure.service.CommonEmailSendService;
import com.citi.risk.scef.limitexposure.service.EmailDetailService;
import com.citi.risk.scef.limitexposure.service.EmailServiceProperties;
import org.apache.commons.configuration.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * JUnit 4 tests for GAIEmailAlertService.
 *
 * Tests cover:
 *   - All three alert types (zero rows, row mismatch, failure)
 *   - Recipient CSV parsing (semicolon-delimited)
 *   - alert.enabled=false suppression
 *   - Blank recipients suppression
 *   - Blank fromAddress suppression
 *   - HTML body content spot-checks
 *   - sendEmail failure never propagates
 */
public class GAIEmailAlertServiceTest {

    @Mock private CommonEmailSendService emailSendService;
    @Mock private EmailDetailService     emailDetailService;
    @Mock private EmailServiceProperties emailServiceProperties;
    @Mock private Configuration          cfg;

    private GAIEmailAlertService service;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(cfg.getBoolean("SCEF.gai.feed.alert.enabled", true)).thenReturn(true);
        when(cfg.getString("SCEF.gai.feed.alert.subject.prefix", "[SCEF-GAI]"))
                .thenReturn("[SCEF-GAI]");
        when(cfg.getString("SCEF.gai.feed.alert.recipients", ""))
                .thenReturn("scef-dev-team@citi.com;scef-uat@citi.com");
        when(emailServiceProperties.getEmailFrom()).thenReturn("scef-noreply@citi.com");

        service = new GAIEmailAlertService(
                emailSendService, emailDetailService, emailServiceProperties, cfg);
    }

    // ── sendZeroRowsAlert ─────────────────────────────────────────────────────

    @Test
    public void sendZeroRowsAlert_callsSendEmail() {
        service.sendZeroRowsAlert("stress-exposure", "20260522");
        verify(emailSendService).sendEmail(any(EmailDetails.class));
    }

    @Test
    public void sendZeroRowsAlert_subjectContainsFeedNameAndCobDate() {
        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsSubject(any(), subjectCaptor.capture());

        String subject = subjectCaptor.getValue();
        assertTrue(subject.contains("stress-exposure"));
        assertTrue(subject.contains("20260522"));
        assertTrue(subject.contains("ZERO ROWS"));
    }

    @Test
    public void sendZeroRowsAlert_bodyContainsZeroRowsContent() {
        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsBodyText(any(), bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        assertTrue(body.contains("Zero rows"));
        assertTrue(body.contains("EVENT"));
        assertTrue(body.contains("RECORD"));
        assertTrue(body.contains("ATTRIBUTE"));
    }

    // ── sendRowMismatchAlert ──────────────────────────────────────────────────

    @Test
    public void sendRowMismatchAlert_callsSendEmail() {
        service.sendRowMismatchAlert("swwr-flag", "20260522", 5, 0, 5);
        verify(emailSendService).sendEmail(any(EmailDetails.class));
    }

    @Test
    public void sendRowMismatchAlert_subjectContainsROW_MISMATCH() {
        service.sendRowMismatchAlert("swwr-flag", "20260522", 5, 0, 5);

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsSubject(any(), subjectCaptor.capture());

        assertTrue(subjectCaptor.getValue().contains("ROW MISMATCH"));
        assertTrue(subjectCaptor.getValue().contains("swwr-flag"));
    }

    @Test
    public void sendRowMismatchAlert_bodyContainsRowCounts() {
        service.sendRowMismatchAlert("swwr-flag", "20260522", 10, 0, 10);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsBodyText(any(), bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        assertTrue(body.contains("10"));
        assertTrue(body.contains("0"));
        assertTrue(body.contains("MISSING"));
        assertTrue(body.contains("OK"));
    }

    @Test
    public void sendRowMismatchAlert_allZeroShowsAllMissing() {
        service.sendRowMismatchAlert("pse-exposure", "20260522", 0, 0, 0);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsBodyText(any(), bodyCaptor.capture());

        // All three should show MISSING
        long missingCount = countOccurrences(bodyCaptor.getValue(), "MISSING");
        assertEquals(3, missingCount);
    }

    // ── sendFailureAlert ──────────────────────────────────────────────────────

    @Test
    public void sendFailureAlert_callsSendEmail() {
        service.sendFailureAlert("oet-flag", "20260522", new RuntimeException("DB down"));
        verify(emailSendService).sendEmail(any(EmailDetails.class));
    }

    @Test
    public void sendFailureAlert_subjectContainsFAILED() {
        service.sendFailureAlert("oet-flag", "20260522", new RuntimeException("DB down"));

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsSubject(any(), subjectCaptor.capture());

        assertTrue(subjectCaptor.getValue().contains("FAILED"));
        assertTrue(subjectCaptor.getValue().contains("oet-flag"));
    }

    @Test
    public void sendFailureAlert_bodyContainsErrorMessage() {
        service.sendFailureAlert("oet-flag", "20260522",
                new RuntimeException("ORA-12541: no listener"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsBodyText(any(), bodyCaptor.capture());

        assertTrue(bodyCaptor.getValue().contains("ORA-12541"));
    }

    @Test
    public void sendFailureAlert_htmlEscapesExceptionMessage() {
        service.sendFailureAlert("stress-exposure", "20260522",
                new RuntimeException("<script>alert('xss')</script>"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsBodyText(any(), bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        assertFalse("Raw <script> tag should not appear in body", body.contains("<script>"));
        assertTrue("Should contain escaped version", body.contains("&lt;script&gt;"));
    }

    @Test
    public void sendFailureAlert_nullExceptionMessage_doesNotThrow() {
        // Exception with no message
        service.sendFailureAlert("stress-exposure", "20260522",
                new RuntimeException((String) null));
        verify(emailSendService).sendEmail(any(EmailDetails.class));
    }

    // ── alert.enabled=false ───────────────────────────────────────────────────

    @Test
    public void sendZeroRowsAlert_alertDisabled_doesNotSendEmail() {
        when(cfg.getBoolean("SCEF.gai.feed.alert.enabled", true)).thenReturn(false);
        service.sendZeroRowsAlert("stress-exposure", "20260522");
        verify(emailSendService, never()).sendEmail(any());
    }

    @Test
    public void sendRowMismatchAlert_alertDisabled_doesNotSendEmail() {
        when(cfg.getBoolean("SCEF.gai.feed.alert.enabled", true)).thenReturn(false);
        service.sendRowMismatchAlert("stress-exposure", "20260522", 5, 0, 5);
        verify(emailSendService, never()).sendEmail(any());
    }

    @Test
    public void sendFailureAlert_alertDisabled_doesNotSendEmail() {
        when(cfg.getBoolean("SCEF.gai.feed.alert.enabled", true)).thenReturn(false);
        service.sendFailureAlert("stress-exposure", "20260522", new RuntimeException("err"));
        verify(emailSendService, never()).sendEmail(any());
    }

    // ── recipient handling ────────────────────────────────────────────────────

    @Test
    public void sendZeroRowsAlert_blankRecipients_doesNotSendEmail() {
        when(cfg.getString("SCEF.gai.feed.alert.recipients", "")).thenReturn("");
        service.sendZeroRowsAlert("stress-exposure", "20260522");
        verify(emailSendService, never()).sendEmail(any());
    }

    @Test
    public void sendZeroRowsAlert_multipleRecipients_allPassedToEmailDetailService() {
        when(cfg.getString("SCEF.gai.feed.alert.recipients", ""))
                .thenReturn("a@citi.com;b@citi.com;c@citi.com");

        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<List> toListCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailDetailService).setEmailToList(any(), toListCaptor.capture());

        List recipients = toListCaptor.getValue();
        assertEquals(3, recipients.size());
        assertTrue(recipients.contains("a@citi.com"));
        assertTrue(recipients.contains("b@citi.com"));
        assertTrue(recipients.contains("c@citi.com"));
    }

    @Test
    public void sendZeroRowsAlert_singleRecipient_works() {
        when(cfg.getString("SCEF.gai.feed.alert.recipients", ""))
                .thenReturn("scef-prod@citi.com");

        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<List> toListCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailDetailService).setEmailToList(any(), toListCaptor.capture());
        assertEquals(1, toListCaptor.getValue().size());
    }

    @Test
    public void sendZeroRowsAlert_trailingSpacesInRecipients_areTrimmed() {
        when(cfg.getString("SCEF.gai.feed.alert.recipients", ""))
                .thenReturn("  a@citi.com ; b@citi.com  ");

        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<List> toListCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailDetailService).setEmailToList(any(), toListCaptor.capture());

        List recipients = toListCaptor.getValue();
        assertTrue(recipients.contains("a@citi.com"));
        assertTrue(recipients.contains("b@citi.com"));
    }

    // ── from address ──────────────────────────────────────────────────────────

    @Test
    public void sendZeroRowsAlert_nullFromAddress_doesNotSendEmail() {
        when(emailServiceProperties.getEmailFrom()).thenReturn(null);
        service.sendZeroRowsAlert("stress-exposure", "20260522");
        verify(emailSendService, never()).sendEmail(any());
    }

    @Test
    public void sendZeroRowsAlert_blankFromAddress_doesNotSendEmail() {
        when(emailServiceProperties.getEmailFrom()).thenReturn("  ");
        service.sendZeroRowsAlert("stress-exposure", "20260522");
        verify(emailSendService, never()).sendEmail(any());
    }

    // ── sendEmail failure never propagates ────────────────────────────────────

    @Test
    public void sendZeroRowsAlert_sendEmailThrows_doesNotPropagate() {
        doThrow(new RuntimeException("SMTP connection refused"))
                .when(emailSendService).sendEmail(any());

        // Must NOT throw — alert failure must never suppress original job exception
        service.sendZeroRowsAlert("stress-exposure", "20260522");
    }

    @Test
    public void sendFailureAlert_sendEmailThrows_doesNotPropagate() {
        doThrow(new RuntimeException("SMTP down"))
                .when(emailSendService).sendEmail(any());

        service.sendFailureAlert("stress-exposure", "20260522",
                new RuntimeException("original error"));
        // No exception should escape
    }

    // ── subject prefix ────────────────────────────────────────────────────────

    @Test
    public void sendZeroRowsAlert_subjectIncludesPrefix() {
        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsSubject(any(), subjectCaptor.capture());

        assertTrue(subjectCaptor.getValue().startsWith("[SCEF-GAI]"));
    }

    @Test
    public void sendZeroRowsAlert_customPrefix_isUsed() {
        when(cfg.getString("SCEF.gai.feed.alert.subject.prefix", "[SCEF-GAI]"))
                .thenReturn("[SCEF-GAI-UAT]");

        service.sendZeroRowsAlert("stress-exposure", "20260522");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsSubject(any(), subjectCaptor.capture());

        assertTrue(subjectCaptor.getValue().startsWith("[SCEF-GAI-UAT]"));
    }

    // ── rootCause traversal ───────────────────────────────────────────────────

    @Test
    public void sendFailureAlert_nestedCause_bodyContainsRootCauseName() {
        RuntimeException root  = new RuntimeException("ORA-04031");
        RuntimeException wrap1 = new RuntimeException("JDBC error", root);
        RuntimeException wrap2 = new RuntimeException("GAI failed", wrap1);

        service.sendFailureAlert("stress-exposure", "20260522", wrap2);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDetailService).setEmailDetailsBodyText(any(), bodyCaptor.capture());

        // Root cause message should appear in body
        assertTrue(bodyCaptor.getValue().contains("ORA-04031"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long countOccurrences(String text, String pattern) {
        int count = 0;
        int idx   = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
