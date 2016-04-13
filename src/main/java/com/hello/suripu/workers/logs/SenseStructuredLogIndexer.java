package com.hello.suripu.workers.logs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.workers.WorkerFeatureFlipper;
import com.librato.rollout.RolloutClient;
import com.segment.analytics.Analytics;
import com.segment.analytics.messages.MessageBuilder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SenseStructuredLogIndexer implements LogIndexer<LoggingProtos.BatchLogMessage> {

    @Inject
    RolloutClient flipper;

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStructuredLogIndexer.class);

    private final SenseEventsDAO senseEventsDAO;
    private final Analytics analytics;
    private final List<DeviceEvents> deviceEventsList;
    private final RingTimeHistoryReadDAO ringHistoryDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final static Pattern PATTERN = Pattern.compile("(\\w+:{1}\\w+)");


    public SenseStructuredLogIndexer(
            final SenseEventsDAO senseEventsDAO,
            final Analytics analytics,
            final RingTimeHistoryReadDAO ringHistoryDDB,
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB) {
        this.senseEventsDAO = senseEventsDAO;
        this.deviceEventsList = Lists.newArrayList();
        this.analytics = analytics;
        this.ringHistoryDAO = ringHistoryDDB;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        ObjectGraphRoot.getInstance().inject(this);
    }


    @Override
    public Integer index() {
        final ImmutableList<DeviceEvents> events = ImmutableList.copyOf(deviceEventsList);
        final Integer count = senseEventsDAO.write(events);
        this.sendToSegment(events);
        deviceEventsList.clear();
        analytics.flush();
        return count;
    }

    @Override
    public void flush() {
        analytics.flush();
    }

    @Override
    public void shutdown() {
        analytics.shutdown();
    }

    public static Set<String> decode(final String text) {

        final Set<String> decoded = Sets.newHashSet();

        final Matcher matcher = PATTERN.matcher(text.replace(" ", ""));
        while (matcher.find()) {
            decoded.add(matcher.group());
        }
        return decoded;
    }

    @Override
    public void collect(final LoggingProtos.BatchLogMessage batchLogMessage) {
        for(final LoggingProtos.LogMessage logMessage : batchLogMessage.getMessagesList()) {
            final Set<String> events = decode(logMessage.getMessage());
            final DateTime createdAt = new DateTime(logMessage.getTs() * 1000L, DateTimeZone.UTC);
            final DeviceEvents deviceEvents = new DeviceEvents(logMessage.getDeviceId(), createdAt, events);
            deviceEventsList.add(deviceEvents);
        }
    }


    private void sendToSegment(final List<DeviceEvents> deviceEventsList) {
        for(final DeviceEvents deviceEvents : deviceEventsList) {

            if(!flipper.deviceFeatureActive(WorkerFeatureFlipper.SEND_TO_SEGMENT, deviceEvents.deviceId, Collections.EMPTY_LIST)) {
                continue;
            }

            final Set<Long> pairedAccounts = pairedAccounts(deviceEvents);
            final Set<Long> alarmAccounts = Sets.newHashSet();

            // only query when we have an alarm event
            if (hasAlarm(deviceEvents.events)) {
                alarmAccounts.addAll(queryAlarmAround(deviceEvents, pairedAccounts, 5));
            }

            final boolean trackAll = flipper.deviceFeatureActive(WorkerFeatureFlipper.SEND_TO_SEGMENT_WAVE, deviceEvents.deviceId, Collections.EMPTY_LIST);
            final List<MessageBuilder> analyticsMessageBuilders = SegmentHelpers.tag(deviceEvents, pairedAccounts, alarmAccounts, trackAll);

            if(!analyticsMessageBuilders.isEmpty()) {
                LOGGER.info("action=send-to-segment count={}", analyticsMessageBuilders.size());
            }

            for(MessageBuilder mb : analyticsMessageBuilders) {
                analytics.enqueue(mb); // This should be non-blocking
            }
        }
    }

    @Override
    public void collect(final LoggingProtos.BatchLogMessage batchLogMessage, final String sequenceNumber) {
        // pass through to help with debugging kinesis records
        collect(batchLogMessage);
    }


    /**
     * Returns the list of account ids for which an alarm rang within n minutes
     * @param deviceEvents
     * @param accountIds
     * @param withinNumMinutes
     * @return
     */
    public  Set<Long> queryAlarmAround(final DeviceEvents deviceEvents, final Set<Long> accountIds, final Integer withinNumMinutes) {
        final Set<Long> accountsWhoseAlarmRang = Sets.newHashSetWithExpectedSize(accountIds.size());
        final DateTime start = deviceEvents.createdAt.minusMinutes(withinNumMinutes);
        final DateTime end = deviceEvents.createdAt.plusMinutes(withinNumMinutes);
        for(final Long accountId : accountIds) {
            LOGGER.info("action=get-ring-times-between start={} end={} account_id={}", start, end, accountId);
            LOGGER.info("action=get-ring-times-between start_millis={} end_millis={} account_id={}", start.getMillis(), end.getMillis(), accountId);
            final List<RingTime> alarms = ringHistoryDAO.getRingTimesBetween(
                    deviceEvents.deviceId,
                    accountId,
                    start,
                    end);
            if(!alarms.isEmpty()) {
                accountsWhoseAlarmRang.add(accountId);
                LOGGER.info("action=get-ring-times-between num_results={} account_id={}", alarms.size(), accountId);
            }
        }

        return accountsWhoseAlarmRang;
    }

    /**
     * Grabs account ids from DDB for given deviceEvents
     * @param deviceEvents
     * @return
     */
    public  Set<Long> pairedAccounts(final DeviceEvents deviceEvents) {
        final Set<Long> pairedAccounts = Sets.newHashSet();
        final List<UserInfo> userInfos = mergedUserInfoDynamoDB.getInfo(deviceEvents.deviceId);
        for(final UserInfo userInfo : userInfos) {
            pairedAccounts.add(userInfo.accountId);
        }
        return pairedAccounts;
    }


    public static boolean hasAlarm(final Set<String> events) {
        return  events.contains(SegmentHelpers.ALARM_RING_EVENT) || events.contains(SegmentHelpers.ALARM_DISMISSED_EVENT);
    }
}
