package com.hello.suripu.workers.splitter;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClient;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
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
public class SenseStreamSplitterCommand extends WorkerEnvironmentCommand<SenseStreamSplitterConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStreamSplitterCommand.class);

    public SenseStreamSplitterCommand(String name, String description) {
        super(name, description);
    }


    @Override
    protected void run(Environment environment, Namespace namespace, SenseStreamSplitterConfiguration configuration) throws Exception {

        if (configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.getDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-sense-splitter", apiKey, env);

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

        // set up ingest stream
        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
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

        // set output kinesis stream
        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.withConnectionTimeout(200); // in ms
        clientConfiguration.withMaxErrorRetry(1);

        final AmazonKinesisAsyncClient kinesisClient = new AmazonKinesisAsyncClient(awsCredentialsProvider, clientConfiguration);
        final KinesisLoggerFactory kinesisLoggerFactory = new KinesisLoggerFactory(
                kinesisClient,
                configuration.getKinesisConfiguration().getStreams()
        );

        final DataLogger kinesisLogger = kinesisLoggerFactory.get(QueueName.SENSE_SENSORS_DATA_SPLIT);

        final IRecordProcessorFactory factory = new SenseStreamSplitterFactory(
                kinesisLogger,
                configuration.getMaxRecords(),
                environment.metrics()
        );


        final Worker worker = new Worker(factory, inputKinesisConfig);
        worker.run();
    }
}