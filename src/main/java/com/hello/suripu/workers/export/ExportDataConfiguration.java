package com.hello.suripu.workers.export;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredropwizard.configuration.RedisConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class ExportDataConfiguration extends WorkerConfiguration {

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("export_data_queue_url")
    private String exportDataQueueUrl = "";
    public String exportDataQueueUrl() {
        return exportDataQueueUrl;
    }
    
    @JsonProperty("lookback_months")
    private Integer lookBackMonths = 36;
    public Integer lookBackMonths() {
        return lookBackMonths;
    }

    @Valid
    @NotNull
    @JsonProperty("export_bucket_name")
    private String exportBucketName = "";
    public String exportBucketName() {
        return exportBucketName;
    }

    @JsonProperty("redis")
    private RedisConfiguration redisConfiguration;
    public RedisConfiguration redisConfiguration() {
        return redisConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("mandrill_api_key")
    private String mandrillApiKey = "";
    public String mandrillApiKey() {
        return mandrillApiKey;
    }
}
