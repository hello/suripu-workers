package com.hello.suripu.workers.alarm;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.ScheduledRingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SmartAlarmLoggerDynamoDB;
import org.joda.time.DateTime;

import java.util.Map;

/**
 * Created by pangwu on 9/23/14.
 */
public class AlarmRecordProcessorFactory implements IRecordProcessorFactory {

    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB;
    private final SmartAlarmLoggerDynamoDB smartAlarmLoggerDynamoDB;
    private final PillDataDAODynamoDB pillDataDAODynamoDB;
    private final AlarmWorkerConfiguration configuration;
    private final Map<String, DateTime> senseIdLastProcessed;
    private final MetricRegistry metricRegistry;


    public AlarmRecordProcessorFactory(
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB,
            final SmartAlarmLoggerDynamoDB smartAlarmLoggerDynamoDB,
            final PillDataDAODynamoDB pillDataDAODynamoDB,
            final AlarmWorkerConfiguration configuration,
            final Map<String, DateTime> senseIdLastProcessed,
            final MetricRegistry metrics) {

        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.pillDataDAODynamoDB = pillDataDAODynamoDB;
        this.scheduledRingTimeHistoryDAODynamoDB = scheduledRingTimeHistoryDAODynamoDB;
        this.smartAlarmLoggerDynamoDB = smartAlarmLoggerDynamoDB;
        this.configuration = configuration;
        this.senseIdLastProcessed = senseIdLastProcessed;
        this.metricRegistry = metrics;
    }


    @Override
    public IRecordProcessor createProcessor() {
        return new AlarmRecordProcessor(this.mergedUserInfoDynamoDB,
                this.scheduledRingTimeHistoryDAODynamoDB,
                this.smartAlarmLoggerDynamoDB,
                this.pillDataDAODynamoDB,
                this.configuration,
                this.senseIdLastProcessed,
                this.metricRegistry
            );
    }
}
