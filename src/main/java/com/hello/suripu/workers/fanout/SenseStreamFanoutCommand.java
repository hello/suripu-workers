package com.hello.suripu.workers.fanout;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClient;
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
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
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

/**
 * Created by ksg on 4/8/16
 */
public class SenseStreamFanoutCommand extends WorkerEnvironmentCommand<SenseStreamFanoutConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStreamFanoutCommand.class);

    public SenseStreamFanoutCommand(String name, String description) {
        super(name, description);
    }


    @Override
    protected void run(Environment environment, Namespace namespace, SenseStreamFanoutConfiguration configuration) throws Exception {

        // metrics
        if (configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers", apiKey, env);

            final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHostName, 2003));

            final GraphiteReporter reporter = GraphiteReporter.forRegistry(environment.metrics())
                    .prefixedWith(prefix)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .filter(MetricFilter.ALL)
                    .build(graphite);
            reporter.start(interval, TimeUnit.SECONDS);

            LOGGER.info("info=stream-fanout-metrics value=enabled.");
        } else {
            LOGGER.warn("info=stream-fanout-metrics value=disabled.");
        }


        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

        // set up feature flipper
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, configuration.dynamoDBConfiguration());
        final AmazonDynamoDB featureDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, tableNames.get(DynamoDBTableName.FEATURES), featureNamespace);

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        // set up Kinesis stream consumer
        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("action=get-queues queue_names={}", queueNames);
        final String queueName = queueNames.get(QueueName.SENSE_SENSORS_DATA);
        LOGGER.info("\n\n\ninfo=stream-fanout-consumes-the-following-queue queue={} !!!\n\n\n", queueName);

        final String workerId = InetAddress.getLocalHost().getCanonicalHostName();
        final KinesisClientLibConfiguration inputKinesisConfig = new KinesisClientLibConfiguration(
                configuration.getAppName(),
                queueName,
                awsCredentialsProvider,
                workerId);
        inputKinesisConfig.withMaxRecords(configuration.getMaxRecords());
        inputKinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());


        if (configuration.getTrimHorizon()) {
            inputKinesisConfig.withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);
        } else {
            inputKinesisConfig.withInitialPositionInStream(InitialPositionInStream.LATEST);
        }

        if(configuration.isDebug()) {
            inputKinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }


        // set up output kinesis stream
        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.withConnectionTimeout(200); // in ms
        clientConfiguration.withMaxErrorRetry(1);

        final AmazonKinesisAsyncClient kinesisClient = new AmazonKinesisAsyncClient(awsCredentialsProvider, clientConfiguration);
        final KinesisLoggerFactory kinesisLoggerFactory = new KinesisLoggerFactory(
                kinesisClient,
                configuration.getKinesisOutputConfiguration().getStreams()
        );

        final DataLogger kinesisLogger = kinesisLoggerFactory.get(QueueName.SENSE_SENSORS_DATA_FANOUT_ONE);

        final IRecordProcessorFactory factory = new SenseStreamFanoutFactory(
                kinesisLogger,
                configuration.getMaxRecords(),
                environment.metrics()
        );

        final Worker worker = new Worker(factory, inputKinesisConfig);
        worker.run();
    }
}