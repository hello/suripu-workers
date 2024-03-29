package com.hello.suripu.workers.alarm;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.protobuf.InvalidProtocolBufferException;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.ScheduledRingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SmartAlarmLoggerDynamoDB;
import com.hello.suripu.core.processors.RingProcessor;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Created by pangwu on 9/23/14.
 */
public class AlarmRecordProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(AlarmRecordProcessor.class);
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB;
    private final SmartAlarmLoggerDynamoDB smartAlarmLoggerDynamoDB;

    private final PillDataDAODynamoDB pillDataDAODynamoDB;
    private final AlarmWorkerConfiguration configuration;

    private final MetricRegistry metrics;
    private final Histogram recordAgesMinutes;
    private final Histogram alarmUpdateLatencyHistogram;

    private final Map<String, DateTime> senseIdLastProcessed;

    public AlarmRecordProcessor(final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB,
                                final SmartAlarmLoggerDynamoDB smartAlarmLoggerDynamoDB,
                                final PillDataDAODynamoDB pillDataDAODynamoDB,
                                final AlarmWorkerConfiguration configuration,
                                final Map<String, DateTime> senseIdLastProcessed,
                                final MetricRegistry metricRegistry){

        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.scheduledRingTimeHistoryDAODynamoDB = scheduledRingTimeHistoryDAODynamoDB;
        this.pillDataDAODynamoDB = pillDataDAODynamoDB;
        this.smartAlarmLoggerDynamoDB = smartAlarmLoggerDynamoDB;

        this.configuration = configuration;

        this.senseIdLastProcessed = senseIdLastProcessed;
        this.metrics = metricRegistry;

        // Create a histogram of the ages of records in minutes, biased towards newer values.
        this.recordAgesMinutes = metrics.histogram(name(AlarmRecordProcessor.class, "record-age-minutes"));
        // Histogram of the time delta in ms from the last time a given senseId was processed by this worker.
        this.alarmUpdateLatencyHistogram = metrics.histogram(name(AlarmRecordProcessor.class, "alarm-update-latency-millis"));
    }

    @Override
    public void initialize(String s) {
        LOGGER.info("AlarmRecordProcessor initialized: " + s);
    }

    private Boolean isRecordTooOld(DateTime endDateTime, DateTime recordDateTime) {
        final Interval interval = new Interval(recordDateTime, endDateTime);
        final int currentRecordAgeMinutes = interval.toPeriod().getMinutes();
        recordAgesMinutes.update(currentRecordAgeMinutes);
        return currentRecordAgeMinutes > configuration.getMaximumRecordAgeMinutes();
    }

    private void updateLatencyHistogram(final String senseId, final DateTime newDateTime) {
        if (senseIdLastProcessed.containsKey(senseId)) {
            final DateTime lastProcessedTime = senseIdLastProcessed.get(senseId);
            final long deltaSinceSenseIdLastProcessed = new Interval(lastProcessedTime, newDateTime).toDurationMillis();
            alarmUpdateLatencyHistogram.update(deltaSinceSenseIdLastProcessed);
        }
        senseIdLastProcessed.put(senseId, newDateTime);
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {

        final Set<String> senseIds = new HashSet<String>();
        LOGGER.info("Got {} records.", records.size());
        for (final Record record : records) {
            try {
                final DataInputProtos.BatchPeriodicDataWorker pb = DataInputProtos.BatchPeriodicDataWorker.parseFrom(record.getData().array());

                if(!pb.getData().hasDeviceId() || pb.getData().getDeviceId().isEmpty()) {
                    LOGGER.warn("Found a periodic_data without a device_id {}");
                    continue;
                }

                final String senseId = pb.getData().getDeviceId();

                // If the record is too old, don't process it so that we can catch up to newer messages.
                if (isRecordTooOld(DateTime.now(), new DateTime(pb.getReceivedAt())) && hasAlarmWorkerDropIfTooOldEnabled(senseId)) {
                    continue;
                }

                senseIds.add(senseId);


            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed to decode protobuf: {}", e.getMessage());
            }
        }

        LOGGER.info("Processing {} unique senseIds.", senseIds.size());
        for(final String senseId : senseIds) {
            try {
                updateLatencyHistogram(senseId, DateTime.now());
                RingProcessor.updateAndReturnNextRingTimeForSense(this.mergedUserInfoDynamoDB,
                        this.scheduledRingTimeHistoryDAODynamoDB,
                        this.smartAlarmLoggerDynamoDB,
                        this.pillDataDAODynamoDB,
                        senseId,
                        DateTime.now(),
                        this.configuration.getProcessAheadTimeInMinutes(),
                        this.configuration.getAggregateWindowSizeInMinute(),
                        this.configuration.getLightSleepThreshold(),
                        flipper
                );
            }catch (Exception ex){
                // Currently catching all exceptions, which means that we could checkpoint after a failure.
                LOGGER.error("Update next ring time for sense {} failed at {}, error {}",
                        senseId,
                        DateTime.now(),
                        ex.getMessage());
            }
        }

        LOGGER.info("Successfully updated smart ring time for {} sense", senseIds.size());
        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("checkpoint {}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
        }

        // Optimization in cases where we have very few new messages
        if(records.size() < 5) {
            LOGGER.info("Batch size was small. Sleeping for 10s");
            try {
                Thread.sleep(10000L);
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted Thread while sleeping: {}", e.getMessage());
            }
        }
    }

    @Override
    public void shutdown(final IRecordProcessorCheckpointer iRecordProcessorCheckpointer, final ShutdownReason shutdownReason) {
        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
        if(shutdownReason == ShutdownReason.TERMINATE) {
            try {
                iRecordProcessorCheckpointer.checkpoint();
            } catch (InvalidStateException e) {
                LOGGER.error(e.getMessage());
            } catch (ShutdownException e) {
                LOGGER.error(e.getMessage());
            }
        }
    }
}
