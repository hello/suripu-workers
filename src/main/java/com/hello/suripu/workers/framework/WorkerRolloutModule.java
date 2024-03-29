package com.hello.suripu.workers.framework;

import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.flipper.DynamoDBAdapter;
import com.hello.suripu.core.processors.InsightProcessor;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessor;
import com.hello.suripu.workers.alarm.AlarmRecordProcessor;
import com.hello.suripu.workers.expansions.AlarmActionRecordProcessor;
import com.hello.suripu.workers.fanout.SenseStreamFanout;
import com.hello.suripu.workers.insights.AggStatsGenerator;
import com.hello.suripu.workers.insights.InsightsGenerator;
import com.hello.suripu.workers.logs.SenseStructuredLogIndexer;
import com.hello.suripu.workers.logs.timeline.TimelineLogProcessor;
import com.hello.suripu.workers.notifications.PushNotificationsProcessor;
import com.hello.suripu.workers.pill.S3RecordProcessor;
import com.hello.suripu.workers.pill.SavePillDataProcessor;
import com.hello.suripu.workers.sense.SenseSaveDDBProcessor;
import com.hello.suripu.workers.sense.SenseSaveProcessor;
import com.hello.suripu.workers.sense.lastSeen.SenseLastSeenProcessor;
import com.librato.rollout.RolloutAdapter;
import com.librato.rollout.RolloutClient;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Created by pangwu on 12/4/14.
 */
@Module(injects = {
        AggStatsGenerator.class,
        AlarmRecordProcessor.class,
        S3RecordProcessor.class,
        SavePillDataProcessor.class,
        SenseSaveProcessor.class,
        SenseSaveDDBProcessor.class,
        InsightsGenerator.class,
        InsightProcessor.class,
        PushNotificationsProcessor.class,
        InstrumentedTimelineProcessor.class,
        TimelineLogProcessor.class,
        SenseLastSeenProcessor.class,
        SenseStreamFanout.class,
        SenseStructuredLogIndexer.class,
        AlarmActionRecordProcessor.class
})
public class WorkerRolloutModule {
    private final FeatureStore featureStore;
    private final Integer pollingIntervalInSeconds;

    public WorkerRolloutModule(final FeatureStore featureStore, final Integer pollingIntervalInSeconds) {
        this.featureStore = featureStore;
        this.pollingIntervalInSeconds = pollingIntervalInSeconds;
    }

    @Provides
    @Singleton
    RolloutAdapter providesRolloutAdapter() {
        return new DynamoDBAdapter(featureStore, pollingIntervalInSeconds);
    }

    @Provides
    @Singleton
    RolloutClient providesRolloutClient(RolloutAdapter adapter) {
        return new RolloutClient(adapter);
    }
}
