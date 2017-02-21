package com.hello.suripu.workers.framework;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import io.dropwizard.Configuration;

public class SuripuWorkersSharedConfiguration extends Configuration {

    @JsonProperty("dynamo_db")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration() {
        return dynamoDBConfiguration;
    }
}
