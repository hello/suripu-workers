package com.hello.suripu.workers.notifications;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.PutRecordsRequest;
import com.amazonaws.services.kinesis.model.PutRecordsRequestEntry;
import com.amazonaws.services.kinesis.model.PutRecordsResult;
import com.amazonaws.services.kinesis.model.PutRecordsResultEntry;
import com.google.common.collect.Lists;
import com.hello.suripu.workers.protobuf.notifications.PushNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jakepiccolo on 5/17/16.
 */
public class PushNotificationKinesisProducer {

    private final static Logger LOGGER = LoggerFactory.getLogger(PushNotificationKinesisProducer.class);

    private final String streamName;
    private final AmazonKinesis amazonKinesis;

    private static final int MAX_PUT_RECORDS_SIZE = 500;


    public PushNotificationKinesisProducer(final String streamName, final AmazonKinesis amazonKinesis) {
        this.streamName = streamName;
        this.amazonKinesis = amazonKinesis;
    }

    /**
     * Attempt to insert all UserPushNotifications into the kinesis stream.
     * @return List of notifications that failed to be added to the stream.
     */
    public List<PushNotification.UserPushNotification> putNotifications(final List<PushNotification.UserPushNotification> userPushNotifications) {
        // Can only insert a limited number of kinesis records at a time.
        final List<List<PushNotification.UserPushNotification>> partitions = Lists.partition(userPushNotifications, MAX_PUT_RECORDS_SIZE);
        final List<PushNotification.UserPushNotification> failedPuts = new ArrayList<>();
        for (final List<PushNotification.UserPushNotification> partition : partitions) {
             failedPuts.addAll(putNotificationsImpl(partition));
        }
        return failedPuts;
    }


    private void validateNotification(final PushNotification.UserPushNotification userPushNotification) {
        if (!userPushNotification.hasSenseId()) {
            LOGGER.error("error=no_sense_id account_id={}", userPushNotification.getAccountId());
            throw new IllegalArgumentException("userPushNotification must have senseId.");
        }
    }

    private List<PushNotification.UserPushNotification> putNotificationsImpl(final List<PushNotification.UserPushNotification> userPushNotifications) {
        final List<PutRecordsRequestEntry> entries = new ArrayList<>(userPushNotifications.size());
        for (final PushNotification.UserPushNotification notification : userPushNotifications) {
            validateNotification(notification);
            final PutRecordsRequestEntry requestEntry = new PutRecordsRequestEntry()
                    .withData(ByteBuffer.wrap(notification.toByteArray()))
                    .withPartitionKey(notification.getSenseId());
            entries.add(requestEntry);
        }

        final PutRecordsRequest request = new PutRecordsRequest().withRecords(entries).withStreamName(streamName);
        final PutRecordsResult putRecordsResult;
        try {
            putRecordsResult = amazonKinesis.putRecords(request);
        } catch (Exception e) {
            LOGGER.error("error=uncaught_kinesis_exception exception={}", e);
            // They all failed, so return them all.
            return userPushNotifications;
        }


        final List<PushNotification.UserPushNotification> failedNotifications = new ArrayList<>();
        if (putRecordsResult.getFailedRecordCount() == 0) {
            return failedNotifications;
        }

        for (int i = 0; i < putRecordsResult.getRecords().size(); i++) {
            final PutRecordsResultEntry resultEntry = putRecordsResult.getRecords().get(i);
            if (resultEntry.getErrorCode() != null) {
                final PushNotification.UserPushNotification failedNotification = userPushNotifications.get(i);
                failedNotifications.add(failedNotification);
                LOGGER.error("error=failed_push_notification_kinesis_put error_code={} error_message={} sense_id={} account_id={}",
                        resultEntry.getErrorCode(), resultEntry.getErrorMessage(), failedNotification.getSenseId(), failedNotification.getAccountId());
            }
        }

        return failedNotifications;
    }

}
