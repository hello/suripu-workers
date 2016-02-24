package com.hello.suripu.workers.logs;

import com.google.common.collect.ImmutableMap;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.db.OnBoardingLogDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;

public class LogIndexerProcessorFactory implements IRecordProcessorFactory {

    private final LogIndexerWorkerConfiguration config;
    private final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory;
    private final OnBoardingLogDAO onBoardingLogDAO;
    private final MetricRegistry metricRegistry;

    public LogIndexerProcessorFactory(final LogIndexerWorkerConfiguration config,
                                      final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory,
                                      final OnBoardingLogDAO onBoardingLogDAO,
                                      final MetricRegistry metricRegistry) {
        this.config = config;
        this.metricRegistry = metricRegistry;
        this.amazonDynamoDBClientFactory = amazonDynamoDBClientFactory;
        this.onBoardingLogDAO = onBoardingLogDAO;
    }

    @Override
    public IRecordProcessor createProcessor() {
        final AmazonDynamoDB senseEventsDBClient = amazonDynamoDBClientFactory.getInstrumented(DynamoDBTableName.SENSE_EVENTS, SenseEventsDAO.class);
        final ImmutableMap<DynamoDBTableName, String> tableNames = this.config.dynamoDBConfiguration().tables();
        final SenseEventsDAO senseEventsDAO = new SenseEventsDAO(senseEventsDBClient, tableNames.get(DynamoDBTableName.SENSE_EVENTS));

        return LogIndexerProcessor.create(senseEventsDAO, this.onBoardingLogDAO, this.metricRegistry);
    }
}
