package com.hello.suripu.workers.expansions;

import is.hello.gaibu.homeauto.models.AlarmActionStatus;

public interface Alerter {

    void alert(String senseId, AlarmActionStatus status);
}
