package com.hello.suripu.workers.supichi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;

/**
 * Created by ksg on 8/11/16
 */
public class SupichiWorkerConfiguration extends WorkerConfiguration {
    public class S3AudioConfiguration {
        @Valid
        @NotNull
        @JsonProperty("s3_bucket_name")
        private String bucketName;
        String getBucketName() { return bucketName; }

        @Valid
        @JsonProperty("s3_audio_prefix_raw")
        private String audioPrefixRaw;
        public String getAudioPrefixRaw() { return audioPrefixRaw; }

        @Valid
        @NotNull
        @JsonProperty("s3_audio_prefix")
        private String audioPrefix;
        String getAudioPrefix() { return audioPrefix; }
    }

    public class KMSConfiguration {
        public class Keys {
            @Valid
            @NotNull
            @JsonProperty("uuid")
            private String uuidKey;
            public String uuid() { return this.uuidKey; }

            @Valid
            @NotNull
            @JsonProperty("audio")
            private String audioKey;
            public String audio() { return this.audioKey; }

            @Valid
            @NotNull
            @JsonProperty("token")
            private String tokenKey;
            public String token() { return this.tokenKey; }
        }

        @Valid
        @NotNull
        @JsonProperty("endpoint")
        private String endpoint;
        public String endpoint() { return this.endpoint; }

        @Valid
        @NotNull
        @JsonProperty("keys")
        private Keys kmsKeys;
        public Keys kmsKeys() { return this.kmsKeys; }
    }


    @JsonProperty("trim_horizon")
    private Boolean trimHorizon = Boolean.TRUE;
    public Boolean trimHorizon() {return trimHorizon;}

    @Valid
    @NotNull
    @Max(1000)
    @JsonProperty("max_records")
    private int maxRecords;
    public int maxRecords() { return maxRecords; }

    @Valid
    @NotNull
    @JsonProperty("sense_upload_audio")
    private S3AudioConfiguration senseUploadAudioConfiguration;
    public S3AudioConfiguration senseUploadAudioConfiguration() { return senseUploadAudioConfiguration;}

    @JsonProperty("keys_management_service")
    private KMSConfiguration kmsConfiguration;
    public KMSConfiguration kmsConfiguration() { return this.kmsConfiguration; }

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @JsonProperty("s3_endpoint")
    private String s3Endpoint;
    public String s3Endpoint() { return s3Endpoint; }

}
