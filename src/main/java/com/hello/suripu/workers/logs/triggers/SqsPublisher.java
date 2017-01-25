package com.hello.suripu.workers.logs.triggers;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import com.hello.suripu.api.queue.TimelineQueueProtos;
import com.hello.suripu.core.metrics.DeviceEvents;
import com.hello.suripu.core.util.DateTimeUtil;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqsPublisher implements Publisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqsPublisher.class);

    private final AmazonSQS amazonSQS;
    private final String queueUrl;

    private final Integer lookBackInMinutes;
    public SqsPublisher(final AmazonSQS amazonSQS, final String queueUrl, final Integer lookBackInMinutes) {
        this.amazonSQS = amazonSQS;
        this.queueUrl = queueUrl;
        this.lookBackInMinutes = lookBackInMinutes;
    }

    @Override
    public void publish(final Long accoundId, final DeviceEvents deviceEvents) {
        LOGGER.info("action=sqs-publish account_id={}", accoundId);

        final TimelineQueueProtos.TriggerMessage triggerMessage = TimelineQueueProtos.TriggerMessage
                .newBuilder()
                .setMessageCreatedAt(deviceEvents.createdAt.getMillis())
                .setAccountId(accoundId)
                .setLookbackWindowInMinutes(lookBackInMinutes)
                .setTargetDate(DateTimeUtil.dateToYmdString(deviceEvents.createdAt.minusDays(1)))
                .build();

        final SendMessageRequest sendMessageRequest = new SendMessageRequest()
                .withMessageBody(Base64.encodeBase64URLSafeString(triggerMessage.toByteArray()))
                .withQueueUrl(queueUrl);
        try {
            final SendMessageResult sendMessageResult =  amazonSQS.sendMessage(sendMessageRequest);
            String md5 = sendMessageResult.getMD5OfMessageBody();
            LOGGER.info("action=send-sqs-message account_id={} md5={} message_id={}", accoundId, md5, sendMessageResult.getMessageId());
        } catch (Exception e) {
            LOGGER.error("error=send-sqs-message msg={}", e.getMessage());
        }
    }
}
