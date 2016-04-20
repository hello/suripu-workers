package com.hello.suripu.workers.framework;

import org.joda.time.DateTime;

public interface WorkerLaunchHistoryDAO {

    void register(String applicationName, String hostname);
    void register(String applicationName, String hostname, DateTime when);
}
