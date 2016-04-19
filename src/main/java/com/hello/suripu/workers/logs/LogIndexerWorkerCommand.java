package com.hello.suripu.workers.logs;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.CreateTableResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.OnBoardingLogDAO;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import com.segment.analytics.Analytics;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.jdbi.ImmutableListContainerFactory;
import io.dropwizard.jdbi.ImmutableSetContainerFactory;
import io.dropwizard.jdbi.OptionalContainerFactory;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class LogIndexerWorkerCommand extends WorkerEnvironmentCommand<LogIndexerWorkerConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(LogIndexerWorkerCommand.class);

    public LogIndexerWorkerCommand(final String name, final String description) {
        super(name, description);
    }

    @Override
    protected void run(Environment environment, Namespace namespace, final LogIndexerWorkerConfiguration configuration) throws Exception {

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final ClientConfiguration clientConfig = new ClientConfiguration().withConnectionTimeout(200).withMaxErrorRetry(1).withMaxConnections(100);
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, clientConfig, configuration.dynamoDBConfiguration());

        final AmazonDynamoDB senseEventsDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.SENSE_EVENTS, SenseEventsDAO.class);
        final SenseEventsDAO senseEventsDAO = new SenseEventsDAO(senseEventsDBClient, tableNames.get(DynamoDBTableName.SENSE_EVENTS));

        final AmazonDynamoDB ringtimeHistoryClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.RING_TIME_HISTORY, RingTimeHistoryDAODynamoDB.class);

        final AmazonDynamoDB alarmInfoClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.ALARM_INFO, MergedUserInfoDynamoDB.class);

        init(senseEventsDBClient, tableNames.get(DynamoDBTableName.SENSE_EVENTS));

        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-logindexer", apiKey, env);

            final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHostName, 2003));

            final GraphiteReporter reporter = GraphiteReporter.forRegistry(environment.metrics())
                .prefixedWith(prefix)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(MetricFilter.ALL)
                .build(graphite);
            reporter.start(interval, TimeUnit.SECONDS);

            LOGGER.info("Metrics enabled.");
        } else {
            LOGGER.warn("Metrics not enabled.");
        }

        final String workerId = InetAddress.getLocalHost().getCanonicalHostName();

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.LOGS);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);


        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                configuration.getAppName(),
                queueName,
                awsCredentialsProvider,
                workerId);
        kinesisConfig.withMaxRecords(configuration.maxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());
        kinesisConfig.withInitialPositionInStream(InitialPositionInStream.LATEST);

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }

        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.FEATURES, FeatureStore.class);
        final FeatureStore featureStore = new FeatureStore(
            featuresDynamoDBClient,
            tableNames.get(DynamoDBTableName.FEATURES),
            featureNamespace
        );

        final DBIFactory factory = new DBIFactory();
        final DBI commonDBI = factory.build(environment, configuration.getCommonDB(), "postgresql-common");

        commonDBI.registerContainerFactory(new OptionalContainerFactory());
        commonDBI.registerContainerFactory(new ImmutableListContainerFactory());
        commonDBI.registerContainerFactory(new ImmutableSetContainerFactory());
        commonDBI.registerArgumentFactory(new JodaArgumentFactory());

        final OnBoardingLogDAO onBoardingLogDAO = commonDBI.onDemand(OnBoardingLogDAO.class);

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final Analytics analytics = Analytics.builder(configuration.segmentWriteKey()).build();
        final RingTimeHistoryReadDAO ringTimeHistoryDAODynamoDB = new NonRateLimitedRingtimeDDB(
                ringtimeHistoryClient,
                configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.RING_TIME_HISTORY)
        );

        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(
                alarmInfoClient,
                configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.ALARM_INFO)
        );

        final IRecordProcessorFactory processorFactory = new LogIndexerProcessorFactory(configuration,
                dynamoDBClientFactory,
                onBoardingLogDAO,
                environment.metrics(),
                analytics,
                ringTimeHistoryDAODynamoDB,
                mergedUserInfoDynamoDB
        );
        final Worker worker = new Worker(processorFactory, kinesisConfig);
        worker.run();
    }


    protected void init(final AmazonDynamoDB senseEventsDBClient, final String tableName) {
        try {
            senseEventsDBClient.describeTable(tableName);
            LOGGER.info("{} already exists.", tableName);
        } catch (AmazonServiceException exception) {
            final CreateTableResult result = SenseEventsDAO.createTable(tableName, senseEventsDBClient);
            final TableDescription description = result.getTableDescription();
            LOGGER.info("{}: {}", tableName, description.getTableStatus());
        }
    }
}