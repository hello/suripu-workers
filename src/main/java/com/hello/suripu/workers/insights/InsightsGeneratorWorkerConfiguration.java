package com.hello.suripu.workers.insights;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.core.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;


import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

import io.dropwizard.db.DataSourceFactory;

/**
 * Created by kingshy on 1/6/15.
 */
public class InsightsGeneratorWorkerConfiguration extends WorkerConfiguration {

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
    @JsonProperty("insights_db")
    private DataSourceFactory insightsDB = new DataSourceFactory();
    public DataSourceFactory getInsightsDB() {
        return insightsDB;
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
    @JsonProperty("dynamo_db")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("sleep_score_version")
    private String sleepScoreVersion;
    public String getSleepScoreVersion() { return sleepScoreVersion; }

    @Valid
    @NotNull
    @JsonProperty("sleep_stats_version")
    private String sleepStatsVersion;
    public String getSleepStatsVersion() {
        return this.sleepStatsVersion;
    }
}
