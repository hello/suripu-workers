package com.hello.suripu.workers.logs.triggers;

import com.hello.suripu.core.metrics.DeviceEvents;

public interface Publisher {
    void publish(Long accoundId, DeviceEvents deviceEvents, Integer offsetMillis);
}
