package com.hello.suripu.workers.expansions;

public enum ExpansionAttemptStatus {
    OK,
    DEVICE_ID_MISSING,
    NOT_FOUND,
    PAST_RINGTIME,
    ALREADY_EXECUTED,
    INVALID_PROTOBUF,
    EXCEEDS_BUFFER_TIME
}
