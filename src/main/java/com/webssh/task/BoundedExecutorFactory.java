package com.webssh.task;

import com.webssh.config.ResourceGovernanceProperties;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一构建有界线程池。
 */
public final class BoundedExecutorFactory {

    private BoundedExecutorFactory() {
    }

    /**
     * 创建带命名线程的有界线程池。
     *
     * @param threadNamePrefix 线程名前缀
     * @param config           线程池配置
     * @return 已启用核心线程超时的线程池
     */
    public static ThreadPoolExecutor newExecutor(String threadNamePrefix,
            ResourceGovernanceProperties.ExecutorPool config) {
        ResourceGovernanceProperties.ExecutorPool safeConfig = config == null
                ? new ResourceGovernanceProperties.ExecutorPool(1, 1, 0)
                : config;
        int coreSize = Math.max(1, safeConfig.getCoreSize());
        int maxSize = Math.max(coreSize, safeConfig.getMaxSize());
        int queueCapacity = Math.max(0, safeConfig.getQueueCapacity());
        BlockingQueue<Runnable> queue = queueCapacity == 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(queueCapacity);
        AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L,
                TimeUnit.SECONDS,
                queue,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName(threadNamePrefix + threadCounter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
