/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.main;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelEvent.ExchangeCompletedEvent;
import org.apache.camel.spi.CamelEvent.ExchangeCreatedEvent;
import org.apache.camel.spi.CamelEvent.ExchangeFailedEvent;
import org.apache.camel.spi.CamelEvent.RouteReloadedEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.apache.camel.spi.EventNotifier} to trigger (shutdown of the Main JVM, or stopping all routes) when
 * maximum number of messages has been processed.
 */
public class MainDurationEventNotifier extends EventNotifierSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MainLifecycleStrategy.class);
    private final CamelContext camelContext;
    private final int maxMessages;
    private final long maxIdleSeconds;
    private final MainShutdownStrategy shutdownStrategy;
    private final boolean stopCamelContext;
    private final boolean restartDuration;
    private final String action;
    private final AtomicInteger doneMessages;

    private volatile StopWatch watch;
    private volatile ScheduledExecutorService idleExecutorService;

    public MainDurationEventNotifier(CamelContext camelContext, int maxMessages, long maxIdleSeconds,
                                     MainShutdownStrategy shutdownStrategy, boolean stopCamelContext,
                                     boolean restartDuration, String action) {
        this.camelContext = camelContext;
        this.maxMessages = maxMessages;
        this.maxIdleSeconds = maxIdleSeconds;
        this.shutdownStrategy = shutdownStrategy;
        this.stopCamelContext = stopCamelContext;
        this.restartDuration = restartDuration;
        this.action = action.toLowerCase();
        this.doneMessages = new AtomicInteger();

        if (maxMessages == 0 && maxIdleSeconds == 0) {
            // we do not need exchange events
            setIgnoreExchangeEvents(true);
        }
    }

    @Override
    public void notify(CamelEvent event) throws Exception {
        try {
            doNotify(event);
        } catch (Exception e) {
            LOG.warn("Error during processing CamelEvent: " + event + ". This exception is ignored.", e);
        }
    }

    protected void doNotify(CamelEvent event) throws Exception {
        // ignore any event that is received if shutdown is in process
        if (!shutdownStrategy.isRunAllowed()) {
            return;
        }

        boolean begin = event instanceof ExchangeCreatedEvent;
        boolean complete = event instanceof ExchangeCompletedEvent || event instanceof ExchangeFailedEvent;
        boolean reloaded = event instanceof RouteReloadedEvent;

        if (reloaded) {
            if (restartDuration) {
                LOG.debug("Routes reloaded. Resetting maxMessages/maxIdleSeconds/maxSeconds");
                shutdownStrategy.restartAwait();
                doneMessages.set(0);
                if (watch != null) {
                    watch.restart();
                }
            }
            return;
        }

        if (maxMessages > 0 && complete) {
            boolean result = doneMessages.incrementAndGet() >= maxMessages;
            LOG.trace("Duration max messages check {} >= {} -> {}", doneMessages.get(), maxMessages, result);

            if (result && shutdownStrategy.isRunAllowed()) {
                if ("shutdown".equalsIgnoreCase(action)) {
                    LOG.info("Duration max messages triggering shutdown of the JVM");
                    // use thread to shut down Camel as otherwise we would block current thread
                    camelContext.getExecutorServiceManager().newThread("CamelMainShutdownCamelContext", this::shutdownTask)
                            .start();
                } else if ("stop".equalsIgnoreCase(action)) {
                    LOG.info("Duration max messages triggering stopping all routes");
                    // use thread to stop routes as otherwise we would block current thread
                    camelContext.getExecutorServiceManager().newThread("CamelMainShutdownCamelContext", this::stopTask).start();
                }
            }
        }

        // idle reacts on both incoming and complete messages
        if (maxIdleSeconds > 0 && (begin || complete)) {
            if (watch != null) {
                LOG.trace("Message activity so restarting stop watch");
                watch.restart();
            }
        }
    }

    @Override
    public boolean isEnabled(CamelEvent event) {
        return event instanceof ExchangeCreatedEvent || event instanceof ExchangeCompletedEvent
                || event instanceof ExchangeFailedEvent || event instanceof RouteReloadedEvent;
    }

    @Override
    public String toString() {
        return "MainDurationEventNotifier[" + maxMessages + " max messages]";
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();

        if (!action.equals("shutdown") && !action.equals("stop")) {
            throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    @Override
    protected void doStart() throws Exception {
        if (maxIdleSeconds > 0) {
            // we only start watch when Camel is started
            camelContext.addStartupListener((context, alreadyStarted) -> watch = new StopWatch());

            // okay we need to trigger on idle after X period, and therefore we need a background task that checks this
            idleExecutorService = Executors.newSingleThreadScheduledExecutor();
            idleExecutorService.scheduleAtFixedRate(this::idleTask, 1, 1, TimeUnit.SECONDS);
        }
    }

    private void stopTask() {
        // don't run the task if shutdown is in process
        if (!shutdownStrategy.isRunAllowed()) {
            return;
        }

        try {
            camelContext.getRouteController().stopAllRoutes();
        } catch (Exception e) {
            LOG.warn("Error during stopping all routes. This exception is ignored.", e);
        }
    }

    private void shutdownTask() {
        // don't run the task if shutdown is in process
        if (!shutdownStrategy.isRunAllowed()) {
            return;
        }

        // shutdown idle checker if in use as we are stopping
        if (idleExecutorService != null) {
            idleExecutorService.shutdownNow();
        }

        try {
            // shutting down CamelContext
            if (stopCamelContext) {
                camelContext.stop();
            }
        } catch (Exception e) {
            LOG.warn("Error during stopping CamelContext. This exception is ignored.", e);
        } finally {
            // trigger stopping the Main
            shutdownStrategy.shutdown();
        }
    }

    private void idleTask() {
        // don't run the task if shutdown is in process
        if (!shutdownStrategy.isRunAllowed()) {
            return;
        }

        if (watch == null) {
            // camel has not been started yet
            return;
        }

        // any inflight messages currently
        int inflight = camelContext.getInflightRepository().size();
        if (inflight > 0) {
            LOG.trace("Duration max idle check is skipped due {} inflight messages", inflight);
            return;
        }

        long seconds = watch.taken() / 1000;
        boolean result = seconds >= maxIdleSeconds;
        LOG.trace("Duration max idle check {} >= {} -> {}", seconds, maxIdleSeconds, result);

        if (result && shutdownStrategy.isRunAllowed()) {
            if ("shutdown".equals(action)) {
                LOG.info("Duration max idle triggering shutdown of the JVM");
                // use thread to stop Camel as otherwise we would block current thread
                camelContext.getExecutorServiceManager().newThread("CamelMainShutdownCamelContext", this::shutdownTask).start();
            } else if ("stop".equals(action)) {
                LOG.info("Duration max idle triggering stopping all routes");
                // use thread to stop Camel as otherwise we would block current thread
                camelContext.getExecutorServiceManager().newThread("CamelMainShutdownCamelContext", this::stopTask).start();
            }
        }
    }
}
