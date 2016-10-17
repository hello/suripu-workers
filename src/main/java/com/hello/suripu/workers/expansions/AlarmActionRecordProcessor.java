package com.hello.suripu.workers.expansions;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.protobuf.InvalidProtocolBufferException;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.suripu.api.output.AlarmAction;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.ScheduledRingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.speech.interfaces.Vault;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import is.hello.gaibu.core.models.DeviceExpansionPair;
import is.hello.gaibu.core.models.Expansion;
import is.hello.gaibu.core.models.ExpansionData;
import is.hello.gaibu.core.models.ExpansionDeviceData;
import is.hello.gaibu.core.models.ExternalToken;
import is.hello.gaibu.core.stores.ExpansionStore;
import is.hello.gaibu.core.stores.ExternalOAuthTokenStore;
import is.hello.gaibu.core.stores.PersistentExpansionDataStore;
import is.hello.gaibu.homeauto.factories.HomeAutomationExpansionDataFactory;
import is.hello.gaibu.homeauto.factories.HomeAutomationExpansionFactory;
import is.hello.gaibu.homeauto.interfaces.HomeAutomationExpansion;

import static com.codahale.metrics.MetricRegistry.name;

public class AlarmActionRecordProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(AlarmActionRecordProcessor.class);
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB;

    private final AlarmActionWorkerConfiguration configuration;

    private final MetricRegistry metrics;

    private final Meter actionsExecuted;

    private final ExpansionStore<Expansion> expansionStore;
    private final ExternalOAuthTokenStore<ExternalToken> externalTokenStore;
    private final PersistentExpansionDataStore expansionDataStore;
    private final Vault tokenKMSVault;

    final LoadingCache<DeviceExpansionPair, Boolean> actionCache;

    final CacheLoader actionCacheLoader = new CacheLoader<DeviceExpansionPair, Boolean>() {
        public Boolean load(final DeviceExpansionPair deviceExpansionPair) {
            LOGGER.debug("Sense Id '{}' not in cache, executing alarm action", deviceExpansionPair.deviceId);
            return attemptAlarmAction(deviceExpansionPair);
        }
    };

    private ObjectMapper mapper = new ObjectMapper();

    public AlarmActionRecordProcessor(final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                      final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB,
                                      final AlarmActionWorkerConfiguration configuration,
                                      final MetricRegistry metricRegistry,
                                      final ExpansionStore<Expansion> expansionStore,
                                      final ExternalOAuthTokenStore<ExternalToken> externalTokenStore,
                                      final PersistentExpansionDataStore expansionDataStore,
                                      final Vault tokenKMSVault){

        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.scheduledRingTimeHistoryDAODynamoDB = scheduledRingTimeHistoryDAODynamoDB;
        this.configuration = configuration;
        this.metrics = metricRegistry;
        this.expansionStore = expansionStore;
        this.externalTokenStore = externalTokenStore;
        this.expansionDataStore = expansionDataStore;
        this.tokenKMSVault = tokenKMSVault;

        this.actionCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build(actionCacheLoader);

        this.actionsExecuted = metrics.meter(name(AlarmActionRecordProcessor.class, "actions-executed"));
    }

    @Override
    public void initialize(String s) {
        LOGGER.info("AlarmActionRecordProcessor initialized: " + s);
    }

    @Override
    public void processRecords(final List<Record> records, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {

        LOGGER.info("Got {} records.", records.size());
        Integer successfulActions = 0;

        for (final Record record : records) {
            try {
                final AlarmAction.alarm_action pb = AlarmAction.alarm_action.parseFrom(record.getData().array());

                if(!pb.hasDeviceId() || pb.getDeviceId().isEmpty()) {
                    LOGGER.warn("Found an alarm action without a device_id {}");
                    continue;
                }
                final String senseId = pb.getDeviceId();

                if(!pb.hasExpansionId() || !pb.hasRingOffsetFromNowInSecond()) {
                    LOGGER.warn("warn=invalid-protobuf sense_id={}", senseId);
                    continue;
                }

                final Long expansionId = pb.getExpansionId();

                final Optional<Expansion> expansionOptional = expansionStore.getApplicationById(expansionId);
                if(!expansionOptional.isPresent()) {
                    LOGGER.warn("warning=expansion-not-found");
                    continue;
                }

                final Expansion expansion = expansionOptional.get();
                final Integer bufferTimeSeconds = HomeAutomationExpansionFactory.getBufferTimeByServiceName(expansion.serviceName);

                //Check against expansion default action buffer time
                if((int)pb.getRingOffsetFromNowInSecond() > bufferTimeSeconds) {
                    continue;
                }

                //Attempt to pull action from cache
                final DeviceExpansionPair deviceExpansionPair = new DeviceExpansionPair(senseId, expansionId);
                final Boolean actionComplete = this.actionCache.getUnchecked(deviceExpansionPair);

                if(actionComplete) {
                    successfulActions++;
                }

            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed to decode protobuf: {}", e.getMessage());
            }
        }

        this.actionsExecuted.mark((long)successfulActions);

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("checkpoint {}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
        }

//        // Optimization in cases where we have very few new messages
//        if(records.size() < 5) {
//            LOGGER.info("Batch size was small. Sleeping for 10s");
//            try {
//                Thread.sleep(10000L);
//            } catch (InterruptedException e) {
//                LOGGER.error("Interrupted Thread while sleeping: {}", e.getMessage());
//            }
//        }
    }

    public Boolean attemptAlarmAction(final DeviceExpansionPair deviceExpansionPair) {

        final Optional<Expansion> expansionOptional = expansionStore.getApplicationById(deviceExpansionPair.expansionId);
        if(!expansionOptional.isPresent()) {
            LOGGER.warn("warning=expansion-not-found");
            return false;
        }

        final String senseId = deviceExpansionPair.deviceId;

        final Expansion expansion = expansionOptional.get();

        LOGGER.debug("Executing Expansion Default Alarm Action expansions_id={}", expansion.id);

        final Optional<ExpansionData> expDataOptional = expansionDataStore.getAppData(expansion.id, senseId);
        if(!expDataOptional.isPresent()) {
            LOGGER.error("error=no-ext-app-data expansion_id={} sense_id={}", expansion.id, senseId);
            return false;
        }

        final ExpansionData expData = expDataOptional.get();

        final Optional<ExpansionDeviceData> expansionDeviceDataOptional = HomeAutomationExpansionDataFactory.getAppData(mapper, expData.data, expansion.serviceName);

        if(!expansionDeviceDataOptional.isPresent()){
            LOGGER.error("error=bad-expansion-data expansion_id={} sense_id={}", expansion.id, senseId);
            return false;
        }

        final ExpansionDeviceData appData = expansionDeviceDataOptional.get();

        final String decryptedToken = getDecryptedExternalToken(senseId, expansion, false);

        final Optional<HomeAutomationExpansion> homeExpansionOptional = HomeAutomationExpansionFactory.getExpansion(configuration.expansionConfiguration().hueAppName(), expansion.serviceName, appData, decryptedToken);
        if(!homeExpansionOptional.isPresent()){
            LOGGER.error("error=get-home-expansion-failed expansion_id={} sense_id={}", expansion.id, senseId);
            return false;
        }

        final HomeAutomationExpansion homeExpansion = homeExpansionOptional.get();

        //Execute default alarm action for expansion
        LOGGER.info("action=run-alarm-action sense_id={} expansion_id={} successful={}", senseId, expansion.id);
        final Boolean isSuccessful = homeExpansion.runDefaultAlarmAction();

        if(!isSuccessful){
            LOGGER.error("error=alarm-action-failed sense_id={} expansion_id={} successful={}", senseId, expansion.id);
        }

        return isSuccessful;
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

    private String getDecryptedExternalToken(final String deviceId, final Expansion expansion, final Boolean isRefreshToken) {
        final Optional<ExternalToken> externalTokenOptional = externalTokenStore.getTokenByDeviceId(deviceId, expansion.id);
        if(!externalTokenOptional.isPresent()) {
            LOGGER.warn("warning=token-not-found");
        }

        ExternalToken externalToken = externalTokenOptional.get();

        //check for expired token and attempt refresh
        if(externalToken.hasExpired(DateTime.now(DateTimeZone.UTC))) {
            LOGGER.error("error=token-expired device_id={}", deviceId);
//            final Optional<ExternalToken> refreshedTokenOptional = refreshToken(deviceId, expansion, externalToken);
//            if(!refreshedTokenOptional.isPresent()){
//                LOGGER.error("error=token-refresh-failed device_id={}", deviceId);
//            }
//
//            externalToken = refreshedTokenOptional.get();
        }

        final Map<String, String> encryptionContext = Maps.newHashMap();
        encryptionContext.put("application_id", externalToken.appId.toString());
        final Optional<String> decryptedTokenOptional;
        if(isRefreshToken) {
            decryptedTokenOptional = tokenKMSVault.decrypt(externalToken.refreshToken, encryptionContext);
        } else {
            decryptedTokenOptional = tokenKMSVault.decrypt(externalToken.accessToken, encryptionContext);
        }


        if(!decryptedTokenOptional.isPresent()) {
            LOGGER.error("error=token-decryption-failure device_id={}", deviceId);
        }
        return decryptedTokenOptional.get();
    }
}
