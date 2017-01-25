package com.hello.suripu.workers.notifications;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.AppStatsDAO;
import com.hello.suripu.core.db.AppStatsDAODynamoDB;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.TimeZoneHistoryDAO;
import com.hello.suripu.core.db.TimeZoneHistoryDAODynamoDB;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.flipper.DynamoDBAdapter;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessor;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessorImpl;
import com.hello.suripu.core.notifications.NotificationSubscriptionsReadDAO;
import com.hello.suripu.core.notifications.PushNotificationEventDynamoDB;
import com.hello.suripu.core.preferences.AccountPreferencesDAO;
import com.hello.suripu.core.preferences.AccountPreferencesDynamoDB;
import com.hello.suripu.coredropwizard.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.framework.WorkerEnvironmentCommand;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import com.librato.rollout.RolloutClient;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;

public class PushNotificationsWorkerCommand extends WorkerEnvironmentCommand<PushNotificationsWorkerConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(PushNotificationsWorkerCommand.class);

    public PushNotificationsWorkerCommand(final String name, final String description) {
        super(name, description);
    }

    @Override
    protected void run(Environment environment, Namespace namespace, final PushNotificationsWorkerConfiguration configuration) throws Exception {

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

        final ImmutableMap<QueueName, String> queueNames = configuration.getQueues();

        LOGGER.debug("{}", queueNames);
        final String queueName = queueNames.get(QueueName.PUSH_NOTIFICATIONS);
        LOGGER.info("\n\n\n!!! This worker is using the following queue: {} !!!\n\n\n", queueName);


        final String workerId = InetAddress.getLocalHost().getCanonicalHostName();
        final KinesisClientLibConfiguration kinesisConfig = new KinesisClientLibConfiguration(
                configuration.getAppName(),
                queueName,
                awsCredentialsProvider,
                workerId);
        kinesisConfig.withMaxRecords(configuration.getMaxRecords());
        kinesisConfig.withKinesisEndpoint(configuration.getKinesisEndpoint());
        kinesisConfig.withInitialPositionInStream(InitialPositionInStream.LATEST); // only moving forward, we don't want to replay push notifications

        if(configuration.isDebug()) {
            kinesisConfig.withMetricsLevel(MetricsLevel.NONE);
        }


        final DBIFactory factory = new DBIFactory();
        final DBI commonDBI = factory.build(environment, configuration.getCommonDB(), "postgresql-common");

        commonDBI.registerArgumentFactory(new JodaArgumentFactory());


        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final ClientConfiguration clientConfig = new ClientConfiguration().withConnectionTimeout(200).withMaxErrorRetry(1).withMaxConnections(100);
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, clientConfig, configuration.dynamoDBConfiguration());

        final TimeZoneHistoryDAO timeZoneHistoryDAO = new TimeZoneHistoryDAODynamoDB(dynamoDBClientFactory.getForTable(DynamoDBTableName.TIMEZONE_HISTORY), tableNames.get(DynamoDBTableName.TIMEZONE_HISTORY));
        final AccountPreferencesDAO accountPreferencesDAO = AccountPreferencesDynamoDB.create(dynamoDBClientFactory.getForTable(DynamoDBTableName.PREFERENCES), tableNames.get(DynamoDBTableName.PREFERENCES));
        final AppStatsDAO appStatsDAO = new AppStatsDAODynamoDB(dynamoDBClientFactory.getForTable(DynamoDBTableName.APP_STATS), tableNames.get(DynamoDBTableName.APP_STATS));

        final NotificationSubscriptionsReadDAO notificationSubscriptionsDAO = commonDBI.onDemand(NotificationSubscriptionsReadDAO.class);
        final AmazonSNS amazonSNS = new AmazonSNSClient(awsCredentialsProvider);
        final AmazonDynamoDB pushNotificationDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PUSH_NOTIFICATION_EVENT);
        final PushNotificationEventDynamoDB pushNotificationEventDynamoDB = new PushNotificationEventDynamoDB(
                pushNotificationDynamoDBClient,
                configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.PUSH_NOTIFICATION_EVENT));

        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.FEATURES, FeatureStore.class);
        final FeatureStore featureStore = new FeatureStore(
                featuresDynamoDBClient,
                tableNames.get(DynamoDBTableName.FEATURES),
                featureNamespace
        );

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final MobilePushNotificationProcessor pushNotificationProcessor = new MobilePushNotificationProcessorImpl.Builder()
                .withSns(amazonSNS)
                .withSubscriptionDAO(notificationSubscriptionsDAO)
                .withPushNotificationEventDynamoDB(pushNotificationEventDynamoDB)
                .withMapper(environment.getObjectMapper())
                .withTimeZoneHistory(timeZoneHistoryDAO)
                .withAccountPreferencesDAO(accountPreferencesDAO)
                .withFeatureFlipper(new RolloutClient(new DynamoDBAdapter(featureStore, 30)))
                .withAppStatsDAO(appStatsDAO)
                .build();

        final HelloPushMessageGenerator pushMessageGenerator = new HelloPushMessageGenerator();

        final IRecordProcessorFactory kinesisFactory = new PushNotificationsProcessorFactory(pushNotificationProcessor, pushMessageGenerator, configuration.getActiveHours());
        final Worker worker = new Worker(kinesisFactory, kinesisConfig);
        worker.run();
    }
}
