package com.hello.suripu.workers.expansions;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.codahale.metrics.MetricRegistry;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.ScheduledRingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.speech.interfaces.Vault;

import is.hello.gaibu.core.models.Expansion;
import is.hello.gaibu.core.models.ExternalToken;
import is.hello.gaibu.core.stores.ExpansionStore;
import is.hello.gaibu.core.stores.ExternalOAuthTokenStore;
import is.hello.gaibu.core.stores.PersistentExpansionDataStore;
import redis.clients.jedis.JedisPool;

/**
 * Created by pangwu on 9/23/14.
 */
public class AlarmActionRecordProcessorFactory implements IRecordProcessorFactory {

    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB;
    private final AlarmActionWorkerConfiguration configuration;
    private final MetricRegistry metricRegistry;
    private final ExpansionStore<Expansion> expansionStore;
    private final ExternalOAuthTokenStore<ExternalToken> externalTokenStore;
    private final PersistentExpansionDataStore expansionDataStore;
    private final Vault tokenKMSVault;
    private final JedisPool jedisPool;


    public AlarmActionRecordProcessorFactory(
            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
            final ScheduledRingTimeHistoryDAODynamoDB scheduledRingTimeHistoryDAODynamoDB,
            final AlarmActionWorkerConfiguration configuration,
            final MetricRegistry metrics,
            final ExpansionStore<Expansion> expansionStore,
            final ExternalOAuthTokenStore<ExternalToken> externalTokenStore,
            final PersistentExpansionDataStore expansionDataStore,
            final Vault tokenKMSVault,
            final JedisPool jedisPool) {

        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.scheduledRingTimeHistoryDAODynamoDB = scheduledRingTimeHistoryDAODynamoDB;
        this.configuration = configuration;
        this.metricRegistry = metrics;
        this.expansionStore = expansionStore;
        this.externalTokenStore = externalTokenStore;
        this.expansionDataStore = expansionDataStore;
        this.tokenKMSVault = tokenKMSVault;
        this.jedisPool = jedisPool;
    }


    @Override
    public IRecordProcessor createProcessor() {
        return new AlarmActionRecordProcessor(this.mergedUserInfoDynamoDB,
                this.scheduledRingTimeHistoryDAODynamoDB,
                this.configuration,
                this.metricRegistry,
                this.expansionStore,
                this.externalTokenStore,
                this.expansionDataStore,
                this.tokenKMSVault,
                this.jedisPool
            );
    }
}
