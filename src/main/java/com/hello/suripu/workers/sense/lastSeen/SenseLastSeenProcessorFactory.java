package com.hello.suripu.workers.sense.lastSeen;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.SensorsViewsDynamoDB;
import com.hello.suripu.core.db.WifiInfoDAO;

public class SenseLastSeenProcessorFactory implements IRecordProcessorFactory {
    private final Integer maxRecords;
    private final WifiInfoDAO wifiInfoDAO;
    private final SensorsViewsDynamoDB sensorsViewsDynamoDB;
    private final MetricRegistry metricRegistry;

    public SenseLastSeenProcessorFactory(final Integer maxRecords,
                                         final WifiInfoDAO wifiInfoDAO,
                                         final SensorsViewsDynamoDB sensorsViewsDynamoDB,
                                         final MetricRegistry metricRegistry){

        this.maxRecords = maxRecords;
        this.wifiInfoDAO = wifiInfoDAO;
        this.sensorsViewsDynamoDB = sensorsViewsDynamoDB;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public IRecordProcessor createProcessor() {
        return new SenseLastSeenProcessor(maxRecords, wifiInfoDAO, sensorsViewsDynamoDB, metricRegistry);
    }
}
