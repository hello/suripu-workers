package com.hello.suripu.workers.notifications;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.responses.Response;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.notifications.HelloPushMessage;
import com.hello.suripu.core.notifications.PushNotificationEvent;
import com.hello.suripu.core.preferences.AccountPreferencesDynamoDB;
import com.hello.suripu.core.preferences.PreferenceName;
import com.hello.suripu.workers.WorkerFeatureFlipper;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import com.hello.suripu.workers.protobuf.notifications.PushNotification;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public class PushNotificationsProcessor extends HelloBaseRecordProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(PushNotificationsProcessor.class);

    private final MobilePushNotificationProcessor mobilePushNotificationProcessor;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final AccountPreferencesDynamoDB accountPreferencesDynamoDB;
    private final PushNotificationsWorkerConfiguration pushNotificationsWorkerConfiguration;

    public PushNotificationsProcessor(
            final MobilePushNotificationProcessor mobilePushNotificationProcessor,
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final AccountPreferencesDynamoDB accountPreferencesDynamoDB,
            final PushNotificationsWorkerConfiguration pushNotificationsWorkerConfiguration)
    {
        this.mobilePushNotificationProcessor = mobilePushNotificationProcessor;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.accountPreferencesDynamoDB = accountPreferencesDynamoDB;
        this.pushNotificationsWorkerConfiguration =  pushNotificationsWorkerConfiguration;
    }


    private enum NotificationType {
        PILL_BATTERY
    }


    @Override
    public void initialize(String s) {

    }


    private PushNotification.UserPushNotification parseProtobuf(final Record record) throws InvalidProtocolBufferException {
        final PushNotification.UserPushNotification pushNotification = PushNotification.UserPushNotification.parseFrom(record.getData().array());
        if (!pushNotification.hasAccountId()) {
            throw new IllegalArgumentException("Protobuf must contain accountId");
        }
        if (!pushNotification.hasSenseId()) {
            throw new IllegalArgumentException("Protobuf must contain senseId");
        }
        return pushNotification;
    }


    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {

        for(final Record record : records) {
            try {
                final PushNotification.UserPushNotification pushNotification = parseProtobuf(record);
                sendNotification(pushNotification);
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed parsing protobuf: {}", e.getMessage());
                LOGGER.error("Moving to next record");
                continue;
            } catch (Exception e) {
                LOGGER.error("{}", e.getMessage());
            }
        }

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("error=InvalidStateException exception={}", e);
        } catch (ShutdownException e) {
            LOGGER.error("error=ShutdownException exception={}", e);
        }
    }

    private void sendNotification(final PushNotification.UserPushNotification userPushNotification) {
        if (!userPushNotification.hasAccountId() || !userPushNotification.hasSenseId()) {
            LOGGER.warn("warning=require_sense_id_and_account_id account_id={} sense_id={}",
                    userPushNotification.getAccountId(), userPushNotification.getSenseId());
            return;
        }

        final Long accountId = userPushNotification.getAccountId();
        final String senseId = userPushNotification.getSenseId();
        final Optional<UserInfo> userInfoOptional = mergedUserInfoDynamoDB.getInfo(senseId, accountId);

        if(!accountPreferencesDynamoDB.isEnabled(accountId, PreferenceName.PUSH_ALERT_CONDITIONS)) {
            LOGGER.debug("push_notification_status=disabled account_id={}", accountId);
            return;
        }

        if (!userInfoOptional.isPresent() || !userInfoOptional.get().timeZone.isPresent()) {
            LOGGER.error("error=could_not_get_timezone account_id={} sense_id={}", accountId, senseId);
            return;
        }

        final boolean pushNotificationsEnabled = flipper.userFeatureActive(
                WorkerFeatureFlipper.PUSH_NOTIFICATIONS_ENABLED, accountId, Collections.<String>emptyList());
        if (!pushNotificationsEnabled) {
            LOGGER.debug("push_notifications_enabled=false account_id={}", accountId);
            return;
        }

        LOGGER.info("push_notifications_enabled=true account_id={}", accountId);

        final DateTimeZone userTimeZone = userInfoOptional.get().timeZone.get();
        final DateTime nowUserTime = DateTime.now(userTimeZone);
        final DateTime nowUTC = new DateTime(nowUserTime, DateTimeZone.UTC);

        PushNotificationEvent pushNotificationEvent = null;

        if (userPushNotification.hasPillBatteryLow()) {
            final NotificationConfig pillBatteryConfig = pushNotificationsWorkerConfiguration.getPillBatteryConfig();
            if (shouldSendNotification(accountId, nowUserTime, pillBatteryConfig, NotificationType.PILL_BATTERY)) {
                pushNotificationEvent = PushNotificationEvent.newBuilder()
                        .withAccountId(accountId)
                        .withType(NotificationType.PILL_BATTERY.name())
                        .withTimestamp(nowUTC)
                        // TODO figure out what body/target/details to use
                        .withHelloPushMessage(new HelloPushMessage("body", "target", "details"))
                        .build();
            }
        }
        // TODO rest of the push notifications

        if (pushNotificationEvent != null) {
            mobilePushNotificationProcessor.push(pushNotificationEvent);
        }
    }

    protected boolean shouldSendNotification(final Long accountId,
                                             final DateTime nowUserLocalTime,
                                             final NotificationConfig notificationConfig,
                                             final NotificationType notificationType)
    {
        final Integer minHour = notificationConfig.getMinHourOfDay();
        final Integer maxHour = notificationConfig.getMaxHourOfDay();
        final Integer daysBetweenNotifications = notificationConfig.getDaysBetweenNotifications();
        final DateTime nowUTC = new DateTime(nowUserLocalTime, DateTimeZone.UTC);

        // Ensure we send within the correct window
        if (nowUserLocalTime.hourOfDay().get() < minHour || nowUserLocalTime.hourOfDay().get() > maxHour) {
            LOGGER.debug("battery_notification_status=not_sent reason=wrong_time_of_day local_hour={} account_id={}",
                    nowUserLocalTime.hourOfDay().get(), accountId);
            return false;
        }

        final Response<List<PushNotificationEvent>> sentNotificationsResponse = mobilePushNotificationProcessor.
                getPushNotificationEventDynamoDB()
                .query(accountId, nowUTC.minusDays(daysBetweenNotifications), nowUTC.plusMinutes(10), notificationType.name());

        // If we couldn't successfully query, don't send a notification, just to be safe.
        // Also only send a notification if we haven't sent one within a specified time.
        return (sentNotificationsResponse.status == Response.Status.SUCCESS) && sentNotificationsResponse.data.isEmpty();
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {

    }
}
