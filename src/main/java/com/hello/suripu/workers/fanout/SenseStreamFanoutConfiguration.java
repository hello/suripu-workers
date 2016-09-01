package com.hello.suripu.workers.fanout;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.KinesisConfiguration;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

/**
 * Created by ksg on 4/8/16
 */
public class SenseStreamFanoutConfiguration extends WorkerConfiguration {
    @Valid
    @NotNull
    @Max(10000)
    @JsonProperty("max_records")
    private Integer maxRecords;
    public Integer getMaxRecords() {
        return maxRecords;
    }

    @Valid
    @NotNull
    @JsonProperty("idle_time_between_reads_millis")
    private long idleTimeBetweenReadsInMillis;
    public long getIdleTimeBetweenReadsInMillis() { return idleTimeBetweenReadsInMillis; }

    @JsonProperty("trim_horizon")
    private Boolean trimHorizon = Boolean.TRUE;
    public Boolean getTrimHorizon() {return trimHorizon;}

    @JsonProperty("output")
    private KinesisConfiguration kinesisOutputConfiguration;
    public KinesisConfiguration getKinesisOutputConfiguration() {
        return kinesisOutputConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

}
