java-dogstatsd-client
==================

A statsd client library implemented in Java.  Allows for Java applications to easily communicate with statsd.

This version is forked from the upstream [java-statsd-client](https://github.com/youdevise/java-statsd-client) project, adding support for [DataDog](http://datadoghq.com/) extensions for use with [dogstatsd](http://docs.datadoghq.com/guides/dogstatsd/).

This version also adds support for empty or null prefixes, to allow a client to send arbitrary statistic names.

Downloads
---------
The client jar is distributed via maven central, and can be downloaded [here](http://search.maven.org/#search%7Cga%7C1%7Cg%3Acom.indeed%20a%3Ajava-dogstatsd-client).

```xml
<dependency>
    <groupId>com.indeed</groupId>
    <artifactId>java-dogstatsd-client</artifactId>
    <version>2.0.12</version>
</dependency>
```

Usage
-----
```java
import com.timgroup.statsd.StatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;

public class Foo {

  private static final StatsDClient statsd = new NonBlockingStatsDClient(
    "my.prefix",                          /* prefix to any stats; may be null or empty string */
    "statsd-host",                        /* common case: localhost */
    8125,                                 /* port */
    new String[] {"tag:value"}            /* DataDog extension: Constant tags, always applied */
  );

  public static final void main(String[] args) {
    statsd.incrementCounter("foo");
    statsd.recordGaugeValue("bar", 100);
    statsd.recordGaugeValue("baz", 0.01); /* DataDog extension: support for floating-point gauges */
    statsd.recordHistogram("qux", 15)     /* DataDog extension: histograms */
    statsd.recordHistogram("qux", 15.5)   /* ...also floating-point */

    /* expects times in milliseconds
     */
    statsd.recordExecutionTime("bag", 25, "cluster:foo"); /* DataDog extension: cluster tag */
  }
}
```
