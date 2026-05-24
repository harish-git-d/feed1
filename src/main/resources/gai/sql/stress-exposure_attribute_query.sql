-- SCEF GAI Feed: stress-exposure ATTRIBUTE
-- Source: ADMCEF.V_SCEF_REQUEST_TABLEAU
--
-- CROSS-REF RULES (critical):
--   A1 EVENT_ID        must match EVENT_ID  in EVENT  file → se.request_id
--   A2 RECORD_ID       must match RECORD_ID in RECORD file → se.contract_key
--   A3 ATTRIBUTE_ADJ_ID = unique identifier for this attribute row
--
-- MANDATORY FIELD RULES:
--   A10 CHECKER_SOEID  must not be blank → COALESCE with REQUEST_CREATOR
--   A31 COB_DATE       must not be blank → use enter_time if COB_DATE null

SELECT
    TRIM(se.request_id)                                        AS EVENT_ID,              -- A1  matches EVENT file
    TRIM(se.contract_key)                                      AS RECORD_ID,             -- A2  matches RECORD file
    TRIM(se.request_id) || '_' || TRIM(se.contract_key)        AS ATTRIBUTE_ADJUSTMENT_ID, -- A3 unique
    'Stress Exposure'                                          AS ATTRIBUTE_NAME,        -- A4
    'Stress Impact is increase in MTM under a stress condition' AS ATTRIBUTE_DESCRIPTION, -- A5
    se.ORIGINAL_STRESS_IMPACT                                  AS OLD_VALUE,             -- A6
    se.STRESS_IMPACT_OVERRIDE                                  AS NEW_VALUE,             -- A7
    TO_CHAR(CAST(se.last_update_time AS DATE), 'MMDDYYYY')     AS EFFECTIVE_DATE,        -- A8
    TO_CHAR(CAST(se.enter_time AS DATE), 'MMDDYYYY')           AS POSTED_DATE,           -- A9
    COALESCE(TRIM(se.CREDIT_OFFICER), TRIM(se.REQUEST_CREATOR)) AS CHECKER_SOEID,       -- A10  not blank
    TRIM(se.REQUEST_CREATOR)                                   AS MAKER_SOEID,           -- A11
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
    TRIM(se.comments)                                          AS ADJUSTMENT_COMMENT,    -- A24
    'Y'                                                        AS CDE_FLAG,              -- A25
    TRIM('NA')                                                 AS UDF1,                  -- A26
    TRIM('NA')                                                 AS UDF2,                  -- A27
    TRIM('NA')                                                 AS UDF3,                  -- A28
    TRIM('NA')                                                 AS UDF4,                  -- A29
    TRIM('NA')                                                 AS UDF5,                  -- A30
    TO_CHAR(CAST(
        COALESCE(se.COB_DATE, se.enter_time) AS DATE), 'MMDDYYYY') AS COB_DATE,          -- A31  not blank
    TRIM('NA')                                                 AS REPORT_ID,             -- A32
    'Posted'                                                   AS ATTRIBUTE_POSTING_STATUS -- A33
FROM ADMCEF.V_SCEF_REQUEST_TABLEAU se
WHERE se.STRESS_IMPACT_OVERRIDE IS NOT NULL
  AND TRUNC(se.enter_time) = TO_DATE(:cobDateYYYYMMDD, 'YYYYMMDD')
ORDER BY se.request_id, se.contract_key
