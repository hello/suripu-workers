package com.hello.suripu.workers.splitter;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.logging.DataLogger;

/**
 * Created by ksg on 4/8/16
 */
public class SenseStreamSplitterFactory implements IRecordProcessorFactory {
    private final DataLogger outputKinesisLogger;
    private final Integer maxRecords;
    private final MetricRegistry metricRegistry;

    public SenseStreamSplitterFactory(final DataLogger outputKinesisLogger, final Integer maxRecords, final MetricRegistry metricRegistry) {
        this.outputKinesisLogger = outputKinesisLogger;
        this.maxRecords = maxRecords;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public IRecordProcessor createProcessor() {
        return new SenseStreamSplitter(outputKinesisLogger, maxRecords, metricRegistry);
    }

}