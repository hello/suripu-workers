package com.hello.suripu.workers.splitter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredw.configuration.KinesisConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

/**
 * Created by ksg on 4/8/16
 */
public class SenseStreamSplitterConfiguration extends WorkerConfiguration {
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

    @JsonProperty("kinesis_output")
    private KinesisConfiguration kinesisConfiguration;
    public KinesisConfiguration getKinesisConfiguration() {
        return kinesisConfiguration;
    }

}