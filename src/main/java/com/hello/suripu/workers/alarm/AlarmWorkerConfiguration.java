package com.hello.suripu.workers.alarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;


import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

/**
 * Created by pangwu on 9/23/14.
 */
public class AlarmWorkerConfiguration extends WorkerConfiguration {


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
    @JsonProperty("light_sleep_init_threshold")
    private Float lightSleepThreshold;

    public Float getLightSleepThreshold(){
        return this.lightSleepThreshold;
    }


    @Valid
    @NotNull
    @JsonProperty("aggregate_window_size_min")
    private Integer aggregateWindowSizeInMinute;

    public Integer getAggregateWindowSizeInMinute(){
        return this.aggregateWindowSizeInMinute;
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
}
