package com.hello.suripu.workers.timeline;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.collect.ImmutableMap;

import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.AccountDAOImpl;
import com.hello.suripu.core.db.AccountReadDAO;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.CalibrationDynamoDB;
import com.hello.suripu.core.db.DefaultModelEnsembleDAO;
import com.hello.suripu.core.db.DefaultModelEnsembleFromS3;
import com.hello.suripu.core.db.DeviceDataDAODynamoDB;
import com.hello.suripu.core.db.DeviceReadDAO;
import com.hello.suripu.core.db.FeatureExtractionModelsDAO;
import com.hello.suripu.core.db.FeatureExtractionModelsDAODynamoDB;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.FeedbackReadDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.OnlineHmmModelsDAO;
import com.hello.suripu.core.db.OnlineHmmModelsDAODynamoDB;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.UserTimelineTestGroupDAO;
import com.hello.suripu.core.db.UserTimelineTestGroupDAOImpl;
import com.hello.suripu.core.db.colors.SenseColorDAO;
import com.hello.suripu.core.db.colors.SenseColorDAOSQLImpl;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.db.util.PostgresIntegerArrayArgumentFactory;
import com.hello.suripu.core.processors.TimelineProcessor;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.coredw8.db.SleepHmmDAODynamoDB;
import com.hello.suripu.coredw8.configuration.S3BucketConfiguration;
import com.hello.suripu.coredw8.db.TimelineDAODynamoDB;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;

import net.sourceforge.argparse4j.inf.Namespace;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.jdbi.ImmutableListContainerFactory;
import io.dropwizard.jdbi.ImmutableSetContainerFactory;
import io.dropwizard.jdbi.OptionalContainerFactory;
import io.dropwizard.setup.Environment;

/**
 * Created by pangwu on 9/23/14.
 */
public class TimelineWorkerCommand extends WorkerEnvironmentCommand<TimelineWorkerConfiguration> {
    private final static Logger LOGGER = LoggerFactory.getLogger(TimelineWorkerCommand.class);

    public TimelineWorkerCommand(String name, String description) {
        super(name, description);
    }

    @Override
    protected void run(Environment environment, Namespace namespace, TimelineWorkerConfiguration configuration) throws Exception {


        final DBIFactory factory = new DBIFactory();
        final DBI commonDB = factory.build(environment, configuration.getCommonDB(), "postgresql-common");
        final DBI sensorsDB = factory.build(environment, configuration.getSensorsDB(), "postgresql-sensors");

        sensorsDB.registerArgumentFactory(new JodaArgumentFactory());
        sensorsDB.registerContainerFactory(new OptionalContainerFactory());
        sensorsDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());
        sensorsDB.registerContainerFactory(new ImmutableListContainerFactory());
        sensorsDB.registerContainerFactory(new ImmutableSetContainerFactory());

        commonDB.registerArgumentFactory(new JodaArgumentFactory());
        commonDB.registerContainerFactory(new OptionalContainerFactory());
        commonDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());
        commonDB.registerContainerFactory(new ImmutableListContainerFactory());
        commonDB.registerContainerFactory(new ImmutableSetContainerFactory());

        final AccountReadDAO accountDAO = commonDB.onDemand(AccountDAOImpl.class);
        final DeviceReadDAO deviceDAO = commonDB.onDemand(DeviceReadDAO.class);
        final FeedbackReadDAO feedbackDAO = commonDB.onDemand(FeedbackReadDAO.class);
        final UserTimelineTestGroupDAO userTimelineTestGroupDAO = commonDB.onDemand(UserTimelineTestGroupDAOImpl.class);

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final ClientConfiguration clientConfig = new ClientConfiguration().withConnectionTimeout(200).withMaxErrorRetry(1).withMaxConnections(100);
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, clientConfig, configuration.getDynamoDBConfiguration());
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.getDynamoDBConfiguration().tables();

        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.withConnectionTimeout(200); // in ms
        clientConfiguration.withMaxErrorRetry(1);
        final AmazonS3 amazonS3 = new AmazonS3Client(awsCredentialsProvider, clientConfiguration);

        final AmazonDynamoDB mergedUserInfoDynamoDBClient = dynamoDBClientFactory.getForEndpoint(
                configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.ALARM_INFO));
        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(mergedUserInfoDynamoDBClient,
                configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.ALARM_INFO));

       /* Priors for bayesnet  */
        final AmazonDynamoDB onlineModelsDb = dynamoDBClientFactory.getForEndpoint(configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.ONLINE_HMM_MODELS));
        final OnlineHmmModelsDAO onlineHmmModelsDAO = OnlineHmmModelsDAODynamoDB.create(onlineModelsDb, configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.ONLINE_HMM_MODELS));

        /* Models for bayesnet */
        final AmazonDynamoDB featureExtractionDb = dynamoDBClientFactory.getForEndpoint(configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.FEATURE_EXTRACTION_MODELS));
        final FeatureExtractionModelsDAO featureExtractionModelsDAO = new FeatureExtractionModelsDAODynamoDB(featureExtractionDb, configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.FEATURE_EXTRACTION_MODELS));

        final S3BucketConfiguration timelineModelEnsemblesConfig = configuration.getTimelineModelEnsemblesConfiguration();
        final S3BucketConfiguration seedModelConfig = configuration.getTimelineSeedModelConfiguration();

        final DefaultModelEnsembleDAO defaultModelEnsembleDAO = DefaultModelEnsembleFromS3.create(amazonS3, timelineModelEnsemblesConfig.getBucket(), timelineModelEnsemblesConfig.getKey(), seedModelConfig.getBucket(), seedModelConfig.getKey());
        final AmazonDynamoDB ringTimeDynamoDBClient = dynamoDBClientFactory.getForEndpoint(
                configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.RING_TIME_HISTORY));
        final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB = new RingTimeHistoryDAODynamoDB(
                ringTimeDynamoDBClient,
                configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.RING_TIME_HISTORY));

        final AmazonDynamoDB featureDynamoDB = dynamoDBClientFactory.getForEndpoint(
                configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.FEATURES));
        final String featureNamespace = (configuration.getDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, "features", featureNamespace);

        final AmazonDynamoDB sleepHmmDynamoDbClient = dynamoDBClientFactory.getForEndpoint(
                configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.SLEEP_HMM));
        final String sleepHmmTableName = configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.SLEEP_HMM);
        final SleepHmmDAODynamoDB sleepHmmDAODynamoDB = new SleepHmmDAODynamoDB(sleepHmmDynamoDbClient, sleepHmmTableName);

        final AmazonDynamoDB dynamoDBStatsClient = dynamoDBClientFactory.getForEndpoint(
                configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.SLEEP_STATS));
        final SleepStatsDAODynamoDB sleepStatsDAODynamoDB = new SleepStatsDAODynamoDB(
                dynamoDBStatsClient,
                configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.SLEEP_STATS),
                configuration.getSleepStatsVersion()
        );

        final AmazonDynamoDB deviceDataDAODynamoDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.DEVICE_DATA, DeviceDataDAODynamoDB.class);
        final DeviceDataDAODynamoDB deviceDataDAODynamoDB = new DeviceDataDAODynamoDB(deviceDataDAODynamoDBClient, tableNames.get(DynamoDBTableName.DEVICE_DATA));

        final AmazonDynamoDB pillDataDAODynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_DATA);
        final PillDataDAODynamoDB pillDataDAODynamoDB = new PillDataDAODynamoDB(pillDataDAODynamoDBClient, tableNames.get(DynamoDBTableName.PILL_DATA));

        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.getDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-timeline", apiKey, env);

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

        final AmazonDynamoDB dynamoDBTimelineClient = dynamoDBClientFactory.getForEndpoint(
                configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.TIMELINE));
        final TimelineDAODynamoDB timelineDAODynamoDB = new TimelineDAODynamoDB(
            dynamoDBTimelineClient,
            configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.TIMELINE),
            configuration.getMaxCacheRefreshDay(),
            environment.metrics());

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final SenseColorDAO senseColorDAO = commonDB.onDemand(SenseColorDAOSQLImpl.class);
        final AmazonDynamoDB calibrationDynamoDBClient = dynamoDBClientFactory.getForEndpoint(configuration.getDynamoDBConfiguration().endpoints().get(DynamoDBTableName.CALIBRATION));
        final CalibrationDAO calibrationDAO = CalibrationDynamoDB.create(calibrationDynamoDBClient, configuration.getDynamoDBConfiguration().tables().get(DynamoDBTableName.CALIBRATION));

        final TimelineProcessor timelineProcessor =
                TimelineProcessor.createTimelineProcessor(pillDataDAODynamoDB,
                deviceDAO, deviceDataDAODynamoDB,
                ringTimeHistoryDAODynamoDB,
                feedbackDAO,
                sleepHmmDAODynamoDB,
                accountDAO,
                sleepStatsDAODynamoDB,
                senseColorDAO,
                onlineHmmModelsDAO,
                featureExtractionModelsDAO,
                calibrationDAO,
                        defaultModelEnsembleDAO,
                        userTimelineTestGroupDAO);

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.BATCH_PILL_DATA);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);


        final String workerId = InetAddress.getLocalHost().getCanonicalHostName() + ":" + UUID.randomUUID();
        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                configuration.getAppName(),
                queueName,
                awsCredentialsProvider,
                workerId);
        kinesisConfig.withMaxRecords(configuration.getMaxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());
        kinesisConfig.withInitialPositionInStream(InitialPositionInStream.LATEST);


        final IRecordProcessorFactory processFactory = new TimelineRecordProcessorFactory(timelineProcessor,
            deviceDAO,
            mergedUserInfoDynamoDB,
            timelineDAODynamoDB,
            configuration,
            environment.metrics());
        final Worker worker = new Worker(processFactory, kinesisConfig);
        worker.run();
    }
}
