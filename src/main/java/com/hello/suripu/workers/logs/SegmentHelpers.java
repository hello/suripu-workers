package com.hello.suripu.workers.logs;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.segment.analytics.messages.MessageBuilder;
import com.segment.analytics.messages.TrackMessage;

import java.util.List;
import java.util.Set;

public class SegmentHelpers {

    public final static String ALARM_RING_EVENT = "alarm:ring";
    private final static String WAVE_EVENT = "gesture:wave";


    /**
     * Creates MessageBuilders to be sent to Segment
     * @param deviceEvents
     * @param pairedAccounts
     * @param accountsWhoseAlarmRang
     * @return
     */
    public static List<MessageBuilder> tag(final DeviceEvents deviceEvents, Set<Long> pairedAccounts, final Set<Long> accountsWhoseAlarmRang) {

        final List<MessageBuilder> messages = Lists.newArrayList();

        final ImmutableMap.Builder traitsBuilder = ImmutableMap.builder();
        for(final String event : deviceEvents.events) {
            if(WAVE_EVENT.equals(event)) {
                traitsBuilder.put("gesture", "wave");
            }
        }

        // Only create segment messages if we have something in traits
        final ImmutableMap traits = traitsBuilder.build();
        if(!traits.isEmpty()) {
            for (final Long accountId : pairedAccounts) {
                final MessageBuilder mb = TrackMessage.builder("gesture").properties(traits)
                        .userId(String.valueOf(accountId))
                        .timestamp(deviceEvents.createdAt.toDate());
                messages.add(mb);
            }
        }

        if(deviceEvents.events.contains(ALARM_RING_EVENT)) {
            // TODO: MAKE THIS CONFIGURABLE
            final ImmutableMap.Builder alarmTag = ImmutableMap.builder();
            alarmTag.put("alarm", "ring");
            for(Long userId : accountsWhoseAlarmRang) {
                final MessageBuilder mb = TrackMessage.builder("alarm")
                        .userId(String.valueOf(userId))
                        .properties(alarmTag.build())
                        .timestamp(deviceEvents.createdAt.toDate());
                messages.add(mb);
            }
        }

        return messages;
    }
}
