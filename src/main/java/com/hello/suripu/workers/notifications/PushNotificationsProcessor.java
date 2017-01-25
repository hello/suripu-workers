package com.hello.suripu.workers.notifications;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.notifications.PushNotification;
import com.hello.suripu.core.notifications.HelloPushMessage;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessor;
import com.hello.suripu.core.notifications.PushNotificationEvent;
import com.hello.suripu.core.notifications.PushNotificationEventType;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class PushNotificationsProcessor extends HelloBaseRecordProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(PushNotificationsProcessor.class);

    private final MobilePushNotificationProcessor mobilePushNotificationProcessor;
    private final HelloPushMessageGenerator helloPushMessageGenerator;

    private String shardId = "N/A";
    private final ImmutableSet<Integer> activeHours;

    public PushNotificationsProcessor(
            final MobilePushNotificationProcessor mobilePushNotificationProcessor,
            final HelloPushMessageGenerator helloPushMessageGenerator,
            final Set<Integer> activeHours) {
        this.mobilePushNotificationProcessor = mobilePushNotificationProcessor;
        this.helloPushMessageGenerator = helloPushMessageGenerator;
        this.activeHours = ImmutableSet.copyOf(activeHours);
    }

    @Override
    public void initialize(String s) {
        this.shardId = s;
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        for(final Record record : records) {
            try {
                final PushNotification.UserPushNotification pn = PushNotification.UserPushNotification.parseFrom(record.getData().array());
                sendMessage(pn);
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("error=protobuf-parse msg={} ", e.getMessage());
            }
        }

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException | ShutdownException e) {
            LOGGER.error("error=failed-checkpoint msg={}", e.getMessage());
        }
    }


    /**
     * Send push notifications if conditions warrant it and within the hours
     */
    private void sendMessage(final PushNotification.UserPushNotification userPushNotification) {

        final Optional<HelloPushMessage> helloPushMessage = helloPushMessageGenerator.generate(userPushNotification);
        if(!helloPushMessage.isPresent()) {
            LOGGER.warn("action=generate-hello-push-message result=failed account_id={}", userPushNotification.getAccountId());
            return;
        }

        final PushNotificationEvent.Builder eventBuilder = PushNotificationEvent.newBuilder()
                .withAccountId(userPushNotification.getAccountId())
                .withHelloPushMessage(helloPushMessage.get())
                .withTimestamp(new DateTime(userPushNotification.getTimestamp(), DateTimeZone.UTC)); // normalized to the day/week

        if(userPushNotification.hasSenseId()) {
            eventBuilder.withSenseId(userPushNotification.getSenseId());
        }


        if(userPushNotification.hasNewSleepScore()) {
            eventBuilder.withType(PushNotificationEventType.SLEEP_SCORE);
        } else if(userPushNotification.hasPillBatteryLow()) {
            eventBuilder.withType(PushNotificationEventType.PILL_BATTERY);
        }

        try {
            mobilePushNotificationProcessor.push(eventBuilder.build());
        } catch (Exception e) {
            LOGGER.error("error=push-failed msg={}", e.getMessage());
        }
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {
        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
        if(shutdownReason == ShutdownReason.TERMINATE) {
            try {
                iRecordProcessorCheckpointer.checkpoint();
            } catch (InvalidStateException | ShutdownException e) {
                LOGGER.error("error=shutdown-failed msg={}", e.getMessage());
            }
        }
    }
}
