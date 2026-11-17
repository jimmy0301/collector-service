package io.cmdb.collector.syncrun.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionResultTest {

  @Test
  void startsEmptyAndSuccessfulSoNothingToDoIsNotAFailure() {
    assertThat(CollectionResult.none())
        .isEqualTo(new CollectionResult(SyncStatus.SUCCESS, 0, List.of()));
  }

  @Test
  void countsEveryRun() {
    var result = CollectionResult.none().with(SyncStatus.SUCCESS).with(SyncStatus.SUCCESS);

    assertThat(result.runs()).isEqualTo(2);
    assertThat(result.status()).isEqualTo(SyncStatus.SUCCESS);
  }

  @Test
  void keepsTheWorstStatusWhateverTheOrder() {
    assertThat(CollectionResult.none().with(SyncStatus.PARTIAL).with(SyncStatus.SUCCESS).status())
        .isEqualTo(SyncStatus.PARTIAL);
    assertThat(CollectionResult.none().with(SyncStatus.FAILED).with(SyncStatus.PARTIAL).status())
        .isEqualTo(SyncStatus.FAILED);
    assertThat(CollectionResult.none().with(SyncStatus.SUCCESS).with(SyncStatus.FAILED).status())
        .isEqualTo(SyncStatus.FAILED);
  }

  @Test
  void keepsWhyEachNonSuccessfulRunFailedAndIgnoresSuccessfulOnes() {
    var result =
        CollectionResult.none()
            .with(new SyncResult("ARGO:A", SyncStatus.SUCCESS, null))
            .with(new SyncResult("ARGO:B", SyncStatus.PARTIAL, "tree x: 502"))
            .with(new SyncResult("ARGO:C", SyncStatus.FAILED, null));

    assertThat(result.runs()).isEqualTo(3);
    assertThat(result.status()).isEqualTo(SyncStatus.FAILED);
    assertThat(result.errors()).containsExactly("ARGO:B: tree x: 502", "ARGO:C: FAILED");
  }

  @Test
  void boundsHowManyAndHowLongTheErrorsAre() {
    var result = CollectionResult.none();
    for (int i = 0; i < 30; i++) {
      result = result.with(new SyncResult("P" + i, SyncStatus.FAILED, "x".repeat(2000)));
    }

    assertThat(result.runs()).isEqualTo(30);
    assertThat(result.errors()).hasSize(20);
    assertThat(result.errors()).allSatisfy(e -> assertThat(e.length()).isLessThanOrEqualTo(500));
  }
}
