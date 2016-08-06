package com.hello.suripu.workers.insights;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.core.db.DeviceReadDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.processors.AggStatsProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by jyfan on 7/1/16.
 */
public class AggStatsGenerator implements IRecordProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(AggStatsGenerator.class);
    private final static Float MAX_ALLOWED_ERROR_PERCENT = 0.2f;

    private final AggStatsProcessor aggStatsProcessor;
    private final DeviceReadDAO deviceReadDAO;


    public AggStatsGenerator(final DeviceReadDAO deviceReadDAO,
                             final AggStatsProcessor aggStatsProcessor) {
        this.deviceReadDAO = deviceReadDAO;
        this.aggStatsProcessor = aggStatsProcessor;

    }

//    @Override
    public void initialize(String s) {}

    @Timed
    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {

        LOGGER.debug("record_size={}", records.size());
        if (records.isEmpty()) {
            LOGGER.warn("warn=empty-record-list");
            return;
        }

        final Optional<Record> lastProcessedRecord = processKinesisRecords(records);

        if (!lastProcessedRecord.isPresent()) {
            LOGGER.error("action=system-exit reason=too-many-errors");
            System.exit(1);
        }

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException | ShutdownException e) {
            LOGGER.error("error=checkpoint-fail exception={}", e.getMessage());
        }

    }

    private Optional<Record> processKinesisRecords(List<Record> records) {

        //Assumes non-empty records list
        final Integer numRecords = records.size();
        final Integer maxAllowedErrors = (int) Math.ceil(numRecords * MAX_ALLOWED_ERROR_PERCENT);

        Record lastProcessedRecord = records.get(0);
        Integer errorCount = 0;

        for (final Record record : records) {
            try {
                final SenseCommandProtos.batched_pill_data data = SenseCommandProtos.batched_pill_data.parseFrom(record.getData().array());

                for (final SenseCommandProtos.pill_data pill : data.getPillsList()) {
                    if (!pill.hasBatteryLevel()) {
                        LOGGER.debug("action=no-agg-stats reason=no-battery-level deviceId={}", pill.getDeviceId().toString());
                        continue;
                    }

                    final Optional<DeviceAccountPair> deviceAccountPairOptional = this.deviceReadDAO.getInternalPillId(pill.getDeviceId());
                    if (!deviceAccountPairOptional.isPresent()) {
                        LOGGER.error("action=no-agg-stats reason=no-device-account-pair deviceId={}", pill.getDeviceId().toString());
                        continue;
                    }

                    //Compute and save agg stats
                    LOGGER.debug("action=get-agg-stats account_id={}", deviceAccountPairOptional.get().accountId);
                    this.aggStatsProcessor.generateCurrentAggStats(deviceAccountPairOptional.get());
                }

                lastProcessedRecord = record;

            } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
                errorCount += 1;
                LOGGER.error("action=received-malformed-protobuf exception={} record={}", e.getMessage(), record.toString());
            }

        }

        LOGGER.info("action=finished-records-list error_count={}", errorCount);
        if (errorCount > maxAllowedErrors) {
            LOGGER.error("action=too-many-errors error_count={} last_record={}", errorCount, lastProcessedRecord.toString());
            return Optional.absent();
        }

        return Optional.of(lastProcessedRecord);
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {
        LOGGER.warn("action=SHUTDOWN-aggStats-processor reason={}", shutdownReason.toString());

        if (shutdownReason == ShutdownReason.TERMINATE) {
            LOGGER.warn("action=going-to-checkpoint shutdown_reason={}", shutdownReason.toString());
            try {
                iRecordProcessorCheckpointer.checkpoint();
                LOGGER.warn("action=checkpoint-success");
            } catch (InvalidStateException | ShutdownException e) {
                LOGGER.error("action=checkpoint-fail error={}", e.getMessage());
            }
        }  else {
            LOGGER.error("action=system-exit reason=encountered-zombie shutdown_reason={}", shutdownReason.toString());
            System.exit(1);
        }

    }
}
