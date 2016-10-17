package com.hello.suripu.workers.expansions;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * Created by ksg on 9/2/16
 */
public class KMSConfiguration {
    public class Keys {
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
