package com.hello.suripu.workers.cli;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.workers.framework.SuripuWorkersSharedConfiguration;
import com.hello.suripu.workers.notifications.PushNotificationEventDynamoDB;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;

public class CreateDynamoDBTables extends ConfiguredCommand<SuripuWorkersSharedConfiguration> {
    protected CreateDynamoDBTables(String name, String description) {
        super(name, description);
    }

    @Override
    protected void run(Bootstrap<SuripuWorkersSharedConfiguration> bootstrap, Namespace namespace, SuripuWorkersSharedConfiguration suripuWorkersSharedConfiguration) throws Exception {



    }

    private void createPushNotificationEventsTable(SuripuWorkersSharedConfiguration configuration, AWSCredentialsProvider awsCredentialsProvider) throws InterruptedException {
        final AmazonDynamoDBClient client = new AmazonDynamoDBClient(awsCredentialsProvider);
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final ImmutableMap<DynamoDBTableName, String> endpoints = configuration.dynamoDBConfiguration().endpoints();

        final String tableName = tableNames.get(DynamoDBTableName.PUSH_NOTIFICATION_EVENT);
        final String endpoint = endpoints.get(DynamoDBTableName.PUSH_NOTIFICATION_EVENT);
        client.setEndpoint(endpoint);

        for(final Integer year : Lists.newArrayList(2017,2018)) {
            final String yearlyTableName = String.format("%s_%d", tableName, year);
            try {
                client.describeTable(yearlyTableName);
                System.out.println(String.format("%s already exists.", yearlyTableName));
            } catch (AmazonServiceException exception) {
                final PushNotificationEventDynamoDB pushNotificationEventDynamoDB = new PushNotificationEventDynamoDB(client, tableName);
                pushNotificationEventDynamoDB.createTable(yearlyTableName);
                System.out.println(String.format("%s created", yearlyTableName));
            }
        }
    }
}
