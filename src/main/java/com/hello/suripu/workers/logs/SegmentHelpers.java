package com.hello.suripu.workers.logs;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.segment.analytics.messages.MessageBuilder;
import com.segment.analytics.messages.TrackMessage;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SegmentHelpers {

    public final static String ALARM_RING_EVENT = "alarm:ring";
    public final static String ALARM_DISMISSED_EVENT = "alarm:dismissed";
    public final static String WAVE_EVENT = "gesture:wave";

    private final static String GESTURE_TRACK_NAME = "SenseGesture";
    private final static String ALARM_RING_TRACK_NAME = "SenseAlarmRing";
    private final static String ALARM_DISMISS_TRACK_NAME = "SenseAlarmDismiss";

    private final static Logger LOGGER = LoggerFactory.getLogger(SegmentHelpers.class);

    /**
     * Creates MessageBuilders to be sent to Segment
     * @param deviceEvents
     * @param accountsWhoseAlarmRang
     * @param externalIdsPairing
     * @return
     */
    public static List<MessageBuilder> tag(
            final DeviceEvents deviceEvents,
            final Set<Long> accountsWhoseAlarmRang,
            final Map<Long, String> externalIdsPairing,
            final Boolean tagAll) {

        final List<MessageBuilder> messages = Lists.newArrayList();

        final Set<String> alarmExternalIds = Sets.newHashSet();
        for(final Long accountId : accountsWhoseAlarmRang) {
            if(externalIdsPairing.containsKey(accountId)) {
                alarmExternalIds.add(externalIdsPairing.get(accountId));
            }
        }

        if(deviceEvents.events.contains(ALARM_RING_EVENT)) {


            // TODO: MAKE THIS CONFIGURABLE
            final List<MessageBuilder> ringMessages = tag(ImmutableMap.of("alarm","ring"), ALARM_RING_TRACK_NAME, alarmExternalIds, deviceEvents.createdAt);
            messages.addAll(ringMessages);
            LOGGER.info("action=tag-alarm-ring segment_count={} sense_id={} events={}", ringMessages.size(), deviceEvents.deviceId, Joiner.on(",").join(deviceEvents.events));
        }

        if(deviceEvents.events.contains(ALARM_DISMISSED_EVENT)) {
            // TODO: MAKE THIS CONFIGURABLE
            final List<MessageBuilder> ringMessages = tag(ImmutableMap.of("alarm","dismissed"), ALARM_DISMISS_TRACK_NAME, alarmExternalIds, deviceEvents.createdAt);
            messages.addAll(ringMessages);
            LOGGER.info("action=tag-alarm-dismiss segment_count={} sense_id={} events={}", ringMessages.size(), deviceEvents.deviceId, Joiner.on(",").join(deviceEvents.events));
        }

        // TO AVOID SPAMMING SEGMENT
        if(tagAll) {
            if(deviceEvents.events.contains(WAVE_EVENT)) {
                final List<MessageBuilder> gestureMessages = tag(ImmutableMap.of("gesture","wave"), GESTURE_TRACK_NAME, externalIdsPairing.values(), deviceEvents.createdAt);
                messages.addAll(gestureMessages);
                LOGGER.info("action=tag-gesture segment_count={} sense_id={} events={}", gestureMessages.size(), deviceEvents.deviceId, Joiner.on(",").join(deviceEvents.events));
            }
        }

        return messages;
    }

    /**
     * Generic method to tag segment events
     * @param tags
     * @param trackName
     * @param externalAccountIds
     * @param createdAt
     * @return
     */
    public static List<MessageBuilder> tag(final ImmutableMap<String,String> tags, final String trackName, final Collection<String> externalAccountIds, final DateTime createdAt) {
        final List<MessageBuilder> messages = Lists.newArrayList();
        for(final String externalAccountId : externalAccountIds) {
            final MessageBuilder mb = TrackMessage.builder(trackName)
                    .userId(externalAccountId)
                    .properties(tags)
                    .timestamp(createdAt.toDate());
            messages.add(mb);
        }
        return messages;
    }
}