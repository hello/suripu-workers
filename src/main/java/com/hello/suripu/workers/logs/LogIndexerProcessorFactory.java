package com.hello.suripu.workers.logs;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.db.AccountDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.OnBoardingLogDAO;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.core.db.SenseEventsDynamoDB;
import com.hello.suripu.coredropwizard.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.logs.triggers.Publisher;
import com.hello.suripu.workers.logs.triggers.SqsPublisher;
import com.segment.analytics.Analytics;

public class LogIndexerProcessorFactory implements IRecordProcessorFactory {

    private final LogIndexerWorkerConfiguration config;
    private final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory;
    private final OnBoardingLogDAO onBoardingLogDAO;
    private final AccountDAO accountDAO;
    private final MetricRegistry metricRegistry;
    private final RingTimeHistoryReadDAO ringTimeHistoryReadDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final Analytics analytics;
    private final AWSCredentialsProvider credentialsProvider;


    public LogIndexerProcessorFactory(final LogIndexerWorkerConfiguration config,
                                      final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory,
                                      final OnBoardingLogDAO onBoardingLogDAO,
                                      final AccountDAO accountDAO,
                                      final MetricRegistry metricRegistry,
                                      final Analytics analytics,
                                      final RingTimeHistoryReadDAO ringTimeHistoryReadDAO,
                                      final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                      final AWSCredentialsProvider awsCredentialsProvider) {
        this.config = config;
        this.metricRegistry = metricRegistry;
        this.amazonDynamoDBClientFactory = amazonDynamoDBClientFactory;
        this.onBoardingLogDAO = onBoardingLogDAO;
        this.accountDAO = accountDAO;
        this.analytics = analytics;
        this.ringTimeHistoryReadDAO = ringTimeHistoryReadDAO;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.credentialsProvider = awsCredentialsProvider;
    }

    @Override
    public IRecordProcessor createProcessor() {
        final AmazonDynamoDB senseEventsDBClient = amazonDynamoDBClientFactory.getInstrumented(DynamoDBTableName.SENSE_EVENTS, SenseEventsDAO.class);
        final ImmutableMap<DynamoDBTableName, String> tableNames = this.config.dynamoDBConfiguration().tables();
        final SenseEventsDAO senseEventsDAO = new SenseEventsDynamoDB(senseEventsDBClient, tableNames.get(DynamoDBTableName.SENSE_EVENTS));
        final Publisher publisher = new SqsPublisher(
                new AmazonSQSClient(credentialsProvider),
                config.sqsQueueSleepScorePush(),
                config.motionLookBackInMinutes()
        );

        return LogIndexerProcessor.create(
                senseEventsDAO,
                this.onBoardingLogDAO,
                this.metricRegistry,
                analytics,
                ringTimeHistoryReadDAO,
                mergedUserInfoDynamoDB,
                publisher, accountDAO);
    }
}
