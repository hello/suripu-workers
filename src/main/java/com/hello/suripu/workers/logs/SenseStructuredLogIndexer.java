package com.hello.suripu.workers.logs;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.db.AccountDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.hello.suripu.core.models.Account;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.workers.WorkerFeatureFlipper;
import com.hello.suripu.workers.logs.triggers.Publisher;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SenseStructuredLogIndexer implements LogIndexer<LoggingProtos.BatchLogMessage> {

    @Inject
    RolloutClient flipper;

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStructuredLogIndexer.class);

    private final SenseEventsDAO senseEventsDAO;
    private final AccountDAO accountDAO;
    private final Analytics analytics;
    private final List<DeviceEvents> deviceEventsList;
    private final RingTimeHistoryReadDAO ringHistoryDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final static Pattern PATTERN = Pattern.compile("(\\w+:{1}\\w+)");
    private final Publisher publisher;

    public SenseStructuredLogIndexer(
            final SenseEventsDAO senseEventsDAO,
            final Analytics analytics,
            final RingTimeHistoryReadDAO ringHistoryDDB,
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final Publisher publisher,
            final AccountDAO accountDAO) {
        this.senseEventsDAO = senseEventsDAO;
        this.deviceEventsList = Lists.newArrayList();
        this.analytics = analytics;
        this.ringHistoryDAO = ringHistoryDDB;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.publisher = publisher;
        this.accountDAO = accountDAO;
        ObjectGraphRoot.getInstance().inject(this);
    }


    @Override
    public Integer index() {
        final ImmutableList<DeviceEvents> events = ImmutableList.copyOf(deviceEventsList);
        final Integer count = senseEventsDAO.write(events);
        this.sendToSegment(events);
        this.sendToQueue(events);
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


    private void sendToQueue(final List<DeviceEvents> deviceEventsList) {

        for(final DeviceEvents deviceEvents : deviceEventsList) {

            if(!hasAlarmDismissed(deviceEvents.events)) {
                continue;
            }
            LOGGER.info("action=dismiss-alarm sense_id={}", deviceEvents.deviceId);

            final Set<AccountIdWithOffset> accountIds = queryAlarmAround(deviceEvents, pairedAccounts(deviceEvents), 10);
            for(final AccountIdWithOffset account : accountIds) {

                if(!flipper.userFeatureActive(FeatureFlipper.PUSH_NOTIFICATIONS_ENABLED, account.accountId, Collections.EMPTY_LIST)) {
                    continue;
                }

                publisher.publish(account.accountId, deviceEvents, account.offsetMillis);
            }
        }
    }

    private void sendToSegment(final List<DeviceEvents> deviceEventsList) {
        for(final DeviceEvents deviceEvents : deviceEventsList) {

            if(!flipper.deviceFeatureActive(WorkerFeatureFlipper.SEND_TO_SEGMENT, deviceEvents.deviceId, Collections.EMPTY_LIST)) {
                continue;
            }

            final Set<AccountIdWithOffset> pairedAccounts = pairedAccounts(deviceEvents);
            final Set<Long> pairedAccountIds = pairedAccounts.stream().map(a -> a.accountId).collect(Collectors.toSet());
            final Map<Long, String> externalIds = pairedAccountExternalIds(pairedAccountIds);
            final Set<Long> alarmAccounts = Sets.newHashSetWithExpectedSize(pairedAccounts.size());

            // only query when we have an alarm event
            if (hasAlarm(deviceEvents.events)) {
                final Set<AccountIdWithOffset> accountsWithOffset = queryAlarmAround(deviceEvents, pairedAccounts, 5);
                for(final AccountIdWithOffset accountIdWithOffset : accountsWithOffset) {
                    alarmAccounts.add(accountIdWithOffset.accountId);
                }

            }


            final boolean trackAll = flipper.deviceFeatureActive(WorkerFeatureFlipper.SEND_TO_SEGMENT_WAVE, deviceEvents.deviceId, Collections.EMPTY_LIST);
            final List<MessageBuilder> analyticsMessageBuilders = SegmentHelpers.tag(deviceEvents, alarmAccounts, externalIds, trackAll);

            if(!analyticsMessageBuilders.isEmpty()) {
                LOGGER.info("action=send-to-segment count={} sense_id={}", analyticsMessageBuilders.size(), deviceEvents.deviceId);
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
    public Set<AccountIdWithOffset> queryAlarmAround(final DeviceEvents deviceEvents, final Set<AccountIdWithOffset> accountIds, final Integer withinNumMinutes) {
        final Set<AccountIdWithOffset> accountsWhoseAlarmRang = Sets.newHashSetWithExpectedSize(accountIds.size());
        final DateTime start = deviceEvents.createdAt.minusMinutes(withinNumMinutes);
        final DateTime end = deviceEvents.createdAt.plusMinutes(withinNumMinutes);
        for(final AccountIdWithOffset account : accountIds) {
            LOGGER.info("action=get-ring-times-between start={} end={} account_id={}", start, end, account.accountId);
            LOGGER.info("action=get-ring-times-between start_millis={} end_millis={} account_id={}", start.getMillis(), end.getMillis(), account.accountId);
            final List<RingTime> alarms = ringHistoryDAO.getRingTimesBetween(
                    deviceEvents.deviceId,
                    account.accountId,
                    start,
                    end);
            if(!alarms.isEmpty()) {
                accountsWhoseAlarmRang.add(account);
                LOGGER.info("action=get-ring-times-between num_results={} account_id={}", alarms.size(), account.accountId);
            }
        }

        return accountsWhoseAlarmRang;
    }


    private class AccountIdWithOffset {
        public final Long accountId;
        public final Integer offsetMillis;

        private AccountIdWithOffset(Long accountId, Integer offsetMillis) {
            this.accountId = accountId;
            this.offsetMillis = offsetMillis;
        }

        @Override
        public boolean equals(Object obj) {

            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) return false;
            final AccountIdWithOffset other = (AccountIdWithOffset) obj;
            return Objects.equals(this.accountId, other.accountId)
                    && Objects.equals(this.offsetMillis, other.offsetMillis);

        }

        @Override
        public int hashCode() {
            return Objects.hash(this.accountId, this.offsetMillis);
        }

    }
    /**
     * Grabs account ids from DDB for given deviceEvents
     * @param deviceEvents
     * @return
     */
    public  Set<AccountIdWithOffset> pairedAccounts(final DeviceEvents deviceEvents) {
        final Set<AccountIdWithOffset> pairedAccounts = Sets.newHashSet();
        final List<UserInfo> userInfos = mergedUserInfoDynamoDB.getInfo(deviceEvents.deviceId);
        for(final UserInfo userInfo : userInfos) {
            if(!userInfo.timeZone.isPresent()) {
                LOGGER.warn("warn=missing-timezone account_id={}", userInfo.accountId);
                continue;
            }
            final int offsetMillis = userInfo.timeZone.get().getOffset(deviceEvents.createdAt);
            pairedAccounts.add(new AccountIdWithOffset(userInfo.accountId, offsetMillis));
        }
        return pairedAccounts;
    }


    public Map<Long, String> pairedAccountExternalIds(final Set<Long> accountIds) {
        final Map<Long, String> pairedExternalIds = Maps.newHashMap();
        for(final Long accountId : accountIds) {
            final Optional<Account> accountOptional = accountDAO.getById(accountId);
            if(accountOptional.isPresent()) {
                pairedExternalIds.put(accountId, accountOptional.get().extId());
            }
        }

        return pairedExternalIds;
    }

    public static boolean hasAlarm(final Set<String> events) {
        return  events.contains(SegmentHelpers.ALARM_RING_EVENT) || events.contains(SegmentHelpers.ALARM_DISMISSED_EVENT);
    }

    public static boolean hasAlarmDismissed(final Set<String> events) {
        return events.contains(SegmentHelpers.ALARM_DISMISSED_EVENT);
    }
}
