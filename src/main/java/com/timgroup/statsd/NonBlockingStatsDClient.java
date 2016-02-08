package com.timgroup.statsd;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A simple StatsD client implementation facilitating metrics recording.
 *
 * <p>Upon instantiation, this client will establish a socket connection to a StatsD instance
 * running on the specified host and port. Metrics are then sent over this connection as they are
 * received by the client.
 * </p>
 *
 * <p>Three key methods are provided for the submission of data-points for the application under
 * scrutiny:
 * <ul>
 *   <li>{@link #incrementCounter} - adds one to the value of the specified named counter</li>
 *   <li>{@link #recordGaugeValue} - records the latest fixed value for the specified named gauge</li>
 *   <li>{@link #recordExecutionTime} - records an execution time in milliseconds for the specified named operation</li>
 *   <li>{@link #recordHistogramValue} - records a value, to be tracked with average, maximum, and percentiles</li>
 *   <li>{@link #recordEvent} - records an event</li>
 *   <li>{@link #recordSetValue} - records a value in a set</li>
 * </ul>
 * From the perspective of the application, these methods are non-blocking, with the resulting
 * IO operations being carried out in a separate thread. Furthermore, these methods are guaranteed
 * not to throw an exception which may disrupt application execution.
 * </p>
 *
 * <p>As part of a clean system shutdown, the {@link #stop()} method should be invoked
 * on any StatsD clients.</p>
 *
 * @author Tom Denley
 *
 */
public final class NonBlockingStatsDClient extends BlockingStatsDClient {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        final ThreadFactory delegate = Executors.defaultThreadFactory();
        @Override public Thread newThread(final Runnable r) {
            final Thread result = delegate.newThread(r);
            result.setName("StatsD-" + result.getName());
            result.setDaemon(true);
            return result;
        }
    });

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are consumed, guaranteeing
     * that failures in metrics will not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param hostname
     *     the host name of the targeted StatsD server
     * @param port
     *     the port of the targeted StatsD server
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(final String prefix, final String hostname, final int port) throws StatsDClientException {
        this(prefix, hostname, port, null, null);
    }

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are consumed, guaranteeing
     * that failures in metrics will not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param hostname
     *     the host name of the targeted StatsD server
     * @param port
     *     the port of the targeted StatsD server
     * @param constantTags
     *     tags to be added to all content sent
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(final String prefix, final String hostname, final int port, final String... constantTags) throws StatsDClientException {
        this(prefix, hostname, port, constantTags, null);
    }

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are passed to the specified
     * handler and then consumed, guaranteeing that failures in metrics will
     * not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param hostname
     *     the host name of the targeted StatsD server
     * @param port
     *     the port of the targeted StatsD server
     * @param constantTags
     *     tags to be added to all content sent
     * @param errorHandler
     *     handler to use when an exception occurs during usage, may be null to indicate noop
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(final String prefix, final String hostname, final int port, final String[] constantTags,
                                   final StatsDClientErrorHandler errorHandler) throws StatsDClientException {
        this(prefix, constantTags, errorHandler, staticStatsDAddressResolution(hostname, port));
    }

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are passed to the specified
     * handler and then consumed, guaranteeing that failures in metrics will
     * not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param constantTags
     *     tags to be added to all content sent
     * @param errorHandler
     *     handler to use when an exception occurs during usage, may be null to indicate noop
     * @param addressLookup
     *     yields the IP address and socket of the StatsD server
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(final String prefix, String[] constantTags, final StatsDClientErrorHandler errorHandler,
                                   final Callable<InetSocketAddress> addressLookup) throws StatsDClientException {
        super(prefix, constantTags, errorHandler, addressLookup);
        this.executor.submit(new QueueConsumer());
    }

    /**
     * Cleanly shut down this StatsD client. This method may throw an exception if
     * the socket cannot be closed.
     */
    @Override
    public void stop() {
        try {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch (final Exception e) {
            handler.handle(e);
        } finally {
            super.stop();
        }
    }

    protected void send(String message) {
        queue.offer(message);
    }

    private void blockingSend(String message) {
        super.send(message);
    }

    private class QueueConsumer implements Runnable {
        @Override public void run() {
            while(!executor.isShutdown()) {
                try {
                    if (queue.peek() == null) {
                        Thread.sleep(10);
                    } else {
                        blockingSend(queue.take());
                    }
                } catch (final Exception e) {
                    handler.handle(e);
                }
            }
        }
    }
}
