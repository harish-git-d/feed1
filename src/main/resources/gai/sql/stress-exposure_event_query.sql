-- SCEF GAI Feed: stress-exposure EVENT
-- Source: ADMCEF.V_SCEF_REQUEST_TABLEAU
-- E1 EVENT_ID must match A1 in ATTRIBUTE file

SELECT DISTINCT
    TRIM(t.request_id)                                         AS EVENT_ID,              -- E1
    'Manual'                                                   AS ADJUSTMENT_METHOD,     -- E2
    'Overwrite Missing or Inaccurate Data'                     AS ADJUSTMENT_TYPE,       -- E3
    'Risk User Adjustment - Stress Exposure - Trade'           AS REASON_CODE,           -- E4
    TRIM('NA')                                                 AS EVENT_TITLE,           -- E5
    TRIM('NA')                                                 AS EVENT_DESCRIPTION,     -- E6
    TRIM(t.comments)                                           AS EVENT_COMMENTS,        -- E7
    'Closed'                                                   AS EVENT_STATUS,          -- E8
    '161534'                                                   AS ADJUSTING_SYSTEM,      -- E9
    TRIM('NA')                                                 AS SYSTEM_TYPE,           -- E10
    TRIM('NA')                                                 AS RELATED_ADJUSTMENTS,   -- E11
    TRIM('NA')                                                 AS RESOLUTION_TARGET_DATE,-- E12
    TRIM('NA')                                                 AS STRATEGIC_FIX_OWNER,   -- E13
    TRIM('NA')                                                 AS STRATEGIC_FIX_COMMENT, -- E14
    TRIM('NA')                                                 AS DCRM_ID,               -- E15
    TRIM('NA')                                                 AS REPORT_NAME,           -- E16
    TRIM('NA')                                                 AS EUC_ID,                -- E17
    TRIM('NA')                                                 AS PROJECT_ID,            -- E18
    TRIM('NA')                                                 AS INC_NUMBER,            -- E19
    'Daily'                                                    AS FREQUENCY,             -- E21
    TRIM(t.REQUEST_CREATOR)                                    AS REQUESTOR_SOEID,       -- E22
    TO_CHAR(CAST(t.enter_time AS DATE), 'MMDDYYYY')            AS COB_DATE,              -- E23
    TO_CHAR(CAST(t.enter_time AS DATE), 'MMDDYYYY')            AS EVENT_DATE_AND_TIME    -- E24
FROM ADMCEF.V_SCEF_REQUEST_TABLEAU t
WHERE t.request_type = 'NSE Override'
  AND t.status       = 'Approved'
  AND TRUNC(t.enter_time) = TO_DATE(:cobDateYYYYMMDD, 'YYYYMMDD')
ORDER BY TRIM(t.request_id)
