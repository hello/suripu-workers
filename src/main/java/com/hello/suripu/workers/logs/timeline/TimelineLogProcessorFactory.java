package com.hello.suripu.workers.logs.timeline;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.TimelineAnalyticsDAO;

public class TimelineLogProcessorFactory implements IRecordProcessorFactory {

    private final TimelineAnalyticsDAO timelineAnalyticsDAO;
    private final MetricRegistry metricRegistry;

    public TimelineLogProcessorFactory(final TimelineAnalyticsDAO timelineAnalyticsDAO, final MetricRegistry metricRegistry) {
        this.timelineAnalyticsDAO = timelineAnalyticsDAO;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public IRecordProcessor createProcessor() {
        return TimelineLogProcessor.create(timelineAnalyticsDAO, metricRegistry);
    }
}
