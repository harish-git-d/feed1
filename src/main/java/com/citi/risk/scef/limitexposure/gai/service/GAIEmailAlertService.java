package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.domain.EmailDetails;
import com.citi.risk.scef.limitexposure.domain.SCEFUserDto;
import com.citi.risk.scef.limitexposure.service.CommonEmailSendService;
import com.citi.risk.scef.limitexposure.service.EmailDetailService;
import com.citi.risk.scef.limitexposure.service.EmailServiceProperties;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sends HTML email alerts for GAI feed issues.
 *
 * Uses EmailDetailService.setEmailDetailsSubject/BodyText +
 * EmailDetailService.setEmailToList + sendEmail — the same pattern
 * as EmailSendServiceImpl.scefDailySendEmail() (images 4-5).
 *
 * Subject and body are set directly via EmailDetailService helper methods,
 * avoiding the FTL template requirement.
 */
public class GAIEmailAlertService {

    private static final Logger logger = LoggerFactory.getLogger(GAIEmailAlertService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CommonEmailSendService emailSendService;
    private final EmailDetailService     emailDetailService;
    private final EmailServiceProperties emailServiceProperties;
    private final Configuration          cfg;

    @Inject
    public GAIEmailAlertService(
            @Named("emailSendServiceImpl")     CommonEmailSendService emailSendService,
            @Named("emailDetailServiceImpl")   EmailDetailService emailDetailService,
            EmailServiceProperties emailServiceProperties,
            Configuration cfg) {
        this.emailSendService       = emailSendService;
        this.emailDetailService     = emailDetailService;
        this.emailServiceProperties = emailServiceProperties;
        this.cfg                    = cfg;
    }

    // ── Alert 1: Zero rows ────────────────────────────────────────────────────

    public void sendZeroRowsAlert(String feedName, String cobDate) {
        if (!alertEnabled()) return;
        String subject = prefix() + " ZERO ROWS — " + feedName + " cobDate=" + cobDate;
        String body    = buildBody("GAI Feed — Zero rows returned", feedName, cobDate,
                "All three queries (EVENT, RECORD, ATTRIBUTE) returned 0 rows. " +
                "Empty files have been generated and transferred.<br><br>Possible causes:" +
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
        String body    = buildBody("GAI Feed — Row count mismatch", feedName, cobDate,
                "One or more file types returned 0 rows. Files have been generated with available data.<br><br>" +
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
        String body    = buildBody("GAI Feed — Job failed", feedName, cobDate,
                "<b>Error:</b> "      + escapeHtml(e.getMessage()) + "<br>" +
                "<b>Root cause:</b> " + escapeHtml(rootCause(e))   + "<br><br>" +
                "Spring Batch will mark this step as FAILED.<br>" +
                "Check Splunk or the Jetty log for the full stack trace.",
                "CRITICAL");
        send(subject, body, feedName);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void send(String subject, String htmlBody, String feedName) {
        try {
            String recipientsCsv = cfg.getString("SCEF.gai.feed.alert.recipients", "");
            if (recipientsCsv == null || recipientsCsv.trim().length() == 0) {
                logger.warn("[GAI][EMAIL] SCEF.gai.feed.alert.recipients not set — " +
                            "alert suppressed: {}", subject);
                return;
            }

            // Build recipient list — same pattern as sendHeartBeatNotificationEmail
            // (EmailSendServiceImpl lines 106-110): split by ";" into SCEFUserDto set
            List<String> toList = new ArrayList<String>();
            for (String email : recipientsCsv.split(";")) {
                String trimmed = email.trim();
                if (trimmed.length() > 0) toList.add(trimmed);
            }

            String fromAddress = emailServiceProperties.getEmailFrom();
            if (fromAddress == null || fromAddress.trim().length() == 0) {
                logger.warn("[GAI][EMAIL] emailServiceProperties.getEmailFrom() is null — " +
                            "alert cannot be sent. Configure email.from. Subject was: {}", subject);
                return;
            }

            // Build EmailDetails using EmailDetailService — same pattern as
            // scefDailySendEmail (EmailSendServiceImpl lines 77-81)
            EmailDetails emailDetails = new EmailDetails();

            emailDetailService.setEmailFromList(emailDetails, fromAddress);
            emailDetailService.setEmailToList(emailDetails, toList);
            emailDetailService.setEmailDetailsSubject(emailDetails, subject);
            emailDetailService.setEmailDetailsBodyText(emailDetails, htmlBody);

            emailSendService.sendEmail(emailDetails);
            logger.info("[GAI][EMAIL] Alert sent: {}", subject);

        } catch (Exception ex) {
            // Alert failure must NEVER suppress the original job exception
            logger.error("[GAI][EMAIL] Failed to send alert '{}': {}", subject, ex.getMessage());
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
               "</table><br><hr/><br>" +
               "<div>" + detail + "</div>" +
               "<br><hr/>" +
               "<p style='color:#aaa;font-size:11px'>SCEF GAI Feed automated alert. Do not reply.</p>" +
               "</body></html>";
    }

    private boolean alertEnabled() {
        return cfg.getBoolean("SCEF.gai.feed.alert.enabled", true);
    }

    private String prefix() {
        String base = cfg.getString("SCEF.gai.feed.alert.subject.prefix", "[SCEF-GAI]");
        try {
            String env = com.citi.risk.scef.limitexposure.config.module.CoreModule
                    .getEnvironment().getLifeCycle().name();
            return base + "[" + env + "]";
        } catch (Exception e) {
            logger.debug("[GAI][EMAIL] Could not resolve environment for subject: {}", e.getMessage());
            return base;
        }
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
