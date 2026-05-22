package com.citi.risk.scef.limitexposure.gai.job;

/**
 * GAI feed job for pse-exposure.
 * Registered in CRCRBatch-context-temp.xml as a DefaultStep bean.
 */
public class GAIPseExposureJob extends AbstractGAIFeedJob {
    @Override
    protected String getFeedName() { return "pse-exposure"; }
}
