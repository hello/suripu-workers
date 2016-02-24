package com.hello.suripu.workers.logs.timeline;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.RatioGauge;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.db.TimelineAnalyticsDAO;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;

public class TimelineLogProcessor extends HelloBaseRecordProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(TimelineLogProcessor.class);
    private final TimelineAnalyticsDAO timelineAnalyticsDAO;

    private MetricRegistry metrics;
    private final Meter timelineLogsReceived;
    private final Meter successfulTimelineLogInsertions;
    private final RatioGauge ratioOfSuccessfulInsertionsToReceived;

    public TimelineLogProcessor(final TimelineAnalyticsDAO timelineAnalyticsDAO) {
        this.timelineAnalyticsDAO = timelineAnalyticsDAO;
        this.timelineLogsReceived = metrics.meter(name(TimelineLogProcessor.class, "received"));
        this.successfulTimelineLogInsertions = metrics.meter(name(TimelineLogProcessor.class, "inserted"));
        this.ratioOfSuccessfulInsertionsToReceived = metrics.register(name(TimelineLogProcessor.class, "inserted-per-received"), new RatioGauge() {
            @Override
            public Ratio getRatio() {
                return Ratio.of(successfulTimelineLogInsertions.getOneMinuteRate(),
                    timelineLogsReceived.getOneMinuteRate());
            }
        });
    }

    public static TimelineLogProcessor create(final TimelineAnalyticsDAO timelineAnalyticsDAO) {
        return new TimelineLogProcessor(timelineAnalyticsDAO);
    }

    @Override
    public void initialize(String shardId) {

    }

    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer checkpointer) {
        final Set<LoggingProtos.TimelineLog> uniqueLogs = Sets.newLinkedHashSet();

        for(final Record record : records) {
            final LoggingProtos.BatchLogMessage batchLogMessage;
            try {
                batchLogMessage = LoggingProtos.BatchLogMessage.parseFrom(record.getData().array());
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed converting protobuf: {}", e.getMessage());
                continue;
            }

            if(batchLogMessage.hasLogType()
                    && batchLogMessage.getLogType().equals(LoggingProtos.BatchLogMessage.LogType.TIMELINE_LOG)) {
                for(final LoggingProtos.TimelineLog timelineLog : batchLogMessage.getTimelineLogList()) {
                    uniqueLogs.add(timelineLog);
                }
            }
        }

        timelineLogsReceived.mark(uniqueLogs.size());

        final int successfulInsertions = timelineAnalyticsDAO.insertBatchWithIndividualRetry(uniqueLogs);
        successfulTimelineLogInsertions.mark(successfulInsertions);

        try {
            checkpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error(e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command, bailing. {}", e.getMessage());
        }
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer checkpointer, ShutdownReason reason) {
        LOGGER.warn("SHUTDOWN: {}", reason.toString());
        if (Objects.equals(ShutdownReason.TERMINATE, reason)) {
            try {
                checkpointer.checkpoint();
            } catch (InvalidStateException e) {
                LOGGER.error(e.getMessage());
            } catch (ShutdownException e) {
                LOGGER.error(e.getMessage());
            }
        }
    }
}
