package com.citi.risk.scef.limitexposure.gai.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * GAI feed metadata loaded from classpath:gai/feed-definitions/{feedName}.yml.
 *
 * YAML shape (use eventFields / recordFields / attributeFields as flat lists):
 *
 *   feedName: stress-exposure
 *   moduleName: STRESSEXP
 *   perspectiveName: Exposure Monitoring
 *   delimiter: "|"
 *   nullDefault: ""
 *   monthly: false
 *   cobDateColumns:
 *     - COB_DATE
 *   dateColumns:
 *     EFFECTIVE_DATE: MMddyyyy
 *     POSTED_DATE:    MMddyyyy
 *   eventFields:
 *     - EVENT_ID
 *     - ...
 *   recordFields:
 *     - RECORD_ID
 *     - ...
 *   attributeFields:
 *     - EVENT_ID
 *     - ...
 */
public class FeedDefinition {

    private String feedName;
    private String moduleName;
    private String gaiFeedId;
    private String sourceSystemCsi;
    private String sourceSystemName;
    private String perspectiveName;
    private String delimiter;
    private String frequency;
    private Boolean monthly;
    private String nullDefault;
    private Map<String, String> dateColumns;
    private List<String> cobDateColumns;
    private List<String> eventFields;
    private List<String> recordFields;
    private List<String> attributeFields;

    // ── feedName ─────────────────────────────────────────────────────────────

    public String getFeedName() { return feedName; }
    public void setFeedName(String feedName) { this.feedName = feedName; }

    // ── moduleName ────────────────────────────────────────────────────────────

    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }

    // ── gaiFeedId — auto-derives from moduleName if not set ──────────────────
    // e.g. moduleName=STRESSEXP → gaiFeedId=SCEF-STRESSEXP

    public String getGaiFeedId() {
        if (gaiFeedId != null && gaiFeedId.trim().length() > 0) return gaiFeedId.trim();
        if (moduleName != null && moduleName.trim().length() > 0) return "SCEF-" + moduleName.trim().toUpperCase();
        return "SCEF-" + feedName.toUpperCase().replace('-', '_');
    }
    public void setGaiFeedId(String gaiFeedId) { this.gaiFeedId = gaiFeedId; }

    // ── source system ─────────────────────────────────────────────────────────

    public String getSourceSystemCsi() { return sourceSystemCsi; }
    public void setSourceSystemCsi(String v) { this.sourceSystemCsi = v; }

    public String getSourceSystemName() { return sourceSystemName; }
    public void setSourceSystemName(String v) { this.sourceSystemName = v; }

    public String getPerspectiveName() { return perspectiveName; }
    public void setPerspectiveName(String v) { this.perspectiveName = v; }

    // ── delimiter — defaults to pipe ─────────────────────────────────────────

    public String getDelimiter() {
        return (delimiter == null || delimiter.length() == 0) ? "|" : delimiter;
    }
    public void setDelimiter(String delimiter) { this.delimiter = delimiter; }

    // ── frequency — DLY or MNTH, auto-derived if not set ────────────────────

    public String getFrequency() {
        if (frequency != null && frequency.trim().length() > 0) return frequency.trim().toUpperCase();
        return isMonthly() ? "MNTH" : "DLY";
    }
    public void setFrequency(String frequency) { this.frequency = frequency; }

    // ── monthly — auto-detects pse-month-end if not set ─────────────────────

    public boolean isMonthly() {
        if (monthly != null) return monthly.booleanValue();
        return (feedName != null && feedName.toLowerCase().contains("month-end"))
            || (moduleName != null && "PSEME".equalsIgnoreCase(moduleName));
    }
    public void setMonthly(boolean monthly) { this.monthly = monthly; }

    // ── nullDefault — written in place of null column values ─────────────────

    public String getNullDefault() { return nullDefault; }
    public void setNullDefault(String nullDefault) { this.nullDefault = nullDefault; }

    // ── date formatting ───────────────────────────────────────────────────────

    public Map<String, String> getDateColumns() { return dateColumns; }
    public void setDateColumns(Map<String, String> dateColumns) { this.dateColumns = dateColumns; }

    public List<String> getCobDateColumns() { return cobDateColumns; }
    public void setCobDateColumns(List<String> cobDateColumns) { this.cobDateColumns = cobDateColumns; }

    /** Returns true if this column should be filled with the job's cobDate value */
    public boolean isCobDateColumn(String column) {
        if (column == null || cobDateColumns == null) return false;
        for (String c : cobDateColumns) {
            if (column.equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    /** Returns the SimpleDateFormat pattern for this column, or null if none configured */
    public String getDatePattern(String column) {
        if (column == null || dateColumns == null) return null;
        for (Map.Entry<String, String> e : dateColumns.entrySet()) {
            if (column.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    // ── field lists ───────────────────────────────────────────────────────────

    public List<String> getEventFields() { return eventFields; }
    public void setEventFields(List<String> eventFields) { this.eventFields = eventFields; }

    public List<String> getRecordFields() { return recordFields; }
    public void setRecordFields(List<String> recordFields) { this.recordFields = recordFields; }

    public List<String> getAttributeFields() { return attributeFields; }
    public void setAttributeFields(List<String> attributeFields) { this.attributeFields = attributeFields; }

    /**
     * Returns the column list for the given file type.
     * @param fileType "EVENT", "RECORD", or "ATTRIBUTE" (case-insensitive)
     */
    public List<String> getColumnsForType(String fileType) {
        if (fileType == null) return Collections.emptyList();
        String t = fileType.trim().toUpperCase();
        if ("EVENT".equals(t))     return safe(eventFields);
        if ("RECORD".equals(t))    return safe(recordFields);
        if ("ATTRIBUTE".equals(t)) return safe(attributeFields);
        return Collections.emptyList();
    }

    private List<String> safe(List<String> v) {
        return v == null ? Collections.<String>emptyList() : new ArrayList<String>(v);
    }
}
