package com.timgroup.statsd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.FatalExceptionHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.Util;

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
 * @author mick semb wever
 *
 */
public final class NonBlockingStatsDClient implements StatsDClient {

    private static final int PACKET_SIZE_BYTES = 1500;
    private static final int RINGBUFFER_DEFAULT_SIZE = 16384;
    private static final int RINGBUFFER_MIN_SIZE = 128;

    private final static Logger LOG = Logger.getLogger(NonBlockingStatsDClient.class.getName());

    private static final StatsDClientErrorHandler NO_OP_HANDLER = new StatsDClientErrorHandler() {
        @Override public void handle(Exception e) { /* No-op */ }
    };

    /**
     * Because NumberFormat is not thread-safe we cannot share instances across threads. Use a ThreadLocal to
     * create one pre thread as this seems to offer a significant performance improvement over creating one per-thread:
     * http://stackoverflow.com/a/1285297/2648
     * https://github.com/indeedeng/java-dogstatsd-client/issues/4
     */
    private static final ThreadLocal<NumberFormat> NUMBER_FORMATTERS = new ThreadLocal<NumberFormat>() {
        @Override
        protected NumberFormat initialValue() {

            // Always create the formatter for the US locale in order to avoid this bug:
            // https://github.com/indeedeng/java-dogstatsd-client/issues/3
            NumberFormat numberFormatter = NumberFormat.getInstance(Locale.US);
            numberFormatter.setGroupingUsed(false);
            numberFormatter.setMaximumFractionDigits(6);
            return numberFormatter;
        }
    };

    private final static EventFactory<Event> FACTORY = new EventFactory<Event>() {
        @Override
        public Event newInstance() {
            return new Event();
        }
    };

    private static final EventTranslatorOneArg<Event,String> TRANSLATOR = new EventTranslatorOneArg<Event,String>() {
        @Override
        public void translateTo(Event event, long sequence, String msg) {
            event.setValue(msg);
        }
    };

    private final String prefix;
    private final DatagramChannel clientChannel;
    private final InetSocketAddress address;
    private final StatsDClientErrorHandler handler;
    private final String constantTagsRendered;

    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        final ThreadFactory delegate = Executors.defaultThreadFactory();
        @Override public Thread newThread(Runnable r) {
            Thread result = delegate.newThread(r);
            result.setName("StatsD-disruptor-" + result.getName());
            result.setDaemon(true);
            return result;
        }
    });

    private final Disruptor<Event> disruptor = new Disruptor<Event>(FACTORY, calculateRingBufferSize(), executor);

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
    public NonBlockingStatsDClient(String prefix, String hostname, int port) throws StatsDClientException {
        this(prefix, hostname, port, null, NO_OP_HANDLER);
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
    public NonBlockingStatsDClient(String prefix, String hostname, int port, String... constantTags) throws StatsDClientException {
        this(prefix, hostname, port, constantTags, NO_OP_HANDLER);
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
     *     handler to use when an exception occurs during usage
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(String prefix, String hostname, int port, String[] constantTags, StatsDClientErrorHandler errorHandler) throws StatsDClientException {
        if(prefix != null && prefix.length() > 0) {
            this.prefix = String.format("%s.", prefix);
        } else {
            this.prefix = "";
        }
        this.handler = errorHandler;

        /* Empty list should be null for faster comparison */
        if(constantTags != null && constantTags.length == 0) {
            constantTags = null;
        }

        if(constantTags != null) {
            this.constantTagsRendered = tagString(constantTags, null);
        } else {
            this.constantTagsRendered = null;
        }

        try {
            this.clientChannel = DatagramChannel.open();
            this.address = new InetSocketAddress(hostname, port);
        } catch (Exception e) {
            throw new StatsDClientException("Failed to start StatsD client", e);
        }

        disruptor.handleEventsWith(new Handler());
        disruptor.handleExceptionsWith(new DisruptorExceptionHandler(this.handler));
        disruptor.start();
    }

    /**
     * Cleanly shut down this StatsD client. This method may throw an exception if
     * the socket cannot be closed.
     */
    @Override
    public void stop() {
        try {
            disruptor.shutdown();
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            handler.handle(e);
        }
        finally {
            if (clientChannel != null) {
                try {
                    clientChannel.close();
                }
                catch (IOException e) {
                    handler.handle(e);
                }
            }
        }
    }

    /**
     * Generate a suffix conveying the given tag list to the client
     */
    static String tagString(final String[] tags, final String tagPrefix) {
        StringBuilder sb;
        if(tagPrefix != null) {
            if(tags == null || tags.length == 0) {
                return tagPrefix;
            }
            sb = new StringBuilder(tagPrefix);
            sb.append(",");
        } else {
            if(tags == null || tags.length == 0) {
                return "";
            }
            sb = new StringBuilder("|#");
        }

        for(int n=tags.length - 1; n>=0; n--) {
            sb.append(tags[n]);
            if(n > 0) {
                sb.append(",");
            }
        }
        return sb.toString();
    }

    /**
     * Generate a suffix conveying the given tag list to the client
     */
    String tagString(final String[] tags) {
        return tagString(tags, constantTagsRendered);
    }

    /**
     * Adjusts the specified counter by a given delta.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the counter to adjust
     * @param delta
     *     the amount to adjust the counter by
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void count(String aspect, long delta, String... tags) {
        send(String.format("%s%s:%d|c%s", prefix, aspect, delta, tagString(tags)));
    }

    /**
     * Increments the specified counter by one.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the counter to increment
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void incrementCounter(String aspect, String... tags) {
        count(aspect, 1, tags);
    }

    /**
     * Convenience method equivalent to {@link #incrementCounter(String, String[])}.
     */
    @Override
    public void increment(String aspect, String... tags) {
        incrementCounter(aspect, tags);
    }

    /**
     * Decrements the specified counter by one.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the counter to decrement
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void decrementCounter(String aspect, String... tags) {
        count(aspect, -1, tags);
    }

    /**
     * Convenience method equivalent to {@link #decrementCounter(String, String[])}.
     */
    @Override
    public void decrement(String aspect, String... tags) {
        decrementCounter(aspect, tags);
    }

    /**
     * Records the latest fixed value for the specified named gauge.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the gauge
     * @param value
     *     the new reading of the gauge
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordGaugeValue(String aspect, double value, String... tags) {
        /* Intentionally using %s rather than %f here to avoid
         * padding with extra 0s to represent precision */
        send(String.format("%s%s:%s|g%s", prefix, aspect, NUMBER_FORMATTERS.get().format(value), tagString(tags)));
    }

    /**
     * Convenience method equivalent to {@link #recordGaugeValue(String, double, String[])}.
     */
    @Override
    public void gauge(String aspect, double value, String... tags) {
        recordGaugeValue(aspect, value, tags);
    }


    /**
     * Records the latest fixed value for the specified named gauge.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the gauge
     * @param value
     *     the new reading of the gauge
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordGaugeValue(String aspect, long value, String... tags) {
        send(String.format("%s%s:%d|g%s", prefix, aspect, value, tagString(tags)));
    }

    /**
     * Convenience method equivalent to {@link #recordGaugeValue(String, long, String[])}.
     */
    @Override
    public void gauge(String aspect, long value, String... tags) {
        recordGaugeValue(aspect, value, tags);
    }

    /**
     * Records an execution time in milliseconds for the specified named operation.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the timed operation
     * @param timeInMs
     *     the time in milliseconds
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordExecutionTime(String aspect, long timeInMs, String... tags) {
        send(String.format("%s%s:%d|ms%s", prefix, aspect, timeInMs, tagString(tags)));
    }

    /**
     * Convenience method equivalent to {@link #recordExecutionTime(String, long, String[])}.
     */
    @Override
    public void time(String aspect, long value, String... tags) {
        recordExecutionTime(aspect, value, tags);
    }

    /**
     * Records a value for the specified named histogram.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the histogram
     * @param value
     *     the value to be incorporated in the histogram
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordHistogramValue(String aspect, double value, String... tags) {
        /* Intentionally using %s rather than %f here to avoid
         * padding with extra 0s to represent precision */
        send(String.format("%s%s:%s|h%s", prefix, aspect, NUMBER_FORMATTERS.get().format(value), tagString(tags)));
    }

    /**
     * Convenience method equivalent to {@link #recordHistogramValue(String, double, String[])}.
     */
    @Override
    public void histogram(String aspect, double value, String... tags) {
        recordHistogramValue(aspect, value, tags);
    }

    /**
     * Records a value for the specified named histogram.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the histogram
     * @param value
     *     the value to be incorporated in the histogram
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordHistogramValue(String aspect, long value, String... tags) {
        send(String.format("%s%s:%d|h%s", prefix, aspect, value, tagString(tags)));
    }

    /**
     * Convenience method equivalent to {@link #recordHistogramValue(String, long, String[])}.
     */
    @Override
    public void histogram(String aspect, long value, String... tags) {
        recordHistogramValue(aspect, value, tags);
    }

    private String eventMap(final Event event) {
        final StringBuilder res = new StringBuilder("");

        final long millisSinceEpoch = event.getMillisSinceEpoch();
        if (millisSinceEpoch != -1) {
            res.append("|d:").append(millisSinceEpoch / 1000);
        }

        final String hostname = event.getHostname();
        if (hostname != null) {
            res.append("|h:").append(hostname);
        }

        final String aggregationKey = event.getAggregationKey();
        if (aggregationKey != null) {
            res.append("|k:").append(aggregationKey);
        }

        final String priority = event.getPriority();
        if (priority != null) {
            res.append("|p:").append(priority);
        }

        final String alertType = event.getAlertType();
        if (alertType != null) {
            res.append("|t:").append(alertType);
        }

        return res.toString();
    }

    /**
     * Records an event
     *
     * <p>This method is a DataDog extension, and may not work with other servers.</p>
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param event
     *     The event to record
     * @param tags
     *     array of tags to be added to the data
     *
     * @see <a href="http://docs.datadoghq.com/guides/dogstatsd/#events-1">http://docs.datadoghq.com/guides/dogstatsd/#events-1</a>
     */
    @Override
    public void recordEvent(final Event event, final String... tags) {
        final String title = prefix + event.getTitle();
        final String text = event.getText();
        send(String.format("_e{%d,%d}:%s|%s%s%s",
                title.length(), text.length(), title, text, eventMap(event), tagString(tags)));
    }

    private void send(String message) {
        if(!disruptor.getRingBuffer().tryPublishEvent(TRANSLATOR, message)) {
            handler.handle(InsufficientCapacityException.INSTANCE);
        }
    }

    private static int calculateRingBufferSize() {
        int ringBufferSize = RINGBUFFER_DEFAULT_SIZE;

        String userPreferredRBSize = System.getProperty(
                "NonBlockingStatsDClient.ringBufferSize",
                String.valueOf(RINGBUFFER_DEFAULT_SIZE));

        try {
            ringBufferSize = Integer.parseInt(userPreferredRBSize);
            if (ringBufferSize < RINGBUFFER_MIN_SIZE) {
                ringBufferSize = RINGBUFFER_MIN_SIZE;

                LOG.warning(String.format(
                        "Invalid RingBufferSize {}, using minimum size {}.",
                        userPreferredRBSize,
                        RINGBUFFER_MIN_SIZE));
            }
        } catch (NumberFormatException ex) {
            LOG.warning(String.format(
                    "Invalid RingBufferSize {}, using default size {}.",
                    userPreferredRBSize,
                    RINGBUFFER_DEFAULT_SIZE));
        }
        return Util.ceilingNextPowerOfTwo(ringBufferSize);
    }

    private static class Event {

        private String value;

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "Event: " + value;
        }
    }

    private class Handler implements EventHandler<Event> {

        private final ByteBuffer sendBuffer = ByteBuffer.allocate(PACKET_SIZE_BYTES);

        @Override
        public void onEvent(Event event, long sequence, boolean batchEnd) throws Exception {
            String message = event.value;
            byte[] data = message.getBytes();
            if(sendBuffer.remaining() < (data.length + 1)) {
                flush();
            }
            if(sendBuffer.position() > 0) {
                sendBuffer.put( (byte) '\n');
            }
            sendBuffer.put(
                    data.length > sendBuffer.remaining() ? Arrays.copyOfRange(data, 0, sendBuffer.remaining()) : data);

            if(batchEnd || 0 == sendBuffer.remaining()) {
                flush();
            }
        }

        private void flush() throws IOException {
            int sizeOfBuffer = sendBuffer.position();
            sendBuffer.flip();
            int sentBytes = clientChannel.send(sendBuffer, address);
            sendBuffer.clear();

            if (sizeOfBuffer != sentBytes) {
                handler.handle(
                        new IOException(
                            String.format(
                                "Could not send entirely stat %s to host %s:%d. Only sent %d bytes out of %d bytes",
                                sendBuffer.toString(),
                                address.getHostName(),
                                address.getPort(),
                                sentBytes,
                                sizeOfBuffer)));
            }
        }
    }

    private static class DisruptorExceptionHandler implements ExceptionHandler {

        private final FatalExceptionHandler throwableHandler = new FatalExceptionHandler();
        private final StatsDClientErrorHandler exceptionHandler;

        public DisruptorExceptionHandler(StatsDClientErrorHandler handler) {
           this.exceptionHandler = handler;
        }

        @Override
        public void handleEventException(Throwable ex, long sequence, Object event) {
            if(ex instanceof Exception) {
                exceptionHandler.handle((Exception) ex);
            } else {
                throwableHandler.handleEventException(ex, sequence, event);
            }
        }

        @Override
        public void handleOnStartException(Throwable ex) {
            if(ex instanceof Exception) {
                exceptionHandler.handle((Exception) ex);
            } else {
                throwableHandler.handleOnStartException(ex);
            }
        }

        @Override
        public void handleOnShutdownException(Throwable ex) {
            if(ex instanceof Exception) {
                exceptionHandler.handle((Exception) ex);
            } else {
                throwableHandler.handleOnShutdownException(ex);
            }
        }
    }
}
