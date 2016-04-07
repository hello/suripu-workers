package com.hello.suripu.workers.logs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import com.librato.rollout.RolloutClient;
import com.segment.analytics.Analytics;
import com.segment.analytics.messages.MessageBuilder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SenseStructuredLogIndexer implements LogIndexer<LoggingProtos.BatchLogMessage> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStructuredLogIndexer.class);

    private final SenseEventsDAO senseEventsDAO;
    private final Analytics analytics;
    private final List<DeviceEvents> deviceEventsList;
    private final RingTimeHistoryReadDAO ringHistoryDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final RolloutClient flipper;

    public SenseStructuredLogIndexer(
            final SenseEventsDAO senseEventsDAO,
            final Analytics analytics,
            final RingTimeHistoryReadDAO ringHistoryDDB,
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final RolloutClient rolloutClient) {
        this.senseEventsDAO = senseEventsDAO;
        this.deviceEventsList = Lists.newArrayList();
        this.analytics = analytics;
        this.ringHistoryDAO = ringHistoryDDB;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.flipper = rolloutClient;
    }


    @Override
    public Integer index() {
        final Integer count = senseEventsDAO.write(ImmutableList.copyOf(deviceEventsList));
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

        final Set<String> decoded = new HashSet<>();
        final Pattern pattern = Pattern.compile("(\\w+:{1}\\w+)");
        final Matcher matcher = pattern.matcher(text.replace(" ", ""));
        while (matcher.find()) {
            decoded.add(matcher.group());
        }
        return decoded;
    }

    @Override
    public void collect(final LoggingProtos.BatchLogMessage batchLogMessage) {
        for(final LoggingProtos.LogMessage logMessage : batchLogMessage.getMessagesList()) {
            LOGGER.info(logMessage.getDeviceId());
            if(!logMessage.getDeviceId().equals("8AF6441AF72321F4")) {
                continue;
            }
            final Set<String> events = decode(logMessage.getMessage());
            final DateTime createdAt = new DateTime(logMessage.getTs() * 1000L, DateTimeZone.UTC);
            final DeviceEvents deviceEvents = new DeviceEvents(logMessage.getDeviceId(), createdAt, events);
            deviceEventsList.add(deviceEvents);
        }


        // WARNING
        for(final DeviceEvents deviceEvents : deviceEventsList) {
            if(!flipper.deviceFeatureActive("segment", deviceEvents.deviceId, Collections.EMPTY_LIST)) {
                continue;
            }

            final Set<Long> pairedAccounts = pairedAccounts(deviceEvents);
            final Set<Long> alarmAccounts = Sets.newHashSet();
            if (hasAlarm(deviceEvents.events)) {
                alarmAccounts.addAll(queryAlarmAround(deviceEvents, pairedAccounts, 5));
            }
            final List<MessageBuilder> analyticsMessageBuilders = SegmentHelpers.tag(deviceEvents,pairedAccounts, alarmAccounts);
            for(MessageBuilder mb : analyticsMessageBuilders) {
                analytics.enqueue(mb); // This should be non-blocking
            }
        }
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
        for(final Long accountId : accountIds) {
            final List<RingTime> alarms = ringHistoryDAO.getRingTimesBetween(
                    deviceEvents.deviceId,
                    accountId,
                    deviceEvents.createdAt.minusMinutes(withinNumMinutes),
                    deviceEvents.createdAt);
            if(!alarms.isEmpty()) {
                accountsWhoseAlarmRang.add(accountId);
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
        return  events.contains(SegmentHelpers.ALARM_RING_EVENT);
    }



}
