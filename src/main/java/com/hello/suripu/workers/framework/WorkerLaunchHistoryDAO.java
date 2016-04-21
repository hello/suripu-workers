package com.hello.suripu.workers.framework;

import org.joda.time.DateTime;

public interface WorkerLaunchHistoryDAO {

    /**
     * Registers the launch time for a given worker applicationName + hostname combo
     * @param applicationName
     * @param hostname
     */
    void register(String applicationName, String hostname);


    /**
     * Registers the launch time for a given worker applicationName + hostname combo
     * and lets you specify when it happens.
     * @param applicationName
     * @param hostname
     * @param when
     */
    void register(String applicationName, String hostname, DateTime when);
}
