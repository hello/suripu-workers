package com.hello.suripu.workers.expansions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;
import com.amazonaws.services.kms.AWSKMSClient;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.alerts.AlertsDAO;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.ScheduledRingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SmartAlarmLoggerDynamoDB;
import com.hello.suripu.core.db.TimeZoneHistoryDAO;
import com.hello.suripu.core.db.TimeZoneHistoryDAODynamoDB;
import com.hello.suripu.core.speech.KmsVault;
import com.hello.suripu.core.speech.interfaces.Vault;
import com.hello.suripu.coredropwizard.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.coredropwizard.configuration.KMSConfiguration;
import com.hello.suripu.coredropwizard.metrics.RegexMetricFilter;
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
import io.dropwizard.setup.Environment;
import is.hello.gaibu.core.db.ExpansionDataDAO;
import is.hello.gaibu.core.db.ExpansionsDAO;
import is.hello.gaibu.core.db.ExternalTokenDAO;
import is.hello.gaibu.core.stores.PersistentExpansionDataStore;
import is.hello.gaibu.core.stores.PersistentExpansionStore;
import is.hello.gaibu.core.stores.PersistentExternalTokenStore;
import okhttp3.OkHttpClient;
import redis.clients.jedis.JedisPool;

public class AlarmActionWorkerCommand extends WorkerEnvironmentCommand<AlarmActionWorkerConfiguration> {
    private final static Logger LOGGER = LoggerFactory.getLogger(AlarmActionWorkerCommand.class);

    public AlarmActionWorkerCommand(String name, String description) {
        super(name, description);
    }

    @Override
    public void run(Environment environment, Namespace namespace, final AlarmActionWorkerConfiguration configuration) throws Exception {

        final DBIFactory factory = new DBIFactory();
        final DBI commonDB = factory.build(environment, configuration.getCommonDB(), "commonDB");

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final ClientConfiguration clientConfig = new ClientConfiguration().withConnectionTimeout(200).withMaxErrorRetry(1).withMaxConnections(100);
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, clientConfig, configuration.getDynamoDBConfiguration());
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.getDynamoDBConfiguration().tables();

        final AmazonDynamoDB pillDataDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_DATA);
        final PillDataDAODynamoDB pillDataDAODynamoDB = new PillDataDAODynamoDB(pillDataDynamoDBClient, tableNames.get(DynamoDBTableName.PILL_DATA));

        final AmazonDynamoDB mergedInfoDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.ALARM_INFO);
        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(mergedInfoDynamoDBClient, tableNames.get(DynamoDBTableName.ALARM_INFO));

        final AmazonDynamoDB ringTimeHistoryDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.RING_TIME);
        final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB = new ScheduledRingTimeHistoryDAODynamoDB(ringTimeHistoryDynamoDBClient, tableNames.get(DynamoDBTableName.RING_TIME));

        final AmazonDynamoDB smartAlarmHistoryDynamoDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.SMART_ALARM_LOG, SmartAlarmLoggerDynamoDB.class);
        final SmartAlarmLoggerDynamoDB smartAlarmLoggerDynamoDB = new SmartAlarmLoggerDynamoDB(smartAlarmHistoryDynamoDBClient, tableNames.get(DynamoDBTableName.SMART_ALARM_LOG));

        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.FEATURES, FeatureStore.class);
        final FeatureStore featureStore = new FeatureStore(
            featuresDynamoDBClient,
            tableNames.get(DynamoDBTableName.FEATURES),
            featureNamespace
        );

        final AmazonDynamoDB timezoneHistoryClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.TIMEZONE_HISTORY);
        final TimeZoneHistoryDAO timeZoneHistoryDAO = new TimeZoneHistoryDAODynamoDB(timezoneHistoryClient, tableNames.get(DynamoDBTableName.TIMEZONE_HISTORY));

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        final String queueName = queueNames.get(QueueName.ALARM_ACTIONS);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);

        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-alarm", apiKey, env);

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

        final String workerId = InetAddress.getLocalHost().getCanonicalHostName() + ":" + UUID.randomUUID();
        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                configuration.getAppName(),
                queueName,
                awsCredentialsProvider,
                workerId);
        kinesisConfig.withMaxRecords(configuration.getMaxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());
        kinesisConfig.withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }

        final KMSConfiguration kmsConfig = configuration.kmsConfiguration();
        final AWSKMSClient awskmsClient = new AWSKMSClient(awsCredentialsProvider);
        awskmsClient.setEndpoint(kmsConfig.endpoint());

        final Vault tokenKMSVault = new KmsVault(awskmsClient, kmsConfig.kmsKeys().token());
        final OkHttpClient httpClient = new OkHttpClient();

        final ExpansionsDAO externalApplicationsDAO = commonDB.onDemand(ExpansionsDAO.class);
        final PersistentExpansionStore expansionStore = new PersistentExpansionStore(externalApplicationsDAO);

        final ExternalTokenDAO externalTokenDAO = commonDB.onDemand(ExternalTokenDAO.class);
        final PersistentExternalTokenStore externalTokenStore = new PersistentExternalTokenStore(externalTokenDAO, expansionStore, tokenKMSVault, httpClient);

        final ExpansionDataDAO expansionDataDAO = commonDB.onDemand(ExpansionDataDAO.class);
        final PersistentExpansionDataStore externalAppDataStore = new PersistentExpansionDataStore(expansionDataDAO);

        final DeviceDAO deviceDAO = commonDB.onDemand(DeviceDAO.class);
        final AlertsDAO alertsDAO = commonDB.onDemand(AlertsDAO.class);

        final JedisPool jedisPool = new JedisPool(
            configuration.redisConfiguration().getHost(),
            configuration.redisConfiguration().getPort()
        );

        final IRecordProcessorFactory processorFactory = new AlarmActionRecordProcessorFactory(
                mergedUserInfoDynamoDB,
                scheduledRingTimeHistoryDAODynamoDB,
                configuration,
                environment.metrics(),
                expansionStore,
                externalTokenStore,
                externalAppDataStore,
                tokenKMSVault,
                jedisPool,
                deviceDAO,
                alertsDAO,
                timeZoneHistoryDAO
        );

        final Worker worker = new Worker(processorFactory, kinesisConfig);
        worker.run();
    }
}
