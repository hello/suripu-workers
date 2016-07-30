package com.hello.suripu.workers.pill.prox;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import org.skife.jdbi.v2.DBI;

public class PillProxDataProcessorFactory implements IRecordProcessorFactory {

    private final PillProxWorkerConfiguration configuration;
    private final DBI dbi;


    public PillProxDataProcessorFactory(final PillProxWorkerConfiguration configuration, final DBI dbi) {
        this.configuration = configuration;
        this.dbi = dbi;
    }

    @Override
    public IRecordProcessor createProcessor() {
        final DeviceDAO deviceDAO = dbi.onDemand(DeviceDAO.class);
        final AmazonDynamoDB ddbClient = new AmazonDynamoDBClient(new DefaultAWSCredentialsProviderChain());
        final PillProxDataDAODynamoDB pillProxDataDAODynamoDB = new PillProxDataDAODynamoDB(ddbClient, configuration.proxTablePrefix());
        final KeyStore pillKeyStore = new KeyStoreDynamoDB(ddbClient,configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.PILL_KEY_STORE), new byte[16], 120);

        return new PillProxDataProcessor(pillProxDataDAODynamoDB, configuration.getMaxRecords(), pillKeyStore, deviceDAO);
    }
}
