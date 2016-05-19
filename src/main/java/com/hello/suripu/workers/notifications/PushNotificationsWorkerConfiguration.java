package com.hello.suripu.workers.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Sets;

import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredw8.configuration.PushNotificationsConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import io.dropwizard.db.DataSourceFactory;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.Set;

public class PushNotificationsWorkerConfiguration extends WorkerConfiguration {
    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();
    public DataSourceFactory getCommonDB() {
        return commonDB;
    }

    @Valid
    @NotNull
    @JsonProperty("max_records")
    private Integer maxRecords;

    public Integer getMaxRecords() {
        return maxRecords;
    }

    @Valid
    @NotNull
    @JsonProperty("push_notifications")
    private PushNotificationsConfiguration pushNotificationsConfiguration;

    public PushNotificationsConfiguration getPushNotificationsConfiguration() {
        return pushNotificationsConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @Valid
    @NotEmpty
    @JsonProperty("active_hours")
    private Set<Integer> activeHours = Sets.newHashSet();
    public Set<Integer> getActiveHours() {return activeHours;}

    @Valid
    @JsonProperty("pill_battery")
    private NotificationConfig pillBatteryConfig;
    public NotificationConfig getPillBatteryConfig() { return pillBatteryConfig; }
}
