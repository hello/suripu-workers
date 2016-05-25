package com.hello.suripu.workers.notifications;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by jakepiccolo on 5/19/16.
 */
class PushNotificationEvent {

    public final Long accountId;
    public final String type;
    public final DateTime timestamp;
    public final HelloPushMessage helloPushMessage;
    public final Optional<String> senseId;

    protected PushNotificationEvent(final Long accountId, final String type, final DateTime timestamp,
                                    final HelloPushMessage helloPushMessage, final Optional<String> senseId)
    {
        this.accountId = accountId;
        this.type = type;
        this.timestamp = timestamp;
        this.helloPushMessage = helloPushMessage;
        this.senseId = senseId;
    }


    //region Object overrides

    @Override
    public boolean equals(final Object obj) {
        if (obj == null || !(obj instanceof PushNotificationEvent)) {
            return false;
        }

        final PushNotificationEvent event = (PushNotificationEvent) obj;
        return Objects.equals(accountId, event.accountId) &&
                Objects.equals(type, event.type) &&
                Objects.equals(timestamp, event.timestamp) &&
                Objects.equals(helloPushMessage, event.helloPushMessage) &&
                Objects.equals(senseId, event.senseId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(PushNotificationEvent.class)
                .add("accountId", accountId)
                .add("type", type)
                .add("timestamp", timestamp)
                .add("helloPushMessage", helloPushMessage)
                .add("senseId", senseId)
                .toString();
    }

    //endregion Object overrides


    //region Builder

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private Long accountId;
        private String type;
        private DateTime timestamp;
        private HelloPushMessage helloPushMessage;
        private Optional<String> senseId = Optional.absent();

        public PushNotificationEvent build() {
            checkNotNull(accountId);
            checkNotNull(type);
            checkNotNull(helloPushMessage);

            final DateTime eventTimestamp = timestamp == null ? DateTime.now(DateTimeZone.UTC) : timestamp;
            return new PushNotificationEvent(accountId, type, eventTimestamp, helloPushMessage, senseId);
        }

        public Builder withAccountId(final Long accountId) {
            this.accountId = accountId;
            return this;
        }

        public Builder withType(final String type) {
            this.type = type;
            return this;
        }

        /**
         * Set the timestamp for the event. Defaults to DateTime.now(UTC)
         */
        public Builder withTimestamp(final DateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder withHelloPushMessage(final HelloPushMessage helloPushMessage) {
            this.helloPushMessage = helloPushMessage;
            return this;
        }

        public Builder withSenseId(final String senseId) {
            this.senseId = Optional.fromNullable(senseId);
            return this;
        }
    }

    //endregion Builder

}
