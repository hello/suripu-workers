package com.hello.suripu.workers.insights;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.hello.suripu.core.db.AggStatsDAODynamoDB;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.DeviceDataDAODynamoDB;
import com.hello.suripu.core.db.DeviceReadDAO;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.SleepStatsDAO;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.colors.SenseColorDAO;
import com.hello.suripu.core.models.Calibration;
import com.hello.suripu.core.processors.AggStatsProcessor;

/**
 * Created by jyfan on 7/12/16.
 */
public class AggStatsGeneratorFactory implements IRecordProcessorFactory {
    private final SleepStatsDAODynamoDB sleepStatsDAODynamoDB;
    final PillDataDAODynamoDB pillDataDAODynamoDB;
    final DeviceReadDAO deviceReadDAO;
    final DeviceDataDAODynamoDB deviceDataDAODynamoDB;
    final SenseColorDAO senseColorDAO;
    final CalibrationDAO calibrationDAO;
    final AggStatsDAODynamoDB aggStatsDAODynamoDB;

    public AggStatsGeneratorFactory(final SleepStatsDAODynamoDB sleepStatsDAODynamoDB,
                                    final PillDataDAODynamoDB pillDataDAODynamoDB,
                                    final DeviceReadDAO deviceReadDAO,
                                    final DeviceDataDAODynamoDB deviceDataDAODynamoDB,
                                    final SenseColorDAO senseColorDAO,
                                    final CalibrationDAO calibrationDAO,
                                    final AggStatsDAODynamoDB aggStatsDAODynamoDB) {
        this.sleepStatsDAODynamoDB = sleepStatsDAODynamoDB;
        this.pillDataDAODynamoDB = pillDataDAODynamoDB;
        this.deviceReadDAO = deviceReadDAO;
        this.deviceDataDAODynamoDB = deviceDataDAODynamoDB;
        this.senseColorDAO = senseColorDAO;
        this.calibrationDAO = calibrationDAO;
        this.aggStatsDAODynamoDB = aggStatsDAODynamoDB;
    }

    @Override
    public IRecordProcessor createProcessor() {

        final AggStatsProcessor.Builder aggStatsProcessorBuilder = new AggStatsProcessor.Builder()
                .withSleepStatsDAODynamoDB(sleepStatsDAODynamoDB)
                .withPillDataDAODynamoDB(pillDataDAODynamoDB)
                .withDeviceDataDAODynamoDB(deviceDataDAODynamoDB)
                .withSenseColorDAO(senseColorDAO)
                .withCalibrationDAO(calibrationDAO)
                .withAggStatsDAO(aggStatsDAODynamoDB);

        final AggStatsProcessor aggStatsProcessor = aggStatsProcessorBuilder.build();


        return new AggStatsGenerator(deviceReadDAO, aggStatsProcessor);
    }
}
