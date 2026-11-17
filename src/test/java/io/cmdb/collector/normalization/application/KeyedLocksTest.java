package io.cmdb.collector.normalization.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeyedLocksTest {

  @Test
  void actionsWithTheSameKeyNeverRunConcurrently() throws Exception {
    var locks = new KeyedLocks(8);
    var inside = new AtomicInteger();
    var maxInside = new AtomicInteger();
    var done = new CountDownLatch(4);

    for (int i = 0; i < 4; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                locks.run(
                    "same",
                    () -> {
                      maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                      try {
                        Thread.sleep(20);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      inside.decrementAndGet();
                    });
                done.countDown();
              });
    }

    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(maxInside.get()).isEqualTo(1);
  }

  @Test
  void aNullKeyIsAllowed() {
    var ran = new AtomicInteger();
    new KeyedLocks(4).run(null, ran::incrementAndGet);
    assertThat(ran.get()).isEqualTo(1);
  }
}
