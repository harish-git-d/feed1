package com.citi.risk.scef.limitexposure.gai.domain;

import java.util.List;
import java.util.Map;

/**
 * Represents one GAI feed definition, loaded from:
 *   classpath:gai/feed-definitions/{feedName}.yml
 */
public class FeedDefinition {

    private String feedName;
    private String gaiFeedId;        // e.g. "SCEF-STRESSEXP" — used in filename
    private String perspectiveName;  // e.g. "Exposure Monitoring"
    private boolean monthly;         // true → MNTH, false → DLY

    /** Column names per file type: EVENT, RECORD, ATTRIBUTE */
    private Map<String, List<String>> columns;

    // ── Getters / Setters (SnakeYAML requires these) ─────────────────────────

    public String getFeedName()              { return feedName; }
    public void setFeedName(String v)        { feedName = v; }

    public String getGaiFeedId()             { return gaiFeedId; }
    public void setGaiFeedId(String v)       { gaiFeedId = v; }

    public String getPerspectiveName()       { return perspectiveName; }
    public void setPerspectiveName(String v) { perspectiveName = v; }

    public boolean isMonthly()               { return monthly; }
    public void setMonthly(boolean v)        { monthly = v; }

    public Map<String, List<String>> getColumns()           { return columns; }
    public void setColumns(Map<String, List<String>> v)     { columns = v; }

    public List<String> getColumnsForType(String fileType) {
        if (columns == null) return List.of();
        return columns.getOrDefault(fileType.toUpperCase(), List.of());
    }
}
