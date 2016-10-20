package com.hello.suripu.workers.expansions;

import com.google.common.base.Optional;
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
import com.hello.suripu.api.expansions.ExpansionProtos;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.ScheduledRingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.models.ValueRange;
import com.hello.suripu.core.speech.interfaces.Vault;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;

import org.apache.commons.codec.digest.DigestUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import is.hello.gaibu.core.models.Expansion;
import is.hello.gaibu.core.models.ExpansionData;
import is.hello.gaibu.core.models.ExpansionDeviceData;
import is.hello.gaibu.core.models.ExternalToken;
import is.hello.gaibu.core.stores.ExpansionStore;
import is.hello.gaibu.core.stores.ExternalOAuthTokenStore;
import is.hello.gaibu.core.stores.PersistentExpansionDataStore;
import is.hello.gaibu.core.utils.TokenUtils;
import is.hello.gaibu.homeauto.factories.HomeAutomationExpansionDataFactory;
import is.hello.gaibu.homeauto.factories.HomeAutomationExpansionFactory;
import is.hello.gaibu.homeauto.interfaces.HomeAutomationExpansion;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import static com.codahale.metrics.MetricRegistry.name;

public class AlarmActionRecordProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(AlarmActionRecordProcessor.class);

    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB;
    private final AlarmActionWorkerConfiguration configuration;
    private final ExpansionStore<Expansion> expansionStore;
    private final ExternalOAuthTokenStore<ExternalToken> externalTokenStore;
    private final PersistentExpansionDataStore expansionDataStore;
    private final Vault tokenKMSVault;
    private final JedisPool jedisPool;

    private final MetricRegistry metrics;
    private final Meter actionsExecuted;

    private static final String GENERIC_EXCEPTION_LOG_MESSAGE = "error=jedis-connection-exception";
    private static final String ALARM_ACTION_ATTEMPTS_KEY = "alarm_actions";
    private static final Integer MAX_REDIS_RECORD_AGE_MINUTES = 30;

    private ObjectMapper mapper = new ObjectMapper();

    public AlarmActionRecordProcessor(final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                      final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB,
                                      final AlarmActionWorkerConfiguration configuration,
                                      final MetricRegistry metricRegistry,
                                      final ExpansionStore<Expansion> expansionStore,
                                      final ExternalOAuthTokenStore<ExternalToken> externalTokenStore,
                                      final PersistentExpansionDataStore expansionDataStore,
                                      final Vault tokenKMSVault,
                                      final JedisPool jedisPool){

        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.scheduledRingTimeHistoryDAODynamoDB = scheduledRingTimeHistoryDAODynamoDB;
        this.configuration = configuration;
        this.metrics = metricRegistry;
        this.expansionStore = expansionStore;
        this.externalTokenStore = externalTokenStore;
        this.expansionDataStore = expansionDataStore;
        this.tokenKMSVault = tokenKMSVault;
        this.jedisPool = jedisPool;

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

        Map<String, Long> actionsExecutedThisBatch = Maps.newHashMap();

        final Map<String, Long> allRecentActions = getAllRecentActions(DateTime.now(DateTimeZone.UTC).minusMinutes(MAX_REDIS_RECORD_AGE_MINUTES).getMillis());

        for (final Record record : records) {
            try {
                final ExpansionProtos.AlarmAction pb = ExpansionProtos.AlarmAction.parseFrom(record.getData().array());

                if(!pb.hasDeviceId() || pb.getDeviceId().isEmpty()) {
                    LOGGER.warn("warn=action-deviceId-missing");
                    continue;
                }
                final String senseId = pb.getDeviceId();

                if(!pb.hasServiceType() || !pb.hasExpectedRingtimeUtc()) {
                    LOGGER.warn("warn=invalid-protobuf sense_id={}", senseId);
                    continue;
                }

                final ExpansionProtos.ServiceType serviceType = pb.getServiceType();

                final Optional<Expansion> expansionOptional = expansionStore.getApplicationByName(serviceType.name());
                if(!expansionOptional.isPresent()) {
                    LOGGER.warn("warning=expansion-not-found");
                    continue;
                }

                final Expansion expansion = expansionOptional.get();
                final Integer bufferTimeSeconds = HomeAutomationExpansionFactory.getBufferTimeByServiceName(expansion.serviceName);

                //Check against expansion default action buffer time
                final long secondsTillRing = (pb.getExpectedRingtimeUtc() - DateTime.now(DateTimeZone.UTC).getMillis()) / 1000;
                if(secondsTillRing < 0) {
                    LOGGER.error("error=action-past-ringtime sense_id={} expansion_id={}", senseId, expansion.id);
                    continue;
                }

                if(secondsTillRing > bufferTimeSeconds) {
                    continue;
                }

                final String deviceExpansionHash = DigestUtils.md5Hex(senseId + expansion.id.toString() + pb.getExpectedRingtimeUtc());

                if(actionsExecutedThisBatch.containsKey(deviceExpansionHash)){
                    //Batch contained another record with the same action that was already executed
                    continue;
                }

                if(allRecentActions.containsKey(deviceExpansionHash)){
                    //This action has already been executed
                    LOGGER.info("action=action-already-executed sense_id={} expansion_id={} expected_ringtime={}", senseId, expansion.id, pb.getExpectedRingtimeUtc());
                    continue;
                }

                final ValueRange actionValueRange = new ValueRange(pb.getTargetValueMin(), pb.getTargetValueMax());

                //Attempt to pull action from cache
                final Boolean actionComplete = attemptAlarmAction(senseId, expansion.id, actionValueRange);

                if(actionComplete) {
                    successfulActions++;
                    actionsExecutedThisBatch.put(deviceExpansionHash, pb.getExpectedRingtimeUtc());
                }

            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("error=protobuf-decode-failure message={}", e.getMessage());
            }
        }

        if(!actionsExecutedThisBatch.isEmpty()) {
            recordAlarmActions(actionsExecutedThisBatch);
        }

        this.actionsExecuted.mark(successfulActions);

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

    public Boolean attemptAlarmAction(final String deviceId, final Long expansionId, final ValueRange actionValues) {

        final Optional<Expansion> expansionOptional = expansionStore.getApplicationById(expansionId);
        if(!expansionOptional.isPresent()) {
            LOGGER.warn("warn=expansion-not-found");
            return false;
        }

        final Expansion expansion = expansionOptional.get();

        LOGGER.info("action=expansion-alarm sense_id={} expansion_id={}", deviceId, expansion.id);

        final Optional<ExpansionData> expDataOptional = expansionDataStore.getAppData(expansion.id, deviceId);
        if(!expDataOptional.isPresent()) {
            LOGGER.error("error=no-ext-app-data expansion_id={} sense_id={}", expansion.id, deviceId);
            return false;
        }

        final ExpansionData expData = expDataOptional.get();

        final Optional<ExpansionDeviceData> expansionDeviceDataOptional = HomeAutomationExpansionDataFactory.getAppData(mapper, expData.data, expansion.serviceName);

        if(!expansionDeviceDataOptional.isPresent()){
            LOGGER.error("error=bad-expansion-data expansion_id={} sense_id={}", expansion.id, deviceId);
            return false;
        }

        final ExpansionDeviceData appData = expansionDeviceDataOptional.get();

        final Optional<String> decryptedTokenOptional = TokenUtils.getDecryptedExternalToken(externalTokenStore, tokenKMSVault, deviceId, expansion, false);

        if(!decryptedTokenOptional.isPresent()) {
            return false;
        }
        final String decryptedToken = decryptedTokenOptional.get();

        final Optional<HomeAutomationExpansion> homeExpansionOptional = HomeAutomationExpansionFactory.getExpansion(configuration.expansionConfiguration().hueAppName(), expansion.serviceName, appData, decryptedToken);
        if(!homeExpansionOptional.isPresent()){
            LOGGER.error("error=get-home-expansion-failed expansion_id={} sense_id={}", expansion.id, deviceId);
            return false;
        }

        final HomeAutomationExpansion homeExpansion = homeExpansionOptional.get();

        //Execute default alarm action for expansion
//        final Boolean isSuccessful = homeExpansion.runDefaultAlarmAction();
        final Boolean isSuccessful = homeExpansion.runAlarmAction(actionValues);

        if(!isSuccessful){
            LOGGER.error("error=alarm-action-failed sense_id={} expansion_id={}", deviceId, expansion.id);
        }

        return isSuccessful;
    }

    public void recordAlarmActions(final Map<String, Long> executedActions) {
        Jedis jedis = null;

        try {
            jedis = jedisPool.getResource();
            final Pipeline pipe = jedis.pipelined();
            pipe.multi();
            for(final Map.Entry <String, Long> entry : executedActions.entrySet()) {
                final Long expectedRingtimeUTC = entry.getValue();
                pipe.zadd(ALARM_ACTION_ATTEMPTS_KEY, expectedRingtimeUTC, entry.getKey());
            }
            pipe.exec();
        }catch (JedisDataException exception) {
            LOGGER.error("error=jedis-data-exception message={}", exception.getMessage());
            jedisPool.returnBrokenResource(jedis);
            return;
        } catch(Exception exception) {
            LOGGER.error("error=redis-unknown-failure message={}", exception.getMessage());
            jedisPool.returnBrokenResource(jedis);
            return;
        }
        finally {
            try{
                jedisPool.returnResource(jedis);
            }catch (JedisConnectionException e) {
                LOGGER.error(GENERIC_EXCEPTION_LOG_MESSAGE + " message={}", e.getMessage());
            }
        }
        LOGGER.debug("action=alarm_actions_recorded action_count={}", executedActions.size());
    }

    public Map<String, Long> getAllRecentActions(final Long oldestEvent) {
        final Map<String, Long> hashRingTimeMap = Maps.newHashMap();
        final Jedis jedis = jedisPool.getResource();
        try {
            //Get all elements in the index range provided (score greater than oldest event millis)
            final Set<Tuple> allRecentAlarmActions = jedis.zrevrangeByScoreWithScores(ALARM_ACTION_ATTEMPTS_KEY, Double.MAX_VALUE, oldestEvent);

            for (final Tuple attempt:allRecentAlarmActions) {
                final String deviceHash = attempt.getElement();
                final long expectedRingTime = (long) attempt.getScore();
                hashRingTimeMap.put(deviceHash, expectedRingTime);
            }
        } catch (JedisDataException exception) {
            LOGGER.error("error=jedis-data-exception message={}", exception.getMessage());
            jedisPool.returnBrokenResource(jedis);
        } catch (Exception exception) {
            LOGGER.error("error=redis-unknown-failure message={}", exception.getMessage());
            jedisPool.returnBrokenResource(jedis);
        } finally {
            try {
                jedisPool.returnResource(jedis);
            } catch (JedisConnectionException e) {
                LOGGER.error(GENERIC_EXCEPTION_LOG_MESSAGE + " message={}", e.getMessage());
            }
        }
        return hashRingTimeMap;
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
}
