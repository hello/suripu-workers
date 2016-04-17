package com.hello.suripu.workers.logs;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.segment.analytics.messages.MessageBuilder;
import com.segment.analytics.messages.TrackMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class SegmentHelpers {

    private final static Logger LOGGER = LoggerFactory.getLogger(SegmentHelpers.class);

    public final static String ALARM_RING_EVENT = "alarm:ring";
    public final static String ALARM_DISMISSED_EVENT = "alarm:dismissed";
    public final static String WAVE_EVENT = "gesture:wave";

    private final static String GESTURE_TRACK_NAME = "SenseGesture";
    private final static String ALARM_TRACK_NAME = "SenseAlarm";

    /**
     * Creates MessageBuilders to be sent to Segment
     * @param deviceEvents
     * @param pairedAccounts
     * @param accountsWhoseAlarmRang
     * @return
     */
    public static List<MessageBuilder> tag(
            final DeviceEvents deviceEvents,
            final Set<Long> pairedAccounts,
            final Set<Long> accountsWhoseAlarmRang,
            final Boolean tagAll) {

        final List<MessageBuilder> alarmMessages = tagAlarms(deviceEvents, accountsWhoseAlarmRang);


        final List<MessageBuilder> messages = Lists.newArrayList();
        messages.addAll(alarmMessages);

        // TO AVOID SPAMMING SEGMENT
        if(tagAll) {
            final List<MessageBuilder> gestureMessages = tagGestures(deviceEvents, pairedAccounts);
            messages.addAll(gestureMessages);
        }

        return messages;
    }


    /**
     * tag gesture events only
     * @param deviceEvents
     * @param pairedAccounts
     * @return
     */
    public static List<MessageBuilder> tagGestures(final DeviceEvents deviceEvents, final Set<Long> pairedAccounts) {

        final List<MessageBuilder> messages = Lists.newArrayList();
        final ImmutableMap.Builder traitsBuilder = ImmutableMap.builder();
        for(final String event : deviceEvents.events) {
            if(WAVE_EVENT.equals(event)) {
                traitsBuilder.put("gesture", "wave");
            }
        }

        // Only create segment messages if we have something in traits
        final ImmutableMap traits = traitsBuilder.build();
        if(traits.isEmpty()) {
            return messages;
        }

        for (final Long accountId : pairedAccounts) {
            final MessageBuilder mb = TrackMessage.builder(GESTURE_TRACK_NAME)
                    .properties(traits)
                    .userId(String.valueOf(accountId))
                    .timestamp(deviceEvents.createdAt.toDate());
            messages.add(mb);
        }

        return messages;
    }

    /**
     * Tag alarm events only
     * @param deviceEvents
     * @param accountsWhoseAlarmRang
     * @return
     */
    public static List<MessageBuilder> tagAlarms(final DeviceEvents deviceEvents, final Set<Long> accountsWhoseAlarmRang) {
        final List<MessageBuilder> messages = Lists.newArrayList();


        if(accountsWhoseAlarmRang.isEmpty()) {
            LOGGER.warn("action=tag-alarms message=empty-account-list");
            return messages;
        }

        final ImmutableMap.Builder alarmTagsBuilder = ImmutableMap.builder();

        if(deviceEvents.events.contains(ALARM_RING_EVENT)) {
            // TODO: MAKE THIS CONFIGURABLE
            alarmTagsBuilder.put("alarm", "ring");
        }

        if(deviceEvents.events.contains(ALARM_DISMISSED_EVENT)) {
            // TODO: MAKE THIS CONFIGURABLE
            alarmTagsBuilder.put("alarm", "dismissed");
        }


        final ImmutableMap alarmTags = alarmTagsBuilder.build();

        if(alarmTags.isEmpty()) {
            LOGGER.info("action=tag-alarms sense_id={} events={}", deviceEvents.deviceId, Joiner.on(",").join(deviceEvents.events));
            LOGGER.warn("action=tag-alarms message=no-alarm-tags");
            return messages;
        }

        for(final Long userId : accountsWhoseAlarmRang) {
            final MessageBuilder mb = TrackMessage.builder(ALARM_TRACK_NAME)
                    .userId(String.valueOf(userId))
                    .properties(alarmTags)
                    .timestamp(deviceEvents.createdAt.toDate());
            messages.add(mb);
        }

        return messages;
    }
}
