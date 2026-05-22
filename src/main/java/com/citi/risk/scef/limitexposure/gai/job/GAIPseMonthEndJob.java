package com.citi.risk.scef.limitexposure.gai.job;

/** GAI feed job for pse-month-end. Registered in CRCRBatch-context-temp.xml as a DefaultStep bean. */
public class GAIPseMonthEndJob extends AbstractGAIFeedJob {
    @Override
    protected String getFeedName() { return "pse-month-end"; }
}
