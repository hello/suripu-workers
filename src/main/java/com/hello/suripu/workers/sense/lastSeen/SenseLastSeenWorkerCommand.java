package com.hello.suripu.workers.sense.lastSeen;

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
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.SensorsViewsDynamoDB;
import com.hello.suripu.core.db.WifiInfoDAO;
import com.hello.suripu.core.db.WifiInfoDynamoDB;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public final class SenseLastSeenWorkerCommand extends WorkerEnvironmentCommand<SenseLastSeenWorkerConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseLastSeenWorkerCommand.class);

    public SenseLastSeenWorkerCommand(String name, String description) {
        super(name, description);
    }

    @Override
    protected void run(Environment environment, Namespace namespace, SenseLastSeenWorkerConfiguration configuration) throws Exception {

        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-senselastseen", apiKey, env);

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

        final AmazonDynamoDBAsync wifiInfoDynamoDBClient = new AmazonDynamoDBAsyncClient(awsCredentialsProvider, AmazonDynamoDBClientFactory.getDefaultClientConfiguration());
        wifiInfoDynamoDBClient.setEndpoint(configuration.dynamoDBConfiguration().endpoints().get(DynamoDBTableName.WIFI_INFO));

        // WARNING: Do not use async methods for anything but SensorsViewsDynamoDB for now
        final AmazonDynamoDBAsync senseLastSeenDynamoDBClient = new AmazonDynamoDBAsyncClient(awsCredentialsProvider, AmazonDynamoDBClientFactory.getDefaultClientConfiguration());
        senseLastSeenDynamoDBClient.setEndpoint(configuration.dynamoDBConfiguration().endpoints().get(DynamoDBTableName.SENSE_LAST_SEEN));

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final AmazonDynamoDB featureDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, tableNames.get(DynamoDBTableName.FEATURES), featureNamespace);

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);


        final WifiInfoDAO wifiInfoDAO = new WifiInfoDynamoDB(wifiInfoDynamoDBClient, tableNames.get(DynamoDBTableName.WIFI_INFO));
        final SensorsViewsDynamoDB sensorsViewsDynamoDB = new SensorsViewsDynamoDB(senseLastSeenDynamoDBClient, tableNames.get(DynamoDBTableName.SENSE_PREFIX), tableNames.get(DynamoDBTableName.SENSE_LAST_SEEN));


        final IRecordProcessorFactory factory = new SenseLastSeenProcessorFactory(
            configuration.getMaxRecords(),
            wifiInfoDAO,
            sensorsViewsDynamoDB,
            environment.metrics()
        );

        final Worker worker = new Worker(factory, kinesisConfig);
        worker.run();
    }
}