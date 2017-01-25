package com.hello.suripu.workers.logs;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.OnBoardingLogDAO;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.workers.logs.triggers.Publisher;
import com.segment.analytics.Analytics;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

public class LogIndexerProcessor implements IRecordProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(LogIndexerProcessor.class);

    private final LogIndexer<LoggingProtos.BatchLogMessage> senseStructuredLogsIndexer;
    private final LogIndexer<LoggingProtos.BatchLogMessage> onBoardingLogIndexer;

    private final Meter structuredLogs;
    private final Meter onboardingLogs;
    private final MetricRegistry metrics;
    private String shardId = "";

    private LogIndexerProcessor(
            final MetricRegistry metricRegistry, final LogIndexer<LoggingProtos.BatchLogMessage> senseStructuredLogsIndexer,
            final LogIndexer<LoggingProtos.BatchLogMessage> onBoardingLogIndexer) {
        this.senseStructuredLogsIndexer = senseStructuredLogsIndexer;
        this.onBoardingLogIndexer = onBoardingLogIndexer;
        this.metrics = metricRegistry;

        this.structuredLogs = metrics.meter(name(LogIndexerProcessor.class, "structured-processed"));
        this.onboardingLogs = metrics.meter(name(LogIndexerProcessor.class, "onboarding-processed"));
    }

    public static LogIndexerProcessor create(final SenseEventsDAO senseEventsDAO,
                                             final OnBoardingLogDAO onBoardingLogDAO,
                                             final MetricRegistry metricRegistry,
                                             final Analytics analytics,
                                             final RingTimeHistoryReadDAO ringTimeHistoryDAODynamoDB,
                                             final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                             final Publisher publisher) {
        return new LogIndexerProcessor(
                metricRegistry,
                new SenseStructuredLogIndexer(senseEventsDAO, analytics, ringTimeHistoryDAODynamoDB, mergedUserInfoDynamoDB, publisher),
                new OnBoardingLogIndexer(onBoardingLogDAO)
        );
    }

    @Override
    public void initialize(String s) {
        this.shardId = s;
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        DateTime lastMessageArrivalTime = DateTime.now(DateTimeZone.UTC);
        for(final Record record : records) {
            try {
                final LoggingProtos.BatchLogMessage batchLogMessage = LoggingProtos.BatchLogMessage.parseFrom(record.getData().array());
                if(batchLogMessage.hasLogType()) {
                    switch (batchLogMessage.getLogType()) {
                        case STRUCTURED_SENSE_LOG:
                            senseStructuredLogsIndexer.collect(batchLogMessage, record.getSequenceNumber());
                            break;
                        case ONBOARDING_LOG:
                            this.onBoardingLogIndexer.collect(batchLogMessage);
                    }
                }
                lastMessageArrivalTime = new DateTime(record.getApproximateArrivalTimestamp(), DateTimeZone.UTC);

            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("error=protobuf-parsing-failed msg={}", e.getMessage());
            }
        }

        try {
            final Integer eventsCount = senseStructuredLogsIndexer.index();
            final Integer onBoardingLogCount = this.onBoardingLogIndexer.index();

            structuredLogs.mark(eventsCount);
            onboardingLogs.mark(onBoardingLogCount);

            iRecordProcessorCheckpointer.checkpoint();
            LOGGER.info("action=checkpointing shard_id={} records={} kv={} onboarding={} last_arrival_time={}",
                    shardId,
                    records.size(),
                    eventsCount,
                    onBoardingLogCount,
                    lastMessageArrivalTime);
        } catch (InvalidStateException | ShutdownException e) {
            LOGGER.error("error={}", e.getMessage());
        }
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {
        LOGGER.warn("Shutting down because: {}", shutdownReason);
        if(shutdownReason == ShutdownReason.TERMINATE) {
            try {
                iRecordProcessorCheckpointer.checkpoint();
                LOGGER.warn("Checkpoint successful after shutdown");
            } catch (InvalidStateException | ShutdownException e) {
                LOGGER.error("error={}", e.getMessage());
            }
        } else {
            LOGGER.error("Unknown shutdown reason. exit()");
            System.exit(1);
        }

    }
}
