package com.hello.suripu.workers;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputDescription;

public class DynamoDBTablesInspection {

    public static void main(String[] args) {
        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final AmazonDynamoDB amazonDynamoDB = new AmazonDynamoDBClient(awsCredentialsProvider);

        final ListTablesResult listTablesResult = amazonDynamoDB.listTables();
        for(final String tableName : listTablesResult.getTableNames()) {

            final DescribeTableResult describeTableResult = amazonDynamoDB.describeTable(tableName);
            final ProvisionedThroughputDescription provisionedThroughputDescription = describeTableResult.getTable().getProvisionedThroughput();

            // Regular tables
            if(!tableName.startsWith("prod") && !tableName.endsWith("Prod") && provisionedThroughputDescription.getWriteCapacityUnits() > 1) {
                System.out.println("table: " + tableName);
                System.out.println("\treads: " + provisionedThroughputDescription.getReadCapacityUnits());
                System.out.println("\twrites: " + provisionedThroughputDescription.getWriteCapacityUnits());
            } else if (!tableName.endsWith("Prod") && provisionedThroughputDescription.getWriteCapacityUnits() > 1) {
                // Workers
                System.out.println("table: " + tableName);
                System.out.println("\treads: " + provisionedThroughputDescription.getReadCapacityUnits());
                System.out.println("\twrites: " + provisionedThroughputDescription.getWriteCapacityUnits());
            }
        }

    }
}
