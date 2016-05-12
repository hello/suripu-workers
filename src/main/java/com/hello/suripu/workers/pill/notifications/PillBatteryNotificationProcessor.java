package com.hello.suripu.workers.pill.notifications;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.responses.Response;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.notifications.HelloPushMessage;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessor;
import com.hello.suripu.core.notifications.PushNotificationEvent;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Created by jakepiccolo on 5/12/16.
 *
 * Class for sending mobile push notifications when a user's sleep pill battery is low.
 */
public class PillBatteryNotificationProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(PillBatteryNotificationProcessor.class);

    private final static String NOTIFICATION_TYPE = "pillBattery";

    private final PillBatteryNotificationConfig pillBatteryNotificationConfig;
    private final MobilePushNotificationProcessor mobilePushNotificationProcessor;
    private final Meter batteryNotificationsSent;

    protected PillBatteryNotificationProcessor(final PillBatteryNotificationConfig pillBatteryNotificationConfig,
                                               final MobilePushNotificationProcessor mobilePushNotificationProcessor,
                                               final Meter batteryNotificationsSent)
    {
        this.pillBatteryNotificationConfig = pillBatteryNotificationConfig;
        this.mobilePushNotificationProcessor = mobilePushNotificationProcessor;
        this.batteryNotificationsSent = batteryNotificationsSent;
    }

    public static PillBatteryNotificationProcessor create(final PillBatteryNotificationConfig pillBatteryNotificationConfig,
                                                          final MobilePushNotificationProcessor mobilePushNotificationProcessor,
                                                          final MetricRegistry metricRegistry)
    {
        final Meter batteryNotificationsSent = metricRegistry.meter(name(PillBatteryNotificationProcessor.class, "battery-notifications-sent"));
        return new PillBatteryNotificationProcessor(pillBatteryNotificationConfig, mobilePushNotificationProcessor, batteryNotificationsSent);
    }

    /**
     * Sends a "Low Pill Battery" push notification, if the user should receive one according to the rules in this object's
     * {@link PillBatteryNotificationConfig}. Returns true if notification send was attempted. This does not guarantee that
     * the notification was sent or delivered.
     *
     * Note that this method is not thread-safe with regard to sending multiple notifications to the same user. Therefore,
     * callers should serialize this method on a single thread to ensure at-most-once notification delivery.
     *
     * However, calling this method serially within the {@link PillBatteryNotificationConfig}'s daysBetweenNotifications
     * window will result in at-most-once delivery.
     *
     * @param pillId - external Pill Id
     * @param userInfo - UserInfo associated with the pill
     * @param batteryLevel - Current pill battery level
     * @param nowUTC - Current DateTime with UTC time zone.
     * @return True if the notification was attempted, else false.
     */
    public boolean sendLowBatteryNotification(final String pillId, final UserInfo userInfo, final int batteryLevel, final DateTime nowUTC) {
        if (!nowUTC.getZone().equals(DateTimeZone.UTC)) {
            throw new IllegalArgumentException("nowUTC needs to have UTC time zone.");
        }

        if ((batteryLevel < pillBatteryNotificationConfig.getBatteryNotificationPercentageThreshold())
                && userInfo.timeZone.isPresent())
        {
            final DateTimeZone userTimeZone = userInfo.timeZone.get();
            final long accountId = userInfo.accountId;

            if (shouldSendLowBatteryNotification(accountId, nowUTC, userTimeZone)) {
                LOGGER.info("battery_notification_status=sending account_id={} battery_level={}",
                        accountId, batteryLevel);

                final PushNotificationEvent event = PushNotificationEvent.newBuilder()
                        .withAccountId(accountId)
                        .withType(NOTIFICATION_TYPE)
                        .withTimestamp(nowUTC)
                        // TODO figure out what body/target/details to use
                        .withHelloPushMessage(new HelloPushMessage("body", "target", "details"))
                        .build();
                mobilePushNotificationProcessor.push(event);
                batteryNotificationsSent.mark();
                return true;
            } else {
                LOGGER.debug("battery_notification_status=not_sent account_id={} battery_level={}",
                        accountId, batteryLevel);
            }
        }
        return false;
    }

    private boolean shouldSendLowBatteryNotification(final Long accountId, final DateTime nowUTC, final DateTimeZone userTimeZone) {
        final DateTime nowLocalTime = new DateTime(nowUTC, userTimeZone);
        final Integer minHour = pillBatteryNotificationConfig.getMinHourOfDay();
        final Integer maxHour = pillBatteryNotificationConfig.getMaxHourOfDay();
        final Integer daysBetweenNotifications = pillBatteryNotificationConfig.getDaysBetweenNotifications();
        if (nowLocalTime.hourOfDay().get() < minHour || nowLocalTime.hourOfDay().get() > maxHour) {
            LOGGER.debug("battery_notification_status=not_sent reason=wrong_time_of_day local_hour={} account_id={}",
                    nowLocalTime.hourOfDay().get(), accountId);
            return false;
        }

        final Response<List<PushNotificationEvent>> sentNotificationsResponse = mobilePushNotificationProcessor.
                getPushNotificationEventDynamoDB()
                .query(accountId, nowUTC.minusDays(daysBetweenNotifications), nowUTC.plusMinutes(10), NOTIFICATION_TYPE);

        // If we couldn't successfully query, don't send a notification, just to be safe.
        // Also only send a notification if we haven't sent one in within a specified time.
        return (sentNotificationsResponse.status == Response.Status.SUCCESS) && sentNotificationsResponse.data.isEmpty();
    }
}
