package com.hello.suripu.workers.sense.lastSeen;

import com.fasterxml.jackson.annotation.JsonProperty;
<<<<<<< HEAD
=======

>>>>>>> master
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

public class SenseLastSeenWorkerConfiguration extends WorkerConfiguration {

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


    @JsonProperty("trim_horizon")
    private Boolean trimHorizon = Boolean.TRUE;
    public Boolean getTrimHorizon() {return trimHorizon;}
}
