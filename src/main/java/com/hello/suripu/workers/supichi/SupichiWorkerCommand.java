package com.hello.suripu.workers.supichi;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;
import com.amazonaws.services.kms.AWSKMSClient;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.speech.KmsVault;
import com.hello.suripu.core.speech.SpeechResultIngestDAODynamoDB;
import com.hello.suripu.core.speech.SpeechTimelineIngestDAODynamoDB;
import com.hello.suripu.core.speech.interfaces.SpeechResultIngestDAO;
import com.hello.suripu.core.speech.interfaces.SpeechTimelineIngestDAO;
import com.hello.suripu.core.speech.interfaces.Vault;
import com.hello.suripu.coredropwizard.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.coredropwizard.metrics.RegexMetricFilter;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public final class SupichiWorkerCommand extends WorkerEnvironmentCommand<SupichiWorkerConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SupichiWorkerCommand.class);

    public SupichiWorkerCommand(String name, String description) {
        super(name, description);
    }

    @Override
    protected void run(Environment environment, Namespace namespace, SupichiWorkerConfiguration configuration) throws Exception {

//        final String queueName = configuration.queueName();
//        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.SPEECH_RESULTS);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

        // set up S3
        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.withConnectionTimeout(200); // in ms
        clientConfiguration.withMaxErrorRetry(1);
        final AmazonS3 amazonS3 = new AmazonS3Client(awsCredentialsProvider, clientConfiguration);
        amazonS3.setRegion(Region.getRegion(Regions.US_EAST_1));
        amazonS3.setEndpoint(configuration.s3Endpoint());

        final String senseUploadBucket = String.format("%s/%s",
                configuration.senseUploadAudioConfiguration().getBucketName(),
                configuration.senseUploadAudioConfiguration().getAudioPrefix());

        // KMS
        final SupichiWorkerConfiguration.KMSConfiguration kmsConfig = configuration.kmsConfiguration();
        final AWSKMSClient awskmsClient = new AWSKMSClient(awsCredentialsProvider);
        awskmsClient.setEndpoint(kmsConfig.endpoint());

        final SSEAwsKeyManagementParams s3SSEKey = new SSEAwsKeyManagementParams(kmsConfig.kmsKeys().audio());
        final Vault kmsVault = new KmsVault(awskmsClient, kmsConfig.kmsKeys().uuid());

        // DDB
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, configuration.dynamoDBConfiguration());
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final AmazonDynamoDB speechTimelineClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SPEECH_TIMELINE);
        final SpeechTimelineIngestDAO speechTimelineIngestDAO = SpeechTimelineIngestDAODynamoDB.create(speechTimelineClient, tableNames.get(DynamoDBTableName.SPEECH_TIMELINE), kmsVault);

        final AmazonDynamoDB speechResultsClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SPEECH_RESULTS);
        final SpeechResultIngestDAO speechResultIngestDAO = SpeechResultIngestDAODynamoDB.create(speechResultsClient, tableNames.get(DynamoDBTableName.SPEECH_RESULTS));


        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-supichi", apiKey, env);

            final ImmutableList<String> metrics = ImmutableList.copyOf(configuration.getGraphite().getIncludeMetrics());
            final RegexMetricFilter metricFilter = new RegexMetricFilter(metrics);

            final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHostName, 2003));

            final GraphiteReporter reporter = GraphiteReporter.forRegistry(environment.metrics())
                .prefixedWith(prefix)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(metricFilter)
                .build(graphite);
            reporter.start(interval, TimeUnit.SECONDS);

            LOGGER.info("Metrics enabled.");
        } else {
            LOGGER.warn("Metrics not enabled.");
        }

        final String workerId = InetAddress.getLocalHost().getCanonicalHostName();
        String kinesisAppName = configuration.getAppName();
        final InitialPositionInStream initialPositionInStream = configuration.trimHorizon() ? InitialPositionInStream.TRIM_HORIZON : InitialPositionInStream.LATEST;

        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                kinesisAppName,
                queueName,
                awsCredentialsProvider,
                workerId);

        kinesisConfig.withMaxRecords(configuration.maxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());
        kinesisConfig.withInitialPositionInStream(initialPositionInStream);
        kinesisConfig.withIdleTimeBetweenReadsInMillis(1000L);

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }

        final IRecordProcessorFactory processorFactory = new SupichiRecordProcessorFactory(
                senseUploadBucket,
                amazonS3,
                s3SSEKey,
                speechTimelineIngestDAO,
                speechResultIngestDAO,
                configuration.isDebug());

        final Worker worker = new Worker(processorFactory, kinesisConfig);
        worker.run();
    }
}
