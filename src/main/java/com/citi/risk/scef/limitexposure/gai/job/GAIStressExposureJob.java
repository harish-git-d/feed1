package com.citi.risk.scef.limitexposure.gai.job;

/**
 * GAI feed job for stress-exposure.
 * Registered in CRCRBatch-context-temp.xml as a DefaultStep bean.
 */
public class GAIStressExposureJob extends AbstractGAIFeedJob {
    @Override
    protected String getFeedName() { return "stress-exposure"; }
}
