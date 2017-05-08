package com.hello.suripu.workers.logs.triggers;

import com.hello.suripu.core.util.DateTimeUtil;
import org.joda.time.DateTime;

public class TimeHelpers {

    public static String lastNight(final DateTime createdAt, final int offsetMillis) {
        return DateTimeUtil.dateToYmdString(createdAt.plusMillis(offsetMillis).minusDays(1));
    }
}
