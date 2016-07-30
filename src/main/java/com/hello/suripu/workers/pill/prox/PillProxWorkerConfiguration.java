package com.hello.suripu.workers.pill.prox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class PillProxWorkerConfiguration extends WorkerConfiguration {
    @JsonProperty("max_records")
    private Integer maxRecords = 200;
    public Integer getMaxRecords() {
        return maxRecords;
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
    @JsonProperty("prox_table_prefix")
    private String proxTablePrefix;
    public String proxTablePrefix() { return proxTablePrefix;}
}
