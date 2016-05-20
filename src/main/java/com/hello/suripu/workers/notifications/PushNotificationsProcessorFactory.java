package com.hello.suripu.workers.notifications;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.notifications.NotificationSubscriptionsReadDAO;
import com.hello.suripu.core.preferences.AccountPreferencesDynamoDB;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.workers.framework.WorkerRolloutModule;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.jdbi.ImmutableListContainerFactory;
import io.dropwizard.jdbi.ImmutableSetContainerFactory;
import io.dropwizard.jdbi.OptionalContainerFactory;
import io.dropwizard.jdbi.args.OptionalArgumentFactory;
import io.dropwizard.setup.Environment;
import org.skife.jdbi.v2.DBI;

public class PushNotificationsProcessorFactory implements IRecordProcessorFactory {

    private final PushNotificationsWorkerConfiguration configuration;
    private final AWSCredentialsProvider awsCredentialsProvider;
    private final Environment environment;

    public PushNotificationsProcessorFactory(Environment environment, final PushNotificationsWorkerConfiguration configuration, final AWSCredentialsProvider awsCredentialsProvider) {
        this.configuration = configuration;
        this.awsCredentialsProvider = awsCredentialsProvider;
        this.environment = environment;
    }

    @Override
    public IRecordProcessor createProcessor()  {

        final DBIFactory factory = new DBIFactory();
        final DBI commonDBI = factory.build(environment, configuration.getCommonDB(), "postgresql-common");

        commonDBI.registerArgumentFactory(new OptionalArgumentFactory(configuration.getCommonDB().getDriverClass()));
        commonDBI.registerContainerFactory(new ImmutableListContainerFactory());
        commonDBI.registerContainerFactory(new ImmutableSetContainerFactory());
        commonDBI.registerContainerFactory(new OptionalContainerFactory());
        commonDBI.registerArgumentFactory(new JodaArgumentFactory());

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final ClientConfiguration clientConfig = new ClientConfiguration().withConnectionTimeout(200).withMaxErrorRetry(1).withMaxConnections(100);
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, clientConfig, configuration.dynamoDBConfiguration());

        final NotificationSubscriptionsReadDAO notificationSubscriptionsDAO = commonDBI.onDemand(NotificationSubscriptionsReadDAO.class);
        final AmazonSNS amazonSNS = new AmazonSNSClient(awsCredentialsProvider);
        final AmazonDynamoDB pushNotificationDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PUSH_NOTIFICATION_EVENT);
        final PushNotificationEventDynamoDB pushNotificationEventDynamoDB = new PushNotificationEventDynamoDB(
                pushNotificationDynamoDBClient,
                configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.PUSH_NOTIFICATION_EVENT));
        final MobilePushNotificationProcessor pushNotificationProcessor = new MobilePushNotificationProcessor(amazonSNS, notificationSubscriptionsDAO, pushNotificationEventDynamoDB);

        final String featureNamespace = (configuration.isDebug()) ? "dev" : "prod";
        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBClientFactory.getInstrumented(DynamoDBTableName.FEATURES, FeatureStore.class);
        final FeatureStore featureStore = new FeatureStore(
            featuresDynamoDBClient,
            tableNames.get(DynamoDBTableName.FEATURES),
            featureNamespace
        );

        final WorkerRolloutModule workerRolloutModule = new WorkerRolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(workerRolloutModule);

        final AmazonDynamoDB mergedUserInfoDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.ALARM_INFO);
        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(mergedUserInfoDynamoDBClient, configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.ALARM_INFO));

        final AmazonDynamoDB accountPreferencesDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PREFERENCES);
        final AccountPreferencesDynamoDB accountPreferencesDynamoDB = AccountPreferencesDynamoDB.create(accountPreferencesDynamoDBClient, tableNames.get(DynamoDBTableName.PREFERENCES));

        return new PushNotificationsProcessor(pushNotificationProcessor, mergedUserInfoDynamoDB, accountPreferencesDynamoDB, configuration);
    }
}
