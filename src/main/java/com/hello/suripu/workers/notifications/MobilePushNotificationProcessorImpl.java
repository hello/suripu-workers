package com.hello.suripu.workers.notifications;

import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.model.EndpointDisabledException;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sns.model.PublishResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hello.suripu.core.db.AccountDAO;
import com.hello.suripu.core.db.AppStatsDAO;
import com.hello.suripu.core.db.TimeZoneHistoryDAO;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.models.Account;
import com.hello.suripu.core.models.MobilePushRegistration;
import com.hello.suripu.core.models.TimeZoneHistory;
import com.hello.suripu.core.notifications.HelloPushMessage;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessor;
import com.hello.suripu.core.notifications.NotificationSubscriptionDAOWrapper;
import com.hello.suripu.core.notifications.NotificationSubscriptionsDAO;
import com.hello.suripu.core.notifications.PushNotificationEvent;
import com.hello.suripu.core.notifications.PushNotificationEventType;
import com.hello.suripu.core.notifications.settings.NotificationSetting;
import com.hello.suripu.core.notifications.settings.NotificationSettingsDAO;
import com.hello.suripu.workers.WorkerFeatureFlipper;
import com.librato.rollout.RolloutClient;
import com.segment.analytics.Analytics;
import com.segment.analytics.messages.MessageBuilder;
import com.segment.analytics.messages.TrackMessage;
import com.vdurmont.semver4j.Semver;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.DateTimeZone;
import org.joda.time.Minutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by jakepiccolo on 5/19/16.
 */
public class MobilePushNotificationProcessorImpl implements MobilePushNotificationProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(MobilePushNotificationProcessorImpl.class);

    private final static String SEGMENT_TRACKNAME = "Push Notifications";
    private final static String DEFAULT_APP_VERSION = "9999.9999.9999";
    private final ObjectMapper mapper;

    private final PushNotificationEventDynamoDB pushNotificationEventDynamoDB;
    private final RolloutClient featureFlipper;
    private final AppStatsDAO appStatsDAO;
    private final NotificationSettingsDAO settingsDAO;
    private final TimeZoneHistoryDAO timeZoneHistoryDAO;
    private final NotificationSubscriptionDAOWrapper wrapper;
    private final Analytics analytics;
    private final Set<Integer> activeHours;
    private final AccountDAO accountDAO;
    private final Map<MobilePushRegistration.OS, String> minAppVersions;

    private MobilePushNotificationProcessorImpl(final PushNotificationEventDynamoDB pushNotificationEventDynamoDB,
                                                final ObjectMapper mapper,
                                                final RolloutClient featureFlipper,
                                                final AppStatsDAO appStatsDAO,
                                                final NotificationSettingsDAO settingsDAO,
                                                final TimeZoneHistoryDAO timeZoneHistoryDAO,
                                                final NotificationSubscriptionDAOWrapper wrapper,
                                                final Analytics analytics,
                                                final Set<Integer> activeHours,
                                                final AccountDAO accountDAO,
                                                final Map<MobilePushRegistration.OS, String> minAppVersions) {
        this.pushNotificationEventDynamoDB = pushNotificationEventDynamoDB;
        this.mapper = mapper;
        this.featureFlipper = featureFlipper;
        this.appStatsDAO = appStatsDAO;
        this.settingsDAO = settingsDAO;
        this.timeZoneHistoryDAO = timeZoneHistoryDAO;
        this.wrapper = wrapper;
        this.analytics = analytics;
        this.activeHours = activeHours;
        this.accountDAO = accountDAO;
        this.minAppVersions = minAppVersions;
    }

    @Override
    public void push(final PushNotificationEvent event) {

        final Optional<TimeZoneHistory> timeZoneHistoryOptional = timeZoneHistoryDAO.getCurrentTimeZone(event.accountId);
        if(!timeZoneHistoryOptional.isPresent()) {
            LOGGER.error("error=missing-timezone-history account_id={}", event.accountId);
            return;
        }

        final DateTimeZone tz = DateTimeZone.forID(timeZoneHistoryOptional.get().timeZoneId);

        if(PushNotificationEventType.SLEEP_SCORE.equals(event.type)) {
            final boolean enabled = settingsDAO.isOn(event.accountId, NotificationSetting.Type.SLEEP_SCORE);
            if(!enabled) {
                LOGGER.info("account_id={} setting={} enabled={}", event.accountId, NotificationSetting.Type.SLEEP_SCORE, enabled);
                return;
            }

            if(featureFlipper.userFeatureActive(FeatureFlipper.APP_LAST_SEEN_PUSH_ENABLED, event.accountId, Collections.EMPTY_LIST)) {
                final Optional<DateTime> lastViewed = appStatsDAO.getQuestionsLastViewed(event.accountId);
                if (lastViewed.isPresent()) {

                    final DateTimeZone dateTimeZone = DateTimeZone.forID(timeZoneHistoryOptional.get().timeZoneId);
                    final DateTime lastViewedLocalTime = new DateTime(lastViewed.get(), dateTimeZone).withTimeAtStartOfDay();
                    final DateTime nowLocalTime = DateTime.now().withTimeAtStartOfDay();
                    final int minutes = Minutes.minutesBetween(nowLocalTime, lastViewedLocalTime).getMinutes();
                    if (minutes > 0) {
                        LOGGER.warn("action=skip-push-notification status=app-opened account_id={} last_seen={}", event.accountId, lastViewedLocalTime);
                        return;
                    }
                }
            }
        } else if(PushNotificationEventType.PILL_BATTERY.equals(event.type)) {
            final boolean enabled = settingsDAO.isOn(event.accountId, NotificationSetting.Type.SYSTEM);
            if(!enabled) {
                LOGGER.info("account_id={} setting={} enabled={}", event.accountId, NotificationSetting.Type.SYSTEM, enabled);
                return;
            }

            if(featureFlipper.userFeatureActive(WorkerFeatureFlipper.PUSH_SYSTEM_ALERT_ENABLED, event.accountId, Collections.EMPTY_LIST)) {
                LOGGER.debug("status=push-ff-system-alert-disabled account_id={}", event.accountId);
                return;
            }

            final DateTime nowLocal = new DateTime(event.timestamp.getMillis(), tz);
            int hourOfDay = nowLocal.getHourOfDay();
            if(!activeHours.contains(hourOfDay)) {
                LOGGER.info("action=disable-non-active-hours account_id={} hour={}", event.accountId, nowLocal.getHourOfDay());
                return;
            }

            final Optional<Account> accountOptional = accountDAO.getById(event.accountId);
            if(accountOptional.isPresent() && accountOptional.get().getAgeInDays() <= 14) {
                LOGGER.warn("action=push-disable-account-too-young account_id={} age={}", event.accountId, accountOptional.get().getAgeInDays());
                return;
            }
        }




        final PushNotificationEvent eventWithTz = new PushNotificationEvent.Builder(event).withTimeZone(tz).build();
        // We often want at-most-once delivery of push notifications, so we insert the record to DDB first.
        // That way if something later in this method fails, we won't accidentally send the same notification twice.
        final boolean successfullyInserted = pushNotificationEventDynamoDB.insert(eventWithTz);
        if (!successfullyInserted) {
            LOGGER.warn("action=duplicate-push-notification account_id={} type={}", event.accountId, event.type);
            return;
        }

        final Long accountId = event.accountId;
        final HelloPushMessage pushMessage = event.helloPushMessage;

        final List<MobilePushRegistration> registrations = wrapper.dao().getMostRecentSubscriptions(accountId, 5);
        LOGGER.info("action=list-registrations account_id={} num_subscriptions={}", event.accountId, registrations.size());

        final List<MobilePushRegistration> toDelete = Lists.newArrayList();

        for (final MobilePushRegistration reg : registrations) {
            final String minAppVersion = minAppVersions.getOrDefault(MobilePushRegistration.OS.fromString(reg.os), DEFAULT_APP_VERSION);
            final Semver registrationAppVersion = new Semver(reg.appVersion);

            if(registrationAppVersion.isLowerThan(minAppVersion)) {
                LOGGER.debug("action=skip-send account_id={} os={} app_version={} min_app_version={}", event.accountId, reg.os, reg.appVersion, minAppVersion);
                toDelete.add(reg);
                continue;
            }

            if(reg.endpoint.isPresent()) {
                final MobilePushRegistration.OS os = MobilePushRegistration.OS.fromString(reg.os);
                final Optional<String> message = makeMessage(os, pushMessage);
                if(!message.isPresent()) {
                    LOGGER.warn("warn=failed-to-generate-message os={} push_message={}", os, pushMessage);
                    continue;
                }

                final PublishRequest pr = new PublishRequest();
                pr.setMessageStructure("json");
                pr.setMessage(message.get());
                pr.setTargetArn(reg.endpoint.get());

                try {
                    final PublishResult result = wrapper.sns().publish(pr);
                    LOGGER.info("account_id={} message_id={} os={} version={}", event.accountId, result.getMessageId(), reg.os, reg.appVersion);

                    final Map<String, String> tags = ImmutableMap.of(
                            "type",event.type.shortName(),
                            "app_version", reg.appVersion,
                            "platform", reg.os,
                            "version", reg.version
                    );

                    final MessageBuilder mb = TrackMessage.builder(SEGMENT_TRACKNAME)
                            .userId(String.valueOf(reg.accountId.or(0L)))
                            .properties(tags)
                            .timestamp(DateTime.now(DateTimeZone.UTC).toDate());

                    analytics.enqueue(mb);
                } catch (final EndpointDisabledException endpointDisabled) {
                    toDelete.add(reg);
                }
                catch (Exception e) {
                    LOGGER.error("error=failed-sending-sns-message message={}", e.getMessage());
                }


            }
        }

        for(final MobilePushRegistration registration : toDelete) {
            LOGGER.info("action=delete-by-device-token account_id={} device_token={} os={}", registration.accountId.or(0L), registration.deviceToken, registration.os);
            wrapper.dao().deleteByDeviceToken(registration.deviceToken);
        }
    }

    private Optional<String> makeAPNSMessage(final HelloPushMessage message) {
        final Map<String, String> messageMap = new HashMap<>();
        final Map<String, String> content = new HashMap<>();
        final Map<String, Object> appleMessageMap = new HashMap<>();
        final Map<String, Object> appMessageMap = new HashMap<>();

        content.put("body", message.body);

        appMessageMap.put("alert", content);
        appMessageMap.put("sound", "default");

        appleMessageMap.put("aps", appMessageMap);

        // Hello custom keys
        // https://developer.apple.com/library/content/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CreatingtheNotificationPayload.html#//apple_ref/doc/uid/TP40008194-CH10-SW1
        appleMessageMap.put("hlo-type", message.target);
        appleMessageMap.put("hlo-detail", message.details);

        try {
            final String jsonString = mapper.writeValueAsString(appleMessageMap);

            messageMap.put("APNS", jsonString);
            return Optional.of(mapper.writeValueAsString(messageMap));

        } catch (JsonProcessingException e) {
            LOGGER.error("Failed serializing to JSON: {}", e.getMessage());
        }

        return Optional.absent();
    }

    private Optional<String> makeAndroidMessage(final HelloPushMessage message) {
        final Map<String, String> messageMap = new HashMap<>();
        final Map<String, String> content = new HashMap<>();
        final Map<String, Object> appMessageMap = new HashMap<>();

        content.put("hlo_title", "Sense");
        content.put("hlo_body", message.body);
        content.put("hlo_type", message.target);
        content.put("hlo_detail", message.details);
        appMessageMap.put("time_to_live", DateTimeConstants.SECONDS_PER_HOUR * 23);
        appMessageMap.put("data", content);

//        appMessageMap.put("collapse_key", "Welcome");
//        appMessageMap.put("delay_while_idle", true);
//        appMessageMap.put("dry_run", false);

        try {
            final String jsonString = mapper.writeValueAsString(appMessageMap);

            messageMap.put("GCM", jsonString);
            return Optional.of(mapper.writeValueAsString(messageMap));

        } catch (JsonProcessingException e) {
            LOGGER.error("Failed serializing to JSON: {}", e.getMessage());
        }

        return Optional.absent();
    }

    private Optional<String> makeMessage(final MobilePushRegistration.OS os, final HelloPushMessage message) {
        switch(os) {
            case ANDROID:
                return makeAndroidMessage(message);
            case IOS:
                return makeAPNSMessage(message);
        }
        return Optional.absent();
    }

    public static class Builder {

        private AmazonSNS sns;
        private NotificationSubscriptionsDAO subscriptionDAO;
        private PushNotificationEventDynamoDB pushNotificationEventDynamoDB;
        private ObjectMapper mapper = new ObjectMapper();
        private RolloutClient featureFlipper;
        private AppStatsDAO appStatsDAO;
        private NotificationSettingsDAO settingsDAO;
        private TimeZoneHistoryDAO timeZoneHistoryDAO;
        private Map<String, String> arns = Maps.newHashMap();
        private Analytics analytics;
        private Set<Integer> activeHours = Sets.newHashSet();
        private AccountDAO accountDAO;
        private Map<MobilePushRegistration.OS, String> minAppVersions = Maps.newHashMap();

        public Builder withSns(final AmazonSNS sns) {
            this.sns = sns;
            return this;
        }

        public Builder withSubscriptionDAO(final NotificationSubscriptionsDAO subscriptionDAO) {
            this.subscriptionDAO = subscriptionDAO;
            return this;
        }

        public Builder withPushNotificationEventDynamoDB(final PushNotificationEventDynamoDB pushNotificationEventDynamoDB) {
            this.pushNotificationEventDynamoDB = pushNotificationEventDynamoDB;
            return this;
        }

        public Builder withMapper(final ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public Builder withFeatureFlipper(final RolloutClient featureFlipper) {
            this.featureFlipper = featureFlipper;
            return this;
        }

        public Builder withAppStatsDAO(final AppStatsDAO appStatsDAO) {
            this.appStatsDAO = appStatsDAO;
            return this;
        }

        public Builder withSettingsDAO(final NotificationSettingsDAO settingsDAO) {
            this.settingsDAO = settingsDAO;
            return this;
        }

        public Builder withTimeZoneHistory(final TimeZoneHistoryDAO timeZoneHistoryDAO) {
            this.timeZoneHistoryDAO = timeZoneHistoryDAO;
            return this;
        }

        public Builder withArns(Map<String,String> arns) {
            this.arns.putAll(arns);
            return this;
        }

        public Builder withAnalytics(final Analytics analytics) {
            this.analytics = analytics;
            return this;
        }

        public Builder withActiveHours(@NotNull final Set<Integer> activeHours) {
            this.activeHours.addAll(activeHours);
            return this;
        }


        public Builder withAccountDAO(final AccountDAO accountDAO) {
            this.accountDAO = accountDAO;
            return this;
        }

        public Builder withMinAppVersions(@NotNull final Map<MobilePushRegistration.OS, String> minAppVersions) {
            this.minAppVersions.putAll(minAppVersions);
            return this;
        }

        public MobilePushNotificationProcessor build() {
            checkNotNull(sns, "sns can not be null");
            checkNotNull(subscriptionDAO, "subscription can not be null");
            checkNotNull(pushNotificationEventDynamoDB, "pushNotificationEventDynamoDB can not be null");
            checkNotNull(featureFlipper, "featureFlipper can not be null");
            checkNotNull(appStatsDAO, "appStatsDAO can not be null");
            checkNotNull(settingsDAO, "settingsDAO can not be null");
            checkNotNull(analytics, "analytics can not be null");
            checkNotNull(accountDAO, "account dao can not be null");

            final NotificationSubscriptionDAOWrapper wrapper = NotificationSubscriptionDAOWrapper.create(subscriptionDAO, sns, arns);
            return new MobilePushNotificationProcessorImpl(pushNotificationEventDynamoDB, mapper,
                    featureFlipper, appStatsDAO, settingsDAO, timeZoneHistoryDAO, wrapper, analytics, activeHours, accountDAO, minAppVersions);
        }

    }
}
