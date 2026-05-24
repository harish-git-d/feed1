-- SCEF GAI Feed: swwr-recovery ATTRIBUTE
-- A1 EVENT_ID  = REQUEST_ID  → matches EVENT file
-- A2 RECORD_ID = REQUEST_ID  → matches RECORD file (RECORD uses REQUEST_ID)
-- TODO: confirm REQUEST_TYPE_DESC

SELECT
    r.REQUEST_ID                                               AS EVENT_ID,              -- A1  matches EVENT
    r.REQUEST_ID                                               AS RECORD_ID,             -- A2  matches RECORD
    r.ADJUSTMENT_ID                                            AS ATTRIBUTE_ADJUSTMENT_ID, -- A3
    'SWWR Recovery'                                             AS ATTRIBUTE_NAME,        -- A4
    TRIM('NA')                                                 AS ATTRIBUTE_DESCRIPTION, -- A5
    TRIM(r.ORIGINAL_FLAG)                                                 AS OLD_VALUE,             -- A6
    TO_CHAR(r.RECOVERY_RATE)                                                 AS NEW_VALUE,             -- A7
    TO_CHAR(CAST(r.LAST_UPDATE_TIME AS DATE), 'MMDDYYYY')      AS EFFECTIVE_DATE,        -- A8
    TO_CHAR(CAST(r.ENTER_TIME AS DATE), 'MMDDYYYY')            AS POSTED_DATE,           -- A9
    COALESCE(TRIM(r.CREDIT_OFFICER), TRIM(r.REQUEST_CREATOR)) AS CHECKER_SOEID,         -- A10  not blank
    TRIM(r.REQUEST_CREATOR)                                    AS MAKER_SOEID,           -- A11
    TRIM('NA')                                                 AS ACCOUNTING_METHODOLOGY,-- A12
    TRIM('NA')                                                 AS FDL_ACCOUNT,           -- A13
    TRIM('NA')                                                 AS GOC,                   -- A14
    TRIM('NA')                                                 AS GL_ACCOUNT1,           -- A15
    TRIM('NA')                                                 AS GL_ACCOUNT2,           -- A16
    TRIM('NA')                                                 AS SUB_REASON_CODE,       -- A17
    'Recurring'                                                AS RECURRENCE,            -- A18
    TRIM('NA')                                                 AS STANDARD_ACCOUNT,      -- A19
    TRIM('NA')                                                 AS FRS_BU,                -- A20
    TRIM('NA')                                                 AS AFFILIATE_BU,          -- A21
    TRIM('NA')                                                 AS REVERSAL_FLAG,         -- A22
    TRIM('NA')                                                 AS BALANCE_TYPE,          -- A23
    TRIM(r.COMMENTS)                                           AS ADJUSTMENT_COMMENT,    -- A24
    'Y'                                                        AS CDE_FLAG,              -- A25
    TRIM('NA')                                                 AS UDF1,                  -- A26
    TRIM('NA')                                                 AS UDF2,                  -- A27
    TRIM('NA')                                                 AS UDF3,                  -- A28
    TRIM('NA')                                                 AS UDF4,                  -- A29
    TRIM('NA')                                                 AS UDF5,                  -- A30
    TO_CHAR(CAST(
        COALESCE(r.COB_DATE, r.ENTER_TIME) AS DATE), 'MMDDYYYY') AS COB_DATE,           -- A31  not blank
    TRIM('NA')                                                 AS REPORT_ID,             -- A32
    'Posted'                                                   AS ATTRIBUTE_POSTING_STATUS -- A33
FROM ADMCEF.SCEF_REQUEST r
JOIN ADMCEF.SCEF_REQUEST_TYPE rt ON r.REQUEST_TYPE = rt.REQUEST_TYPE
WHERE rt.REQUEST_TYPE_DESC = 'TODO_CONFIRM'
  AND r.STATUS = 2
  AND TRUNC(r.COB_DATE) = TO_DATE(:cobDateYYYYMMDD, 'YYYYMMDD')
ORDER BY r.REQUEST_ID
