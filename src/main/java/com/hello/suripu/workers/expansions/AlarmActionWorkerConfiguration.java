package com.hello.suripu.workers.expansions;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredropwizard.configuration.RedisConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

import io.dropwizard.db.DataSourceFactory;

/**
 * Created by pangwu on 9/23/14.
 */
public class AlarmActionWorkerConfiguration extends WorkerConfiguration {


    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();
    public DataSourceFactory getCommonDB() {
        return commonDB;
    }

    @Valid
    @NotNull
    @JsonProperty("smart_alarm_process_ahead_in_minutes")
    private Integer processAheadTimeInMinutes;

    public Integer getProcessAheadTimeInMinutes() {
        return this.processAheadTimeInMinutes;
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
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration getDynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }


    @Valid
    @NotNull
    @JsonProperty("maximum_record_age_minutes")
    private Integer maximumRecordAgeMinutes;
    public Integer getMaximumRecordAgeMinutes() { return this.maximumRecordAgeMinutes; }

    @JsonProperty("expansions")
    private ExpansionConfiguration expansionConfiguration;
    public ExpansionConfiguration expansionConfiguration() { return this.expansionConfiguration; }

    @JsonProperty("keys_management_service")
    private KMSConfiguration kmsConfiguration;
    public KMSConfiguration kmsConfiguration() { return this.kmsConfiguration; }

    @JsonProperty("redis")
    private RedisConfiguration redisConfiguration;
    public RedisConfiguration redisConfiguration() {
        return redisConfiguration;
    }

}
