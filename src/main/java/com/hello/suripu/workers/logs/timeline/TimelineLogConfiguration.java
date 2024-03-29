package com.hello.suripu.workers.logs.timeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class TimelineLogConfiguration extends WorkerConfiguration {


    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();
    public DataSourceFactory getCommonDB() {
        return commonDB;
    }


    @JsonProperty("max_records")
    private Integer maxRecords = 1000;
    public Integer maxRecords() {
        return maxRecords;
    }


    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }
}
