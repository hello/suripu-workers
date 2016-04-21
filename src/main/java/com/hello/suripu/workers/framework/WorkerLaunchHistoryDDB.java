package com.hello.suripu.workers.framework;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.google.common.collect.Lists;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WorkerLaunchHistoryDDB implements WorkerLaunchHistoryDAO {

    private final String tableName;
    private final DynamoDB dynamoDB;

    private final static Logger LOGGER = LoggerFactory.getLogger(WorkerLaunchHistoryDDB.class);

    private final static String APPLICATION_NAME_ATTRIBUTE_NAME = "application_name";
    private final static String HOSTNAME_ATTRIBUTE_NAME = "hostname";
    private final static String LAUNCHED_AT_ATTRIBUTE_NAME = "launched_at";

    // milliseconds required since multiple hosts could boot at the same time
    private final static String DATE_TIME_STRING_TEMPLATE = "yyyy-MM-dd HH:mm:ss.SSS";
    private final static DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormat.forPattern(DATE_TIME_STRING_TEMPLATE);
    private WorkerLaunchHistoryDDB(final DynamoDB dynamoDB, final String tableName) {
        this.tableName = tableName;
        this.dynamoDB = dynamoDB;
    }

    /**
     * Creates a DDB WorkerLaunchHistory to record when workers start
     * @param amazonDynamoDB
     * @param tableName
     * @return
     */
    public static WorkerLaunchHistoryDDB create(final AmazonDynamoDB amazonDynamoDB, final String tableName) {
        final DynamoDB dynamoDB = new DynamoDB(amazonDynamoDB);
        return new WorkerLaunchHistoryDDB(dynamoDB, tableName);
    }

    public static WorkerLaunchHistoryDDB createWithTable(final AmazonDynamoDB amazonDynamoDB, final String tableName) {
        final DynamoDB dynamoDB = new DynamoDB(amazonDynamoDB);
        try {
            WorkerLaunchHistoryDDB.createTable(amazonDynamoDB, tableName);
        } catch (ResourceInUseException resourceInUseException) {
            LOGGER.info("action=create-ddb-table table_name={} result=already-exists", tableName);
            // ignore if table already exists
            // other exceptions will throw
        } catch (InterruptedException interruptedException) {
            LOGGER.error("action=create-ddb-table table_name={} result={}", tableName, interruptedException.getMessage());
            throw new RuntimeException(interruptedException.getMessage()); // force app to fail to boot
        }
        return new WorkerLaunchHistoryDDB(dynamoDB, tableName);
    }

    @Override
    public void register(String applicationName, String hostname) {
        final DateTime when = DateTime.now(DateTimeZone.UTC);
        register(applicationName, hostname, when);
    }

    @Override
    public void register(String applicationName, String hostname, DateTime when) {
        final Table table = dynamoDB.getTable(tableName);
        final Item item = new Item()
                .withString(APPLICATION_NAME_ATTRIBUTE_NAME, applicationName)
                .withString(LAUNCHED_AT_ATTRIBUTE_NAME, when.toString(DATE_TIME_FORMATTER))
                .withString(HOSTNAME_ATTRIBUTE_NAME, hostname);

        table.putItem(item);
    }

    public static void createTable(final AmazonDynamoDB amazonDynamoDB, final String tableName) throws InterruptedException {
        final DynamoDB dynamoDB = new DynamoDB(amazonDynamoDB);

        final List<KeySchemaElement> keySchemaElements = Lists.newArrayList(
                new KeySchemaElement().withAttributeName(APPLICATION_NAME_ATTRIBUTE_NAME).withKeyType(KeyType.HASH),
                new KeySchemaElement().withAttributeName(LAUNCHED_AT_ATTRIBUTE_NAME).withKeyType(KeyType.RANGE)
        );

        final List<AttributeDefinition> attributeDefinitions = Lists.newArrayList(
                new AttributeDefinition().withAttributeName(APPLICATION_NAME_ATTRIBUTE_NAME).withAttributeType(ScalarAttributeType.S),
                new AttributeDefinition().withAttributeName(LAUNCHED_AT_ATTRIBUTE_NAME).withAttributeType(ScalarAttributeType.S)
        );

        final ProvisionedThroughput provisionedThroughput = new ProvisionedThroughput()
                .withReadCapacityUnits(1L)
                .withWriteCapacityUnits(1L);

        final Table table = dynamoDB.createTable(tableName, keySchemaElements,  attributeDefinitions, provisionedThroughput);
        table.waitForActive();
        LOGGER.info("action=create-ddb-table table_name={} result=table-created", tableName);
    }
}
