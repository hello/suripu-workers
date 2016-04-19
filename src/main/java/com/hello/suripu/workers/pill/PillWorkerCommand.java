package com.hello.suripu.workers.pill;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
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
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.PillDataIngestDAO;
import com.hello.suripu.core.db.PillHeartBeatDAO;
import com.hello.suripu.core.db.TrackerMotionDAO;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.pill.heartbeat.PillHeartBeatDAODynamoDB;
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

public final class PillWorkerCommand extends WorkerEnvironmentCommand<PillWorkerConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(PillWorkerCommand.class);

    private boolean useDynamoPillData = false;

    public PillWorkerCommand(String name, String description) {
        super(name, description);
    }

    public PillWorkerCommand(String name, String description, final boolean useDynamoPillData) {
        this(name, description);
        this.useDynamoPillData = useDynamoPillData;
    }

    @Override
    protected void run(Environment environment, Namespace namespace, PillWorkerConfiguration configuration) throws Exception {
        final DBIFactory factory = new DBIFactory();
        final DBI commonDBI = factory.build(environment, configuration.getCommonDB(), "postgresql-common");
        final DBI sensorsDBI = factory.build(environment, configuration.getSensorsDB(), "postgresql-sensors");

        // Joda Argument factory is not supported by default by DW, needs to be added manually
        commonDBI.registerArgumentFactory(new JodaArgumentFactory());

        final DeviceDAO deviceDAO = commonDBI.onDemand(DeviceDAO.class);
        final PillHeartBeatDAO heartBeatDAO = commonDBI.onDemand(PillHeartBeatDAO.class);

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.BATCH_PILL_DATA);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);



        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-pill", apiKey, env);

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

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final String workerId = InetAddress.getLocalHost().getCanonicalHostName();
        String kinesisAppName = configuration.getAppName();
        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                kinesisAppName,
                queueName,
                awsCredentialsProvider,
                workerId);
        kinesisConfig.withMaxRecords(configuration.getMaxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());
        kinesisConfig.withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, configuration.dynamoDBConfiguration());

        final AmazonDynamoDB featureDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, tableNames.get(DynamoDBTableName.FEATURES), featureNamespace);

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final AmazonDynamoDB mergedUserInfoDynamoDBClient = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.ALARM_INFO);
        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(mergedUserInfoDynamoDBClient, tableNames.get(DynamoDBTableName.ALARM_INFO));

        final AmazonDynamoDB pillKeyStoreDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_KEY_STORE);
        final KeyStore pillKeyStore = new KeyStoreDynamoDB(pillKeyStoreDynamoDB,tableNames.get(DynamoDBTableName.PILL_KEY_STORE), new byte[16], 120);
        final AmazonDynamoDB pillDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_HEARTBEAT);
        final PillHeartBeatDAODynamoDB pillHeartBeatDAODynamoDB = PillHeartBeatDAODynamoDB.create(pillDynamoDB, tableNames.get(DynamoDBTableName.PILL_HEARTBEAT));

        PillDataIngestDAO pillDataIngestDAO;
        Boolean savePillHeartBeat = true;
        if (useDynamoPillData) {
            final AmazonDynamoDB trackerMotionDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_DATA);
            pillDataIngestDAO = new PillDataDAODynamoDB(trackerMotionDynamoDB, tableNames.get(DynamoDBTableName.PILL_DATA));
            savePillHeartBeat = false;
        } else {
            sensorsDBI.registerArgumentFactory(new JodaArgumentFactory());
            pillDataIngestDAO = sensorsDBI.onDemand(TrackerMotionDAO.class);
        }

        final IRecordProcessorFactory processorFactory = new SavePillDataProcessorFactory(
            pillDataIngestDAO,
            configuration.getBatchSize(),
            mergedUserInfoDynamoDB,
            pillKeyStore,
            deviceDAO,
            pillHeartBeatDAODynamoDB,
            savePillHeartBeat,
            environment.metrics()
        );
        final Worker worker = new Worker(processorFactory, kinesisConfig);
        worker.run();
    }
}
