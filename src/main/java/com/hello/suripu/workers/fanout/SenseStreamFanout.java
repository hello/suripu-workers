package com.hello.suripu.workers.fanout;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.DataLoggerBatchPayload;
import com.hello.suripu.core.logging.KinesisBatchPutResult;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Created by ksg on 4/8/16
 */
public class SenseStreamFanout extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStreamFanout.class);

    private final DataLogger dataLogger;
    private final Integer maxRecords;
    private String shardId = "";

    private final Meter capacity;
    private final Meter recordsProcessed;
    private final Meter recordsPut;
    private final Meter recordsSuccess;
    private final Meter recordsFailed;

    private class FanoutBatchData {
        public DataLoggerBatchPayload payload;
        public String originalSequenceNumber;

        FanoutBatchData(final DataLoggerBatchPayload payload, final String originalSequenceNumber) {
            this.originalSequenceNumber = originalSequenceNumber;
            this.payload = payload;
        }
    }

    private List<FanoutBatchData> batchData = Lists.newArrayList();
    private String lastSuccessfulSequenceNumber = "";

    public SenseStreamFanout(final DataLogger dataLogger, final Integer maxRecords, final MetricRegistry metrics) {
        this.dataLogger = dataLogger;
        this.maxRecords = maxRecords;
        this.capacity = metrics.meter(name(SenseStreamFanout.class, "capacity"));
        this.recordsProcessed = metrics.meter(name(SenseStreamFanout.class, "records-processed"));
        this.recordsPut = metrics.meter(name(SenseStreamFanout.class, "records-sent-to-put"));
        this.recordsSuccess = metrics.meter(name(SenseStreamFanout.class, "records-success"));
        this.recordsFailed = metrics.meter(name(SenseStreamFanout.class, "records-failed"));
    }

    @Override
    public void initialize(String s) {
        shardId = s;
    }

    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {

        for(final Record record : records) {
            final String partitionKey = record.getPartitionKey();
            this.recordsProcessed.mark();
            batchData.add(new FanoutBatchData(
                    new DataLoggerBatchPayload(partitionKey, record.getData().array()),
                    record.getSequenceNumber()));
        }

        // compile all available data
        final List<DataLoggerBatchPayload> batchPayload = Lists.newArrayList();
        for (final FanoutBatchData data : batchData) {
            batchPayload.add(data.payload);
        }

        recordsPut.mark(batchPayload.size());

        final KinesisBatchPutResult batchPutResult = dataLogger.putRecords(batchPayload);

        // process failures
        final List<FanoutBatchData> failedRecords = Lists.newArrayList();
        if (batchPutResult.success != batchPutResult.batchSize) {
            for (int i = 0; i < batchPutResult.successPuts.size(); i++) {
                if (!batchPutResult.successPuts.get(i)) {
                    LOGGER.error("error=fail-put-to-fanout-Kinesis, partition_key={}, shard_number={}",
                            batchData.get(i).payload.deviceId,
                            batchData.get(i).originalSequenceNumber);

                    failedRecords.add(batchData.get(i));
                    recordsFailed.mark();
                } else {
                    lastSuccessfulSequenceNumber = batchData.get(i).originalSequenceNumber;
                }
            }
        }

        recordsSuccess.mark(batchPutResult.success);

        batchData.clear();

        // re-add failed
        if (!failedRecords.isEmpty()) {
            batchData.addAll(failedRecords);
        }

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("error=checkpoint-invalid-state-exception error_msg={}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("error=checkpoint-received-shutdown-command-bailing error_msg={}", e.getMessage());
        }

        final int batchCapacity = Math.round(records.size() / (float) maxRecords * 100.0f) ;
        LOGGER.info("shard={} batch-capacity={}%", shardId, batchCapacity);
        capacity.mark(batchCapacity);
    }


    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {
        LOGGER.warn("warning=SHUTDOWN reason={} shard={}", shutdownReason.toString(), shardId);
        if(shutdownReason == ShutdownReason.TERMINATE) {
            LOGGER.warn("warning=shutting-down-going-to-checkpoint");
            try {
                if (lastSuccessfulSequenceNumber.isEmpty()) {
                    iRecordProcessorCheckpointer.checkpoint();
                } else {
                    iRecordProcessorCheckpointer.checkpoint(lastSuccessfulSequenceNumber);
                }
                LOGGER.warn("warning=shutting-down-checkpoint-successfully");
            } catch (InvalidStateException | ShutdownException e) {
                LOGGER.error("error=shutting-down-checkpoint-failed, error_msg={}", e.getMessage());
            }
        }  else {
            LOGGER.error("error=unknown-shutdown-reason-exit last_seq_num={}", lastSuccessfulSequenceNumber);
            System.exit(1);
        }

    }
}
