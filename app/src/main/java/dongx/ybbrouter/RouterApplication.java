// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RouterApplication extends Application {
    Repository repository;
    final ExecutorService network = Executors.newFixedThreadPool(4);
    final ExecutorService catalogs = Executors.newSingleThreadExecutor();
    final AtomicBoolean commandBusy = new AtomicBoolean();
    volatile String workingIp = "";
    volatile boolean workingStart;
    volatile String pendingIp = "";
    volatile boolean pendingStart;
    volatile long pendingUntil;
    @Override public void onCreate() { super.onCreate(); repository = new Repository(this); }
}
