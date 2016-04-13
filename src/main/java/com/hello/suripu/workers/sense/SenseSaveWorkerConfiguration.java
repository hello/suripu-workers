package com.hello.suripu.workers.sense;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredw8.configuration.RedisConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

public class SenseSaveWorkerConfiguration extends WorkerConfiguration {

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
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
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
    @JsonProperty("redis")
    private RedisConfiguration redisConfiguration;
    public RedisConfiguration getRedisConfiguration() {
        return redisConfiguration;
    }

    @JsonProperty("trim_horizon")
    private Boolean trimHorizon = Boolean.TRUE;
    public Boolean getTrimHorizon() {return trimHorizon;}
}
