package io.cmdb.collector.normalization.application;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Striped locks keyed by ordering_key: events of the same resource are never processed
 * concurrently, while different resources still run in parallel. (Async listeners run in parallel
 * by default; together with the upsert condition "only a newer observation overwrites", an old
 * event can never overwrite newer state.)
 */
final class KeyedLocks {

  private final ReentrantLock[] locks;

  KeyedLocks(int stripes) {
    locks = new ReentrantLock[stripes];
    for (int i = 0; i < stripes; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  void run(String key, Runnable action) {
    ReentrantLock lock = locks[Math.floorMod(key == null ? 0 : key.hashCode(), locks.length)];
    lock.lock();
    try {
      action.run();
    } finally {
      lock.unlock();
    }
  }
}
