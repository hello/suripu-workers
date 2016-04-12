package com.hello.suripu.workers.logs;

public interface LogIndexer<T> {

    void collect(final T t);
    void collect(final T t, final String sequenceNumber);
    Integer index();
    void flush();
    void shutdown();
}
