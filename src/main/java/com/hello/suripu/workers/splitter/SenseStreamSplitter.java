package com.hello.suripu.workers.splitter;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Created by ksg on 4/8/16
 */
public class SenseStreamSplitter extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStreamSplitter.class);

    private final DataLogger dataLogger;
    private final Integer maxRecords;
    private MetricRegistry metrics;
    private String shardId = "";

    private final Meter capacity;
    private final Meter recordsProcessed;
    private final Meter recordsFailed;

    public SenseStreamSplitter(final DataLogger dataLogger, final Integer maxRecords, final MetricRegistry metrics) {
        this.dataLogger = dataLogger;
        this.maxRecords = maxRecords;
        this.metrics = metrics;
        this.capacity = this.metrics.meter(name(SenseStreamSplitter.class, "capacity"));
        this.recordsProcessed = this.metrics.meter(name(SenseStreamSplitter.class, "records-processed"));
        this.recordsFailed = this.metrics.meter(name(SenseStreamSplitter.class, "records-failed"));
    }

    @Override
    public void initialize(String s) {
        shardId = s;
    }

    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        for(final Record record : records) {
            DataInputProtos.BatchPeriodicDataWorker batchPeriodicDataWorker;
            try {
                batchPeriodicDataWorker = DataInputProtos.BatchPeriodicDataWorker.parseFrom(record.getData().array());
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed parsing protobuf: {}", e.getMessage());
                LOGGER.error("Moving to next record");
                continue;
            }

            final String deviceId = batchPeriodicDataWorker.getData().getDeviceId();
            this.recordsProcessed.mark();
            try {
                dataLogger.put(deviceId, record.getData().array());
            } catch (Exception e) {
                LOGGER.error("error=kinesis-insert-sense_sensors_data {}", e.getMessage());
                this.recordsFailed.mark();
            }

        }

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("checkpoint {}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
        }

        final int batchCapacity = Math.round(records.size() / (float) maxRecords * 100.0f) ;
        LOGGER.info("{} - capacity: {}%", shardId, batchCapacity);
        capacity.mark(batchCapacity);
    }


    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {

    }
}
