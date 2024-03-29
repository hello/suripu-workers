package com.hello.suripu.workers.sense.lastSeen;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.protobuf.InvalidProtocolBufferException;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.Timed;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.core.db.SensorsViewsDynamoDB;
import com.hello.suripu.core.db.WifiInfoDAO;
import com.hello.suripu.core.models.DeviceData;
import com.hello.suripu.core.models.WifiInfo;
import com.hello.suripu.core.util.SenseProcessorUtils;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;


public class SenseLastSeenProcessor extends HelloBaseRecordProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseLastSeenProcessor.class);

    private final static Integer WIFI_INFO_BATCH_MAX_SIZE = 25;
    private final static Integer SIGNIFICANT_RSSI_CHANGE = 5;
    private final static Integer BLOOM_FILTER_PERIOD_MINUTES = 2;   // re-create bloom filter every 2 minutes because it's impossible to remove an element from it
    private final static Integer BLOOM_FILTER_CAPACITY = 40000; // this constant should be much greater than number of senses we have
    private final static double BLOOM_FILTER_ERROR_RATE = 0.05;

    private final Integer maxRecords;
    private final WifiInfoDAO wifiInfoDAO;
    private final SensorsViewsDynamoDB sensorsViewsDynamoDB;

    private final MetricRegistry metrics;
    private final Meter messagesProcessed;
    private final Meter capacity;

    private Map<String, WifiInfo> wifiInfoPerBatch = Maps.newHashMap();
    private Map<String, WifiInfo> wifiInfoHistory = Maps.newHashMap();

    private BloomFilter<CharSequence> bloomFilter;
    private DateTime lastBloomFilterCreated;

    private String shardId = "";

    public SenseLastSeenProcessor(final Integer maxRecords,
                                  final WifiInfoDAO wifiInfoDAO,
                                  final SensorsViewsDynamoDB sensorsViewsDynamoDB,
                                  final MetricRegistry metricRegistry) {
        this.maxRecords = maxRecords;
        this.wifiInfoDAO = wifiInfoDAO;
        this.sensorsViewsDynamoDB = sensorsViewsDynamoDB;
        this.metrics = metricRegistry;

        this.messagesProcessed = metrics.meter(name(SenseLastSeenProcessor.class, "messages-processed"));
        this.capacity = metrics.meter(name(SenseLastSeenProcessor.class, "capacity"));
    }

    @Override
    public void initialize(String s) {
        this.shardId = s;
        createNewBloomFilter();
    }

    private void createNewBloomFilter() {
        this.bloomFilter = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), BLOOM_FILTER_CAPACITY, BLOOM_FILTER_ERROR_RATE);
        this.lastBloomFilterCreated = DateTime.now(DateTimeZone.UTC);
    }

    @Timed
    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        final Map<String, DeviceData> lastSeenSenseDataMap = Maps.newHashMap();

        if(DateTime.now(DateTimeZone.UTC).isAfter(this.lastBloomFilterCreated.plusMinutes(BLOOM_FILTER_PERIOD_MINUTES))) {
            createNewBloomFilter();
            LOGGER.trace("New bloom filter created at {}", this.lastBloomFilterCreated);
        }
        final Set<String> seenSenses = Sets.newHashSet();
        for(final Record record : records) {
            DataInputProtos.BatchPeriodicDataWorker batchPeriodicDataWorker;
            try {
                batchPeriodicDataWorker = DataInputProtos.BatchPeriodicDataWorker.parseFrom(record.getData().array());
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed parsing protobuf: {}", e.getMessage());
                LOGGER.error("Moving to next record");
                continue;
            }

            //LOGGER.info("Protobuf message {}", TextFormat.shortDebugString(batchPeriodicDataWorker));

            final DataInputProtos.batched_periodic_data batchPeriodicData = batchPeriodicDataWorker.getData();

            collectWifiInfo(batchPeriodicData);

            final String senseExternalId = batchPeriodicData.getDeviceId();
            final Optional<DeviceData> lastSeenSenseDataOptional = getSenseData(batchPeriodicDataWorker);

            seenSenses.add(senseExternalId);

            if (!lastSeenSenseDataOptional.isPresent()){
                LOGGER.error("error=missing-batch-data device_id={} sequence_number={}", senseExternalId, record.getSequenceNumber());
                continue;
            }


            // Do not persist data for sense there is no interaction and we have seen recently
            if (!hasInteraction(lastSeenSenseDataOptional.get()) && bloomFilter.mightContain(senseExternalId)) {
                LOGGER.trace("Skip persisting last-seen-data for sense {} as it might have been seen within last {} minutes", senseExternalId, BLOOM_FILTER_PERIOD_MINUTES);
                continue;
            }


            // If all is well, update bloom filter and persist data
            bloomFilter.put(senseExternalId);
            lastSeenSenseDataMap.put(senseExternalId, lastSeenSenseDataOptional.get());
        }

        trackWifiInfo(wifiInfoPerBatch);
        wifiInfoPerBatch.clear();

        sensorsViewsDynamoDB.saveLastSeenDeviceDataAsync(lastSeenSenseDataMap);
        LOGGER.info("Saved last seen for {} senses", lastSeenSenseDataMap.size());

        messagesProcessed.mark(records.size());

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("checkpoint {}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
        }

        final int batchCapacity = Math.round(records.size() / (float) maxRecords * 100.0f);
        LOGGER.info("{} - seen device: {}", shardId, seenSenses.size());
        LOGGER.info("{} - capacity: {}%", shardId, batchCapacity);
        capacity.mark(batchCapacity);
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer iRecordProcessorCheckpointer, ShutdownReason shutdownReason) {

        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
        if(shutdownReason == ShutdownReason.TERMINATE) {
            LOGGER.warn("Going to checkpoint");
            try {
                iRecordProcessorCheckpointer.checkpoint();
                LOGGER.warn("Checkpointed successfully");
            } catch (InvalidStateException e) {
                LOGGER.error(e.getMessage());
            } catch (ShutdownException e) {
                LOGGER.error(e.getMessage());
            }
        }

    }

    private void collectWifiInfo (final DataInputProtos.batched_periodic_data batchedPeriodicData) {
        final String connectedSSID = batchedPeriodicData.hasConnectedSsid() ? batchedPeriodicData.getConnectedSsid() : "unknown_ssid";
        final String senseId = batchedPeriodicData.getDeviceId();
        final Integer rssi = WifiInfo.RSSI_NONE; // FW is no longer giving us the proper RSSI

        // If we have persisted wifi info for a sense since the worker started, then consider skipping if ...
        if (wifiInfoHistory.containsKey(senseId) && wifiInfoHistory.get(senseId).ssid.equals(connectedSSID)) {

            // If the corresponding feature is not turned on, skip writing as we assume rssi won't change
            if (!hasPersistSignificantWifiRssiChangeEnabled(senseId)) {
                LOGGER.trace("Skip writing because of {}'s unchanged network {}", senseId, connectedSSID);
                return;
            }

            // If the corresponding feature is turned on, skip writing unless rssi has changed significantly
            if (!hasSignificantRssiChange(wifiInfoHistory, senseId, rssi)) {
                LOGGER.trace("Skip writing because there is no significant wifi info change for {}'s network {}", senseId, connectedSSID);
                return;
            }
        }

        // Otherwise, persist new wifi info and memorize it in history for next iteration reference
        final WifiInfo wifiInfo = WifiInfo.create(senseId, connectedSSID, rssi, new DateTime(batchedPeriodicData.getData(0).getUnixTime() * 1000L, DateTimeZone.UTC));
        wifiInfoPerBatch.put(senseId, wifiInfo);
        wifiInfoHistory.put(senseId, wifiInfo);
    }

    private Optional<DeviceData> getSenseData(final DataInputProtos.BatchPeriodicDataWorker batchPeriodicDataWorker) {
        final String senseExternalId = batchPeriodicDataWorker.getData().getDeviceId();
        if (batchPeriodicDataWorker.getData().getDataList().isEmpty()) {
            LOGGER.error("error=empty-batch-data device_id={}", senseExternalId);
            return Optional.absent();
        }

        final DataInputProtos.periodic_data periodicData = batchPeriodicDataWorker.getData().getDataList().get(batchPeriodicDataWorker.getData().getDataList().size() - 1);

        final long createdAtTimestamp = batchPeriodicDataWorker.getReceivedAt();
        final DateTime createdAtRounded = new DateTime(createdAtTimestamp, DateTimeZone.UTC);

        final DateTime sampleDateTime = SenseProcessorUtils.getSampleTime(
                createdAtRounded, periodicData, attemptToRecoverSenseReportedTimeStamp(senseExternalId)
        );
        final Integer firmwareVersion = SenseProcessorUtils.getFirmwareVersion(batchPeriodicDataWorker, periodicData);
        if (SenseProcessorUtils.isClockOutOfSync(sampleDateTime, createdAtRounded)) {
            LOGGER.error("Clock out of sync Created time = {}, sample time = {}, now = {}", createdAtRounded, sampleDateTime, DateTime.now());
            return Optional.absent();
        }
        return Optional.of(new DeviceData.Builder()
                .withAmbientTemperature(periodicData.getTemperature())
                .withAmbientHumidity(periodicData.getHumidity())
                .withAmbientLight(periodicData.getLight())
                .withAmbientAirQualityRaw(periodicData.getDust())
                .withAudioPeakDisturbancesDB(periodicData.hasAudioPeakDisturbanceEnergyDb() ? periodicData.getAudioPeakDisturbanceEnergyDb() : 0)
                .withFirmwareVersion(firmwareVersion)
                .withDateTimeUTC(sampleDateTime)
                .withAccountId(0L)   // Account ID is not needed for last seen data
                .withDeviceId(0L)    // Sense internal ID is not needed for last seen data
                .withOffsetMillis(0) // Timezone offset is not needed for last seen data
                .build());
    }


    public void trackWifiInfo(final Map<String, WifiInfo> wifiInfoPerBatch) {
        final List<WifiInfo> wifiInfoList = Lists.newArrayList(wifiInfoPerBatch.values());
        Collections.shuffle(wifiInfoList);
        if (wifiInfoList.isEmpty()) {
            return;
        }
        final List<WifiInfo> persistedWifiInfoList = wifiInfoList.subList(0, Math.min(WIFI_INFO_BATCH_MAX_SIZE, wifiInfoList.size()));
        wifiInfoDAO.putBatch(persistedWifiInfoList);
        LOGGER.info("Tracked wifi info for {} senses", persistedWifiInfoList.size());
        LOGGER.trace("Tracked wifi info for senses {}", wifiInfoPerBatch.keySet());
    }

    @VisibleForTesting
    public static Boolean hasSignificantRssiChange(final Map<String, WifiInfo> wifiInfoHistory, final String senseId, final Integer rssi) {
        if (!wifiInfoHistory.containsKey(senseId)){
            return true;
        }
        return Math.abs(wifiInfoHistory.get(senseId).rssi - rssi) >= SIGNIFICANT_RSSI_CHANGE;
    }

    private Boolean hasInteraction(final DeviceData senseData) {
        return (senseData.waveCount > 0) || (senseData.holdCount > 0);
    }
}
