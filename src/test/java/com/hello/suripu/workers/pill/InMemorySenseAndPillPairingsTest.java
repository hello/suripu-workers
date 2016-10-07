package com.hello.suripu.workers.pill;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.models.UserInfo;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InMemorySenseAndPillPairingsTest {

    @Test
    public void testEmptyList() {
        final InMemorySenseAndPillPairings pairings = new InMemorySenseAndPillPairings();
        pairings.add("sense_one", Lists.newArrayList());
        assertFalse("pill paired to sense", pairings.paired("sense_one", "pill_one"));
    }

    @Test
    public void testEmptyUserInfo() {
        final InMemorySenseAndPillPairings pairings = new InMemorySenseAndPillPairings();
        final String senseId = "sense_one";
        final List<UserInfo> userInfoList = Lists.newArrayList(
                UserInfo.createEmpty(senseId, 12L)
        );
        pairings.add(senseId, userInfoList);

        assertFalse("pill paired to sense", pairings.paired(senseId, "pill_one"));
    }

    @Test
    public void testUserInfo() {
        final InMemorySenseAndPillPairings pairings = new InMemorySenseAndPillPairings();
        final String senseId = "sense_one";
        final String pillId = "pill_one";
        final OutputProtos.SyncResponse.PillSettings pillOne = OutputProtos.SyncResponse.PillSettings
                .newBuilder()
                .setPillId(pillId)
                .build();

        final List<UserInfo> userInfoList = Lists.newArrayList(
                new UserInfo(
                        senseId,
                        888L,
                        new ArrayList<>(),
                        Optional.absent(),
                        Optional.of(DateTimeZone.forID("America/Los_Angeles")),
                        Optional.of(pillOne),
                        System.currentTimeMillis()
                )
        );
        pairings.add(senseId, userInfoList);
        assertTrue("pill paired to sense", pairings.paired(senseId, pillId));
        assertTrue("has timezone", pairings.timezone(pillId).isPresent());
    }
}
