package com.citi.risk.scef.limitexposure.gai.service;

import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sends HTML email alerts for GAI feed issues using SCEF's existing
 * CommonEmailSendService — same injection pattern as AgedReportJob.
 *
 * ── INTEGRATION STEPS ────────────────────────────────────────────────────────
 * 1. In IntelliJ, search for:  CommonEmailSendService
 *    Note the full package name (e.g. com.citi.risk.scef.limitexposure.service.CommonEmailSendService)
 *    Replace the TODO_PACKAGE import below.
 *
 * 2. Search for: emailSendService.sendEmail(
 *    in AgedReportJob or EmailSendServiceImpl to find the EmailDto/email object class.
 *    Replace TODO_EMAIL_CLASS below and fill in the setter calls in send().
 *
 * 3. Add to core.properties:
 *    SCEF.gai.feed.alert.enabled=true
 *    SCEF.gai.feed.alert.recipients=your-team@citi.com
 *    SCEF.gai.feed.alert.from=scef-noreply@citi.com
 *    SCEF.gai.feed.alert.subject.prefix=[SCEF-GAI]
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Three alert scenarios:
 *   sendZeroRowsAlert()     — all 3 queries returned 0 rows
 *   sendRowMismatchAlert()  — event/record/attribute counts differ (one is 0)
 *   sendFailureAlert()      — unhandled exception in job
 */
public class GAIEmailAlertService {

    private static final Logger logger = LoggerFactory.getLogger(GAIEmailAlertService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // TODO_PACKAGE: replace with the actual import from your SCEF codebase
    // Search IntelliJ for: @Named("emailSendServiceImpl")
    // Example: com.citi.risk.scef.limitexposure.service.CommonEmailSendService
    private final Object emailSendService;
    private final Configuration cfg;

    @Inject
    public GAIEmailAlertService(
            @Named("emailSendServiceImpl") Object emailSendService,
            Configuration cfg) {
        this.emailSendService = emailSendService;
        this.cfg = cfg;
    }

    // ── Alert 1: Zero rows ────────────────────────────────────────────────────

    public void sendZeroRowsAlert(String feedName, String cobDate) {
        if (!alertEnabled()) return;

        String subject = prefix() + " ZERO ROWS — " + feedName + " cobDate=" + cobDate;
        String body = buildBody("GAI Feed — Zero rows returned",
                feedName, cobDate,
                "All three queries (EVENT, RECORD, ATTRIBUTE) returned 0 rows. " +
                "No files were generated.<br><br>Possible causes:" +
                "<ul>" +
                "<li>No approved requests exist for this COB date</li>" +
                "<li>cobDate parameter is incorrect</li>" +
                "<li>REQUEST_TYPE filter in SQL does not match live data</li>" +
                "</ul>",
                "WARNING");

        send(subject, body, feedName);
    }

    // ── Alert 2: Row count mismatch ───────────────────────────────────────────

    public void sendRowMismatchAlert(String feedName, String cobDate,
                                      int eventCount, int recordCount, int attributeCount) {
        if (!alertEnabled()) return;

        String subject = prefix() + " ROW MISMATCH — " + feedName + " cobDate=" + cobDate;
        String body = buildBody("GAI Feed — Row count mismatch",
                feedName, cobDate,
                "One or more file types returned 0 rows. No files were generated.<br><br>" +
                "<table border='1' cellpadding='4' cellspacing='0' style='border-collapse:collapse'>" +
                "<tr style='background:#eee'><th>File Type</th><th>Row Count</th><th>Status</th></tr>" +
                "<tr><td>EVENT</td><td>"     + eventCount     + "</td><td>" + rowStatus(eventCount)     + "</td></tr>" +
                "<tr><td>RECORD</td><td>"    + recordCount    + "</td><td>" + rowStatus(recordCount)    + "</td></tr>" +
                "<tr><td>ATTRIBUTE</td><td>" + attributeCount + "</td><td>" + rowStatus(attributeCount) + "</td></tr>" +
                "</table>",
                "WARNING");

        send(subject, body, feedName);
    }

    // ── Alert 3: Job failure ──────────────────────────────────────────────────

    public void sendFailureAlert(String feedName, String cobDate, Exception e) {
        if (!alertEnabled()) return;

        String subject = prefix() + " FAILED — " + feedName + " cobDate=" + cobDate;
        String body = buildBody("GAI Feed — Job failed",
                feedName, cobDate,
                "<b>Error:</b> "      + escapeHtml(e.getMessage())   + "<br>" +
                "<b>Root cause:</b> " + escapeHtml(rootCause(e))     + "<br><br>" +
                "Spring Batch will mark this step as FAILED.<br>" +
                "Check Splunk or the Jetty log for the full stack trace.",
                "CRITICAL");

        send(subject, body, feedName);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void send(String subject, String htmlBody, String feedName) {
        try {
            String recipients = cfg.getString("SCEF.gai.feed.alert.recipients", "");
            String from       = cfg.getString("SCEF.gai.feed.alert.from", "scef-noreply@citi.com");

            if (recipients == null || recipients.trim().length() == 0) {
                logger.warn("[GAI][EMAIL] SCEF.gai.feed.alert.recipients not set — alert suppressed: {}", subject);
                return;
            }

            // TODO_EMAIL_CLASS: replace Object with SCEF's actual email DTO class.
            // Search AgedReportJob for: emailSendService.sendEmail(
            // That will show you the class and its setters.
            // Common pattern:
            //
            //   EmailDto email = new EmailDto();
            //   email.setTo(recipients);
            //   email.setFrom(from);
            //   email.setSubject(subject);
            //   email.setBody(htmlBody);
            //   email.setHtml(true);
            //   emailSendService.sendEmail(email);

            logger.warn("[GAI][EMAIL] TODO_EMAIL_CLASS: wire up actual email DTO. " +
                        "Subject would be: {}", subject);

        } catch (Exception ex) {
            // Alert failure must never suppress the original job exception
            logger.error("[GAI][EMAIL] Failed to send alert: {} — {}", subject, ex.getMessage());
        }
    }

    private String buildBody(String heading, String feedName,
                              String cobDate, String detail, String severity) {
        String now    = LocalDateTime.now().format(DT_FMT);
        String colour = "CRITICAL".equals(severity) ? "#cc0000"
                      : "WARNING".equals(severity)  ? "#e65c00"
                      : "#333333";
        return "<html><body style='font-family:Arial,sans-serif;font-size:14px'>" +
               "<h2 style='color:" + colour + ";margin-bottom:8px'>" + heading + "</h2>" +
               "<table cellpadding='6' style='border-collapse:collapse'>" +
               "<tr><td><b>Feed</b></td><td>"     + feedName + "</td></tr>" +
               "<tr><td><b>COB Date</b></td><td>" + cobDate  + "</td></tr>" +
               "<tr><td><b>Severity</b></td><td><b style='color:" + colour + "'>" + severity + "</b></td></tr>" +
               "<tr><td><b>Time</b></td><td>"     + now      + "</td></tr>" +
               "</table><br><hr style='border:none;border-top:1px solid #ccc'/><br>" +
               "<div>" + detail + "</div>" +
               "<br><hr style='border:none;border-top:1px solid #eee'/>" +
               "<p style='color:#aaa;font-size:11px'>SCEF GAI Feed automated alert. Do not reply.</p>" +
               "</body></html>";
    }

    private boolean alertEnabled() {
        return cfg.getBoolean("SCEF.gai.feed.alert.enabled", true);
    }

    private String prefix() {
        return cfg.getString("SCEF.gai.feed.alert.subject.prefix", "[SCEF-GAI]");
    }

    private String rowStatus(int count) {
        return count > 0
                ? "<span style='color:green;font-weight:bold'>OK (" + count + ")</span>"
                : "<span style='color:red;font-weight:bold'>MISSING (0)</span>";
    }

    private String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() +
               (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
