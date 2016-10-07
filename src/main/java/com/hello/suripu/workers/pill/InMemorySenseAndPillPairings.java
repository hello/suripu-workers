package com.hello.suripu.workers.pill;

import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.hello.suripu.core.models.UserInfo;
import org.joda.time.DateTimeZone;

import java.util.List;
import java.util.Map;

public class InMemorySenseAndPillPairings {

    final private Map<String, DateTimeZone> pillIds = Maps.newHashMap();
    final private ArrayListMultimap<String, String> senseToPills = ArrayListMultimap.create();

    public void add(final String senseId, final List<UserInfo> userInfos) {
        for(final UserInfo userInfo : userInfos) {
            if(userInfo.pillColor.isPresent() && userInfo.timeZone.isPresent()) {
                final String pillId = userInfo.pillColor.get().getPillId();
                pillIds.put(pillId, userInfo.timeZone.get());
                senseToPills.put(senseId, pillId);
            }
        }
    }

    public Optional<DateTimeZone> timezone(final String pillId) {
        return Optional.fromNullable(pillIds.get(pillId));
    }

    public boolean paired(final String senseId, final String pillId) {
        final List<String> pills = senseToPills.get(senseId);
        if(pillIds.isEmpty()) {
            return false;
        }

        for(final String pill : pills) {
            if(pill.equals(pillId)) {
                return true;
            }
        }

        return false;
    }
}
