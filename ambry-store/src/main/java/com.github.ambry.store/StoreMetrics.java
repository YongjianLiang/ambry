package com.github.ambry.store;

import com.github.ambry.metrics.Counter;
import com.github.ambry.metrics.MetricsHelper;
import com.github.ambry.metrics.ReadableMetricsRegistry;

/**
 * The metrics for the store.
 */
public class StoreMetrics extends MetricsHelper {

  public final Counter writes;
  public final Counter reads;
  public final Counter deletes;
  public final Counter overflowWriteError;

  public StoreMetrics(String storeName, ReadableMetricsRegistry registry) {
    super(registry);
    writes = newCounter(storeName + "-Writes");
    reads = newCounter(storeName + "-Reads");
    deletes = newCounter(storeName + "-Deletes");
    overflowWriteError = newCounter(storeName + "-OverflowWriteError");
  }
}