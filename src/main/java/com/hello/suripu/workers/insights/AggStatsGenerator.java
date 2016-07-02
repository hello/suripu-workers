package com.hello.suripu.workers.insights;

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

            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("action=received-malformed-protobuf exception={} record={}", e.getMessage(), record.toString());
            } catch (IllegalArgumentException e) {
                LOGGER.error("action=fail-decrypt-pill-data data={} error={}", record.getData().array(), e.getMessage());
            }
        }

        //TODO: Checkpoint
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {
        LOGGER.warn("action=shutdown-aggStats-processor reason={}", shutdownReason.toString());
    }
}
