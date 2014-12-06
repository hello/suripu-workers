package com.hello.suripu.workers.alarm;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.core.db.MergedAlarmInfoDynamoDB;
import com.hello.suripu.core.db.RingTimeDAODynamoDB;
import com.hello.suripu.core.db.TrackerMotionDAO;
import com.hello.suripu.core.processors.RingProcessor;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.apache.commons.codec.binary.Hex;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by pangwu on 9/23/14.
 */
public class AlarmRecordProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(AlarmRecordProcessor.class);
    private final MergedAlarmInfoDynamoDB mergedAlarmInfoDynamoDB;
    private final RingTimeDAODynamoDB ringTimeDAODynamoDB;

    private final TrackerMotionDAO trackerMotionDAO;
    private final AlarmWorkerConfiguration configuration;

    public AlarmRecordProcessor(final MergedAlarmInfoDynamoDB mergedAlarmInfoDynamoDB,
                                final RingTimeDAODynamoDB ringTimeDAODynamoDB,
                                final TrackerMotionDAO trackerMotionDAO,
                                final AlarmWorkerConfiguration configuration){

        this.mergedAlarmInfoDynamoDB = mergedAlarmInfoDynamoDB;
        this.ringTimeDAODynamoDB = ringTimeDAODynamoDB;
        this.trackerMotionDAO = trackerMotionDAO;

        this.configuration = configuration;

    }

    @Override
    public void initialize(String s) {
        LOGGER.info("AlarmRecordProcessor initialized: " + s);
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {

        final Set<String> deviceIds = new HashSet<String>();

        for (final Record record : records) {
            try {
                final DataInputProtos.periodic_data data = DataInputProtos.periodic_data.parseFrom(record.getData().array());

                // get MAC address of morpheus
                final byte[] mac = Arrays.copyOf(data.getMac().toByteArray(), 6);
                final String morpheusId = new String(Hex.encodeHex(mac));

                deviceIds.add(morpheusId);


            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed to decode protobuf: {}", e.getMessage());
            }
        }

        final DateTime currentTime = DateTime.now();
        LOGGER.info("Got {} records.", records.size());
        for(final String morpheusId:deviceIds) {
            RingProcessor.updateNextRingTime(this.mergedAlarmInfoDynamoDB,
                    this.ringTimeDAODynamoDB,
                    this.trackerMotionDAO,
                    morpheusId,
                    currentTime,
                    this.configuration.getProcessAheadTimeInMinutes(),
                    this.configuration.getAggregateWindowSizeInMinute(),
                    this.configuration.getLightSleepThreshold(),
                    feature
                    );
        }


        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("checkpoint {}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
        }
    }

    @Override
    public void shutdown(final IRecordProcessorCheckpointer iRecordProcessorCheckpointer, final ShutdownReason shutdownReason) {
        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
    }
}
