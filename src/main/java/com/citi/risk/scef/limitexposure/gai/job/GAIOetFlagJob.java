package com.citi.risk.scef.limitexposure.gai.job;

/** GAI feed job for oet-flag. Registered in CRCRBatch-context-temp.xml as a DefaultStep bean. */
public class GAIOetFlagJob extends AbstractGAIFeedJob {
    @Override
    protected String getFeedName() { return "oet-flag"; }
}
