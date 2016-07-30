package com.hello.suripu.workers;

import com.hello.suripu.workers.alarm.AlarmWorkerCommand;
import com.hello.suripu.workers.fanout.SenseStreamFanoutCommand;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import com.hello.suripu.workers.insights.InsightsGeneratorWorkerCommand;
import com.hello.suripu.workers.logs.LogIndexerWorkerCommand;
import com.hello.suripu.workers.logs.timeline.TimelineLogCommand;
import com.hello.suripu.workers.notifications.PushNotificationsWorkerCommand;
import com.hello.suripu.workers.pill.PillWorkerCommand;
import com.hello.suripu.workers.pill.prox.PillProxWorkerCommand;
import com.hello.suripu.workers.sense.SenseSaveWorkerCommand;
import com.hello.suripu.workers.sense.lastSeen.SenseLastSeenWorkerCommand;
import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.joda.time.DateTimeZone;

import java.util.TimeZone;

public class HelloWorker extends Application<WorkerConfiguration> {

    public static void main(String[] args) throws Exception {
        java.security.Security.setProperty("networkaddress.cache.ttl", "10");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DateTimeZone.setDefault(DateTimeZone.UTC);
        new HelloWorker().run(args);
    }

    @Override
    public void initialize(Bootstrap<WorkerConfiguration> bootstrap) {
        bootstrap.addCommand(new PillWorkerCommand("pill", "all things about pill"));
        bootstrap.addCommand(new PillWorkerCommand("pill_save_ddb", "save pill data to DynamoDB", true));
        bootstrap.addCommand(new SenseSaveWorkerCommand("sense_save", "saving sense sensor data"));
        bootstrap.addCommand(new SenseSaveWorkerCommand("sense_save_ddb", "saving sense sensor data to DynamoDB", true, false));
        bootstrap.addCommand(new SenseLastSeenWorkerCommand("sense_last_seen", "saving sense last seen data"));
        bootstrap.addCommand(new AlarmWorkerCommand("smart_alarm", "Start smart alarm worker"));
        bootstrap.addCommand(new LogIndexerWorkerCommand("index_logs", "Indexes logs from Kinesis stream into searchify index"));
        bootstrap.addCommand(new InsightsGeneratorWorkerCommand("insights_generator", "generate insights for users"));
        bootstrap.addCommand(new PushNotificationsWorkerCommand("push", "send push notifications"));
        bootstrap.addCommand(new TimelineLogCommand("timeline_log", "timeline log"));
        bootstrap.addCommand(new SenseStreamFanoutCommand("sense_stream_fanout", "fanout sense stream"));
        bootstrap.addCommand(new PillProxWorkerCommand("prox", "pill prox"));
    }

    @Override
    public void run(WorkerConfiguration configuration, Environment environment) throws Exception {
        // Do nothing
    }
}
