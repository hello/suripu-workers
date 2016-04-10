package com.hello.suripu.workers.logs;

import com.google.common.collect.Sets;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.segment.analytics.messages.MessageBuilder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class SegmentHelpersTest {

    private DeviceEvents deviceEvents(final Set<String> events){
        return new DeviceEvents("FAKE", DateTime.now(DateTimeZone.UTC), events);
    }

    @Test
    public void testTagSingleUserAlarmEventButNoWithinRange() {
        final DeviceEvents deviceEvents = deviceEvents(Sets.newHashSet(SegmentHelpers.ALARM_RING_EVENT));

        final Set<Long> pairedAccounts = Sets.newHashSet(1L,2L);
        final Set<Long> withAlarm = Sets.newHashSet(); // simulates out of range for alarm query
        final List<MessageBuilder> mbs = SegmentHelpers.tag(deviceEvents, pairedAccounts, withAlarm, false);
        assertThat(mbs.isEmpty(), is(true));
    }

    @Test
    public void testTagSingleUserAlarmEvent() {
        final DeviceEvents deviceEvents = deviceEvents(Sets.newHashSet(SegmentHelpers.ALARM_RING_EVENT));

        final Set<Long> pairedAccounts = Sets.newHashSet(1L,2L);
        final Set<Long> withAlarm = Sets.newHashSet(1L); // simulates one account having alarm within range
        final List<MessageBuilder> mbs = SegmentHelpers.tag(deviceEvents, pairedAccounts, withAlarm, false);
        assertThat(mbs.size(), is(withAlarm.size()));
    }

    @Test
    public void testTagMultipleUsersAlarmEvent() {
        final DeviceEvents deviceEvents = deviceEvents(Sets.newHashSet(SegmentHelpers.ALARM_RING_EVENT));

        final Set<Long> pairedAccounts = Sets.newHashSet(1L,2L);
        final Set<Long> withAlarm = Sets.newHashSet(1L, 2L); // simulates two accounts having an alarm within range
        final List<MessageBuilder> mbs = SegmentHelpers.tag(deviceEvents, pairedAccounts, withAlarm, false);
        assertThat(mbs.size(), is(withAlarm.size()));
    }

    @Test
    public void testTagMultipleUsersWaveAndSingleUserAlarm() {
        final DeviceEvents deviceEvents = deviceEvents(Sets.newHashSet(SegmentHelpers.ALARM_RING_EVENT, SegmentHelpers.WAVE_EVENT));

        final Set<Long> pairedAccounts = Sets.newHashSet(1L,2L);
        final Set<Long> withAlarm = Sets.newHashSet(1L);
        final List<MessageBuilder> mbs = SegmentHelpers.tag(deviceEvents, pairedAccounts, withAlarm, true);
        assertThat(mbs.size(), is(withAlarm.size() + pairedAccounts.size()));
    }


}
