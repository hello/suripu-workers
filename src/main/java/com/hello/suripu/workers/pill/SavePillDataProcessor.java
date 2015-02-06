package com.hello.suripu.workers.pill;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillHeartBeatDAO;
import com.hello.suripu.core.db.TrackerMotionDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SavePillDataProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(SavePillDataProcessor.class);

    private final TrackerMotionDAO trackerMotionDAO;
    private final int batchSize;
    private final PillHeartBeatDAO pillHeartBeatDAO;
    private final KeyStore pillKeyStore;
    private final DeviceDAO deviceDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;

    public SavePillDataProcessor(final TrackerMotionDAO trackerMotionDAO, final int batchSize, final PillHeartBeatDAO pillHeartBeatDAO, final KeyStore pillKeyStore, final DeviceDAO deviceDAO, final MergedUserInfoDynamoDB mergedUserInfoDynamoDB) {
        this.trackerMotionDAO = trackerMotionDAO;
        this.batchSize = batchSize;
        this.pillHeartBeatDAO = pillHeartBeatDAO;
        this.pillKeyStore = pillKeyStore;
        this.deviceDAO = deviceDAO;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
    }

    @Override
    public void initialize(String s) {
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        LOGGER.debug("Size = {}", records.size());

        // parse kinesis records
        final ArrayList<TrackerMotion> trackerData = new ArrayList<>(records.size());

        for (final Record record : records) {
            try {
                final SenseCommandProtos.batched_pill_data batched_pill_data = SenseCommandProtos.batched_pill_data.parseFrom(record.getData().array());
                for(final SenseCommandProtos.pill_data data : batched_pill_data.getPillsList()) {

                    final Optional<byte[]> decryptionKey = pillKeyStore.get(data.getDeviceId());
                    //TODO: Get the actual decryption key.
                    if(!decryptionKey.isPresent()) {
                        LOGGER.error("Missing decryption key for pill: {}", data.getDeviceId());
                        continue;
                    }

                    final Optional<DeviceAccountPair> optionalPair = deviceDAO.getInternalPillId(data.getDeviceId());
                    if(!optionalPair.isPresent()) {
                        LOGGER.error("Missing pairing in account tracker map for pill: {}", data.getDeviceId());
                        continue;
                    }

                    final DeviceAccountPair pair = optionalPair.get();
                    // Warning: mutable state!!!
                    Optional<UserInfo> userInfoOptional = mergedUserInfoDynamoDB.getInfo(batched_pill_data.getDeviceId(), pair.accountId);

                    if(!userInfoOptional.isPresent()) {
                        LOGGER.error("Missing UserInfo for account: {} and pill_id = {} and sense_id = {}", pair.accountId, pair.externalDeviceId, batched_pill_data.getDeviceId());
                        continue;
                    }

                    final UserInfo userInfo = userInfoOptional.get();
                    final Optional<DateTimeZone> timeZoneOptional = userInfo.timeZone;
                    if(!timeZoneOptional.isPresent()) {
                        LOGGER.error("No timezone for account {} with pill_id = {}", pair.accountId, pair.externalDeviceId);
                        continue;
                    }


                    if(data.hasMotionDataEntrypted()){
                        final TrackerMotion trackerMotion = TrackerMotion.create(data, pair, timeZoneOptional.get(), decryptionKey.get());
                        trackerData.add(trackerMotion);
                        LOGGER.debug("Tracker Data added for batch insert for pill_id = {}", pair.externalDeviceId);
                    }

                    if(data.hasBatteryLevel()){
                        final int batteryLevel = data.getBatteryLevel();
                        final int upTimeInSeconds = data.getUptime();
                        final int firmwareVersion = data.getFirmwareVersion();
                        final DateTime lastUpdated = new DateTime(data.getTimestamp(), DateTimeZone.UTC);
                        pillHeartBeatDAO.silentInsert(pair.internalDeviceId, batteryLevel, upTimeInSeconds, firmwareVersion, lastUpdated);
                    }
                }
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed to decode protobuf: {}", e.getMessage());
            } catch (IllegalArgumentException e) {
                LOGGER.error("Failed to decrypted pill data {}, error: {}", record.getData().array(), e.getMessage());
            }
        }

        if (trackerData.size() > 0) {
            LOGGER.info("About to batch insert: {} tracker motion samples", trackerData.size());
            this.trackerMotionDAO.batchInsertTrackerMotionData(trackerData, this.batchSize);
            LOGGER.info("Finished batch insert: {} tracker motion samples", trackerData.size());
            try {
                iRecordProcessorCheckpointer.checkpoint();
                LOGGER.info("Successful checkpoint.");
            } catch (InvalidStateException e) {
                LOGGER.error("checkpoint {}", e.getMessage());
            } catch (ShutdownException e) {
                LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
            }
        }

    }

    @Override
    public void shutdown(final IRecordProcessorCheckpointer iRecordProcessorCheckpointer, final ShutdownReason shutdownReason) {
        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
    }
}
