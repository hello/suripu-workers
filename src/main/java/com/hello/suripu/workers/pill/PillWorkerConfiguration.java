package com.hello.suripu.workers.pill;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import com.hello.suripu.workers.pill.notifications.PillBatteryNotificationConfig;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

public class PillWorkerConfiguration extends WorkerConfiguration {

    @Valid
    @NotNull
    @JsonProperty("sensors_db")
    private DataSourceFactory sensorsDB = new DataSourceFactory();
    public DataSourceFactory getSensorsDB() {
        return sensorsDB;
    }

    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();
    public DataSourceFactory getCommonDB() {
        return commonDB;
    }

    @Valid
    @NotNull
    @Max(1000)
    @JsonProperty("max_records")
    private Integer maxRecords;

    public Integer getMaxRecords() {
        return maxRecords;
    }

    @Valid
    @NotNull
    @Max(100)
    @JsonProperty("batch_size")
    private Integer batchSize;

    public Integer getBatchSize() {
        return batchSize;
    }

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @Valid
    @JsonProperty("battery_notification")
    private PillBatteryNotificationConfig batteryNotificationConfig;
    public PillBatteryNotificationConfig getBatteryNotificationConfig() {
        if (batteryNotificationConfig == null) {
            return new PillBatteryNotificationConfig();
        }
        return batteryNotificationConfig;
    }
}
