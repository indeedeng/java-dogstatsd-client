package com.timgroup.statsd;

/**
 * @author sfreitag
 */
public class NonBlockingStatsDClientTest extends StatsDClientTest<NonBlockingStatsDClient> {
    @Override
    public NonBlockingStatsDClient buildClient(String prefix, String[] constantTags) {
        return new NonBlockingStatsDClient(prefix, STATSD_SERVER_HOST, STATSD_SERVER_PORT, constantTags);
    }
}
