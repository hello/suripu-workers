package com.hello.suripu.workers.insights;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.AccountDAO;
import com.hello.suripu.core.db.AccountDAOImpl;
import com.hello.suripu.core.db.AggregateSleepScoreDAODynamoDB;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.CalibrationDynamoDB;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.DeviceDataDAODynamoDB;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.InsightsDAODynamoDB;
import com.hello.suripu.core.db.MarketingInsightsSeenDAODynamoDB;
import com.hello.suripu.core.db.QuestionResponseReadDAO;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.TrendsInsightsDAO;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.insights.InsightsLastSeenDAO;
import com.hello.suripu.core.insights.InsightsLastSeenDynamoDB;
import com.hello.suripu.core.preferences.AccountPreferencesDAO;
import com.hello.suripu.core.preferences.AccountPreferencesDynamoDB;
import com.hello.suripu.core.processors.insights.LightData;
import com.hello.suripu.core.processors.insights.WakeStdDevData;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.coredw8.metrics.RegexMetricFilter;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.jdbi.ImmutableListContainerFactory;
import io.dropwizard.jdbi.ImmutableSetContainerFactory;
import io.dropwizard.jdbi.OptionalContainerFactory;
import io.dropwizard.jdbi.args.OptionalArgumentFactory;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Created by kingshy on 1/6/15.
 */
public class InsightsGeneratorWorkerCommand extends WorkerEnvironmentCommand<InsightsGeneratorWorkerConfiguration> {
    private final static Logger LOGGER = LoggerFactory.getLogger(InsightsGeneratorWorkerCommand.class);

    public InsightsGeneratorWorkerCommand(String name, String description) {
        super(name, description);
    }

    @Override
    protected void run(Environment environment, Namespace namespace, InsightsGeneratorWorkerConfiguration configuration) throws Exception {

        final DBIFactory factory = new DBIFactory();
        final DBI commonDBI = factory.build(environment, configuration.getCommonDB(), "postgresql-common");
        final DBI insightsDBI = factory.build(environment, configuration.getInsightsDB(), "postgresql-insights");

        commonDBI.registerArgumentFactory(new OptionalArgumentFactory(configuration.getCommonDB().getDriverClass()));
        commonDBI.registerContainerFactory(new ImmutableListContainerFactory());
        commonDBI.registerContainerFactory(new ImmutableSetContainerFactory());
        commonDBI.registerContainerFactory(new OptionalContainerFactory());
        commonDBI.registerArgumentFactory(new JodaArgumentFactory());

        final AccountDAO accountDAO = commonDBI.onDemand(AccountDAOImpl.class);
        final DeviceDAO deviceDAO = commonDBI.onDemand(DeviceDAO.class);

        insightsDBI.registerArgumentFactory(new OptionalArgumentFactory(configuration.getInsightsDB().getDriverClass()));
        insightsDBI.registerContainerFactory(new ImmutableListContainerFactory());
        insightsDBI.registerContainerFactory(new ImmutableSetContainerFactory());
        insightsDBI.registerContainerFactory(new OptionalContainerFactory());
        insightsDBI.registerArgumentFactory(new JodaArgumentFactory());

        final TrendsInsightsDAO trendsInsightsDAO = insightsDBI.onDemand(TrendsInsightsDAO.class);
        final QuestionResponseReadDAO questionResponseDAO = insightsDBI.onDemand(QuestionResponseReadDAO.class);

        // metrics stuff
        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.isDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-workers-insights", apiKey, env);

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

        // setup kinesis queue
        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.BATCH_PILL_DATA);
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
        kinesisConfig.withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }

        // setup dynamoDB clients
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final AmazonDynamoDBClientFactory amazonDynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, configuration.dynamoDBConfiguration());

        final AmazonDynamoDB featureDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final FeatureStore featureStore = new FeatureStore(featureDynamoDB, tableNames.get(DynamoDBTableName.FEATURES), featureNamespace);

        final AmazonDynamoDB insightsDynamoDB = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.INSIGHTS);
        final InsightsDAODynamoDB insightsDAODynamoDB = new InsightsDAODynamoDB(insightsDynamoDB,
                tableNames.get(DynamoDBTableName.INSIGHTS));

        final AmazonDynamoDB insightsLastSeenDynamoDBClient= amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.INSIGHTS_LAST_SEEN);
        final InsightsLastSeenDAO insightsLastSeenDAO= InsightsLastSeenDynamoDB.create(insightsLastSeenDynamoDBClient,
                tableNames.get(DynamoDBTableName.INSIGHTS_LAST_SEEN));

        final AmazonDynamoDB accountPreferencesDynamoDBClient = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.PREFERENCES);
        final AccountPreferencesDAO accountPreferencesDynamoDB = AccountPreferencesDynamoDB.create(accountPreferencesDynamoDBClient,
                tableNames.get(DynamoDBTableName.PREFERENCES));

        final AmazonDynamoDB dynamoDBScoreClient = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_SCORE);
        final AggregateSleepScoreDAODynamoDB aggregateSleepScoreDAODynamoDB = new AggregateSleepScoreDAODynamoDB(
                dynamoDBScoreClient,
                tableNames.get(DynamoDBTableName.SLEEP_SCORE),
                configuration.getSleepScoreVersion()
        );

        final AmazonDynamoDB dynamoDBStatsClient = amazonDynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_STATS);
        final SleepStatsDAODynamoDB sleepStatsDAODynamoDB = new SleepStatsDAODynamoDB(dynamoDBStatsClient,
                tableNames.get(DynamoDBTableName.SLEEP_STATS),
                configuration.getSleepStatsVersion());


        final AmazonDynamoDB calibrationDynamoDBClient = amazonDynamoDBClientFactory.getInstrumented(DynamoDBTableName.CALIBRATION, CalibrationDynamoDB.class);
        final CalibrationDAO calibrationDAO = CalibrationDynamoDB.create(
                calibrationDynamoDBClient,
                tableNames.get(DynamoDBTableName.CALIBRATION)
        );

        final AmazonDynamoDB deviceDataDAODynamoDBClient = amazonDynamoDBClientFactory.getInstrumented(DynamoDBTableName.DEVICE_DATA, DeviceDataDAODynamoDB.class);
        final DeviceDataDAODynamoDB deviceDataDAODynamoDB = new DeviceDataDAODynamoDB(deviceDataDAODynamoDBClient, tableNames.get(DynamoDBTableName.DEVICE_DATA));

        final AmazonDynamoDB marketingInsightsDDBClient = amazonDynamoDBClientFactory.getInstrumented(DynamoDBTableName.MARKETING_INSIGHTS_SEEN, MarketingInsightsSeenDAODynamoDB.class);
        final MarketingInsightsSeenDAODynamoDB MarketingInsightsSeenDAODynamoDB = new MarketingInsightsSeenDAODynamoDB(marketingInsightsDDBClient, tableNames.get(DynamoDBTableName.MARKETING_INSIGHTS_SEEN));

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        // external data for insights computation
        final LightData lightData = new LightData(); // lights global distribution
        final WakeStdDevData wakeStdDevData = new WakeStdDevData();

        final IRecordProcessorFactory processorFactory = new InsightsGeneratorFactory(
                accountDAO,
                deviceDataDAODynamoDB,
                deviceDAO,
                aggregateSleepScoreDAODynamoDB,
                insightsDAODynamoDB,
                insightsLastSeenDAO,
                trendsInsightsDAO,
                questionResponseDAO,
                sleepStatsDAODynamoDB,
                lightData,
                wakeStdDevData,
                accountPreferencesDynamoDB,
                calibrationDAO,
                MarketingInsightsSeenDAODynamoDB);
        final Worker worker = new Worker(processorFactory, kinesisConfig);
        worker.run();
    }

}
