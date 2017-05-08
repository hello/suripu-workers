package com.hello.suripu.workers.logs.triggers;

import com.google.common.collect.Lists;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class TimeHelperTest {

    class TestInput {
        final String name;
        final DateTime nowUTC;
        final int offset;
        final String expectedDay;

        TestInput(final String name, final DateTime nowUTC, final int offset, final String expectedDay) {
            this.name = name;
            this.nowUTC = nowUTC;
            this.offset = offset;
            this.expectedDay = expectedDay;
        }
    }

    @Test
    public void testLastNight() {
        final List<TestInput> inputs = Lists.newArrayList(
          new TestInput(
                  "SF",
                  new DateTime(2017,5,8, 20,0,0, DateTimeZone.UTC),
                  -25200000,
                  "2017-05-07"
          ),
            new TestInput(
                "Melbourne",
                new DateTime(2017,5,8, 20,0,0, DateTimeZone.UTC),
                    36000000,
                "2017-05-08"
            )
        );

        for (final TestInput testInput : inputs) {
            assertTrue(testInput.name, testInput.expectedDay.equals(TimeHelpers.lastNight(testInput.nowUTC, testInput.offset)));
        }
    }
}
