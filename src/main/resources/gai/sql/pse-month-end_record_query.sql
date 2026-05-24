-- SCEF GAI Feed: pse-month-end RECORD
-- R2 RECORD_TYPE = 'F', R5 ORIGINAL_FEED_ID = 'SCEF'
-- TODO: confirm REQUEST_TYPE_DESC

SELECT DISTINCT
    r.REQUEST_ID                                               AS RECORD_ID,             -- R1
    'F'                                              AS RECORD_TYPE,           -- R2
    TRIM(r.CURRENCY_CD)                                        AS TRANSACTION_CCY,       -- R3
    '161534'                                                   AS ORIGINAL_CSI,          -- R4
    'SCEF'                                                     AS ORIGINAL_FEED_ID,      -- R5  CRITICAL
    TRIM(r.LV_CODE)                                            AS LVID,                  -- R6
    'Posted'                                                   AS ADJUSTMENT_POSTING_STATUS, -- R7
    TRIM('NA')                                                 AS PRODUCT_ATTRIBUTE1,    -- R8
    TRIM('NA')                                                 AS PRODUCT_ATTRIBUTE2,    -- R9
    TRIM('NA')                                                 AS ESP,                   -- R10
    TRIM('NA')                                                 AS EPT,                   -- R11
    TRIM(r.GFCID)                                              AS CUSTOMER_ID,           -- R12
    TRIM('NA')                                                 AS FIRM_ACCOUNT_MNEMONIC, -- R13
    TRIM('NA')                                                 AS FIT_CODE,              -- R14
    TRIM('NA')                                                 AS INSTRUMENT_CUSIP_ID,   -- R15
    TRIM('NA')                                                 AS ONBALANCE_CCY,         -- R16
    TRIM('NA')                                                 AS ONBALANCE_USD,         -- R17
    TRIM('NA')                                                 AS OFFBALANCE_CCY,        -- R18
    TRIM('NA')                                                 AS OFFBALANCE_USD,        -- R19
    TRIM('NA')                                                 AS PNL_CCY,               -- R20
    TRIM('NA')                                                 AS PNL_USD,               -- R21
    TRIM('NA')                                                 AS NOTIONAL_CCY,          -- R22
    TRIM('NA')                                                 AS NOTIONAL_USD,          -- R23
    TRIM('NA')                                                 AS SUPPLEMENTAL_CCY,      -- R24
    TRIM('NA')                                                 AS SUPPLEMENTAL_USD,      -- R25
    TRIM('NA')                                                 AS UIPID,                 -- R26
    TRIM(r.UITID)                                              AS UITID,                 -- R27
    TO_CHAR(CAST(r.COB_DATE AS DATE), 'MMDDYYYY')              AS COB_DATE               -- R28
FROM ADMCEF.SCEF_REQUEST r
JOIN ADMCEF.SCEF_REQUEST_TYPE rt ON r.REQUEST_TYPE = rt.REQUEST_TYPE
WHERE rt.REQUEST_TYPE_DESC = 'TODO_CONFIRM'
  AND r.STATUS = 2
  AND TRUNC(r.COB_DATE) = TO_DATE(:cobDateYYYYMMDD, 'YYYYMMDD')
ORDER BY r.REQUEST_ID
