package com.hello.suripu.workers.pill;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.PillDataIngestDAO;
import com.hello.suripu.core.notifications.sender.NotificationSender;
import com.hello.suripu.core.pill.heartbeat.PillHeartBeatDAODynamoDB;

public class SavePillDataProcessorFactory implements IRecordProcessorFactory {

    private final int batchSize;
    private final PillDataIngestDAO pillDataIngestDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final KeyStore pillKeyStore;
    private final DeviceDAO deviceDAO;
    private final PillHeartBeatDAODynamoDB pillHeartBeatDAODynamoDB;
    private final Boolean savePillHeartBeat;
    private final MetricRegistry metricRegistry;
    private final int batteryNotificationThreshold;
    private final NotificationSender notificationSender;

    public SavePillDataProcessorFactory(
            final PillDataIngestDAO pillDataIngestDAO,
            final int batchSize,
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final KeyStore pillKeyStore,
            final DeviceDAO deviceDAO,
            final PillHeartBeatDAODynamoDB pillHeartBeatDAODynamoDB,
            final Boolean savePillHeartBeat,
            final MetricRegistry metricRegistry,
            final int batteryNotificationThreshold,
            final NotificationSender notificationSender)
    {
        this.pillDataIngestDAO = pillDataIngestDAO;
        this.batchSize = batchSize;
        this.mergedUserInfoDynamoDB= mergedUserInfoDynamoDB;
        this.pillKeyStore = pillKeyStore;
        this.deviceDAO = deviceDAO;
        this.pillHeartBeatDAODynamoDB = pillHeartBeatDAODynamoDB;
        this.savePillHeartBeat = savePillHeartBeat;
        this.metricRegistry = metricRegistry;
        this.batteryNotificationThreshold = batteryNotificationThreshold;
        this.notificationSender = notificationSender;
    }

    @Override
    public IRecordProcessor createProcessor() {
        return new SavePillDataProcessor(pillDataIngestDAO, batchSize, pillKeyStore, deviceDAO, mergedUserInfoDynamoDB,
                pillHeartBeatDAODynamoDB, savePillHeartBeat, metricRegistry, batteryNotificationThreshold, notificationSender);
    }
}
