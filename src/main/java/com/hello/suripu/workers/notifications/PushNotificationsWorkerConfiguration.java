package com.hello.suripu.workers.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hello.suripu.core.models.MobilePushRegistration;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredropwizard.configuration.PushNotificationsConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import io.dropwizard.db.DataSourceFactory;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.Map;
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

    @NotNull
    @JsonProperty("segment_write_key")
    private String segmentWriteKey;
    public String segmentWriteKey() {
        return segmentWriteKey;
    }


    @NotNull
    @JsonProperty("min_mobile_app_versions")
    private Map<MobilePushRegistration.OS, String> minAppVersions = Maps.newHashMap();
    public Map<MobilePushRegistration.OS, String> minAppVersions() {
        return minAppVersions;
    }
}
