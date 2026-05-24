-- SCEF GAI Feed: stress-exposure RECORD
-- Source: ADMCEF.V_SCEF_REQUEST_TABLEAU
-- R1 RECORD_ID = contract_key — must match A2 RECORD_ID in ATTRIBUTE file
-- R2 RECORD_TYPE = 'T' (Trade)
-- R5 ORIGINAL_FEED_ID = 'SCEF' (source system name)

SELECT DISTINCT
    TRIM(t.contract_key)                                       AS RECORD_ID,             -- R1
    'T'                                                        AS RECORD_TYPE,           -- R2  Trade
    'USD'                                                      AS TRANSACTION_CCY,       -- R3
    '161534'                                                   AS ORIGINAL_CSI,          -- R4
    'SCEF'                                                     AS ORIGINAL_FEED_ID,      -- R5  CRITICAL
    TRIM(t.uitid)                                              AS LVID,                  -- R6
    'Posted'                                                   AS ADJUSTMENT_POSTING_STATUS, -- R7
    TRIM('NA')                                                 AS PRODUCT_ATTRIBUTE1,    -- R8
    TRIM('NA')                                                 AS PRODUCT_ATTRIBUTE2,    -- R9
    TRIM('NA')                                                 AS ESP,                   -- R10
    TRIM('NA')                                                 AS EPT,                   -- R11
    TRIM(t.gfcid)                                              AS CUSTOMER_ID,           -- R12
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
    TRIM(t.uitid)                                              AS UITID,                 -- R27
    TO_CHAR(CAST(t.enter_time AS DATE), 'MMDDYYYY')            AS COB_DATE               -- R28
FROM ADMCEF.V_SCEF_REQUEST_TABLEAU t
WHERE t.request_type = 'NSE Override'
  AND t.status       = 'Approved'
  AND TRUNC(t.enter_time) = TO_DATE(:cobDateYYYYMMDD, 'YYYYMMDD')
ORDER BY TRIM(t.contract_key)
