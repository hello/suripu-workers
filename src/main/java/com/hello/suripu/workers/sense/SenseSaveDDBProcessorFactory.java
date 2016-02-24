package com.hello.suripu.workers.sense;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.DeviceDataIngestDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;

/**
 * Created by jakepiccolo on 11/4/15.
 */
public class SenseSaveDDBProcessorFactory implements IRecordProcessorFactory {
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final DeviceDataIngestDAO deviceDataDAO;
    private final Integer maxRecords;
    private final MetricRegistry metricRegistry;

    public SenseSaveDDBProcessorFactory(
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final DeviceDataIngestDAO deviceDataDAO,
            final Integer maxRecords,
            final MetricRegistry metricRegistry)
    {
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.deviceDataDAO = deviceDataDAO;
        this.maxRecords = maxRecords;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public IRecordProcessor createProcessor() {
        return new SenseSaveDDBProcessor(mergedUserInfoDynamoDB, deviceDataDAO, maxRecords, metricRegistry);
    }
}
