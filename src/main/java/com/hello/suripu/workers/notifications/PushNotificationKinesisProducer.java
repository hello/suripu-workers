package com.hello.suripu.workers.notifications;

import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.hello.suripu.workers.protobuf.notifications.PushNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Created by jakepiccolo on 5/17/16.
 */
public class PushNotificationKinesisProducer {

    private final static Logger LOGGER = LoggerFactory.getLogger(PushNotificationKinesisProducer.class);

    private final String streamName;
    private final KinesisProducer kinesisProducer;


    public PushNotificationKinesisProducer(final String streamName, final KinesisProducer kinesisProducer) {
        this.streamName = streamName;
        this.kinesisProducer = kinesisProducer;
    }

    public void putNotification(final PushNotification.UserPushNotification userPushNotification) {
        if (!userPushNotification.hasSenseId()) {
            LOGGER.error("error=no_sense_id account_id={}", userPushNotification.getAccountId());
            throw new IllegalArgumentException("userPushNotification must have senseId.");
        }
        kinesisProducer.addUserRecord(streamName, userPushNotification.getSenseId(), ByteBuffer.wrap(userPushNotification.toByteArray()));
    }

}
