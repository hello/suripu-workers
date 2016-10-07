package com.hello.suripu.workers.pill;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillDataIngestDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.pill.heartbeat.PillHeartBeat;
import com.hello.suripu.core.pill.heartbeat.PillHeartBeatDAODynamoDB;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.apache.commons.codec.digest.DigestUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;

public class SavePillDataProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(SavePillDataProcessor.class);

    private final PillDataIngestDAO pillDataIngestDAO;
    private final int batchSize;
    private final KeyStore pillKeyStore;
    private final DeviceDAO deviceDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final PillHeartBeatDAODynamoDB pillHeartBeatDAODynamoDB; // will replace with interface as soon as we have validated this works
    private final Boolean savePillHeartbeat;

    private final Meter messagesProcessed;
    private final Meter batchSaved;

    public SavePillDataProcessor(final PillDataIngestDAO pillDataIngestDAO,
                                 final int batchSize,
                                 final KeyStore pillKeyStore,
                                 final DeviceDAO deviceDAO,
                                 final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                 final PillHeartBeatDAODynamoDB pillHeartBeatDAODynamoDB,
                                 final Boolean savePillHeartbeat,
                                 final MetricRegistry metrics)
    {
        this.pillDataIngestDAO = pillDataIngestDAO;
        this.batchSize = batchSize;
        this.pillKeyStore = pillKeyStore;
        this.deviceDAO = deviceDAO;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.pillHeartBeatDAODynamoDB = pillHeartBeatDAODynamoDB;
        this.savePillHeartbeat = savePillHeartbeat;

        this.messagesProcessed = metrics.meter(name(SavePillDataProcessor.class, "messages-processed"));
        this.batchSaved = metrics.meter(name(SavePillDataProcessor.class, "batch-saved"));
    }

    @Override
    public void initialize(String s) {
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        LOGGER.debug("Size = {}", records.size());

        final ArrayList<TrackerMotion> trackerData = new ArrayList<>(records.size());
        final Set<PillHeartBeat> pillHeartBeats = Sets.newHashSet(); // for dynamoDB writes

        final List<String> pillIds = Lists.newArrayList();
        final List<SenseCommandProtos.batched_pill_data> batchData = Lists.newArrayList();
        final ArrayListMultimap<String, String> pillIdToSenseId = ArrayListMultimap.create();
        final Set<String> senseIds = Sets.newHashSet();

        for (final Record record : records) {
            try {
                final SenseCommandProtos.batched_pill_data batched_pill_data = SenseCommandProtos.batched_pill_data.parseFrom(record.getData().array());
                batchData.add(batched_pill_data);

                for (final SenseCommandProtos.pill_data data : batched_pill_data.getPillsList()) {
                    pillIds.add(data.getDeviceId());
                    senseIds.add(batched_pill_data.getDeviceId());

                    if(printPillDebugInfo(data.getDeviceId())) {
                        if(data.hasBatteryLevel()) {
                            LOGGER.info("pill_id={} type=heartbeat", data.getDeviceId());
                        } else {
                            final String md5 = DigestUtils.md5Hex(data.getMotionDataEntrypted().toByteArray());
                            LOGGER.info("pill_id={} sense_id={} md5={}", data.getDeviceId(), data.getDeviceId(), md5);
                        }
                    }
                }
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("error=invalid-protobuf msg={}", e.getMessage());
            } catch (IllegalArgumentException e) {
                LOGGER.error("error=parsing-protobuf-failed sequence_number={} partition_key={} msg={}", record.getSequenceNumber(), record.getPartitionKey(), e.getMessage());
            }
        }

        try {
            // Fetch data from Dynamo and DB
            final Set<String> uniquePillIds = Sets.newHashSet(pillIds);
            final Map<String, Optional<byte[]>> pillKeys = getKeys(Lists.newArrayList(uniquePillIds));

            // get account_ids associated with the pill external_ids
            final Map<String, Optional<DeviceAccountPair>> pairs = getPillPairing(uniquePillIds);

            final InMemorySenseAndPillPairings pairings = getTimeZones(senseIds);

            for(final SenseCommandProtos.batched_pill_data batch : batchData) {
                final String senseId = batch.getDeviceId();
                for (final SenseCommandProtos.pill_data data : batch.getPillsList()) {
                    final String pillId = data.getDeviceId();
                    if(pairings.paired(senseId, pillId)) {
                        LOGGER.warn("msg=not-paired pill_id={} sense_id={}", pillId, senseId);
                        continue;
                    }

                    final Optional<byte[]> decryptionKey = pillKeys.get(pillId);

                    // The key should not be null
                    if (!decryptionKey.isPresent()) {
                        LOGGER.error("error=missing-decryption-key pill_id={}", pillId);
                        continue;
                    }

                    final Optional<DeviceAccountPair> optionalPair = pairs.get(pillId);
                    if (!optionalPair.isPresent()) {
                        LOGGER.error("error=missing-pairing pill_id={}", pillId);
                        continue;
                    }

                    final DeviceAccountPair pair = optionalPair.get();

                    final Optional<DateTimeZone> timeZoneOptional = pairings.timezone(pillId);
                    if (!timeZoneOptional.isPresent()) {
                        LOGGER.error("error=missing-timezone account_id={} pill_id={}", pair.accountId, pair.externalDeviceId);
                        continue;
                    }

                    if (data.hasMotionDataEntrypted()) {
                        try {
                            final TrackerMotion trackerMotion = TrackerMotion.create(data, pair, timeZoneOptional.get(), decryptionKey.get());

                            trackerData.add(trackerMotion);
                            if (printPillDebugInfo(pillId)) {
                                LOGGER.trace("action=add-motion-data pill_id={}", pillId);
                            }
                        } catch (TrackerMotion.InvalidEncryptedPayloadException exception) {
                            LOGGER.error("error=payload-decrypt-failed pill_id={} account_id={}", pillId, pair.accountId);
                        }
                    } else {
                        // heartbeats
                        if (!data.hasBatteryLevel()) {
                            continue;
                        }

                        final int batteryLevel = data.getBatteryLevel();
                        final int upTimeInSeconds = data.getUptime();
                        final int firmwareVersion = data.getFirmwareVersion();
                        final Long ts = data.getTimestamp() * 1000L;
                        final DateTime lastUpdated = new DateTime(ts, DateTimeZone.UTC);

                        final PillHeartBeat pillHeartBeat = PillHeartBeat.create(pillId, batteryLevel, firmwareVersion, upTimeInSeconds, lastUpdated);
                        if(printPillDebugInfo(pillId)) {
                            LOGGER.info("action=heartbeat pill_id={} last_updated={}", pillId, lastUpdated);
                        }
                        pillHeartBeats.add(pillHeartBeat);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed processing pill: {}", e.getMessage());
            LOGGER.error("Failed processing pill: {}", e);
        }

        if (trackerData.size() > 0) {
            LOGGER.info("About to batch insert: {} tracker motion samples", trackerData.size());
            this.pillDataIngestDAO.batchInsertTrackerMotionData(trackerData, this.batchSize);
            batchSaved.mark(trackerData.size());
            LOGGER.info("Finished batch insert: {} tracker motion samples", trackerData.size());
        }

        // only write heartbeats for postgres worker
        if (this.savePillHeartbeat && !pillHeartBeats.isEmpty()) {
            final Set<PillHeartBeat> unproccessed = this.pillHeartBeatDAODynamoDB.put(pillHeartBeats);
            final float perc = ((float) unproccessed.size() / (float) pillHeartBeats.size()) * 100.0f;
            LOGGER.info("Finished dynamo batch insert: {} heartbeats, {} {}% unprocessed", pillHeartBeats.size(), unproccessed.size(), perc);
        }

        if(!trackerData.isEmpty() || !pillHeartBeats.isEmpty()) {
            try {
                iRecordProcessorCheckpointer.checkpoint();
                LOGGER.info("Successful checkpoint.");
            } catch (InvalidStateException e) {
                LOGGER.error("checkpoint {}", e.getMessage());
            } catch (ShutdownException e) {
                LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
            }
        }

        messagesProcessed.mark(records.size());
    }


    Map<String, Optional<byte[]>> getKeys(final List<String> pillIds) {
        final Map<String, Optional<byte[]>> pillKeys = Maps.newHashMap();
        if (pillIds.isEmpty()) {
            return pillKeys;
        }

        for(final List<String> sublist : Lists.partition(pillIds, 100)) {
            final Map<String, Optional<byte[]>> keys = pillKeyStore.getBatch(Sets.newHashSet(sublist));
            if (keys.isEmpty() && !sublist.isEmpty()) {
                LOGGER.error("Failed to retrieve decryption keys. Can't proceed. Bailing. pill_ids={}", pillIds);
                System.exit(1);
            }
            pillKeys.putAll(keys);
        }

        return pillKeys;
    }

    Map<String,Optional<DeviceAccountPair>> getPillPairing(Set<String> uniquePillIds) {
        final Map<String, Optional<DeviceAccountPair>> pairs = Maps.newHashMapWithExpectedSize(uniquePillIds.size());
        for (final String pillId : uniquePillIds) {
            final Optional<DeviceAccountPair> optionalPair = deviceDAO.getInternalPillId(pillId);
            pairs.put(pillId, optionalPair);
        }
        return pairs;
    }



    InMemorySenseAndPillPairings getTimeZones(final Set<String> senseIds) {

        final InMemorySenseAndPillPairings senseAndPillPairings = new InMemorySenseAndPillPairings();
        for(final String senseId : senseIds) {
            final List<UserInfo> users = mergedUserInfoDynamoDB.getInfo(senseId);
            senseAndPillPairings.add(senseId, users);
        }
        return senseAndPillPairings;
    }

    @Override
    public void shutdown(final IRecordProcessorCheckpointer iRecordProcessorCheckpointer, final ShutdownReason shutdownReason) {
        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
        if(shutdownReason == ShutdownReason.TERMINATE) {
            LOGGER.warn("Got Terminate. Attempting to checkpoint.");
            try {
                iRecordProcessorCheckpointer.checkpoint();
                LOGGER.warn("Checkpoint successful.");
            } catch (InvalidStateException | ShutdownException e) {
                LOGGER.error(e.getMessage());
            }
        }
    }
}
