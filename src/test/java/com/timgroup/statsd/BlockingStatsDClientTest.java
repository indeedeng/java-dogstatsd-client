package com.timgroup.statsd;

/**
 * @author sfreitag
 */
public class BlockingStatsDClientTest extends StatsDClientTest<BlockingStatsDClient> {
    @Override
    public BlockingStatsDClient buildClient(String prefix, String[] constantTags) {
        return new BlockingStatsDClient(prefix, STATSD_SERVER_HOST, STATSD_SERVER_PORT, constantTags);
    }
}
