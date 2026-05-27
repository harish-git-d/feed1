package com.citi.risk.scef.limitexposure.gai;

/**
 * Dedicated exception for GAI feed failures.
 * Replaces generic RuntimeException throws to satisfy SonarQube S112.
 */
public class GAIFeedException extends RuntimeException {

    public GAIFeedException(String message) {
        super(message);
    }

    public GAIFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
