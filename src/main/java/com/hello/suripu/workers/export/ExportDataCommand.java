package com.hello.suripu.workers.export;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.SleepStatsDAO;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.coredropwizard.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import com.microtripit.mandrillapp.lutung.MandrillApi;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExportDataCommand extends WorkerEnvironmentCommand<ExportDataConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(ExportDataCommand.class);

    public ExportDataCommand(String name, String description) {
        super(name, description);
    }
    
    @Override
    protected void run(Environment environment, Namespace namespace, ExportDataConfiguration configuration) throws Exception {
        
        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, configuration.dynamoDBConfiguration());
        final AmazonDynamoDB featureDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, tableNames.get(DynamoDBTableName.FEATURES), featureNamespace);

        final AmazonDynamoDB sleepStatsDDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_STATS);
        
        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        // set up Kinesis stream consumer
        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        final AmazonSQSBufferedAsyncClient client = new AmazonSQSBufferedAsyncClient(new AmazonSQSAsyncClient(awsCredentialsProvider));
        final SleepStatsDAO sleepStatsDAO  = new SleepStatsDAODynamoDB(
                sleepStatsDDB,
                tableNames.get(DynamoDBTableName.SLEEP_STATS),
                "v_0_2"
        );

        final AmazonS3 amazonS3 = new AmazonS3Client(awsCredentialsProvider);

        final MandrillApi mandrillApi = new MandrillApi(configuration.mandrillApiKey());
        final ExportDataProcessor exportDataProcessor = new ExportDataProcessor(
                client,
                amazonS3,
                sleepStatsDAO,
                configuration,
                environment.getObjectMapper(),
                mandrillApi);
        
        // blocks
        exportDataProcessor.process();

    }
}