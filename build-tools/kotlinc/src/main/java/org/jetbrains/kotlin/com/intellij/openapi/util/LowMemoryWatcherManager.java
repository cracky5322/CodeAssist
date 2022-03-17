package org.jetbrains.kotlin.com.intellij.openapi.util;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.concurrency.SequentialTaskExecutor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

public final class LowMemoryWatcherManager implements Disposable {

    private static Logger getLogger() {
        return Logger.getInstance(LowMemoryWatcherManager.class);
    }

    private static final long MEM_THRESHOLD = 5 /*MB*/ * 1024 * 1024;
    private final ExecutorService myExecutorService;

    private Future<?> mySubmitted; // guarded by myJanitor
    private final Future<?> myMemoryPoolMXBeansFuture;
    private final Consumer<Boolean> myJanitor = new Consumer<Boolean>() {
        @Override
        public void accept(Boolean afterGc) {
            // Clearing `mySubmitted` before all listeners are called, to avoid data races when a listener is added in the middle of execution
            // and is lost. This may, however, cause listeners to execute more than once (potentially even in parallel).
            synchronized (myJanitor) {
                mySubmitted = null;
            }
            LowMemoryWatcher.onLowMemorySignalReceived(afterGc);
        }
    };

    public LowMemoryWatcherManager(ExecutorService backendExecutorService) {
        // whether LowMemoryWatcher runnables should be executed on the same thread that the low memory events come
        myExecutorService = Boolean.getBoolean("low.memory.watcher.sync") ?
                ConcurrencyUtil.newSameThreadExecutorService() :
                SequentialTaskExecutor
                        .createSequentialApplicationPoolExecutor("LowMemoryWatcherManager", backendExecutorService);

        myMemoryPoolMXBeansFuture = initializeMXBeanListenersLater(backendExecutorService);
    }

    private Future<?> initializeMXBeanListenersLater(ExecutorService backendExecutorService) {
        // do it in the other thread to get it out of the way during startup
        return backendExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
                        if (bean.getType() == MemoryType.HEAP &&
                            bean.isCollectionUsageThresholdSupported() &&
                            bean.isUsageThresholdSupported()) {
                            long max = bean.getUsage().getMax();
                            long threshold = Math.min((long) (max * LowMemoryWatcherManager
                                                              .getOccupiedMemoryThreshold()),
                                                      max - MEM_THRESHOLD);
                            if (threshold > 0) {
                                bean.setUsageThreshold(threshold);
                                bean.setCollectionUsageThreshold(threshold);
                            }
                        }
                    }
                    ((NotificationEmitter) ManagementFactory.getMemoryMXBean())
                            .addNotificationListener(myLowMemoryListener, null, null);
                } catch (Throwable e) {
                    // should not happen normally
                    getLogger().info("Errors initializing LowMemoryWatcher: ", e);
                }
            }

            @Override
            public String toString() {
                return "initializeMXBeanListeners runnable";
            }
        });
    }

    private final NotificationListener myLowMemoryListener = new NotificationListener() {
        @Override
        public void handleNotification(Notification notification, Object __) {
            if (LowMemoryWatcher.notificationsSuppressed()) return;
            boolean memoryThreshold = MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED.equals(notification.getType());
            boolean memoryCollectionThreshold = MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType());

            if (memoryThreshold || memoryCollectionThreshold) {
                synchronized (myJanitor) {
                    if (mySubmitted == null) {
                        mySubmitted = myExecutorService.submit(() -> myJanitor.accept(memoryCollectionThreshold));
                        // maybe it's executed too fast or even synchronously
                        if (mySubmitted.isDone()) {
                            mySubmitted = null;
                        }
                    }
                }
            }
        }
    };

    private static float getOccupiedMemoryThreshold() {
        return SystemProperties.getFloatProperty("low.memory.watcher.notification.threshold", 0.95f);
    }

    @Override
    public void dispose() {
        try {
            myMemoryPoolMXBeansFuture.get();
            ((NotificationEmitter)ManagementFactory.getMemoryMXBean()).removeNotificationListener(myLowMemoryListener);
        }
        catch (Exception e) {
            getLogger().error(e);
        }
        synchronized (myJanitor) {
            if (mySubmitted != null) {
                mySubmitted.cancel(false);
                mySubmitted = null;
            }
        }

        LowMemoryWatcher.stopAll();
    }

    public void waitForInitComplete(int timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        myMemoryPoolMXBeansFuture.get(timeout, unit);
    }
}