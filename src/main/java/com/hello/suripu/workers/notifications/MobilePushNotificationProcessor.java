package com.hello.suripu.workers.notifications;

import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.model.PublishRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.hello.suripu.core.models.MobilePushRegistration;
import com.hello.suripu.core.notifications.NotificationSubscriptionsReadDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by jakepiccolo on 5/19/16.
 */
class MobilePushNotificationProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(MobilePushNotificationProcessor.class);

    final ObjectMapper mapper;

    private final AmazonSNS sns;
    private final NotificationSubscriptionsReadDAO dao;
    private final PushNotificationEventDynamoDB pushNotificationEventDynamoDB;

    public MobilePushNotificationProcessor(final AmazonSNS sns, final NotificationSubscriptionsReadDAO dao,
                                           final PushNotificationEventDynamoDB pushNotificationEventDynamoDB,
                                           final ObjectMapper mapper) {
        this.sns = sns;
        this.dao = dao;
        this.pushNotificationEventDynamoDB = pushNotificationEventDynamoDB;
        this.mapper = mapper;
    }

    public static MobilePushNotificationProcessor create(final AmazonSNS sns, final NotificationSubscriptionsReadDAO dao,
                                                         final PushNotificationEventDynamoDB pushNotificationEventDynamoDB) {
        return new MobilePushNotificationProcessor(sns, dao, pushNotificationEventDynamoDB, new ObjectMapper());
    }

    public static MobilePushNotificationProcessor create(final AmazonSNS sns, final NotificationSubscriptionsReadDAO dao,
                                                         final PushNotificationEventDynamoDB pushNotificationEventDynamoDB,
                                                         final ObjectMapper mapper) {
        return new MobilePushNotificationProcessor(sns, dao, pushNotificationEventDynamoDB, mapper);
    }

    public PushNotificationEventDynamoDB getPushNotificationEventDynamoDB() {
        return pushNotificationEventDynamoDB;
    }

    public void push(final PushNotificationEvent event) {

        // We often want at-most-once delivery of push notifications, so we insert the record to DDB first.
        // That way if something later in this method fails, we won't accidentally send the same notification twice.
        final boolean successfullyInserted = pushNotificationEventDynamoDB.insert(event);
        if (!successfullyInserted) {
            return;
        }

        final Long accountId = event.accountId;
        final HelloPushMessage pushMessage = event.helloPushMessage;

        final List<MobilePushRegistration> registrations = dao.getSubscriptions(accountId);
        for (final MobilePushRegistration reg : registrations) {
            if(reg.endpoint.isPresent()) {
                final MobilePushRegistration.OS os = MobilePushRegistration.OS.fromString(reg.os);
                final Optional<String> message = makeMessage(os, pushMessage);
                if(!message.isPresent()) {
                    LOGGER.info("Did not get any suitable message for {}", reg);
                    continue;
                }
                LOGGER.info(message.get());
                final PublishRequest pr = new PublishRequest();
                pr.setMessageStructure("json");
                pr.setMessage(message.get());
                pr.setTargetArn(reg.endpoint.get());
                try {
                    sns.publish(pr);
                } catch (Exception e) {
                    LOGGER.error("Failed sending message : {}", e.getMessage());
                }
            }
        }
    }

    private Optional<String> makeAPNSMessage(final HelloPushMessage message) {
        final Map<String, String> messageMap = new HashMap<>();
        final Map<String, String> content = new HashMap<>();
        final Map<String, Object> appleMessageMap = new HashMap<>();
        final Map<String, Object> appMessageMap = new HashMap<>();

        content.put("body", message.body);
        content.put("target", message.target);
        content.put("details", message.details);


        appMessageMap.put("alert", content);
        appMessageMap.put("sound", "default");

        appleMessageMap.put("aps", appMessageMap);

        final ObjectMapper mapper = new ObjectMapper();

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

        content.put("message", message.body);
        content.put("target", message.target);
        content.put("details", message.details);

        appMessageMap.put("collapse_key", "Welcome");
//        appMessageMap.put("delay_while_idle", true);
//        appMessageMap.put("time_to_live", 125);
//        appMessageMap.put("dry_run", false);
        appMessageMap.put("data", content);



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

}
