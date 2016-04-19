package com.hello.suripu.workers.sense;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient;
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
import com.hello.suripu.core.db.DeviceDataDAO;
import com.hello.suripu.core.db.DeviceDataDAODynamoDB;
import com.hello.suripu.core.db.DeviceDataIngestDAO;
import com.hello.suripu.core.db.DeviceReadDAO;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.SensorsViewsDynamoDB;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public final class SenseSaveWorkerCommand extends WorkerEnvironmentCommand<SenseSaveWorkerConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseSaveWorkerCommand.class);

    private boolean useDynamoDeviceData = false;
    private boolean updateLastSeen = true;

    public SenseSaveWorkerCommand(String name, String description) {
        super(name, description);
    }

    public SenseSaveWorkerCommand(String name, String description, final boolean useDynamoDeviceData, final boolean updateLastSeen) {
        this(name, description);
        this.useDynamoDeviceData = useDynamoDeviceData;
        this.updateLastSeen = updateLastSeen;
    }

    @Override
    protected void run(Environment environment, Namespace namespace, SenseSaveWorkerConfiguration configuration) throws Exception {

        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-sensesave", apiKey, env);

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

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.SENSE_SENSORS_DATA);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final String workerId = InetAddress.getLocalHost().getCanonicalHostName();
        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                configuration.getAppName(),
                queueName,
                awsCredentialsProvider,
                workerId);
        kinesisConfig.withMaxRecords(configuration.getMaxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());

        if(configuration.getTrimHorizon()) {
            kinesisConfig.withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);
        } else {
            kinesisConfig.withInitialPositionInStream(InitialPositionInStream.LATEST);
        }

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }

        final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, configuration.dynamoDBConfiguration());


        final AmazonDynamoDB alarmInfoDynamoDBClient = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.ALARM_INFO);

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final AmazonDynamoDB featureDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, tableNames.get(DynamoDBTableName.FEATURES), featureNamespace);

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(alarmInfoDynamoDBClient , tableNames.get(DynamoDBTableName.ALARM_INFO));

        final DeviceDataIngestDAO deviceDataIngestDAO;
        final IRecordProcessorFactory factory;
        if (useDynamoDeviceData) {
            final AmazonDynamoDB deviceDataDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.DEVICE_DATA);
            deviceDataIngestDAO = new DeviceDataDAODynamoDB(deviceDataDynamoDB, tableNames.get(DynamoDBTableName.DEVICE_DATA));
            factory = new SenseSaveDDBProcessorFactory(mergedUserInfoDynamoDB, deviceDataIngestDAO, configuration.getMaxRecords(), environment.metrics());
        } else {

            final DBIFactory dbiFactory = new DBIFactory();
            final DBI commonDBI = dbiFactory.build(environment, configuration.getCommonDB(), "postgresql");

            commonDBI.registerArgumentFactory(new JodaArgumentFactory());

            final DeviceReadDAO deviceDAO = commonDBI.onDemand(DeviceReadDAO.class);

            final DBI sensorsDBI = dbiFactory.build(environment, configuration.getSensorsDB(), "postgresql");
            sensorsDBI.registerArgumentFactory(new JodaArgumentFactory());
            deviceDataIngestDAO = sensorsDBI.onDemand(DeviceDataDAO.class);

            // WARNING: Do not use async methods for anything but SensorsViewsDynamoDB for now
            final AmazonDynamoDBAsync senseLastSeenDynamoDBClient = new AmazonDynamoDBAsyncClient(awsCredentialsProvider, AmazonDynamoDBClientFactory.getDefaultClientConfiguration());
            senseLastSeenDynamoDBClient.setEndpoint(configuration.dynamoDBConfiguration().endpoints().get(DynamoDBTableName.SENSE_LAST_SEEN));

            final SensorsViewsDynamoDB sensorsViewsDynamoDB = new SensorsViewsDynamoDB(
                    senseLastSeenDynamoDBClient,
                    tableNames.get(DynamoDBTableName.SENSE_PREFIX),
                    tableNames.get(DynamoDBTableName.SENSE_LAST_SEEN)
            );

            factory = new SenseSaveProcessorFactory(
                deviceDAO,
                mergedUserInfoDynamoDB,
                sensorsViewsDynamoDB,
                deviceDataIngestDAO,
                configuration.getMaxRecords(),
                updateLastSeen,
                environment.metrics()
            );
        }

        final Worker worker = new Worker(factory, kinesisConfig);
        worker.run();
    }
}