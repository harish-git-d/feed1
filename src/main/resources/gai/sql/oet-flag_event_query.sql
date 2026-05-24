-- SCEF GAI Feed: oet-flag EVENT — Full field list E1-E24
-- TODO: confirm REQUEST_TYPE_DESC

SELECT DISTINCT
    r.REQUEST_ID                                               AS EVENT_ID,              -- E1
    'Manual'                                                   AS ADJUSTMENT_METHOD,     -- E2
    'Overwrite Missing or Inaccurate Data'                     AS ADJUSTMENT_TYPE,       -- E3
    'Risk User Adjustment - OET Flag - Trade'                                                AS REASON_CODE,           -- E4
    TRIM('NA')                                                 AS EVENT_TITLE,           -- E5
    TRIM('NA')                                                 AS EVENT_DESCRIPTION,     -- E6
    TRIM(r.COMMENTS)                                           AS EVENT_COMMENTS,        -- E7
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
    'Daily'                                                  AS FREQUENCY,             -- E21
    TRIM(r.REQUEST_CREATOR)                                    AS REQUESTOR_SOEID,       -- E22
    r.COB_DATE              AS COB_DATE,              -- E23
    r.ENTER_TIME            AS EVENT_DATE_AND_TIME    -- E24
FROM ADMCEF.SCEF_REQUEST r
JOIN ADMCEF.SCEF_REQUEST_TYPE rt ON r.REQUEST_TYPE = rt.REQUEST_TYPE
WHERE rt.REQUEST_TYPE_DESC = 'TODO_CONFIRM'
  AND r.STATUS = 2
  AND TRUNC(r.COB_DATE) = TO_DATE(:cobDateYYYYMMDD, 'YYYYMMDD')
ORDER BY r.REQUEST_ID
