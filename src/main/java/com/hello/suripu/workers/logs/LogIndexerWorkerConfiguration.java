package com.hello.suripu.workers.logs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredw8.configuration.RedisConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class LogIndexerWorkerConfiguration extends WorkerConfiguration {

    @JsonProperty("max_records")
    private Integer maxRecords = 50;
    public Integer maxRecords() {
        return maxRecords;
    }

    @Valid
    @NotNull
    @JsonProperty("dynamo_db")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();
    public DataSourceFactory getCommonDB() {
        return commonDB;
    }

    @JsonProperty("redis")
    private RedisConfiguration redisConfiguration;
    public RedisConfiguration redisConfiguration() {
        return redisConfiguration;
    }
}
