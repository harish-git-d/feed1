package com.citi.risk.scef.limitexposure.gai.job;

/** GAI feed job for swwr-flag. Registered in CRCRBatch-context-temp.xml as a DefaultStep bean. */
public class GAISwwrFlagJob extends AbstractGAIFeedJob {
    @Override
    protected String getFeedName() { return "swwr-flag"; }
}
