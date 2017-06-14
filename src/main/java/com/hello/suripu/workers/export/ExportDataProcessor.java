package com.hello.suripu.workers.export;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.util.Md5Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.collect.Lists;
import com.hello.suripu.core.db.SleepStatsDAO;
import com.hello.suripu.core.models.AggregateSleepStats;
import com.hello.suripu.core.util.DateTimeUtil;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExportDataProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(ExportDataProcessor.class);
    
    private final AmazonS3 amazonS3;
    private final SleepStatsDAO sleepStatsDAO;
    private final ExportDataConfiguration configuration;
    private AmazonSQS amazonSQS;
    private final ObjectMapper mapper;

    public ExportDataProcessor(final AmazonSQSBufferedAsyncClient client, final AmazonS3 amazonS3, final SleepStatsDAO sleepStatsDAO,
                               final ExportDataConfiguration configuration, final ObjectMapper objectMapper) {
        this.amazonSQS = client;
        this.amazonS3 = amazonS3;
        this.sleepStatsDAO = sleepStatsDAO;
        this.configuration = configuration;
        this.mapper = objectMapper;
    }

    public void process() throws InterruptedException {
        final TypeFactory factory = TypeFactory.defaultInstance();
        final MapType type = factory.constructMapType(HashMap.class, String.class, String.class);

        LOGGER.warn("action=export msg=start-loop");
        while(true) {
            final ReceiveMessageResult results = amazonSQS.receiveMessage(configuration.exportDataQueueUrl());
            LOGGER.info("action=export num_msg={}", results.getMessages().size());
            for(final Message message : results.getMessages()) {
                LOGGER.info("action=export msg_id={}", message.getMessageId());
                try {
                    final HashMap<String, String> content = mapper.readValue(message.getBody(), type);
                    export(content);
                } catch (Exception e) {
                    LOGGER.error("action=export error={}", e.getMessage());
                }

                amazonSQS.deleteMessage(configuration.exportDataQueueUrl(), message.getReceiptHandle());
            }

            Thread.sleep(10000L);
        }
    }

    private void export(final Map<String, String> messageContent) {

        final String email = messageContent.getOrDefault("email", "");
        final String uuid = messageContent.getOrDefault("uuid", "");
        final String accountId = messageContent.getOrDefault("account_id", "0");
        if(email.isEmpty() || uuid.isEmpty()) {
            LOGGER.warn("action=export msg=emtpy-params email={} uuid={}", email, uuid);
            return;
        }

        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final DateTime then = now.minusMonths(configuration.lookBackMonths());
        final String end = DateTimeUtil.dateToYmdString(now);
        final String start = DateTimeUtil.dateToYmdString(then);


        final List<AggregateSleepStats> stats = sleepStatsDAO.getBatchStats(Long.parseLong(accountId), start, end);
        if(stats.isEmpty()) {
            LOGGER.warn("action=export msg=no-sleep-stats email={}", email);
            return;
        }

        final String key = String.format("sleep_stats/%s.json", uuid);
        
        try {
            final byte[] content = mapper.writeValueAsBytes(stats);
            final String md5 = Md5Utils.md5AsBase64(content);
            try (final InputStream inputStream = new ByteArrayInputStream(content)){

                final ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(content.length);
                final PutObjectRequest putObjectRequest = new PutObjectRequest(
                        configuration.exportBucketName(),
                        key,
                        inputStream,
                        metadata);
                final PutObjectResult result = amazonS3.putObject(putObjectRequest);
                LOGGER.info("action=export computed_md5={} received_md5={} email={}", md5, result.getContentMd5(), email);
                final Date sevenDays = DateTime.now(DateTimeZone.UTC).plusDays(7).toDate();
                final URL signedUrl = amazonS3.generatePresignedUrl(configuration.exportBucketName(), key, sevenDays);
                boolean emailSent = sendEmail(email, signedUrl.toExternalForm());
                if(emailSent) {
                    LOGGER.info("action=export msg=email-sent email={}", email);
                }
                
            } catch (IOException e) {
                LOGGER.error("action=export error={}", e.getMessage());
            }


        } catch (JsonProcessingException e) {
            LOGGER.error("action=export email={} error={}", email, e.getMessage());
        }
    }

    public static final String EMAIL_EXPORT_HTML_TEMPLATE = "<html>\n<head>\n    <title>Data export</title>\n</head>\n<body>\n    <p>Hello,</p>\n    <p>Here is a link to download your data: <a href=\"%s\">%s</a></p>\n<p>Thanks</p></body>\n</html>";


    private Boolean sendEmail(final String emailTo, final String signedUrl) {

        final String htmlMessage = String.format(EMAIL_EXPORT_HTML_TEMPLATE, signedUrl, signedUrl);

        // Grrr mutable objects
        final MandrillMessage message = new MandrillMessage();
        message.setSubject("Your data is available for download");
        message.setHtml(htmlMessage);
        message.setAutoText(true);
        message.setFromEmail("support@hello.is");
        message.setFromName("Hello");


        final MandrillMessage.Recipient recipient = new MandrillMessage.Recipient();
        recipient.setEmail(emailTo);

        final List<MandrillMessage.Recipient> recipients = Lists.newArrayList(recipient);
        message.setTo(recipients);

        final List<String> tags = Lists.newArrayList("export_data");
        message.setTags(tags);

        try {
            final MandrillMessageStatus[] messageStatusReports = mandrillApi.messages().send(message, false);
            return Boolean.TRUE;
        } catch (MandrillApiError mandrillApiError) {
            LOGGER.error("error=mandrill-failed-sending-email error_msg={}", mandrillApiError.getMessage());
        } catch (IOException e) {
            LOGGER.error("error=failed-sending-email error_msg={}", e.getMessage());
        }

        return Boolean.FALSE;
    }
}
