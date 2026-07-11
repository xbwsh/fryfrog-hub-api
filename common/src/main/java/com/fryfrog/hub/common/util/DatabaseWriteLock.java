package com.fryfrog.hub.common.util;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局数据库写锁，防止 SQLite 并发写入导致 SQLITE_BUSY。
 * 所有需要写入数据库的操作应通过 runInWriteLock 执行。
 */
public final class DatabaseWriteLock {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private DatabaseWriteLock() {}

    /** 获取写锁（可中断） */
    public static void lock() {
        try {
            LOCK.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring database write lock", e);
        }
    }

    /** 释放写锁 */
    public static void unlock() {
        LOCK.unlock();
    }

    /** 在写锁保护下执行任务 */
    public static void runInWriteLock(Runnable task) {
        lock();
        try {
            task.run();
        } finally {
            unlock();
        }
    }
}
