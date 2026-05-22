package com.citi.risk.scef.limitexposure.gai.job;

/**
 * GAI feed job for swwr-recovery.
 * Registered in CRCRBatch-context-temp.xml as a DefaultStep bean.
 */
public class GAISwwrRecoveryJob extends AbstractGAIFeedJob {
    @Override
    protected String getFeedName() { return "swwr-recovery"; }
}
