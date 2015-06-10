package com.hello.suripu.workers.logs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.db.SenseEventsDAO;
import com.hello.suripu.core.metrics.DeviceEvents;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class SenseStructuredLogIndexer implements LogIndexer<LoggingProtos.BatchLogMessage> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseStructuredLogIndexer.class);
    private final SenseEventsDAO senseEventsDAO;
    private final List<DeviceEvents> deviceEventsList;


    public SenseStructuredLogIndexer(final SenseEventsDAO senseEventsDAO) {
        this.senseEventsDAO = senseEventsDAO;
        this.deviceEventsList = Lists.newArrayList();
    }


    @Override
    public Integer index() {
        final Integer count = senseEventsDAO.write(ImmutableList.copyOf(deviceEventsList));
        deviceEventsList.clear();
        return count;
    }

    public static Set<String> decode(final String text) {
        final String noBraces = text.replace("}{",",").replace("{","").replace("}","").replace(", ", ",");
        final String[] pairs = noBraces.split(",");
        return ImmutableSet.copyOf(pairs);
    }

    @Override
    public void collect(final LoggingProtos.BatchLogMessage batchLogMessage) {
        for(final LoggingProtos.LogMessage logMessage : batchLogMessage.getMessagesList()) {
            final Set<String> events = decode(logMessage.getMessage());
            final DateTime createdAt = new DateTime(logMessage.getTs() * 1000L, DateTimeZone.UTC);
            final DeviceEvents deviceEvents = new DeviceEvents(logMessage.getDeviceId(), createdAt, events);
            deviceEventsList.add(deviceEvents);
        }
    }
}
