/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.repositories.blobstore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RateLimiter;
import org.apache.lucene.util.BytesRef;
import org.opensearch.ExceptionsHelper;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionRunnable;
import org.opensearch.action.StepListener;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.RepositoryCleanupInProgress;
import org.opensearch.cluster.SnapshotDeletionsInProgress;
import org.opensearch.cluster.SnapshotsInProgress;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.metadata.RepositoriesMetadata;
import org.opensearch.cluster.metadata.RepositoryMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.service.ClusterManagerTaskThrottler;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.Numbers;
import org.opensearch.common.SetOnce;
import org.opensearch.common.UUIDs;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobMetadata;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.BlobStore;
import org.opensearch.common.blobstore.DeleteResult;
import org.opensearch.common.blobstore.fs.FsBlobContainer;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.common.compress.Compressor;
import org.opensearch.common.compress.CompressorFactory;
import org.opensearch.common.compress.CompressorType;
import org.opensearch.core.common.compress.NotXContentException;
import org.opensearch.common.io.Streams;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.common.lucene.store.InputStreamIndexInput;
import org.opensearch.common.metrics.CounterMetric;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.lease.Releasable;
import org.opensearch.core.util.BytesRefUtils;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.snapshots.IndexShardRestoreFailedException;
import org.opensearch.core.index.snapshots.IndexShardSnapshotFailedException;
import org.opensearch.index.snapshots.IndexShardSnapshotStatus;
import org.opensearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot;
import org.opensearch.index.snapshots.blobstore.RemoteStoreShardShallowCopySnapshot;
import org.opensearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshots;
import org.opensearch.index.snapshots.blobstore.IndexShardSnapshot;
import org.opensearch.index.snapshots.blobstore.RateLimitingInputStream;
import org.opensearch.index.snapshots.blobstore.SlicedInputStream;
import org.opensearch.index.snapshots.blobstore.SnapshotFiles;
import org.opensearch.index.store.RemoteSegmentStoreDirectoryFactory;
import org.opensearch.index.store.Store;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.index.store.lockmanager.FileLockInfo;
import org.opensearch.index.store.lockmanager.RemoteStoreLockManager;
import org.opensearch.index.store.lockmanager.RemoteStoreLockManagerFactory;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.indices.recovery.RecoveryState;
import org.opensearch.repositories.IndexId;
import org.opensearch.repositories.IndexMetaDataGenerations;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.RepositoryCleanupResult;
import org.opensearch.repositories.RepositoryData;
import org.opensearch.repositories.RepositoryException;
import org.opensearch.repositories.RepositoryOperation;
import org.opensearch.repositories.RepositoryShardId;
import org.opensearch.repositories.RepositoryStats;
import org.opensearch.repositories.RepositoryVerificationException;
import org.opensearch.repositories.ShardGenerations;
import org.opensearch.snapshots.AbortedSnapshotException;
import org.opensearch.snapshots.SnapshotException;
import org.opensearch.snapshots.SnapshotId;
import org.opensearch.snapshots.SnapshotInfo;
import org.opensearch.snapshots.SnapshotMissingException;
import org.opensearch.snapshots.SnapshotsService;
import org.opensearch.threadpool.ThreadPool;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.opensearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot.FileInfo.canonicalName;

/**
 * BlobStore - based implementation of Snapshot Repository
 * <p>
 * This repository works with any {@link BlobStore} implementation. The blobStore could be (and preferred) lazy initialized in
 * {@link #createBlobStore()}.
 * </p>
 * For in depth documentation on how exactly implementations of this class interact with the snapshot functionality please refer to the
 * documentation of the package {@link org.opensearch.repositories.blobstore}.
 *
 * @opensearch.internal
 */
public abstract class BlobStoreRepository extends AbstractLifecycleComponent implements Repository {
    private static final Logger logger = LogManager.getLogger(BlobStoreRepository.class);

    protected volatile RepositoryMetadata metadata;

    protected final ThreadPool threadPool;

    public static final String SNAPSHOT_PREFIX = "snap-";

    public static final String SHALLOW_SNAPSHOT_PREFIX = "shallow-snap-";

    public static final String INDEX_FILE_PREFIX = "index-";

    public static final String INDEX_LATEST_BLOB = "index.latest";

    private static final String TESTS_FILE = "tests-";

    public static final String METADATA_PREFIX = "meta-";

    public static final String METADATA_NAME_FORMAT = METADATA_PREFIX + "%s.dat";

    public static final String SNAPSHOT_NAME_FORMAT = SNAPSHOT_PREFIX + "%s.dat";

    public static final String SHALLOW_SNAPSHOT_NAME_FORMAT = SHALLOW_SNAPSHOT_PREFIX + "%s.dat";

    private static final String SNAPSHOT_INDEX_PREFIX = "index-";

    private static final String SNAPSHOT_INDEX_NAME_FORMAT = SNAPSHOT_INDEX_PREFIX + "%s";

    private static final String UPLOADED_DATA_BLOB_PREFIX = "__";

    /**
     * Prefix used for the identifiers of data blobs that were not actually written to the repository physically because their contents are
     * already stored in the metadata referencing them, i.e. in {@link BlobStoreIndexShardSnapshot} and
     * {@link BlobStoreIndexShardSnapshots}. This is the case for files for which {@link StoreFileMetadata#hashEqualsContents()} is
     * {@code true}.
     */
    public static final String VIRTUAL_DATA_BLOB_PREFIX = "v__";

    /**
     * When set to {@code true}, {@link #bestEffortConsistency} will be set to {@code true} and concurrent modifications of the repository
     * contents will not result in the repository being marked as corrupted.
     * Note: This setting is intended as a backwards compatibility solution for 7.x and will go away in 8.
     */
    public static final Setting<Boolean> ALLOW_CONCURRENT_MODIFICATION = Setting.boolSetting(
        "allow_concurrent_modifications",
        false,
        Setting.Property.Deprecated
    );

    /**
     * Setting to disable caching of the latest repository data.
     */
    public static final Setting<Boolean> CACHE_REPOSITORY_DATA = Setting.boolSetting(
        "cache_repository_data",
        true,
        Setting.Property.Deprecated
    );

    /**
     * Size hint for the IO buffer size to use when reading from and writing to the repository.
     */
    public static final Setting<ByteSizeValue> BUFFER_SIZE_SETTING = Setting.byteSizeSetting(
        "io_buffer_size",
        ByteSizeValue.parseBytesSizeValue("128kb", "io_buffer_size"),
        ByteSizeValue.parseBytesSizeValue("8kb", "buffer_size"),
        ByteSizeValue.parseBytesSizeValue("16mb", "io_buffer_size"),
        Setting.Property.NodeScope
    );

    public static final Setting<Boolean> REMOTE_STORE_INDEX_SHALLOW_COPY = Setting.boolSetting("remote_store_index_shallow_copy", false);

    /**
     * Setting to set batch size of stale snapshot shard blobs that will be deleted by snapshot workers as part of snapshot deletion.
     * For optimal performance the value of the setting should be equal to or close to repository's max # of keys that can be deleted in single operation
     * Most cloud storage support upto 1000 key(s) deletion in single operation, thus keeping default value to be 1000.
     */
    public static final Setting<Integer> MAX_SNAPSHOT_SHARD_BLOB_DELETE_BATCH_SIZE = Setting.intSetting(
        "max_snapshot_shard_blob_delete_batch_size",
        1000, // the default maximum batch size of stale snapshot shard blobs deletion
        Setting.Property.NodeScope
    );

    public static final Setting<Boolean> COMPRESS_SETTING = Setting.boolSetting("compress", false, Setting.Property.NodeScope);

    public static final Setting<CompressorType> COMPRESSION_TYPE_SETTING = new Setting<>(
        "compression_type",
        CompressorType.DEFLATE.name().toLowerCase(Locale.ROOT),
        s -> CompressorType.valueOf(s.toUpperCase(Locale.ROOT)),
        Setting.Property.NodeScope
    );

    /**
     * Setting to disable writing the {@code index.latest} blob which enables the contents of this repository to be used with a
     * url-repository.
     */
    public static final Setting<Boolean> SUPPORT_URL_REPO = Setting.boolSetting("support_url_repo", true, Setting.Property.NodeScope);

    /***
     * Setting to set repository as readonly
     */
    public static final Setting<Boolean> READONLY_SETTING = Setting.boolSetting("readonly", false, Setting.Property.NodeScope);

    protected final boolean supportURLRepo;

    private final int maxShardBlobDeleteBatch;

    private final Compressor compressor;

    private final boolean cacheRepositoryData;

    private final RateLimiter snapshotRateLimiter;

    private final RateLimiter restoreRateLimiter;

    private final CounterMetric snapshotRateLimitingTimeInNanos = new CounterMetric();

    private final CounterMetric restoreRateLimitingTimeInNanos = new CounterMetric();

    public static final ChecksumBlobStoreFormat<Metadata> GLOBAL_METADATA_FORMAT = new ChecksumBlobStoreFormat<>(
        "metadata",
        METADATA_NAME_FORMAT,
        Metadata::fromXContent
    );

    public static final ChecksumBlobStoreFormat<IndexMetadata> INDEX_METADATA_FORMAT = new ChecksumBlobStoreFormat<>(
        "index-metadata",
        METADATA_NAME_FORMAT,
        IndexMetadata::fromXContent
    );

    private static final String SNAPSHOT_CODEC = "snapshot";

    public static final ChecksumBlobStoreFormat<SnapshotInfo> SNAPSHOT_FORMAT = new ChecksumBlobStoreFormat<>(
        SNAPSHOT_CODEC,
        SNAPSHOT_NAME_FORMAT,
        SnapshotInfo::fromXContentInternal
    );

    public static final ChecksumBlobStoreFormat<BlobStoreIndexShardSnapshot> INDEX_SHARD_SNAPSHOT_FORMAT = new ChecksumBlobStoreFormat<>(
        SNAPSHOT_CODEC,
        SNAPSHOT_NAME_FORMAT,
        BlobStoreIndexShardSnapshot::fromXContent
    );

    public static final ChecksumBlobStoreFormat<RemoteStoreShardShallowCopySnapshot> REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT =
        new ChecksumBlobStoreFormat<>(SNAPSHOT_CODEC, SHALLOW_SNAPSHOT_NAME_FORMAT, RemoteStoreShardShallowCopySnapshot::fromXContent);

    public static final ChecksumBlobStoreFormat<BlobStoreIndexShardSnapshots> INDEX_SHARD_SNAPSHOTS_FORMAT = new ChecksumBlobStoreFormat<>(
        "snapshots",
        SNAPSHOT_INDEX_NAME_FORMAT,
        BlobStoreIndexShardSnapshots::fromXContent
    );

    private final boolean readOnly;

    private final Object lock = new Object();

    private final SetOnce<BlobContainer> blobContainer = new SetOnce<>();

    private final SetOnce<BlobStore> blobStore = new SetOnce<>();

    private final ClusterService clusterService;

    private final RecoverySettings recoverySettings;

    private final NamedXContentRegistry namedXContentRegistry;

    /**
     * Flag that is set to {@code true} if this instance is started with {@link #metadata} that has a higher value for
     * {@link RepositoryMetadata#pendingGeneration()} than for {@link RepositoryMetadata#generation()} indicating a full cluster restart
     * potentially accounting for the the last {@code index-N} write in the cluster state.
     * Note: While it is true that this value could also be set to {@code true} for an instance on a node that is just joining the cluster
     * during a new {@code index-N} write, this does not present a problem. The node will still load the correct {@link RepositoryData} in
     * all cases and simply do a redundant listing of the repository contents if it tries to load {@link RepositoryData} and falls back
     * to {@link #latestIndexBlobId()} to validate the value of {@link RepositoryMetadata#generation()}.
     */
    private boolean uncleanStart;

    /**
     * This flag indicates that the repository can not exclusively rely on the value stored in {@link #latestKnownRepoGen} to determine the
     * latest repository generation but must inspect its physical contents as well via {@link #latestIndexBlobId()}.
     * This flag is set in the following situations:
     * <ul>
     *     <li>All repositories that are read-only, i.e. for which {@link #isReadOnly()} returns {@code true} because there are no
     *     guarantees that another cluster is not writing to the repository at the same time</li>
     *     <li>The value of {@link RepositoryMetadata#generation()} for this repository is {@link RepositoryData#UNKNOWN_REPO_GEN}
     *     indicating that no consistent repository generation is tracked in the cluster state yet.</li>
     *     <li>The {@link #uncleanStart} flag is set to {@code true}</li>
     * </ul>
     */
    private volatile boolean bestEffortConsistency;

    /**
     * IO buffer size hint for reading and writing to the underlying blob store.
     */
    protected final int bufferSize;

    /**
     * Constructs new BlobStoreRepository
     * @param metadata   The metadata for this repository including name and settings
     * @param clusterService ClusterService
     */
    protected BlobStoreRepository(
        final RepositoryMetadata metadata,
        final boolean compress,
        final NamedXContentRegistry namedXContentRegistry,
        final ClusterService clusterService,
        final RecoverySettings recoverySettings
    ) {
        this.metadata = metadata;
        this.namedXContentRegistry = namedXContentRegistry;
        this.threadPool = clusterService.getClusterApplierService().threadPool();
        this.clusterService = clusterService;
        this.recoverySettings = recoverySettings;
        this.supportURLRepo = SUPPORT_URL_REPO.get(metadata.settings());
        snapshotRateLimiter = getRateLimiter(metadata.settings(), "max_snapshot_bytes_per_sec", new ByteSizeValue(40, ByteSizeUnit.MB));
        restoreRateLimiter = getRateLimiter(metadata.settings(), "max_restore_bytes_per_sec", ByteSizeValue.ZERO);
        readOnly = READONLY_SETTING.get(metadata.settings());
        cacheRepositoryData = CACHE_REPOSITORY_DATA.get(metadata.settings());
        bufferSize = Math.toIntExact(BUFFER_SIZE_SETTING.get(metadata.settings()).getBytes());
        maxShardBlobDeleteBatch = MAX_SNAPSHOT_SHARD_BLOB_DELETE_BATCH_SIZE.get(metadata.settings());
        this.compressor = compress ? COMPRESSION_TYPE_SETTING.get(metadata.settings()).compressor() : CompressorFactory.NONE_COMPRESSOR;
    }

    @Override
    protected void doStart() {
        uncleanStart = metadata.pendingGeneration() > RepositoryData.EMPTY_REPO_GEN
            && metadata.generation() != metadata.pendingGeneration();
        ByteSizeValue chunkSize = chunkSize();
        if (chunkSize != null && chunkSize.getBytes() <= 0) {
            throw new IllegalArgumentException("the chunk size cannot be negative: [" + chunkSize + "]");
        }
    }

    @Override
    protected void doStop() {}

    @Override
    protected void doClose() {
        BlobStore store;
        // to close blobStore if blobStore initialization is started during close
        synchronized (lock) {
            store = blobStore.get();
        }
        if (store != null) {
            try {
                store.close();
            } catch (Exception t) {
                logger.warn("cannot close blob store", t);
            }
        }
    }

    @Override
    public void executeConsistentStateUpdate(
        Function<RepositoryData, ClusterStateUpdateTask> createUpdateTask,
        String source,
        Consumer<Exception> onFailure
    ) {
        final RepositoryMetadata repositoryMetadataStart = metadata;
        getRepositoryData(ActionListener.wrap(repositoryData -> {
            final ClusterStateUpdateTask updateTask = createUpdateTask.apply(repositoryData);
            clusterService.submitStateUpdateTask(source, new ClusterStateUpdateTask(updateTask.priority()) {

                private boolean executedTask = false;

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    // Comparing the full metadata here on purpose instead of simply comparing the safe generation.
                    // If the safe generation has changed, then we have to reload repository data and start over.
                    // If the pending generation has changed we are in the midst of a write operation and might pick up the
                    // updated repository data and state on the retry. We don't want to wait for the write to finish though
                    // because it could fail for any number of reasons so we just retry instead of waiting on the cluster state
                    // to change in any form.
                    if (repositoryMetadataStart.equals(getRepoMetadata(currentState))) {
                        executedTask = true;
                        return updateTask.execute(currentState);
                    }
                    return currentState;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    if (executedTask) {
                        updateTask.onFailure(source, e);
                    } else {
                        onFailure.accept(e);
                    }
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    if (executedTask) {
                        updateTask.clusterStateProcessed(source, oldState, newState);
                    } else {
                        executeConsistentStateUpdate(createUpdateTask, source, onFailure);
                    }
                }

                @Override
                public TimeValue timeout() {
                    return updateTask.timeout();
                }

                @Override
                public ClusterManagerTaskThrottler.ThrottlingKey getClusterManagerThrottlingKey() {
                    return updateTask.getClusterManagerThrottlingKey();
                }
            });
        }, onFailure));
    }

    @Override
    public void cloneShardSnapshot(
        SnapshotId source,
        SnapshotId target,
        RepositoryShardId shardId,
        @Nullable String shardGeneration,
        ActionListener<String> listener
    ) {
        if (isReadOnly()) {
            listener.onFailure(new RepositoryException(metadata.name(), "cannot clone shard snapshot on a readonly repository"));
            return;
        }
        final IndexId index = shardId.index();
        final int shardNum = shardId.shardId();
        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
        executor.execute(ActionRunnable.supply(listener, () -> {
            final long startTime = threadPool.absoluteTimeInMillis();
            final BlobContainer shardContainer = shardContainer(index, shardNum);
            final String newGen;
            final BlobStoreIndexShardSnapshots existingSnapshots;
            final String existingShardGen;
            if (shardGeneration == null) {
                Tuple<BlobStoreIndexShardSnapshots, Long> tuple = buildBlobStoreIndexShardSnapshots(
                    shardContainer.listBlobsByPrefix(INDEX_FILE_PREFIX).keySet(),
                    shardContainer
                );
                existingShardGen = String.valueOf(tuple.v2());
                newGen = String.valueOf(tuple.v2() + 1);
                existingSnapshots = tuple.v1();
            } else {
                newGen = UUIDs.randomBase64UUID();
                existingSnapshots = buildBlobStoreIndexShardSnapshots(Collections.emptySet(), shardContainer, shardGeneration).v1();
                existingShardGen = shardGeneration;
            }
            SnapshotFiles existingTargetFiles = null;
            SnapshotFiles sourceFiles = null;
            for (SnapshotFiles existingSnapshot : existingSnapshots) {
                final String snapshotName = existingSnapshot.snapshot();
                if (snapshotName.equals(target.getName())) {
                    existingTargetFiles = existingSnapshot;
                } else if (snapshotName.equals(source.getName())) {
                    sourceFiles = existingSnapshot;
                }
                if (sourceFiles != null && existingTargetFiles != null) {
                    break;
                }
            }
            if (sourceFiles == null) {
                throw new RepositoryException(
                    metadata.name(),
                    "Can't create clone of ["
                        + shardId
                        + "] for snapshot ["
                        + target
                        + "]. The source snapshot ["
                        + source
                        + "] was not found in the shard metadata."
                );
            }
            if (existingTargetFiles != null) {
                if (existingTargetFiles.isSame(sourceFiles)) {
                    return existingShardGen;
                }
                throw new RepositoryException(
                    metadata.name(),
                    "Can't create clone of ["
                        + shardId
                        + "] for snapshot ["
                        + target
                        + "]. A snapshot by that name already exists for this shard."
                );
            }
            // We don't need to check if there exists a shallow snapshot with the same name as we have the check before starting the clone
            // operation ensuring that the snapshot name is available by checking the repository data. Also, the new clone snapshot would
            // have a different UUID and hence a new unique snap-N file will be created.
            IndexShardSnapshot indexShardSnapshot = loadShardSnapshot(shardContainer, source);
            assert indexShardSnapshot instanceof BlobStoreIndexShardSnapshot
                : "indexShardSnapshot should be an instance of BlobStoreIndexShardSnapshot";
            final BlobStoreIndexShardSnapshot sourceMeta = (BlobStoreIndexShardSnapshot) indexShardSnapshot;
            logger.trace("[{}] [{}] writing shard snapshot file for clone", shardId, target);
            INDEX_SHARD_SNAPSHOT_FORMAT.write(
                sourceMeta.asClone(target.getName(), startTime, threadPool.absoluteTimeInMillis() - startTime),
                shardContainer,
                target.getUUID(),
                compressor
            );
            INDEX_SHARD_SNAPSHOTS_FORMAT.write(
                existingSnapshots.withClone(source.getName(), target.getName()),
                shardContainer,
                newGen,
                compressor
            );
            return newGen;
        }));
    }

    @Override
    public void cloneRemoteStoreIndexShardSnapshot(
        SnapshotId source,
        SnapshotId target,
        RepositoryShardId shardId,
        @Nullable String shardGeneration,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<String> listener
    ) {
        if (isReadOnly()) {
            listener.onFailure(new RepositoryException(metadata.name(), "cannot clone shard snapshot on a readonly repository"));
            return;
        }
        final IndexId index = shardId.index();
        final int shardNum = shardId.shardId();
        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
        executor.execute(ActionRunnable.supply(listener, () -> {
            final long startTime = threadPool.relativeTimeInMillis();
            final BlobContainer shardContainer = shardContainer(index, shardNum);
            // We don't need to check if there exists a shallow/full copy snapshot with the same name as we have the check before starting
            // the clone operation ensuring that the snapshot name is available by checking the repository data. Also, the new clone shallow
            // snapshot would have a different UUID and hence a new unique shallow-snap-N file will be created.
            IndexShardSnapshot indexShardSnapshot = loadShardSnapshot(shardContainer, source);
            assert indexShardSnapshot instanceof RemoteStoreShardShallowCopySnapshot
                : "indexShardSnapshot should be an instance of RemoteStoreShardShallowCopySnapshot";
            RemoteStoreShardShallowCopySnapshot remStoreBasedShardMetadata = (RemoteStoreShardShallowCopySnapshot) indexShardSnapshot;
            String indexUUID = remStoreBasedShardMetadata.getIndexUUID();
            String remoteStoreRepository = remStoreBasedShardMetadata.getRemoteStoreRepository();
            RemoteStoreLockManager remoteStoreMetadataLockManger = remoteStoreLockManagerFactory.newLockManager(
                remoteStoreRepository,
                indexUUID,
                String.valueOf(shardId.shardId())
            );
            remoteStoreMetadataLockManger.cloneLock(
                FileLockInfo.getLockInfoBuilder().withAcquirerId(source.getUUID()).build(),
                FileLockInfo.getLockInfoBuilder().withAcquirerId(target.getUUID()).build()
            );
            REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT.write(
                remStoreBasedShardMetadata.asClone(target.getName(), startTime, threadPool.absoluteTimeInMillis() - startTime),
                shardContainer,
                target.getUUID(),
                compressor
            );
            return shardGeneration;
        }));
    }

    // Inspects all cluster state elements that contain a hint about what the current repository generation is and updates
    // #latestKnownRepoGen if a newer than currently known generation is found
    @Override
    public void updateState(ClusterState state) {
        metadata = getRepoMetadata(state);
        uncleanStart = uncleanStart && metadata.generation() != metadata.pendingGeneration();
        final boolean wasBestEffortConsistency = bestEffortConsistency;
        bestEffortConsistency = uncleanStart
            || isReadOnly()
            || metadata.generation() == RepositoryData.UNKNOWN_REPO_GEN
            || ALLOW_CONCURRENT_MODIFICATION.get(metadata.settings());
        if (isReadOnly()) {
            // No need to waste cycles, no operations can run against a read-only repository
            return;
        }
        if (bestEffortConsistency) {
            final SnapshotsInProgress snapshotsInProgress = state.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
            long bestGenerationFromCS = bestGeneration(snapshotsInProgress.entries());
            // Don't use generation from the delete task if we already found a generation for an in progress snapshot.
            // In this case, the generation points at the generation the repo will be in after the snapshot finishes so it may not yet
            // exist
            if (bestGenerationFromCS == RepositoryData.EMPTY_REPO_GEN) {
                bestGenerationFromCS = bestGeneration(
                    state.custom(SnapshotDeletionsInProgress.TYPE, SnapshotDeletionsInProgress.EMPTY).getEntries()
                );
            }
            if (bestGenerationFromCS == RepositoryData.EMPTY_REPO_GEN) {
                bestGenerationFromCS = bestGeneration(
                    state.custom(RepositoryCleanupInProgress.TYPE, RepositoryCleanupInProgress.EMPTY).entries()
                );
            }
            final long finalBestGen = Math.max(bestGenerationFromCS, metadata.generation());
            latestKnownRepoGen.updateAndGet(known -> Math.max(known, finalBestGen));
        } else {
            final long previousBest = latestKnownRepoGen.getAndSet(metadata.generation());
            if (previousBest != metadata.generation()) {
                assert wasBestEffortConsistency
                    || metadata.generation() == RepositoryData.CORRUPTED_REPO_GEN
                    || previousBest < metadata.generation() : "Illegal move from repository generation ["
                        + previousBest
                        + "] to generation ["
                        + metadata.generation()
                        + "]";
                logger.debug("Updated repository generation from [{}] to [{}]", previousBest, metadata.generation());
            }
        }
    }

    private long bestGeneration(Collection<? extends RepositoryOperation> operations) {
        final String repoName = metadata.name();
        return operations.stream()
            .filter(e -> e.repository().equals(repoName))
            .mapToLong(RepositoryOperation::repositoryStateId)
            .max()
            .orElse(RepositoryData.EMPTY_REPO_GEN);
    }

    public ThreadPool threadPool() {
        return threadPool;
    }

    // package private, only use for testing
    BlobContainer getBlobContainer() {
        return blobContainer.get();
    }

    // for test purposes only
    protected BlobStore getBlobStore() {
        return blobStore.get();
    }

    /**
     * maintains single lazy instance of {@link BlobContainer}
     */
    protected BlobContainer blobContainer() {
        assertSnapshotOrGenericThread();

        BlobContainer blobContainer = this.blobContainer.get();
        if (blobContainer == null) {
            synchronized (lock) {
                blobContainer = this.blobContainer.get();
                if (blobContainer == null) {
                    blobContainer = blobStore().blobContainer(basePath());
                    this.blobContainer.set(blobContainer);
                }
            }
        }

        return blobContainer;
    }

    /**
     * Maintains single lazy instance of {@link BlobStore}.
     * Public for testing.
     */
    public BlobStore blobStore() {
        BlobStore store = blobStore.get();
        if (store == null) {
            synchronized (lock) {
                store = blobStore.get();
                if (store == null) {
                    if (lifecycle.started() == false) {
                        throw new RepositoryException(metadata.name(), "repository is not in started state");
                    }
                    try {
                        store = createBlobStore();
                    } catch (RepositoryException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RepositoryException(metadata.name(), "cannot create blob store", e);
                    }
                    blobStore.set(store);
                }
            }
        }
        return store;
    }

    /**
     * Creates new BlobStore to read and write data.
     */
    protected abstract BlobStore createBlobStore() throws Exception;

    /**
     * Returns base path of the repository
     */
    public abstract BlobPath basePath();

    /**
     * Returns true if metadata and snapshot files should be compressed
     *
     * @return true if compression is needed
     */
    protected final boolean isCompress() {
        return compressor != CompressorFactory.NONE_COMPRESSOR;
    }

    /**
     * Returns data file chunk size.
     * <p>
     * This method should return null if no chunking is needed.
     *
     * @return chunk size
     */
    protected ByteSizeValue chunkSize() {
        return null;
    }

    @Override
    public RepositoryMetadata getMetadata() {
        return metadata;
    }

    @Override
    public RepositoryStats stats() {
        final BlobStore store = blobStore.get();
        if (store == null) {
            return RepositoryStats.EMPTY_STATS;
        }
        return new RepositoryStats(store.stats());
    }

    public void deleteSnapshotsAndReleaseLockFiles(
        Collection<SnapshotId> snapshotIds,
        long repositoryStateId,
        Version repositoryMetaVersion,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<RepositoryData> listener
    ) {
        if (isReadOnly()) {
            listener.onFailure(new RepositoryException(metadata.name(), "cannot delete snapshot from a readonly repository"));
        } else {
            threadPool.executor(ThreadPool.Names.SNAPSHOT).execute(new AbstractRunnable() {
                @Override
                protected void doRun() throws Exception {
                    final Map<String, BlobMetadata> rootBlobs = blobContainer().listBlobs();
                    final RepositoryData repositoryData = safeRepositoryData(repositoryStateId, rootBlobs);
                    // Cache the indices that were found before writing out the new index-N blob so that a stuck cluster-manager will never
                    // delete an index that was created by another cluster-manager node after writing this index-N blob.
                    final Map<String, BlobContainer> foundIndices = blobStore().blobContainer(indicesPath()).children();
                    doDeleteShardSnapshots(
                        snapshotIds,
                        repositoryStateId,
                        foundIndices,
                        rootBlobs,
                        repositoryData,
                        repositoryMetaVersion,
                        remoteStoreLockManagerFactory,
                        listener
                    );
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(new RepositoryException(metadata.name(), "failed to delete snapshots " + snapshotIds, e));
                }
            });
        }
    }

    @Override
    public void deleteSnapshots(
        Collection<SnapshotId> snapshotIds,
        long repositoryStateId,
        Version repositoryMetaVersion,
        ActionListener<RepositoryData> listener
    ) {
        deleteSnapshotsAndReleaseLockFiles(
            snapshotIds,
            repositoryStateId,
            repositoryMetaVersion,
            null, // Passing null since no remote store lock files need to be cleaned up.
            listener
        );
    }

    /**
     * Loads {@link RepositoryData} ensuring that it is consistent with the given {@code rootBlobs} as well of the assumed generation.
     *
     * @param repositoryStateId Expected repository generation
     * @param rootBlobs         Blobs at the repository root
     * @return RepositoryData
     */
    private RepositoryData safeRepositoryData(long repositoryStateId, Map<String, BlobMetadata> rootBlobs) throws IOException {
        final long generation = latestGeneration(rootBlobs.keySet());
        final long genToLoad;
        final Tuple<Long, BytesReference> cached;
        if (bestEffortConsistency) {
            genToLoad = latestKnownRepoGen.updateAndGet(known -> Math.max(known, repositoryStateId));
            cached = null;
        } else {
            genToLoad = latestKnownRepoGen.get();
            cached = latestKnownRepositoryData.get();
        }
        if (genToLoad > generation) {
            // It's always a possibility to not see the latest index-N in the listing here on an eventually consistent blob store, just
            // debug log it. Any blobs leaked as a result of an inconsistent listing here will be cleaned up in a subsequent cleanup or
            // snapshot delete run anyway.
            logger.debug(
                "Determined repository's generation from its contents to ["
                    + generation
                    + "] but "
                    + "current generation is at least ["
                    + genToLoad
                    + "]"
            );
        }
        if (genToLoad != repositoryStateId) {
            throw new RepositoryException(
                metadata.name(),
                "concurrent modification of the index-N file, expected current generation ["
                    + repositoryStateId
                    + "], actual current generation ["
                    + genToLoad
                    + "]"
            );
        }
        if (cached != null && cached.v1() == genToLoad) {
            return repositoryDataFromCachedEntry(cached);
        }
        return getRepositoryData(genToLoad);
    }

    /**
     * After updating the {@link RepositoryData} each of the shards directories is individually first moved to the next shard generation
     * and then has all now unreferenced blobs in it deleted. If remoteStoreLockManagerFactory is not null, remotestore lock files are
     * released when deleting the respective shallow-snap-UUID blobs.
     *
     * @param snapshotIds                   SnapshotIds to delete
     * @param repositoryStateId             Expected repository state id
     * @param foundIndices                  All indices folders found in the repository before executing any writes to the repository during this
     *                                      delete operation
     * @param rootBlobs                     All blobs found at the root of the repository before executing any writes to the repository during this
     *                                      delete operation
     * @param repositoryData                RepositoryData found the in the repository before executing this delete
     * @param remoteStoreLockManagerFactory RemoteStoreLockManagerFactory to be used for cleaning up remote store lock files
     * @param listener                      Listener to invoke once finished
     */
    private void doDeleteShardSnapshots(
        Collection<SnapshotId> snapshotIds,
        long repositoryStateId,
        Map<String, BlobContainer> foundIndices,
        Map<String, BlobMetadata> rootBlobs,
        RepositoryData repositoryData,
        Version repoMetaVersion,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<RepositoryData> listener
    ) {
        // First write the new shard state metadata (with the removed snapshot) and compute deletion targets
        final StepListener<Collection<ShardSnapshotMetaDeleteResult>> writeShardMetaDataAndComputeDeletesStep = new StepListener<>();
        writeUpdatedShardMetaDataAndComputeDeletes(
            snapshotIds,
            repositoryData,
            true,
            remoteStoreLockManagerFactory,
            writeShardMetaDataAndComputeDeletesStep
        );
        // Once we have put the new shard-level metadata into place, we can update the repository metadata as follows:
        // 1. Remove the snapshots from the list of existing snapshots
        // 2. Update the index shard generations of all updated shard folders
        //
        // Note: If we fail updating any of the individual shard paths, none of them are changed since the newly created
        // index-${gen_uuid} will not be referenced by the existing RepositoryData and new RepositoryData is only
        // written if all shard paths have been successfully updated.
        final StepListener<RepositoryData> writeUpdatedRepoDataStep = new StepListener<>();
        writeShardMetaDataAndComputeDeletesStep.whenComplete(deleteResults -> {
            final ShardGenerations.Builder builder = ShardGenerations.builder();
            for (ShardSnapshotMetaDeleteResult newGen : deleteResults) {
                builder.put(newGen.indexId, newGen.shardId, newGen.newGeneration);
            }
            final RepositoryData updatedRepoData = repositoryData.removeSnapshots(snapshotIds, builder.build());
            writeIndexGen(
                updatedRepoData,
                repositoryStateId,
                repoMetaVersion,
                Function.identity(),
                ActionListener.wrap(writeUpdatedRepoDataStep::onResponse, listener::onFailure)
            );
        }, listener::onFailure);
        // Once we have updated the repository, run the clean-ups
        writeUpdatedRepoDataStep.whenComplete(updatedRepoData -> {
            // Run unreferenced blobs cleanup in parallel to shard-level snapshot deletion
            final ActionListener<Void> afterCleanupsListener = new GroupedActionListener<>(
                ActionListener.wrap(() -> listener.onResponse(updatedRepoData)),
                2
            );
            cleanupUnlinkedRootAndIndicesBlobs(
                snapshotIds,
                foundIndices,
                rootBlobs,
                updatedRepoData,
                remoteStoreLockManagerFactory,
                afterCleanupsListener
            );
            asyncCleanupUnlinkedShardLevelBlobs(
                repositoryData,
                snapshotIds,
                writeShardMetaDataAndComputeDeletesStep.result(),
                remoteStoreLockManagerFactory,
                afterCleanupsListener
            );
        }, listener::onFailure);
    }

    private void cleanupUnlinkedRootAndIndicesBlobs(
        Collection<SnapshotId> deletedSnapshots,
        Map<String, BlobContainer> foundIndices,
        Map<String, BlobMetadata> rootBlobs,
        RepositoryData updatedRepoData,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<Void> listener
    ) {
        cleanupStaleBlobs(
            deletedSnapshots,
            foundIndices,
            rootBlobs,
            updatedRepoData,
            remoteStoreLockManagerFactory,
            ActionListener.map(listener, ignored -> null)
        );
    }

    private void asyncCleanupUnlinkedShardLevelBlobs(
        RepositoryData oldRepositoryData,
        Collection<SnapshotId> snapshotIds,
        Collection<ShardSnapshotMetaDeleteResult> deleteResults,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<Void> listener
    ) {
        final List<String> filesToDelete = resolveFilesToDelete(oldRepositoryData, snapshotIds, deleteResults);
        if (filesToDelete.isEmpty()) {
            listener.onResponse(null);
            return;
        }

        try {
            AtomicInteger counter = new AtomicInteger();
            Collection<List<String>> subList = filesToDelete.stream()
                .collect(Collectors.groupingBy(it -> counter.getAndIncrement() / maxShardBlobDeleteBatch))
                .values();
            final BlockingQueue<List<String>> staleFilesToDeleteInBatch = new LinkedBlockingQueue<>(subList);

            final GroupedActionListener<Void> groupedListener = new GroupedActionListener<>(
                ActionListener.wrap(r -> { listener.onResponse(null); }, listener::onFailure),
                staleFilesToDeleteInBatch.size()
            );

            // Start as many workers as fit into the snapshot pool at once at the most
            final int workers = Math.min(threadPool.info(ThreadPool.Names.SNAPSHOT).getMax(), staleFilesToDeleteInBatch.size());
            for (int i = 0; i < workers; ++i) {
                executeStaleShardDelete(staleFilesToDeleteInBatch, remoteStoreLockManagerFactory, groupedListener);
            }

        } catch (Exception e) {
            // TODO: We shouldn't be blanket catching and suppressing all exceptions here and instead handle them safely upstream.
            // Currently this catch exists as a stop gap solution to tackle unexpected runtime exceptions from implementations
            // bubbling up and breaking the snapshot functionality.
            assert false : e;
            logger.warn(new ParameterizedMessage("[{}] Exception during cleanup of stale shard blobs", snapshotIds), e);
            listener.onFailure(e);
        }
    }

    // When remoteStoreLockManagerFactory is non-null, while deleting the files, lock files are also released before deletion of respective
    // shallow-snap-UUID files. And if it is null, we just delete the stale shard blobs.
    private void executeStaleShardDelete(
        BlockingQueue<List<String>> staleFilesToDeleteInBatch,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        GroupedActionListener<Void> listener
    ) throws InterruptedException {
        List<String> filesToDelete = staleFilesToDeleteInBatch.poll(0L, TimeUnit.MILLISECONDS);
        if (filesToDelete != null) {
            threadPool.executor(ThreadPool.Names.SNAPSHOT).execute(ActionRunnable.wrap(listener, l -> {
                try {
                    if (remoteStoreLockManagerFactory != null) {
                        for (String fileToDelete : filesToDelete) {
                            if (fileToDelete.contains(SHALLOW_SNAPSHOT_PREFIX)) {
                                String[] fileToDeletePath = fileToDelete.split("/");
                                String indexId = fileToDeletePath[1];
                                String shardId = fileToDeletePath[2];
                                String shallowSnapBlob = fileToDeletePath[3];
                                String snapshotUUID = shallowSnapBlob.substring(
                                    SHALLOW_SNAPSHOT_PREFIX.length(),
                                    shallowSnapBlob.length() - ".dat".length()
                                );
                                BlobContainer shardContainer = blobStore().blobContainer(indicesPath().add(indexId).add(shardId));
                                RemoteStoreShardShallowCopySnapshot remoteStoreShardShallowCopySnapshot =
                                    REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT.read(
                                        shardContainer,
                                        snapshotUUID,
                                        namedXContentRegistry
                                    );
                                String indexUUID = remoteStoreShardShallowCopySnapshot.getIndexUUID();
                                String remoteStoreRepoForIndex = remoteStoreShardShallowCopySnapshot.getRemoteStoreRepository();
                                // Releasing lock file before deleting the shallow-snap-UUID file because in case of any failure while
                                // releasing the lock file, we would still have the shallow-snap-UUID file and that would be used during
                                // next delete operation for releasing this lock file
                                RemoteStoreLockManager remoteStoreMetadataLockManager = remoteStoreLockManagerFactory.newLockManager(
                                    remoteStoreRepoForIndex,
                                    indexUUID,
                                    shardId
                                );
                                remoteStoreMetadataLockManager.release(
                                    FileLockInfo.getLockInfoBuilder().withAcquirerId(snapshotUUID).build()
                                );
                                if (!isIndexPresent(clusterService, indexUUID)) {
                                    // this is a temporary solution where snapshot deletion triggers remote store side
                                    // cleanup if index is already deleted. We will add a poller in future to take
                                    // care of remote store side cleanup.
                                    // see https://github.com/opensearch-project/OpenSearch/issues/8469
                                    new RemoteSegmentStoreDirectoryFactory(
                                        remoteStoreLockManagerFactory.getRepositoriesService(),
                                        threadPool
                                    ).newDirectory(remoteStoreRepoForIndex, indexUUID, shardId).close();
                                }
                            }
                        }
                    }
                    // Deleting the shard blobs
                    deleteFromContainer(blobContainer(), filesToDelete);
                    l.onResponse(null);
                } catch (Exception e) {
                    logger.warn(
                        () -> new ParameterizedMessage(
                            "[{}] Failed to delete following blobs during snapshot delete : {}",
                            metadata.name(),
                            filesToDelete
                        ),
                        e
                    );
                    l.onFailure(e);
                }
                executeStaleShardDelete(staleFilesToDeleteInBatch, remoteStoreLockManagerFactory, listener);
            }));
        }
    }

    // updates the shard state metadata for shards of a snapshot that is to be deleted. Also computes the files to be cleaned up.
    private void writeUpdatedShardMetaDataAndComputeDeletes(
        Collection<SnapshotId> snapshotIds,
        RepositoryData oldRepositoryData,
        boolean useUUIDs,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<Collection<ShardSnapshotMetaDeleteResult>> onAllShardsCompleted
    ) {

        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
        final List<IndexId> indices = oldRepositoryData.indicesToUpdateAfterRemovingSnapshot(snapshotIds);

        if (indices.isEmpty()) {
            onAllShardsCompleted.onResponse(Collections.emptyList());
            return;
        }

        // Listener that flattens out the delete results for each index
        final ActionListener<Collection<ShardSnapshotMetaDeleteResult>> deleteIndexMetadataListener = new GroupedActionListener<>(
            ActionListener.map(onAllShardsCompleted, res -> res.stream().flatMap(Collection::stream).collect(Collectors.toList())),
            indices.size()
        );

        for (IndexId indexId : indices) {
            final Set<SnapshotId> survivingSnapshots = oldRepositoryData.getSnapshots(indexId)
                .stream()
                .filter(id -> snapshotIds.contains(id) == false)
                .collect(Collectors.toSet());
            final StepListener<Collection<Integer>> shardCountListener = new StepListener<>();
            final Collection<String> indexMetaGenerations = snapshotIds.stream()
                .map(id -> oldRepositoryData.indexMetaDataGenerations().indexMetaBlobId(id, indexId))
                .collect(Collectors.toSet());
            final ActionListener<Integer> allShardCountsListener = new GroupedActionListener<>(
                shardCountListener,
                indexMetaGenerations.size()
            );
            final BlobContainer indexContainer = indexContainer(indexId);
            for (String indexMetaGeneration : indexMetaGenerations) {
                executor.execute(ActionRunnable.supply(allShardCountsListener, () -> {
                    try {
                        return INDEX_METADATA_FORMAT.read(indexContainer, indexMetaGeneration, namedXContentRegistry).getNumberOfShards();
                    } catch (Exception ex) {
                        logger.warn(
                            () -> new ParameterizedMessage(
                                "[{}] [{}] failed to read metadata for index",
                                indexMetaGeneration,
                                indexId.getName()
                            ),
                            ex
                        );
                        // Just invoke the listener without any shard generations to count it down, this index will be cleaned up
                        // by the stale data cleanup in the end.
                        // TODO: Getting here means repository corruption. We should find a way of dealing with this instead of just
                        // ignoring it and letting the cleanup deal with it.
                        return null;
                    }
                }));
            }
            shardCountListener.whenComplete(counts -> {
                final int shardCount = counts.stream().mapToInt(i -> i).max().orElse(0);
                if (shardCount == 0) {
                    deleteIndexMetadataListener.onResponse(null);
                    return;
                }
                // Listener for collecting the results of removing the snapshot from each shard's metadata in the current index
                final ActionListener<ShardSnapshotMetaDeleteResult> allShardsListener = new GroupedActionListener<>(
                    deleteIndexMetadataListener,
                    shardCount
                );
                for (int shardId = 0; shardId < shardCount; shardId++) {
                    final int finalShardId = shardId;
                    executor.execute(new AbstractRunnable() {
                        @Override
                        protected void doRun() throws Exception {
                            final BlobContainer shardContainer = shardContainer(indexId, finalShardId);
                            final Set<String> blobs = shardContainer.listBlobs().keySet();
                            final BlobStoreIndexShardSnapshots blobStoreIndexShardSnapshots;
                            final long newGen;

                            // Index-N file would be present if snapshots other than shallow snapshots are present for this shard
                            if (blobs.stream()
                                .filter(blob -> blob.startsWith(SNAPSHOT_INDEX_PREFIX))
                                .collect(Collectors.toSet())
                                .size() > 0) {
                                if (useUUIDs) {
                                    newGen = -1L;
                                    blobStoreIndexShardSnapshots = buildBlobStoreIndexShardSnapshots(
                                        blobs,
                                        shardContainer,
                                        oldRepositoryData.shardGenerations().getShardGen(indexId, finalShardId)
                                    ).v1();
                                } else {
                                    Tuple<BlobStoreIndexShardSnapshots, Long> tuple = buildBlobStoreIndexShardSnapshots(
                                        blobs,
                                        shardContainer
                                    );
                                    newGen = tuple.v2() + 1;
                                    blobStoreIndexShardSnapshots = tuple.v1();
                                }
                            } else {
                                newGen = -1L;
                                blobStoreIndexShardSnapshots = BlobStoreIndexShardSnapshots.EMPTY;
                            }
                            allShardsListener.onResponse(
                                deleteFromShardSnapshotMeta(
                                    survivingSnapshots,
                                    indexId,
                                    finalShardId,
                                    snapshotIds,
                                    shardContainer,
                                    blobs,
                                    blobStoreIndexShardSnapshots,
                                    newGen,
                                    remoteStoreLockManagerFactory
                                )
                            );
                        }

                        @Override
                        public void onFailure(Exception ex) {
                            logger.warn(
                                () -> new ParameterizedMessage(
                                    "{} failed to delete shard data for shard [{}][{}]",
                                    snapshotIds,
                                    indexId.getName(),
                                    finalShardId
                                ),
                                ex
                            );
                            // Just passing null here to count down the listener instead of failing it, the stale data left behind
                            // here will be retried in the next delete or repository cleanup
                            allShardsListener.onResponse(null);
                        }
                    });
                }
            }, deleteIndexMetadataListener::onFailure);
        }
    }

    private List<String> resolveFilesToDelete(
        RepositoryData oldRepositoryData,
        Collection<SnapshotId> snapshotIds,
        Collection<ShardSnapshotMetaDeleteResult> deleteResults
    ) {
        final String basePath = basePath().buildAsString();
        final int basePathLen = basePath.length();
        final Map<IndexId, Collection<String>> indexMetaGenerations = oldRepositoryData.indexMetaDataToRemoveAfterRemovingSnapshots(
            snapshotIds
        );
        return Stream.concat(deleteResults.stream().flatMap(shardResult -> {
            final String shardPath = shardContainer(shardResult.indexId, shardResult.shardId).path().buildAsString();
            return shardResult.blobsToDelete.stream().map(blob -> shardPath + blob);
        }), indexMetaGenerations.entrySet().stream().flatMap(entry -> {
            final String indexContainerPath = indexContainer(entry.getKey()).path().buildAsString();
            return entry.getValue().stream().map(id -> indexContainerPath + INDEX_METADATA_FORMAT.blobName(id));
        })).map(absolutePath -> {
            assert absolutePath.startsWith(basePath);
            return absolutePath.substring(basePathLen);
        }).collect(Collectors.toList());
    }

    /**
     * Cleans up stale blobs directly under the repository root as well as all indices paths that aren't referenced by any existing
     * snapshots. This method is only to be called directly after a new {@link RepositoryData} was written to the repository and with
     * parameters {@code foundIndices}, {@code rootBlobs}. If remoteStoreLockManagerFactory is not null, remote store lock files are
     * released when deleting the respective shallow-snap-UUID blobs.
     *
     * @param deletedSnapshots              if this method is called as part of a delete operation, the snapshot ids just deleted or empty if called as
     *                                      part of a repository cleanup
     * @param foundIndices                  all indices blob containers found in the repository before {@code newRepoData} was written
     * @param rootBlobs                     all blobs found directly under the repository root
     * @param newRepoData                   new repository data that was just written
     * @param remoteStoreLockManagerFactory RemoteStoreLockManagerFactory to be used for cleaning up remote store lock files.
     * @param listener                      listener to invoke with the combined {@link DeleteResult} of all blobs removed in this operation
     */
    private void cleanupStaleBlobs(
        Collection<SnapshotId> deletedSnapshots,
        Map<String, BlobContainer> foundIndices,
        Map<String, BlobMetadata> rootBlobs,
        RepositoryData newRepoData,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<DeleteResult> listener
    ) {
        final GroupedActionListener<DeleteResult> groupedListener = new GroupedActionListener<>(ActionListener.wrap(deleteResults -> {
            DeleteResult deleteResult = DeleteResult.ZERO;
            for (DeleteResult result : deleteResults) {
                deleteResult = deleteResult.add(result);
            }
            listener.onResponse(deleteResult);
        }, listener::onFailure), 2);

        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
        final List<String> staleRootBlobs = staleRootBlobs(newRepoData, rootBlobs.keySet());
        if (staleRootBlobs.isEmpty()) {
            groupedListener.onResponse(DeleteResult.ZERO);
        } else {
            executor.execute(ActionRunnable.supply(groupedListener, () -> {
                List<String> deletedBlobs = cleanupStaleRootFiles(newRepoData.getGenId() - 1, deletedSnapshots, staleRootBlobs);
                return new DeleteResult(deletedBlobs.size(), deletedBlobs.stream().mapToLong(name -> rootBlobs.get(name).length()).sum());
            }));
        }

        final Set<String> survivingIndexIds = newRepoData.getIndices().values().stream().map(IndexId::getId).collect(Collectors.toSet());
        if (foundIndices.keySet().equals(survivingIndexIds)) {
            groupedListener.onResponse(DeleteResult.ZERO);
        } else {
            cleanupStaleIndices(foundIndices, survivingIndexIds, remoteStoreLockManagerFactory, groupedListener);
        }
    }

    /**
     * Runs cleanup actions on the repository. Increments the repository state id by one before executing any modifications on the
     * repository. If remoteStoreLockManagerFactory is not null, remote store lock files are released when deleting the respective
     * shallow-snap-UUID blobs.
     * TODO: Add shard level cleanups
     * TODO: Add unreferenced index metadata cleanup
     * <ul>
     *     <li>Deleting stale indices {@link #cleanupStaleIndices}</li>
     *     <li>Deleting unreferenced root level blobs {@link #cleanupStaleRootFiles}</li>
     * </ul>
     * @param repositoryStateId             Current repository state id
     * @param repositoryMetaVersion         version of the updated repository metadata to write
     * @param remoteStoreLockManagerFactory RemoteStoreLockManagerFactory to be used for cleaning up remote store lock files.
     * @param listener                      Listener to complete when done
     */
    public void cleanup(
        long repositoryStateId,
        Version repositoryMetaVersion,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        ActionListener<RepositoryCleanupResult> listener
    ) {
        try {
            if (isReadOnly()) {
                throw new RepositoryException(metadata.name(), "cannot run cleanup on readonly repository");
            }
            Map<String, BlobMetadata> rootBlobs = blobContainer().listBlobs();
            final RepositoryData repositoryData = safeRepositoryData(repositoryStateId, rootBlobs);
            final Map<String, BlobContainer> foundIndices = blobStore().blobContainer(indicesPath()).children();
            final Set<String> survivingIndexIds = repositoryData.getIndices()
                .values()
                .stream()
                .map(IndexId::getId)
                .collect(Collectors.toSet());
            final List<String> staleRootBlobs = staleRootBlobs(repositoryData, rootBlobs.keySet());
            if (survivingIndexIds.equals(foundIndices.keySet()) && staleRootBlobs.isEmpty()) {
                // Nothing to clean up we return
                listener.onResponse(new RepositoryCleanupResult(DeleteResult.ZERO));
            } else {
                // write new index-N blob to ensure concurrent operations will fail
                writeIndexGen(
                    repositoryData,
                    repositoryStateId,
                    repositoryMetaVersion,
                    Function.identity(),
                    ActionListener.wrap(
                        v -> cleanupStaleBlobs(
                            Collections.emptyList(),
                            foundIndices,
                            rootBlobs,
                            repositoryData,
                            remoteStoreLockManagerFactory,
                            ActionListener.map(listener, RepositoryCleanupResult::new)
                        ),
                        listener::onFailure
                    )
                );
            }
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    // Finds all blobs directly under the repository root path that are not referenced by the current RepositoryData
    private static List<String> staleRootBlobs(RepositoryData repositoryData, Set<String> rootBlobNames) {
        final Set<String> allSnapshotIds = repositoryData.getSnapshotIds().stream().map(SnapshotId::getUUID).collect(Collectors.toSet());
        return rootBlobNames.stream().filter(blob -> {
            if (FsBlobContainer.isTempBlobName(blob)) {
                return true;
            }
            if (blob.endsWith(".dat")) {
                final String foundUUID;
                if (blob.startsWith(SNAPSHOT_PREFIX)) {
                    foundUUID = blob.substring(SNAPSHOT_PREFIX.length(), blob.length() - ".dat".length());
                    assert SNAPSHOT_FORMAT.blobName(foundUUID).equals(blob);
                } else if (blob.startsWith(METADATA_PREFIX)) {
                    foundUUID = blob.substring(METADATA_PREFIX.length(), blob.length() - ".dat".length());
                    assert GLOBAL_METADATA_FORMAT.blobName(foundUUID).equals(blob);
                } else {
                    return false;
                }
                return allSnapshotIds.contains(foundUUID) == false;
            } else if (blob.startsWith(INDEX_FILE_PREFIX)) {
                // TODO: Include the current generation here once we remove keeping index-(N-1) around from #writeIndexGen
                return repositoryData.getGenId() > Long.parseLong(blob.substring(INDEX_FILE_PREFIX.length()));
            }
            return false;
        }).collect(Collectors.toList());
    }

    private List<String> cleanupStaleRootFiles(
        long previousGeneration,
        Collection<SnapshotId> deletedSnapshots,
        List<String> blobsToDelete
    ) {
        if (blobsToDelete.isEmpty()) {
            return blobsToDelete;
        }
        try {
            if (logger.isInfoEnabled()) {
                // If we're running root level cleanup as part of a snapshot delete we should not log the snapshot- and global metadata
                // blobs associated with the just deleted snapshots as they are expected to exist and not stale. Otherwise every snapshot
                // delete would also log a confusing INFO message about "stale blobs".
                final Set<String> blobNamesToIgnore = deletedSnapshots.stream()
                    .flatMap(
                        snapshotId -> Stream.of(
                            GLOBAL_METADATA_FORMAT.blobName(snapshotId.getUUID()),
                            SNAPSHOT_FORMAT.blobName(snapshotId.getUUID()),
                            INDEX_FILE_PREFIX + previousGeneration
                        )
                    )
                    .collect(Collectors.toSet());
                final List<String> blobsToLog = blobsToDelete.stream()
                    .filter(b -> blobNamesToIgnore.contains(b) == false)
                    .collect(Collectors.toList());
                if (blobsToLog.isEmpty() == false) {
                    logger.info("[{}] Found stale root level blobs {}. Cleaning them up", metadata.name(), blobsToLog);
                }
            }
            deleteFromContainer(blobContainer(), blobsToDelete);
            return blobsToDelete;
        } catch (IOException e) {
            logger.warn(
                () -> new ParameterizedMessage(
                    "[{}] The following blobs are no longer part of any snapshot [{}] but failed to remove them",
                    metadata.name(),
                    blobsToDelete
                ),
                e
            );
        } catch (Exception e) {
            // TODO: We shouldn't be blanket catching and suppressing all exceptions here and instead handle them safely upstream.
            // Currently this catch exists as a stop gap solution to tackle unexpected runtime exceptions from implementations
            // bubbling up and breaking the snapshot functionality.
            assert false : e;
            logger.warn(new ParameterizedMessage("[{}] Exception during cleanup of root level blobs", metadata.name()), e);
        }
        return Collections.emptyList();
    }

    private void cleanupStaleIndices(
        Map<String, BlobContainer> foundIndices,
        Set<String> survivingIndexIds,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        GroupedActionListener<DeleteResult> listener
    ) {
        final GroupedActionListener<DeleteResult> groupedListener = new GroupedActionListener<>(ActionListener.wrap(deleteResults -> {
            DeleteResult deleteResult = DeleteResult.ZERO;
            for (DeleteResult result : deleteResults) {
                deleteResult = deleteResult.add(result);
            }
            listener.onResponse(deleteResult);
        }, listener::onFailure), foundIndices.size() - survivingIndexIds.size());

        try {
            final BlockingQueue<Map.Entry<String, BlobContainer>> staleIndicesToDelete = new LinkedBlockingQueue<>();
            for (Map.Entry<String, BlobContainer> indexEntry : foundIndices.entrySet()) {
                if (survivingIndexIds.contains(indexEntry.getKey()) == false) {
                    staleIndicesToDelete.put(indexEntry);
                }
            }

            // Start as many workers as fit into the snapshot pool at once at the most
            final int workers = Math.min(
                threadPool.info(ThreadPool.Names.SNAPSHOT).getMax(),
                foundIndices.size() - survivingIndexIds.size()
            );
            for (int i = 0; i < workers; ++i) {
                executeOneStaleIndexDelete(staleIndicesToDelete, remoteStoreLockManagerFactory, groupedListener);
            }
        } catch (Exception e) {
            // TODO: We shouldn't be blanket catching and suppressing all exceptions here and instead handle them safely upstream.
            // Currently this catch exists as a stop gap solution to tackle unexpected runtime exceptions from implementations
            // bubbling up and breaking the snapshot functionality.
            assert false : e;
            logger.warn(new ParameterizedMessage("[{}] Exception during cleanup of stale indices", metadata.name()), e);
        }
    }

    private static boolean isIndexPresent(ClusterService clusterService, String indexUUID) {
        for (final IndexMetadata indexMetadata : clusterService.state().metadata().getIndices().values()) {
            if (indexUUID.equals(indexMetadata.getIndexUUID())) {
                return true;
            }
        }
        return false;
    }

    private void executeOneStaleIndexDelete(
        BlockingQueue<Map.Entry<String, BlobContainer>> staleIndicesToDelete,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory,
        GroupedActionListener<DeleteResult> listener
    ) throws InterruptedException {
        Map.Entry<String, BlobContainer> indexEntry = staleIndicesToDelete.poll(0L, TimeUnit.MILLISECONDS);
        if (indexEntry != null) {
            final String indexSnId = indexEntry.getKey();
            threadPool.executor(ThreadPool.Names.SNAPSHOT).execute(ActionRunnable.supply(listener, () -> {
                DeleteResult deleteResult = DeleteResult.ZERO;
                try {
                    logger.debug("[{}] Found stale index [{}]. Cleaning it up", metadata.name(), indexSnId);
                    if (remoteStoreLockManagerFactory != null) {
                        Map<String, BlobContainer> shardBlobs = indexEntry.getValue().children();
                        if (!shardBlobs.isEmpty()) {
                            for (Map.Entry<String, BlobContainer> shardBlob : shardBlobs.entrySet()) {
                                Map<String, BlobMetadata> shardLevelBlobs = shardBlob.getValue().listBlobs();
                                for (Map.Entry<String, BlobMetadata> shardLevelBlob : shardLevelBlobs.entrySet()) {
                                    String blob = shardLevelBlob.getKey();
                                    String snapshotUUID = blob.substring(SHALLOW_SNAPSHOT_PREFIX.length(), blob.length() - ".dat".length());
                                    if (blob.startsWith(SHALLOW_SNAPSHOT_PREFIX) && blob.endsWith(".dat")) {
                                        RemoteStoreShardShallowCopySnapshot remoteStoreShardShallowCopySnapshot =
                                            REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT.read(
                                                shardBlob.getValue(),
                                                snapshotUUID,
                                                namedXContentRegistry
                                            );
                                        String indexUUID = remoteStoreShardShallowCopySnapshot.getIndexUUID();
                                        String remoteStoreRepoForIndex = remoteStoreShardShallowCopySnapshot.getRemoteStoreRepository();
                                        // Releasing lock files before deleting the shallow-snap-UUID file because in case of any failure
                                        // while releasing the lock file, we would still have the corresponding shallow-snap-UUID file
                                        // and that would be used during next delete operation for releasing this stale lock file
                                        RemoteStoreLockManager remoteStoreMetadataLockManager = remoteStoreLockManagerFactory
                                            .newLockManager(remoteStoreRepoForIndex, indexUUID, shardBlob.getKey());
                                        remoteStoreMetadataLockManager.release(
                                            FileLockInfo.getLockInfoBuilder().withAcquirerId(snapshotUUID).build()
                                        );
                                        if (!isIndexPresent(clusterService, indexUUID)) {
                                            // this is a temporary solution where snapshot deletion triggers remote store side
                                            // cleanup if index is already deleted. We will add a poller in future to take
                                            // care of remote store side cleanup.
                                            // see https://github.com/opensearch-project/OpenSearch/issues/8469
                                            new RemoteSegmentStoreDirectoryFactory(
                                                remoteStoreLockManagerFactory.getRepositoriesService(),
                                                threadPool
                                            ).newDirectory(remoteStoreRepoForIndex, indexUUID, shardBlob.getKey()).close();
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Deleting the index folder
                    deleteResult = indexEntry.getValue().delete();
                    logger.debug("[{}] Cleaned up stale index [{}]", metadata.name(), indexSnId);
                } catch (IOException e) {
                    logger.warn(
                        () -> new ParameterizedMessage(
                            "[{}] index {} is no longer part of any snapshots in the repository, "
                                + "but failed to clean up their index folders",
                            metadata.name(),
                            indexSnId
                        ),
                        e
                    );
                } catch (Exception e) {
                    assert false : e;
                    logger.warn(new ParameterizedMessage("[{}] Exception during single stale index delete", metadata.name()), e);
                }

                executeOneStaleIndexDelete(staleIndicesToDelete, remoteStoreLockManagerFactory, listener);
                return deleteResult;
            }));
        }
    }

    @Override
    public void finalizeSnapshot(
        final ShardGenerations shardGenerations,
        final long repositoryStateId,
        final Metadata clusterMetadata,
        SnapshotInfo snapshotInfo,
        Version repositoryMetaVersion,
        Function<ClusterState, ClusterState> stateTransformer,
        final ActionListener<RepositoryData> listener
    ) {
        assert repositoryStateId > RepositoryData.UNKNOWN_REPO_GEN : "Must finalize based on a valid repository generation but received ["
            + repositoryStateId
            + "]";
        final Collection<IndexId> indices = shardGenerations.indices();
        final SnapshotId snapshotId = snapshotInfo.snapshotId();
        // Once we are done writing the updated index-N blob we remove the now unreferenced index-${uuid} blobs in each shard
        // directory
        final Consumer<Exception> onUpdateFailure = e -> listener.onFailure(
            new SnapshotException(metadata.name(), snapshotId, "failed to update snapshot in repository", e)
        );

        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);

        final StepListener<RepositoryData> repoDataListener = new StepListener<>();
        getRepositoryData(repoDataListener);
        repoDataListener.whenComplete(existingRepositoryData -> {

            final Map<IndexId, String> indexMetas = ConcurrentCollections.newConcurrentMap();
            final Map<String, String> indexMetaIdentifiers = ConcurrentCollections.newConcurrentMap();

            final ActionListener<Void> allMetaListener = new GroupedActionListener<>(ActionListener.wrap(v -> {
                final RepositoryData updatedRepositoryData = existingRepositoryData.addSnapshot(
                    snapshotId,
                    snapshotInfo.state(),
                    Version.CURRENT,
                    shardGenerations,
                    indexMetas,
                    indexMetaIdentifiers
                );
                writeIndexGen(
                    updatedRepositoryData,
                    repositoryStateId,
                    repositoryMetaVersion,
                    stateTransformer,
                    ActionListener.wrap(newRepoData -> {
                        cleanupOldShardGens(existingRepositoryData, updatedRepositoryData);
                        listener.onResponse(newRepoData);
                    }, onUpdateFailure)
                );
            }, onUpdateFailure), 2 + indices.size());

            // We ignore all FileAlreadyExistsException when writing metadata since otherwise a cluster-manager failover
            // while in this method will mean that no snap-${uuid}.dat blob is ever written for this snapshot. This is safe because
            // any updated version of the index or global metadata will be compatible with the segments written in this snapshot as well.
            // Failing on an already existing index-${repoGeneration} below ensures that the index.latest blob is not updated in a way
            // that decrements the generation it points at

            // Write Global MetaData
            executor.execute(
                ActionRunnable.run(
                    allMetaListener,
                    () -> GLOBAL_METADATA_FORMAT.write(clusterMetadata, blobContainer(), snapshotId.getUUID(), compressor)
                )
            );

            // write the index metadata for each index in the snapshot
            for (IndexId index : indices) {
                executor.execute(ActionRunnable.run(allMetaListener, () -> {
                    final IndexMetadata indexMetaData = clusterMetadata.index(index.getName());
                    final String identifiers = IndexMetaDataGenerations.buildUniqueIdentifier(indexMetaData);
                    String metaUUID = existingRepositoryData.indexMetaDataGenerations().getIndexMetaBlobId(identifiers);
                    if (metaUUID == null) {
                        // We don't yet have this version of the metadata so we write it
                        metaUUID = UUIDs.base64UUID();
                        INDEX_METADATA_FORMAT.write(indexMetaData, indexContainer(index), metaUUID, compressor);
                        indexMetaIdentifiers.put(identifiers, metaUUID);
                    }
                    indexMetas.put(index, identifiers);
                }));
            }
            executor.execute(
                ActionRunnable.run(
                    allMetaListener,
                    () -> SNAPSHOT_FORMAT.write(snapshotInfo, blobContainer(), snapshotId.getUUID(), compressor)
                )
            );
        }, onUpdateFailure);
    }

    // Delete all old shard gen blobs that aren't referenced any longer as a result from moving to updated repository data
    private void cleanupOldShardGens(RepositoryData existingRepositoryData, RepositoryData updatedRepositoryData) {
        final List<String> toDelete = new ArrayList<>();
        final int prefixPathLen = basePath().buildAsString().length();
        updatedRepositoryData.shardGenerations()
            .obsoleteShardGenerations(existingRepositoryData.shardGenerations())
            .forEach(
                (indexId, gens) -> gens.forEach(
                    (shardId, oldGen) -> toDelete.add(
                        shardContainer(indexId, shardId).path().buildAsString().substring(prefixPathLen) + INDEX_FILE_PREFIX + oldGen
                    )
                )
            );
        try {
            deleteFromContainer(blobContainer(), toDelete);
        } catch (Exception e) {
            logger.warn("Failed to clean up old shard generation blobs", e);
        }
    }

    @Override
    public SnapshotInfo getSnapshotInfo(final SnapshotId snapshotId) {
        try {
            return SNAPSHOT_FORMAT.read(blobContainer(), snapshotId.getUUID(), namedXContentRegistry);
        } catch (NoSuchFileException ex) {
            throw new SnapshotMissingException(metadata.name(), snapshotId, ex);
        } catch (IOException | NotXContentException ex) {
            throw new SnapshotException(metadata.name(), snapshotId, "failed to get snapshots", ex);
        }
    }

    @Override
    public Metadata getSnapshotGlobalMetadata(final SnapshotId snapshotId) {
        try {
            return GLOBAL_METADATA_FORMAT.read(blobContainer(), snapshotId.getUUID(), namedXContentRegistry);
        } catch (NoSuchFileException ex) {
            throw new SnapshotMissingException(metadata.name(), snapshotId, ex);
        } catch (IOException ex) {
            throw new SnapshotException(metadata.name(), snapshotId, "failed to read global metadata", ex);
        }
    }

    @Override
    public IndexMetadata getSnapshotIndexMetaData(RepositoryData repositoryData, SnapshotId snapshotId, IndexId index) throws IOException {
        try {
            return INDEX_METADATA_FORMAT.read(
                indexContainer(index),
                repositoryData.indexMetaDataGenerations().indexMetaBlobId(snapshotId, index),
                namedXContentRegistry
            );
        } catch (NoSuchFileException e) {
            throw new SnapshotMissingException(metadata.name(), snapshotId, e);
        }
    }

    private void deleteFromContainer(BlobContainer container, List<String> blobs) throws IOException {
        logger.trace(() -> new ParameterizedMessage("[{}] Deleting {} from [{}]", metadata.name(), blobs, container.path()));
        container.deleteBlobsIgnoringIfNotExists(blobs);
    }

    private BlobPath indicesPath() {
        return basePath().add("indices");
    }

    private BlobContainer indexContainer(IndexId indexId) {
        return blobStore().blobContainer(indicesPath().add(indexId.getId()));
    }

    private BlobContainer shardContainer(IndexId indexId, ShardId shardId) {
        return shardContainer(indexId, shardId.getId());
    }

    public BlobContainer shardContainer(IndexId indexId, int shardId) {
        return blobStore().blobContainer(indicesPath().add(indexId.getId()).add(Integer.toString(shardId)));
    }

    /**
     * Configures RateLimiter based on repository and global settings
     *
     * @param repositorySettings repository settings
     * @param setting            setting to use to configure rate limiter
     * @param defaultRate        default limiting rate
     * @return rate limiter or null of no throttling is needed
     */
    private RateLimiter getRateLimiter(Settings repositorySettings, String setting, ByteSizeValue defaultRate) {
        ByteSizeValue maxSnapshotBytesPerSec = repositorySettings.getAsBytesSize(setting, defaultRate);
        if (maxSnapshotBytesPerSec.getBytes() <= 0) {
            return null;
        } else {
            return new RateLimiter.SimpleRateLimiter(maxSnapshotBytesPerSec.getMbFrac());
        }
    }

    @Override
    public long getSnapshotThrottleTimeInNanos() {
        return snapshotRateLimitingTimeInNanos.count();
    }

    @Override
    public long getRestoreThrottleTimeInNanos() {
        return restoreRateLimitingTimeInNanos.count();
    }

    protected void assertSnapshotOrGenericThread() {
        assert Thread.currentThread().getName().contains('[' + ThreadPool.Names.SNAPSHOT + ']')
            || Thread.currentThread().getName().contains('[' + ThreadPool.Names.GENERIC + ']') : "Expected current thread ["
                + Thread.currentThread()
                + "] to be the snapshot or generic thread.";
    }

    @Override
    public String startVerification() {
        try {
            if (isReadOnly()) {
                // It's readonly - so there is not much we can do here to verify it apart from reading the blob store metadata
                latestIndexBlobId();
                return "read-only";
            } else {
                String seed = UUIDs.randomBase64UUID();
                byte[] testBytes = Strings.toUTF8Bytes(seed);
                BlobContainer testContainer = blobStore().blobContainer(basePath().add(testBlobPrefix(seed)));
                BytesArray bytes = new BytesArray(testBytes);
                try (InputStream stream = bytes.streamInput()) {
                    testContainer.writeBlobAtomic("master.dat", stream, bytes.length(), true);
                }
                return seed;
            }
        } catch (Exception exp) {
            throw new RepositoryVerificationException(
                metadata.name(),
                "path " + basePath() + " is not accessible on cluster-manager node",
                exp
            );
        }
    }

    @Override
    public void endVerification(String seed) {
        if (isReadOnly() == false) {
            try {
                final String testPrefix = testBlobPrefix(seed);
                blobStore().blobContainer(basePath().add(testPrefix)).delete();
            } catch (Exception exp) {
                throw new RepositoryVerificationException(metadata.name(), "cannot delete test data at " + basePath(), exp);
            }
        }
    }

    // Tracks the latest known repository generation in a best-effort way to detect inconsistent listing of root level index-N blobs
    // and concurrent modifications.
    private final AtomicLong latestKnownRepoGen = new AtomicLong(RepositoryData.UNKNOWN_REPO_GEN);

    // Best effort cache of the latest known repository data and its generation, cached serialized as compressed json
    private final AtomicReference<Tuple<Long, BytesReference>> latestKnownRepositoryData = new AtomicReference<>();

    @Override
    public void getRepositoryData(ActionListener<RepositoryData> listener) {
        if (latestKnownRepoGen.get() == RepositoryData.CORRUPTED_REPO_GEN) {
            listener.onFailure(corruptedStateException(null));
            return;
        }
        final Tuple<Long, BytesReference> cached = latestKnownRepositoryData.get();
        // Fast path loading repository data directly from cache if we're in fully consistent mode and the cache matches up with
        // the latest known repository generation
        if (bestEffortConsistency == false && cached != null && cached.v1() == latestKnownRepoGen.get()) {
            try {
                listener.onResponse(repositoryDataFromCachedEntry(cached));
            } catch (Exception e) {
                listener.onFailure(e);
            }
            return;
        }
        // Slow path if we were not able to safely read the repository data from cache
        threadPool.generic().execute(ActionRunnable.wrap(listener, this::doGetRepositoryData));
    }

    private void doGetRepositoryData(ActionListener<RepositoryData> listener) {
        // Retry loading RepositoryData in a loop in case we run into concurrent modifications of the repository.
        // Keep track of the most recent generation we failed to load so we can break out of the loop if we fail to load the same
        // generation repeatedly.
        long lastFailedGeneration = RepositoryData.UNKNOWN_REPO_GEN;
        while (true) {
            final long genToLoad;
            if (bestEffortConsistency) {
                // We're only using #latestKnownRepoGen as a hint in this mode and listing repo contents as a secondary way of trying
                // to find a higher generation
                final long generation;
                try {
                    generation = latestIndexBlobId();
                } catch (IOException ioe) {
                    listener.onFailure(
                        new RepositoryException(metadata.name(), "Could not determine repository generation from root blobs", ioe)
                    );
                    return;
                }
                genToLoad = latestKnownRepoGen.updateAndGet(known -> Math.max(known, generation));
                if (genToLoad > generation) {
                    logger.info(
                        "Determined repository generation ["
                            + generation
                            + "] from repository contents but correct generation must be at least ["
                            + genToLoad
                            + "]"
                    );
                }
            } else {
                // We only rely on the generation tracked in #latestKnownRepoGen which is exclusively updated from the cluster state
                genToLoad = latestKnownRepoGen.get();
            }
            try {
                final Tuple<Long, BytesReference> cached = latestKnownRepositoryData.get();
                final RepositoryData loaded;
                // Caching is not used with #bestEffortConsistency see docs on #cacheRepositoryData for details
                if (bestEffortConsistency == false && cached != null && cached.v1() == genToLoad) {
                    loaded = repositoryDataFromCachedEntry(cached);
                } else {
                    loaded = getRepositoryData(genToLoad);
                    // We can cache serialized in the most recent version here without regard to the actual repository metadata version
                    // since we're only caching the information that we just wrote and thus won't accidentally cache any information that
                    // isn't safe
                    cacheRepositoryData(
                        BytesReference.bytes(loaded.snapshotsToXContent(XContentFactory.jsonBuilder(), Version.CURRENT)),
                        genToLoad
                    );
                }
                listener.onResponse(loaded);
                return;
            } catch (RepositoryException e) {
                // If the generation to load changed concurrently and we didn't just try loading the same generation before we retry
                if (genToLoad != latestKnownRepoGen.get() && genToLoad != lastFailedGeneration) {
                    lastFailedGeneration = genToLoad;
                    logger.warn(
                        "Failed to load repository data generation ["
                            + genToLoad
                            + "] because a concurrent operation moved the current generation to ["
                            + latestKnownRepoGen.get()
                            + "]",
                        e
                    );
                    continue;
                }
                if (bestEffortConsistency == false && ExceptionsHelper.unwrap(e, NoSuchFileException.class) != null) {
                    // We did not find the expected index-N even though the cluster state continues to point at the missing value
                    // of N so we mark this repository as corrupted.
                    markRepoCorrupted(
                        genToLoad,
                        e,
                        ActionListener.wrap(v -> listener.onFailure(corruptedStateException(e)), listener::onFailure)
                    );
                } else {
                    listener.onFailure(e);
                }
                return;
            } catch (Exception e) {
                listener.onFailure(new RepositoryException(metadata.name(), "Unexpected exception when loading repository data", e));
                return;
            }
        }
    }

    /**
     * Puts the given {@link RepositoryData} into the cache if it is of a newer generation and only if the repository is not using
     * {@link #bestEffortConsistency}. When using {@link #bestEffortConsistency} the repository is using listing to find the latest
     * {@code index-N} blob and there are no hard guarantees that a given repository generation won't be reused since an external
     * modification can lead to moving from a higher {@code N} to a lower {@code N} value which mean we can't safely assume that a given
     * generation will always contain the same {@link RepositoryData}.
     *
     * @param updated    serialized RepositoryData to cache if newer than the cache contents
     * @param generation repository generation of the given repository data
     */
    private void cacheRepositoryData(BytesReference updated, long generation) {
        if (cacheRepositoryData && bestEffortConsistency == false) {
            final BytesReference serialized;
            try {
                serialized = CompressorFactory.defaultCompressor().compress(updated);
                final int len = serialized.length();
                if (len > ByteSizeUnit.KB.toBytes(500)) {
                    logger.debug(
                        "Not caching repository data of size [{}] for repository [{}] because it is larger than 500KB in"
                            + " serialized size",
                        len,
                        metadata.name()
                    );
                    if (len > ByteSizeUnit.MB.toBytes(5)) {
                        logger.warn(
                            "Your repository metadata blob for repository [{}] is larger than 5MB. Consider moving to a fresh"
                                + " repository for new snapshots or deleting unneeded snapshots from your repository to ensure stable"
                                + " repository behavior going forward.",
                            metadata.name()
                        );
                    }
                    // Set empty repository data to not waste heap for an outdated cached value
                    latestKnownRepositoryData.set(null);
                    return;
                }
            } catch (IOException e) {
                assert false : new AssertionError("Impossible, no IO happens here", e);
                logger.warn("Failed to serialize repository data", e);
                return;
            }
            latestKnownRepositoryData.updateAndGet(known -> {
                if (known != null && known.v1() > generation) {
                    return known;
                }
                return new Tuple<>(generation, serialized);
            });
        }
    }

    private RepositoryData repositoryDataFromCachedEntry(Tuple<Long, BytesReference> cacheEntry) throws IOException {
        try (InputStream input = CompressorFactory.defaultCompressor().threadLocalInputStream(cacheEntry.v2().streamInput())) {
            return RepositoryData.snapshotsFromXContent(
                MediaTypeRegistry.JSON.xContent().createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, input),
                cacheEntry.v1()
            );
        }
    }

    private RepositoryException corruptedStateException(@Nullable Exception cause) {
        return new RepositoryException(
            metadata.name(),
            "Could not read repository data because the contents of the repository do not match its "
                + "expected state. This is likely the result of either concurrently modifying the contents of the "
                + "repository by a process other than this cluster or an issue with the repository's underlying storage. "
                + "The repository has been disabled to prevent corrupting its contents. To re-enable it "
                + "and continue using it please remove the repository from the cluster and add it again to make "
                + "the cluster recover the known state of the repository from its physical contents.",
            cause
        );
    }

    /**
     * Marks the repository as corrupted. This puts the repository in a state where its tracked value for
     * {@link RepositoryMetadata#pendingGeneration()} is unchanged while its value for {@link RepositoryMetadata#generation()} is set to
     * {@link RepositoryData#CORRUPTED_REPO_GEN}. In this state, the repository can not be used any longer and must be removed and
     * recreated after the problem that lead to it being marked as corrupted has been fixed.
     *
     * @param corruptedGeneration generation that failed to load because the index file was not found but that should have loaded
     * @param originalException   exception that lead to the failing to load the {@code index-N} blob
     * @param listener            listener to invoke once done
     */
    private void markRepoCorrupted(long corruptedGeneration, Exception originalException, ActionListener<Void> listener) {
        assert corruptedGeneration != RepositoryData.UNKNOWN_REPO_GEN;
        assert bestEffortConsistency == false;
        clusterService.submitStateUpdateTask(
            "mark repository corrupted [" + metadata.name() + "][" + corruptedGeneration + "]",
            new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    final RepositoriesMetadata state = currentState.metadata().custom(RepositoriesMetadata.TYPE);
                    final RepositoryMetadata repoState = state.repository(metadata.name());
                    if (repoState.generation() != corruptedGeneration) {
                        throw new IllegalStateException(
                            "Tried to mark repo generation ["
                                + corruptedGeneration
                                + "] as corrupted but its state concurrently changed to ["
                                + repoState
                                + "]"
                        );
                    }
                    return ClusterState.builder(currentState)
                        .metadata(
                            Metadata.builder(currentState.metadata())
                                .putCustom(
                                    RepositoriesMetadata.TYPE,
                                    state.withUpdatedGeneration(
                                        metadata.name(),
                                        RepositoryData.CORRUPTED_REPO_GEN,
                                        repoState.pendingGeneration()
                                    )
                                )
                                .build()
                        )
                        .build();
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(
                        new RepositoryException(
                            metadata.name(),
                            "Failed marking repository state as corrupted",
                            ExceptionsHelper.useOrSuppress(e, originalException)
                        )
                    );
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    listener.onResponse(null);
                }
            }
        );
    }

    private RepositoryData getRepositoryData(long indexGen) {
        if (indexGen == RepositoryData.EMPTY_REPO_GEN) {
            return RepositoryData.EMPTY;
        }
        try {
            final String snapshotsIndexBlobName = INDEX_FILE_PREFIX + indexGen;

            // EMPTY is safe here because RepositoryData#fromXContent calls namedObject
            try (
                InputStream blob = blobContainer().readBlob(snapshotsIndexBlobName);
                XContentParser parser = MediaTypeRegistry.JSON.xContent()
                    .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, blob)
            ) {
                return RepositoryData.snapshotsFromXContent(parser, indexGen);
            }
        } catch (IOException ioe) {
            if (bestEffortConsistency) {
                // If we fail to load the generation we tracked in latestKnownRepoGen we reset it.
                // This is done as a fail-safe in case a user manually deletes the contents of the repository in which case subsequent
                // operations must start from the EMPTY_REPO_GEN again
                if (latestKnownRepoGen.compareAndSet(indexGen, RepositoryData.EMPTY_REPO_GEN)) {
                    logger.warn("Resetting repository generation tracker because we failed to read generation [" + indexGen + "]", ioe);
                }
            }
            throw new RepositoryException(metadata.name(), "could not read repository data from index blob", ioe);
        }
    }

    private static String testBlobPrefix(String seed) {
        return TESTS_FILE + seed;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Writing a new index generation is a three step process.
     * First, the {@link RepositoryMetadata} entry for this repository is set into a pending state by incrementing its
     * pending generation {@code P} while its safe generation {@code N} remains unchanged.
     * Second, the updated {@link RepositoryData} is written to generation {@code P + 1}.
     * Lastly, the {@link RepositoryMetadata} entry for this repository is updated to the new generation {@code P + 1} and thus
     * pending and safe generation are set to the same value marking the end of the update of the repository data.
     *
     * @param repositoryData RepositoryData to write
     * @param expectedGen    expected repository generation at the start of the operation
     * @param version        version of the repository metadata to write
     * @param stateFilter    filter for the last cluster state update executed by this method
     * @param listener       completion listener
     */
    protected void writeIndexGen(
        RepositoryData repositoryData,
        long expectedGen,
        Version version,
        Function<ClusterState, ClusterState> stateFilter,
        ActionListener<RepositoryData> listener
    ) {
        assert isReadOnly() == false; // can not write to a read only repository
        final long currentGen = repositoryData.getGenId();
        if (currentGen != expectedGen) {
            // the index file was updated by a concurrent operation, so we were operating on stale
            // repository data
            listener.onFailure(
                new RepositoryException(
                    metadata.name(),
                    "concurrent modification of the index-N file, expected current generation ["
                        + expectedGen
                        + "], actual current generation ["
                        + currentGen
                        + "]"
                )
            );
            return;
        }

        // Step 1: Set repository generation state to the next possible pending generation
        final StepListener<Long> setPendingStep = new StepListener<>();
        clusterService.submitStateUpdateTask(
            "set pending repository generation [" + metadata.name() + "][" + expectedGen + "]",
            new ClusterStateUpdateTask() {

                private long newGen;

                @Override
                public ClusterState execute(ClusterState currentState) {
                    final RepositoryMetadata meta = getRepoMetadata(currentState);
                    final String repoName = metadata.name();
                    final long genInState = meta.generation();
                    final boolean uninitializedMeta = meta.generation() == RepositoryData.UNKNOWN_REPO_GEN || bestEffortConsistency;
                    if (uninitializedMeta == false && meta.pendingGeneration() != genInState) {
                        logger.info(
                            "Trying to write new repository data over unfinished write, repo [{}] is at "
                                + "safe generation [{}] and pending generation [{}]",
                            meta.name(),
                            genInState,
                            meta.pendingGeneration()
                        );
                    }
                    assert expectedGen == RepositoryData.EMPTY_REPO_GEN || uninitializedMeta || expectedGen == meta.generation()
                        : "Expected non-empty generation [" + expectedGen + "] does not match generation tracked in [" + meta + "]";
                    // If we run into the empty repo generation for the expected gen, the repo is assumed to have been cleared of
                    // all contents by an external process so we reset the safe generation to the empty generation.
                    final long safeGeneration = expectedGen == RepositoryData.EMPTY_REPO_GEN
                        ? RepositoryData.EMPTY_REPO_GEN
                        : (uninitializedMeta ? expectedGen : genInState);
                    // Regardless of whether or not the safe generation has been reset, the pending generation always increments so that
                    // even if a repository has been manually cleared of all contents we will never reuse the same repository generation.
                    // This is motivated by the consistency behavior the S3 based blob repository implementation has to support which does
                    // not offer any consistency guarantees when it comes to overwriting the same blob name with different content.
                    final long nextPendingGen = metadata.pendingGeneration() + 1;
                    newGen = uninitializedMeta ? Math.max(expectedGen + 1, nextPendingGen) : nextPendingGen;
                    assert newGen > latestKnownRepoGen.get() : "Attempted new generation ["
                        + newGen
                        + "] must be larger than latest known generation ["
                        + latestKnownRepoGen.get()
                        + "]";
                    return ClusterState.builder(currentState)
                        .metadata(
                            Metadata.builder(currentState.getMetadata())
                                .putCustom(
                                    RepositoriesMetadata.TYPE,
                                    currentState.metadata()
                                        .<RepositoriesMetadata>custom(RepositoriesMetadata.TYPE)
                                        .withUpdatedGeneration(repoName, safeGeneration, newGen)
                                )
                                .build()
                        )
                        .build();
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(
                        new RepositoryException(metadata.name(), "Failed to execute cluster state update [" + source + "]", e)
                    );
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    setPendingStep.onResponse(newGen);
                }
            }
        );

        final StepListener<RepositoryData> filterRepositoryDataStep = new StepListener<>();

        // Step 2: Write new index-N blob to repository and update index.latest
        setPendingStep.whenComplete(newGen -> threadPool().executor(ThreadPool.Names.SNAPSHOT).execute(ActionRunnable.wrap(listener, l -> {
            // BwC logic: Load snapshot version information if any snapshot is missing a version in RepositoryData so that the new
            // RepositoryData contains a version for every snapshot
            final List<SnapshotId> snapshotIdsWithoutVersion = repositoryData.getSnapshotIds()
                .stream()
                .filter(snapshotId -> repositoryData.getVersion(snapshotId) == null)
                .collect(Collectors.toList());
            if (snapshotIdsWithoutVersion.isEmpty() == false) {
                final Map<SnapshotId, Version> updatedVersionMap = new ConcurrentHashMap<>();
                final GroupedActionListener<Void> loadAllVersionsListener = new GroupedActionListener<>(
                    ActionListener.runAfter(new ActionListener<Collection<Void>>() {
                        @Override
                        public void onResponse(Collection<Void> voids) {
                            logger.info(
                                "Successfully loaded all snapshot's version information for {} from snapshot metadata",
                                AllocationService.firstListElementsToCommaDelimitedString(
                                    snapshotIdsWithoutVersion,
                                    SnapshotId::toString,
                                    logger.isDebugEnabled()
                                )
                            );
                        }

                        @Override
                        public void onFailure(Exception e) {
                            logger.warn("Failure when trying to load missing version information from snapshot metadata", e);
                        }
                    }, () -> filterRepositoryDataStep.onResponse(repositoryData.withVersions(updatedVersionMap))),
                    snapshotIdsWithoutVersion.size()
                );
                for (SnapshotId snapshotId : snapshotIdsWithoutVersion) {
                    threadPool().executor(ThreadPool.Names.SNAPSHOT)
                        .execute(
                            ActionRunnable.run(
                                loadAllVersionsListener,
                                () -> updatedVersionMap.put(snapshotId, getSnapshotInfo(snapshotId).version())
                            )
                        );
                }
            } else {
                filterRepositoryDataStep.onResponse(repositoryData);
            }
        })), listener::onFailure);
        filterRepositoryDataStep.whenComplete(filteredRepositoryData -> {
            final long newGen = setPendingStep.result();
            final RepositoryData newRepositoryData = filteredRepositoryData.withGenId(newGen);
            if (latestKnownRepoGen.get() >= newGen) {
                throw new IllegalArgumentException(
                    "Tried writing generation ["
                        + newGen
                        + "] but repository is at least at generation ["
                        + latestKnownRepoGen.get()
                        + "] already"
                );
            }
            // write the index file
            if (ensureSafeGenerationExists(expectedGen, listener::onFailure) == false) {
                return;
            }
            final String indexBlob = INDEX_FILE_PREFIX + Long.toString(newGen);
            logger.debug("Repository [{}] writing new index generational blob [{}]", metadata.name(), indexBlob);
            final BytesReference serializedRepoData = BytesReference.bytes(
                newRepositoryData.snapshotsToXContent(XContentFactory.jsonBuilder(), version)
            );
            writeAtomic(blobContainer(), indexBlob, serializedRepoData, true);
            maybeWriteIndexLatest(newGen);

            // Step 3: Update CS to reflect new repository generation.
            clusterService.submitStateUpdateTask(
                "set safe repository generation [" + metadata.name() + "][" + newGen + "]",
                new ClusterStateUpdateTask() {
                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        final RepositoryMetadata meta = getRepoMetadata(currentState);
                        if (meta.generation() != expectedGen) {
                            throw new IllegalStateException(
                                "Tried to update repo generation to [" + newGen + "] but saw unexpected generation in state [" + meta + "]"
                            );
                        }
                        if (meta.pendingGeneration() != newGen) {
                            throw new IllegalStateException(
                                "Tried to update from unexpected pending repo generation ["
                                    + meta.pendingGeneration()
                                    + "] after write to generation ["
                                    + newGen
                                    + "]"
                            );
                        }
                        return updateRepositoryGenerationsIfNecessary(
                            stateFilter.apply(
                                ClusterState.builder(currentState)
                                    .metadata(
                                        Metadata.builder(currentState.getMetadata())
                                            .putCustom(
                                                RepositoriesMetadata.TYPE,
                                                currentState.metadata()
                                                    .<RepositoriesMetadata>custom(RepositoriesMetadata.TYPE)
                                                    .withUpdatedGeneration(metadata.name(), newGen, newGen)
                                            )
                                    )
                                    .build()
                            ),
                            expectedGen,
                            newGen
                        );
                    }

                    @Override
                    public void onFailure(String source, Exception e) {
                        listener.onFailure(
                            new RepositoryException(metadata.name(), "Failed to execute cluster state update [" + source + "]", e)
                        );
                    }

                    @Override
                    public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                        cacheRepositoryData(serializedRepoData, newGen);
                        threadPool.executor(ThreadPool.Names.SNAPSHOT).execute(ActionRunnable.supply(listener, () -> {
                            // Delete all now outdated index files up to 1000 blobs back from the new generation.
                            // If there are more than 1000 dangling index-N cleanup functionality on repo delete will take care of them.
                            // Deleting one older than the current expectedGen is done for BwC reasons as older versions used to keep
                            // two index-N blobs around.
                            final List<String> oldIndexN = LongStream.range(Math.max(Math.max(expectedGen - 1, 0), newGen - 1000), newGen)
                                .mapToObj(gen -> INDEX_FILE_PREFIX + gen)
                                .collect(Collectors.toList());
                            try {
                                deleteFromContainer(blobContainer(), oldIndexN);
                            } catch (IOException e) {
                                logger.warn(() -> new ParameterizedMessage("Failed to clean up old index blobs {}", oldIndexN), e);
                            }
                            return newRepositoryData;
                        }));
                    }
                }
            );
        }, listener::onFailure);
    }

    /**
     * Write {@code index.latest} blob to support using this repository as the basis of a url repository.
     *
     * @param newGen new repository generation
     */
    private void maybeWriteIndexLatest(long newGen) {
        if (supportURLRepo) {
            logger.debug("Repository [{}] updating index.latest with generation [{}]", metadata.name(), newGen);
            try {
                writeAtomic(blobContainer(), INDEX_LATEST_BLOB, new BytesArray(Numbers.longToBytes(newGen)), false);
            } catch (Exception e) {
                logger.warn(
                    () -> new ParameterizedMessage(
                        "Failed to write index.latest blob. If you do not intend to use this "
                            + "repository as the basis for a URL repository you may turn off attempting to write the index.latest blob by "
                            + "setting repository setting [{}] to [false]",
                        SUPPORT_URL_REPO.getKey()
                    ),
                    e
                );
            }
        }
    }

    /**
     * Ensures that {@link RepositoryData} for the given {@code safeGeneration} actually physically exists in the repository.
     * This method is used by {@link #writeIndexGen} to make sure that no writes are executed on top of a concurrently modified repository.
     * This check is necessary because {@link RepositoryData} is mostly read from the cached value in {@link #latestKnownRepositoryData}
     * which could be stale in the broken situation of a concurrent write to the repository.
     *
     * @param safeGeneration generation to verify existence for
     * @param onFailure      callback to invoke with failure in case the repository generation is not physically found in the repository
     */
    private boolean ensureSafeGenerationExists(long safeGeneration, Consumer<Exception> onFailure) throws IOException {
        logger.debug("Ensure generation [{}] that is the basis for this write exists in [{}]", safeGeneration, metadata.name());
        if (safeGeneration != RepositoryData.EMPTY_REPO_GEN && blobContainer().blobExists(INDEX_FILE_PREFIX + safeGeneration) == false) {
            final Exception exception = new RepositoryException(
                metadata.name(),
                "concurrent modification of the index-N file, expected current generation ["
                    + safeGeneration
                    + "] but it was not found in the repository"
            );
            markRepoCorrupted(safeGeneration, exception, new ActionListener<Void>() {
                @Override
                public void onResponse(Void aVoid) {
                    onFailure.accept(exception);
                }

                @Override
                public void onFailure(Exception e) {
                    onFailure.accept(e);
                }
            });
            return false;
        }
        return true;
    }

    /**
     * Updates the repository generation that running deletes and snapshot finalizations will be based on for this repository if any such
     * operations are found in the cluster state while setting the safe repository generation.
     *
     * @param state  cluster state to update
     * @param oldGen previous safe repository generation
     * @param newGen new safe repository generation
     * @return updated cluster state
     */
    private ClusterState updateRepositoryGenerationsIfNecessary(ClusterState state, long oldGen, long newGen) {
        final String repoName = metadata.name();
        final SnapshotsInProgress updatedSnapshotsInProgress;
        boolean changedSnapshots = false;
        final List<SnapshotsInProgress.Entry> snapshotEntries = new ArrayList<>();
        for (SnapshotsInProgress.Entry entry : state.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY).entries()) {
            if (entry.repository().equals(repoName) && entry.repositoryStateId() == oldGen) {
                snapshotEntries.add(entry.withRepoGen(newGen));
                changedSnapshots = true;
            } else {
                snapshotEntries.add(entry);
            }
        }
        updatedSnapshotsInProgress = changedSnapshots ? SnapshotsInProgress.of(snapshotEntries) : null;
        final SnapshotDeletionsInProgress updatedDeletionsInProgress;
        boolean changedDeletions = false;
        final List<SnapshotDeletionsInProgress.Entry> deletionEntries = new ArrayList<>();
        for (SnapshotDeletionsInProgress.Entry entry : state.custom(SnapshotDeletionsInProgress.TYPE, SnapshotDeletionsInProgress.EMPTY)
            .getEntries()) {
            if (entry.repository().equals(repoName) && entry.repositoryStateId() == oldGen) {
                deletionEntries.add(entry.withRepoGen(newGen));
                changedDeletions = true;
            } else {
                deletionEntries.add(entry);
            }
        }
        updatedDeletionsInProgress = changedDeletions ? SnapshotDeletionsInProgress.of(deletionEntries) : null;
        return SnapshotsService.updateWithSnapshots(state, updatedSnapshotsInProgress, updatedDeletionsInProgress);
    }

    private RepositoryMetadata getRepoMetadata(ClusterState state) {
        final RepositoryMetadata repositoryMetadata = state.getMetadata()
            .<RepositoriesMetadata>custom(RepositoriesMetadata.TYPE)
            .repository(metadata.name());
        assert repositoryMetadata != null;
        return repositoryMetadata;
    }

    /**
     * Get the latest snapshot index blob id.  Snapshot index blobs are named index-N, where N is
     * the next version number from when the index blob was written.  Each individual index-N blob is
     * only written once and never overwritten.  The highest numbered index-N blob is the latest one
     * that contains the current snapshots in the repository.
     *
     * Package private for testing
     */
    long latestIndexBlobId() throws IOException {
        try {
            // First, try listing all index-N blobs (there should only be two index-N blobs at any given
            // time in a repository if cleanup is happening properly) and pick the index-N blob with the
            // highest N value - this will be the latest index blob for the repository. Note, we do this
            // instead of directly reading the index.latest blob to get the current index-N blob because
            // index.latest is not written atomically and is not immutable - on every index-N change,
            // we first delete the old index.latest and then write the new one. If the repository is not
            // read-only, it is possible that we try deleting the index.latest blob while it is being read
            // by some other operation (such as the get snapshots operation). In some file systems, it is
            // illegal to delete a file while it is being read elsewhere (e.g. Windows). For read-only
            // repositories, we read for index.latest, both because listing blob prefixes is often unsupported
            // and because the index.latest blob will never be deleted and re-written.
            return listBlobsToGetLatestIndexId();
        } catch (UnsupportedOperationException e) {
            // If its a read-only repository, listing blobs by prefix may not be supported (e.g. a URL repository),
            // in this case, try reading the latest index generation from the index.latest blob
            try {
                return readSnapshotIndexLatestBlob();
            } catch (NoSuchFileException nsfe) {
                return RepositoryData.EMPTY_REPO_GEN;
            }
        }
    }

    // package private for testing
    long readSnapshotIndexLatestBlob() throws IOException {
        return BytesRefUtils.bytesToLong(Streams.readFully(blobContainer().readBlob(INDEX_LATEST_BLOB)).toBytesRef());
    }

    private long listBlobsToGetLatestIndexId() throws IOException {
        return latestGeneration(blobContainer().listBlobsByPrefix(INDEX_FILE_PREFIX).keySet());
    }

    private long latestGeneration(Collection<String> rootBlobs) {
        long latest = RepositoryData.EMPTY_REPO_GEN;
        for (String blobName : rootBlobs) {
            if (blobName.startsWith(INDEX_FILE_PREFIX) == false) {
                continue;
            }
            try {
                final long curr = Long.parseLong(blobName.substring(INDEX_FILE_PREFIX.length()));
                latest = Math.max(latest, curr);
            } catch (NumberFormatException nfe) {
                // the index- blob wasn't of the format index-N where N is a number,
                // no idea what this blob is but it doesn't belong in the repository!
                logger.warn("[{}] Unknown blob in the repository: {}", metadata.name(), blobName);
            }
        }
        return latest;
    }

    private void writeAtomic(BlobContainer container, final String blobName, final BytesReference bytesRef, boolean failIfAlreadyExists)
        throws IOException {
        try (InputStream stream = bytesRef.streamInput()) {
            logger.trace(() -> new ParameterizedMessage("[{}] Writing [{}] to {} atomically", metadata.name(), blobName, container.path()));
            container.writeBlobAtomic(blobName, stream, bytesRef.length(), failIfAlreadyExists);
        }
    }

    @Override
    public void snapshotRemoteStoreIndexShard(
        Store store,
        SnapshotId snapshotId,
        IndexId indexId,
        IndexCommit snapshotIndexCommit,
        String shardStateIdentifier,
        IndexShardSnapshotStatus snapshotStatus,
        long primaryTerm,
        long startTime,
        ActionListener<String> listener
    ) {
        if (isReadOnly()) {
            listener.onFailure(new RepositoryException(metadata.name(), "cannot snapshot shard on a readonly repository"));
            return;
        }
        final ShardId shardId = store.shardId();
        try {
            final String generation = snapshotStatus.generation();
            logger.info("[{}] [{}] snapshot to [{}] [{}] ...", shardId, snapshotId, metadata.name(), generation);
            final BlobContainer shardContainer = shardContainer(indexId, shardId);

            long indexTotalFileSize = 0;
            // local store is being used here to fetch the files metadata instead of remote store as currently
            // remote store is mirroring the local store.
            List<String> fileNames = new ArrayList<>(snapshotIndexCommit.getFileNames());
            Store.MetadataSnapshot commitSnapshotMetadata = store.getMetadata(snapshotIndexCommit);
            for (String fileName : fileNames) {
                indexTotalFileSize += commitSnapshotMetadata.get(fileName).length();
            }
            int indexTotalNumberOfFiles = fileNames.size();

            snapshotStatus.moveToStarted(
                startTime,
                0, // incremental File Count is zero as we are storing the data as part of remote store.
                indexTotalNumberOfFiles,
                0, // incremental File Size is zero as we are storing the data as part of remote store.
                indexTotalFileSize
            );

            final IndexShardSnapshotStatus.Copy lastSnapshotStatus = snapshotStatus.moveToFinalize(snapshotIndexCommit.getGeneration());

            // now create and write the commit point
            logger.trace("[{}] [{}] writing shard snapshot file", shardId, snapshotId);
            try {
                REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT.write(
                    new RemoteStoreShardShallowCopySnapshot(
                        snapshotId.getName(),
                        lastSnapshotStatus.getIndexVersion(),
                        primaryTerm,
                        snapshotIndexCommit.getGeneration(),
                        lastSnapshotStatus.getStartTime(),
                        threadPool.absoluteTimeInMillis() - lastSnapshotStatus.getStartTime(),
                        indexTotalNumberOfFiles,
                        indexTotalFileSize,
                        store.indexSettings().getUUID(),
                        store.indexSettings().getRemoteStoreRepository(),
                        this.basePath().toString(),
                        fileNames
                    ),
                    shardContainer,
                    snapshotId.getUUID(),
                    compressor
                );
            } catch (IOException e) {
                throw new IndexShardSnapshotFailedException(
                    shardId,
                    "Failed to write commit point for snapshot " + snapshotId.getName() + "(" + snapshotId.getUUID() + ")",
                    e
                );
            }
            snapshotStatus.moveToDone(threadPool.absoluteTimeInMillis(), generation);
            listener.onResponse(generation);

        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    @Override
    public void snapshotShard(
        Store store,
        MapperService mapperService,
        SnapshotId snapshotId,
        IndexId indexId,
        IndexCommit snapshotIndexCommit,
        String shardStateIdentifier,
        IndexShardSnapshotStatus snapshotStatus,
        Version repositoryMetaVersion,
        Map<String, Object> userMetadata,
        ActionListener<String> listener
    ) {
        if (isReadOnly()) {
            listener.onFailure(new RepositoryException(metadata.name(), "cannot snapshot shard on a readonly repository"));
            return;
        }
        final ShardId shardId = store.shardId();
        final long startTime = threadPool.absoluteTimeInMillis();
        try {
            final String generation = snapshotStatus.generation();
            logger.debug("[{}] [{}] snapshot to [{}] [{}] ...", shardId, snapshotId, metadata.name(), generation);
            final BlobContainer shardContainer = shardContainer(indexId, shardId);
            final Set<String> blobs;
            if (generation == null) {
                try {
                    blobs = shardContainer.listBlobsByPrefix(INDEX_FILE_PREFIX).keySet();
                } catch (IOException e) {
                    throw new IndexShardSnapshotFailedException(shardId, "failed to list blobs", e);
                }
            } else {
                blobs = Collections.singleton(INDEX_FILE_PREFIX + generation);
            }

            Tuple<BlobStoreIndexShardSnapshots, String> tuple = buildBlobStoreIndexShardSnapshots(blobs, shardContainer, generation);
            BlobStoreIndexShardSnapshots snapshots = tuple.v1();
            String fileListGeneration = tuple.v2();

            if (snapshots.snapshots().stream().anyMatch(sf -> sf.snapshot().equals(snapshotId.getName()))) {
                throw new IndexShardSnapshotFailedException(
                    shardId,
                    "Duplicate snapshot name [" + snapshotId.getName() + "] detected, aborting"
                );
            }
            // First inspect all known SegmentInfos instances to see if we already have an equivalent commit in the repository
            final List<BlobStoreIndexShardSnapshot.FileInfo> filesFromSegmentInfos = Optional.ofNullable(shardStateIdentifier).map(id -> {
                for (SnapshotFiles snapshotFileSet : snapshots.snapshots()) {
                    if (id.equals(snapshotFileSet.shardStateIdentifier())) {
                        return snapshotFileSet.indexFiles();
                    }
                }
                return null;
            }).orElse(null);

            final List<BlobStoreIndexShardSnapshot.FileInfo> indexCommitPointFiles;
            int indexIncrementalFileCount = 0;
            int indexTotalNumberOfFiles = 0;
            long indexIncrementalSize = 0;
            long indexTotalFileSize = 0;
            final BlockingQueue<BlobStoreIndexShardSnapshot.FileInfo> filesToSnapshot = new LinkedBlockingQueue<>();
            // If we did not find a set of files that is equal to the current commit we determine the files to upload by comparing files
            // in the commit with files already in the repository
            if (filesFromSegmentInfos == null) {
                indexCommitPointFiles = new ArrayList<>();
                final Collection<String> fileNames;
                final Store.MetadataSnapshot metadataFromStore;
                try (Releasable ignored = incrementStoreRef(store, snapshotStatus, shardId)) {
                    // TODO apparently we don't use the MetadataSnapshot#.recoveryDiff(...) here but we should
                    try {
                        logger.trace("[{}] [{}] Loading store metadata using index commit [{}]", shardId, snapshotId, snapshotIndexCommit);
                        metadataFromStore = store.getMetadata(snapshotIndexCommit);
                        fileNames = snapshotIndexCommit.getFileNames();
                    } catch (IOException e) {
                        throw new IndexShardSnapshotFailedException(shardId, "Failed to get store file metadata", e);
                    }
                }
                for (String fileName : fileNames) {
                    if (snapshotStatus.isAborted()) {
                        logger.debug("[{}] [{}] Aborted on the file [{}], exiting", shardId, snapshotId, fileName);
                        throw new AbortedSnapshotException();
                    }

                    logger.trace("[{}] [{}] Processing [{}]", shardId, snapshotId, fileName);
                    final StoreFileMetadata md = metadataFromStore.get(fileName);
                    BlobStoreIndexShardSnapshot.FileInfo existingFileInfo = null;
                    List<BlobStoreIndexShardSnapshot.FileInfo> filesInfo = snapshots.findPhysicalIndexFiles(fileName);
                    if (filesInfo != null) {
                        for (BlobStoreIndexShardSnapshot.FileInfo fileInfo : filesInfo) {
                            if (fileInfo.isSame(md)) {
                                // a commit point file with the same name, size and checksum was already copied to repository
                                // we will reuse it for this snapshot
                                existingFileInfo = fileInfo;
                                break;
                            }
                        }
                    }

                    // We can skip writing blobs where the metadata hash is equal to the blob's contents because we store the hash/contents
                    // directly in the shard level metadata in this case
                    final boolean needsWrite = md.hashEqualsContents() == false;
                    indexTotalFileSize += md.length();
                    indexTotalNumberOfFiles++;

                    if (existingFileInfo == null) {
                        indexIncrementalFileCount++;
                        indexIncrementalSize += md.length();
                        // create a new FileInfo
                        BlobStoreIndexShardSnapshot.FileInfo snapshotFileInfo = new BlobStoreIndexShardSnapshot.FileInfo(
                            (needsWrite ? UPLOADED_DATA_BLOB_PREFIX : VIRTUAL_DATA_BLOB_PREFIX) + UUIDs.randomBase64UUID(),
                            md,
                            chunkSize()
                        );
                        indexCommitPointFiles.add(snapshotFileInfo);
                        if (needsWrite) {
                            filesToSnapshot.add(snapshotFileInfo);
                        }
                        assert needsWrite || assertFileContentsMatchHash(snapshotFileInfo, store);
                    } else {
                        indexCommitPointFiles.add(existingFileInfo);
                    }
                }
            } else {
                for (BlobStoreIndexShardSnapshot.FileInfo fileInfo : filesFromSegmentInfos) {
                    indexTotalNumberOfFiles++;
                    indexTotalFileSize += fileInfo.length();
                }
                indexCommitPointFiles = filesFromSegmentInfos;
            }

            snapshotStatus.moveToStarted(
                startTime,
                indexIncrementalFileCount,
                indexTotalNumberOfFiles,
                indexIncrementalSize,
                indexTotalFileSize
            );

            final String indexGeneration;
            // build a new BlobStoreIndexShardSnapshot, that includes this one and all the saved ones
            List<SnapshotFiles> newSnapshotsList = new ArrayList<>();
            newSnapshotsList.add(new SnapshotFiles(snapshotId.getName(), indexCommitPointFiles, shardStateIdentifier));
            for (SnapshotFiles point : snapshots) {
                newSnapshotsList.add(point);
            }
            final BlobStoreIndexShardSnapshots updatedBlobStoreIndexShardSnapshots = new BlobStoreIndexShardSnapshots(newSnapshotsList);
            // When using shard generations we can safely write the index-${uuid} blob before writing out any of the actual data
            // for this shard since the uuid named blob will simply not be referenced in case of error and thus we will never
            // reference a generation that has not had all its files fully upload.
            indexGeneration = UUIDs.randomBase64UUID();
            try {
                INDEX_SHARD_SNAPSHOTS_FORMAT.write(updatedBlobStoreIndexShardSnapshots, shardContainer, indexGeneration, compressor);
            } catch (IOException e) {
                throw new IndexShardSnapshotFailedException(
                    shardId,
                    "Failed to write shard level snapshot metadata for ["
                        + snapshotId
                        + "] to ["
                        + INDEX_SHARD_SNAPSHOTS_FORMAT.blobName(indexGeneration)
                        + "]",
                    e
                );
            }
            final StepListener<Collection<Void>> allFilesUploadedListener = new StepListener<>();
            allFilesUploadedListener.whenComplete(v -> {
                final IndexShardSnapshotStatus.Copy lastSnapshotStatus = snapshotStatus.moveToFinalize(snapshotIndexCommit.getGeneration());

                // now create and write the commit point
                logger.trace("[{}] [{}] writing shard snapshot file", shardId, snapshotId);
                try {
                    INDEX_SHARD_SNAPSHOT_FORMAT.write(
                        new BlobStoreIndexShardSnapshot(
                            snapshotId.getName(),
                            lastSnapshotStatus.getIndexVersion(),
                            indexCommitPointFiles,
                            lastSnapshotStatus.getStartTime(),
                            threadPool.absoluteTimeInMillis() - lastSnapshotStatus.getStartTime(),
                            lastSnapshotStatus.getIncrementalFileCount(),
                            lastSnapshotStatus.getIncrementalSize()
                        ),
                        shardContainer,
                        snapshotId.getUUID(),
                        compressor
                    );
                } catch (IOException e) {
                    throw new IndexShardSnapshotFailedException(shardId, "Failed to write commit point", e);
                }
                snapshotStatus.moveToDone(threadPool.absoluteTimeInMillis(), indexGeneration);
                listener.onResponse(indexGeneration);
            }, listener::onFailure);
            if (indexIncrementalFileCount == 0) {
                allFilesUploadedListener.onResponse(Collections.emptyList());
                return;
            }
            final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
            // Start as many workers as fit into the snapshot pool at once at the most
            final int workers = Math.min(threadPool.info(ThreadPool.Names.SNAPSHOT).getMax(), indexIncrementalFileCount);
            final ActionListener<Void> filesListener = fileQueueListener(filesToSnapshot, workers, allFilesUploadedListener);
            for (int i = 0; i < workers; ++i) {
                executeOneFileSnapshot(store, snapshotId, indexId, snapshotStatus, filesToSnapshot, executor, filesListener);
            }
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private void executeOneFileSnapshot(
        Store store,
        SnapshotId snapshotId,
        IndexId indexId,
        IndexShardSnapshotStatus snapshotStatus,
        BlockingQueue<BlobStoreIndexShardSnapshot.FileInfo> filesToSnapshot,
        Executor executor,
        ActionListener<Void> listener
    ) throws InterruptedException {
        final ShardId shardId = store.shardId();
        final BlobStoreIndexShardSnapshot.FileInfo snapshotFileInfo = filesToSnapshot.poll(0L, TimeUnit.MILLISECONDS);
        if (snapshotFileInfo == null) {
            listener.onResponse(null);
        } else {
            executor.execute(ActionRunnable.wrap(listener, l -> {
                try (Releasable ignored = incrementStoreRef(store, snapshotStatus, shardId)) {
                    snapshotFile(snapshotFileInfo, indexId, shardId, snapshotId, snapshotStatus, store);
                    executeOneFileSnapshot(store, snapshotId, indexId, snapshotStatus, filesToSnapshot, executor, l);
                }
            }));
        }
    }

    private static Releasable incrementStoreRef(Store store, IndexShardSnapshotStatus snapshotStatus, ShardId shardId) {
        if (store.tryIncRef() == false) {
            if (snapshotStatus.isAborted()) {
                throw new AbortedSnapshotException();
            } else {
                assert false : "Store should not be closed concurrently unless snapshot is aborted";
                throw new IndexShardSnapshotFailedException(shardId, "Store got closed concurrently");
            }
        }
        return store::decRef;
    }

    private static boolean assertFileContentsMatchHash(BlobStoreIndexShardSnapshot.FileInfo fileInfo, Store store) {
        try (IndexInput indexInput = store.openVerifyingInput(fileInfo.physicalName(), IOContext.READONCE, fileInfo.metadata())) {
            final byte[] tmp = new byte[Math.toIntExact(fileInfo.metadata().length())];
            indexInput.readBytes(tmp, 0, tmp.length);
            assert fileInfo.metadata().hash().bytesEquals(new BytesRef(tmp));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return true;
    }

    @Override
    public void restoreShard(
        Store store,
        SnapshotId snapshotId,
        IndexId indexId,
        ShardId snapshotShardId,
        RecoveryState recoveryState,
        ActionListener<Void> listener
    ) {
        final ShardId shardId = store.shardId();
        final ActionListener<Void> restoreListener = ActionListener.delegateResponse(
            listener,
            (l, e) -> l.onFailure(new IndexShardRestoreFailedException(shardId, "failed to restore snapshot [" + snapshotId + "]", e))
        );
        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
        final BlobContainer container = shardContainer(indexId, snapshotShardId);
        executor.execute(ActionRunnable.wrap(restoreListener, l -> {
            IndexShardSnapshot indexShardSnapshot = loadShardSnapshot(container, snapshotId);
            assert indexShardSnapshot instanceof BlobStoreIndexShardSnapshot
                : "indexShardSnapshot should be an instance of BlobStoreIndexShardSnapshot";
            final BlobStoreIndexShardSnapshot snapshot = (BlobStoreIndexShardSnapshot) indexShardSnapshot;
            final SnapshotFiles snapshotFiles = new SnapshotFiles(snapshot.snapshot(), snapshot.indexFiles(), null);
            new FileRestoreContext(metadata.name(), shardId, snapshotId, recoveryState) {
                @Override
                protected void restoreFiles(
                    List<BlobStoreIndexShardSnapshot.FileInfo> filesToRecover,
                    Store store,
                    ActionListener<Void> listener
                ) {
                    if (filesToRecover.isEmpty()) {
                        listener.onResponse(null);
                    } else {
                        // Start as many workers as fit into the snapshot pool at once at the most
                        final int workers = Math.min(
                            threadPool.info(ThreadPool.Names.SNAPSHOT).getMax(),
                            snapshotFiles.indexFiles().size()
                        );
                        final BlockingQueue<BlobStoreIndexShardSnapshot.FileInfo> files = new LinkedBlockingQueue<>(filesToRecover);
                        final ActionListener<Void> allFilesListener = fileQueueListener(
                            files,
                            workers,
                            ActionListener.map(listener, v -> null)
                        );
                        // restore the files from the snapshot to the Lucene store
                        for (int i = 0; i < workers; ++i) {
                            try {
                                executeOneFileRestore(files, allFilesListener);
                            } catch (Exception e) {
                                allFilesListener.onFailure(e);
                            }
                        }
                    }
                }

                private void executeOneFileRestore(
                    BlockingQueue<BlobStoreIndexShardSnapshot.FileInfo> files,
                    ActionListener<Void> allFilesListener
                ) throws InterruptedException {
                    final BlobStoreIndexShardSnapshot.FileInfo fileToRecover = files.poll(0L, TimeUnit.MILLISECONDS);
                    if (fileToRecover == null) {
                        allFilesListener.onResponse(null);
                    } else {
                        executor.execute(ActionRunnable.wrap(allFilesListener, filesListener -> {
                            store.incRef();
                            try {
                                restoreFile(fileToRecover, store);
                            } finally {
                                store.decRef();
                            }
                            executeOneFileRestore(files, filesListener);
                        }));
                    }
                }

                private void restoreFile(BlobStoreIndexShardSnapshot.FileInfo fileInfo, Store store) throws IOException {
                    ensureNotClosing(store);
                    logger.trace(() -> new ParameterizedMessage("[{}] restoring [{}] to [{}]", metadata.name(), fileInfo, store));
                    boolean success = false;
                    try (
                        IndexOutput indexOutput = store.createVerifyingOutput(
                            fileInfo.physicalName(),
                            fileInfo.metadata(),
                            IOContext.DEFAULT
                        )
                    ) {
                        if (fileInfo.name().startsWith(VIRTUAL_DATA_BLOB_PREFIX)) {
                            final BytesRef hash = fileInfo.metadata().hash();
                            indexOutput.writeBytes(hash.bytes, hash.offset, hash.length);
                            recoveryState.getIndex().addRecoveredBytesToFile(fileInfo.physicalName(), hash.length);
                        } else {
                            try (InputStream stream = maybeRateLimitRestores(new SlicedInputStream(fileInfo.numberOfParts()) {
                                @Override
                                protected InputStream openSlice(int slice) throws IOException {
                                    ensureNotClosing(store);
                                    return container.readBlob(fileInfo.partName(slice));
                                }
                            })) {
                                final byte[] buffer = new byte[Math.toIntExact(Math.min(bufferSize, fileInfo.length()))];
                                int length;
                                while ((length = stream.read(buffer)) > 0) {
                                    ensureNotClosing(store);
                                    indexOutput.writeBytes(buffer, 0, length);
                                    recoveryState.getIndex().addRecoveredBytesToFile(fileInfo.physicalName(), length);
                                }
                            }
                        }
                        Store.verify(indexOutput);
                        indexOutput.close();
                        store.directory().sync(Collections.singleton(fileInfo.physicalName()));
                        success = true;
                    } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException ex) {
                        try {
                            store.markStoreCorrupted(ex);
                        } catch (IOException e) {
                            logger.warn("store cannot be marked as corrupted", e);
                        }
                        throw ex;
                    } finally {
                        if (success == false) {
                            store.deleteQuiet(fileInfo.physicalName());
                        }
                    }
                }

                void ensureNotClosing(final Store store) throws AlreadyClosedException {
                    assert store.refCount() > 0;
                    if (store.isClosing()) {
                        throw new AlreadyClosedException("store is closing");
                    }
                }

            }.restore(snapshotFiles, store, l);
        }));
    }

    private static ActionListener<Void> fileQueueListener(
        BlockingQueue<BlobStoreIndexShardSnapshot.FileInfo> files,
        int workers,
        ActionListener<Collection<Void>> listener
    ) {
        return ActionListener.delegateResponse(new GroupedActionListener<>(listener, workers), (l, e) -> {
            files.clear(); // Stop uploading the remaining files if we run into any exception
            l.onFailure(e);
        });
    }

    private static InputStream maybeRateLimit(InputStream stream, Supplier<RateLimiter> rateLimiterSupplier, CounterMetric metric) {
        return new RateLimitingInputStream(stream, rateLimiterSupplier, metric::inc);
    }

    public InputStream maybeRateLimitRestores(InputStream stream) {
        return maybeRateLimit(
            maybeRateLimit(stream, () -> restoreRateLimiter, restoreRateLimitingTimeInNanos),
            recoverySettings::rateLimiter,
            restoreRateLimitingTimeInNanos
        );
    }

    public InputStream maybeRateLimitSnapshots(InputStream stream) {
        return maybeRateLimit(stream, () -> snapshotRateLimiter, snapshotRateLimitingTimeInNanos);
    }

    @Override
    public RemoteStoreShardShallowCopySnapshot getRemoteStoreShallowCopyShardMetadata(
        SnapshotId snapshotId,
        IndexId indexId,
        ShardId snapshotShardId
    ) {
        final BlobContainer container = shardContainer(indexId, snapshotShardId);
        IndexShardSnapshot indexShardSnapshot = loadShardSnapshot(container, snapshotId);
        assert indexShardSnapshot instanceof RemoteStoreShardShallowCopySnapshot
            : "indexShardSnapshot should be an instance of RemoteStoreShardShallowCopySnapshot";
        return (RemoteStoreShardShallowCopySnapshot) indexShardSnapshot;
    }

    @Override
    public IndexShardSnapshotStatus getShardSnapshotStatus(SnapshotId snapshotId, IndexId indexId, ShardId shardId) {
        IndexShardSnapshot snapshot = loadShardSnapshot(shardContainer(indexId, shardId), snapshotId);
        return snapshot.getIndexShardSnapshotStatus();
    }

    @Override
    public void verify(String seed, DiscoveryNode localNode) {
        assertSnapshotOrGenericThread();
        if (isReadOnly()) {
            try {
                latestIndexBlobId();
            } catch (Exception e) {
                throw new RepositoryVerificationException(
                    metadata.name(),
                    "path " + basePath() + " is not accessible on node " + localNode,
                    e
                );
            }
        } else {
            BlobContainer testBlobContainer = blobStore().blobContainer(basePath().add(testBlobPrefix(seed)));
            try {
                BytesArray bytes = new BytesArray(seed);
                try (InputStream stream = bytes.streamInput()) {
                    testBlobContainer.writeBlob("data-" + localNode.getId() + ".dat", stream, bytes.length(), true);
                }
            } catch (Exception exp) {
                throw new RepositoryVerificationException(
                    metadata.name(),
                    "store location [" + blobStore() + "] is not accessible on the node [" + localNode + "]",
                    exp
                );
            }
            try (InputStream masterDat = testBlobContainer.readBlob("master.dat")) {
                final String seedRead = Streams.readFully(masterDat).utf8ToString();
                if (seedRead.equals(seed) == false) {
                    throw new RepositoryVerificationException(
                        metadata.name(),
                        "Seed read from master.dat was [" + seedRead + "] but expected seed [" + seed + "]"
                    );
                }
            } catch (NoSuchFileException e) {
                throw new RepositoryVerificationException(
                    metadata.name(),
                    "a file written by cluster-manager to the store ["
                        + blobStore()
                        + "] cannot be accessed on the node ["
                        + localNode
                        + "]. "
                        + "This might indicate that the store ["
                        + blobStore()
                        + "] is not shared between this node and the cluster-manager node or "
                        + "that permissions on the store don't allow reading files written by the cluster-manager node",
                    e
                );
            } catch (Exception e) {
                throw new RepositoryVerificationException(metadata.name(), "Failed to verify repository", e);
            }
        }
    }

    @Override
    public String toString() {
        return "BlobStoreRepository[" + "[" + metadata.name() + "], [" + blobStore.get() + ']' + ']';
    }

    /**
     * Delete snapshot from shard level metadata.
     *
     * @param indexGeneration generation to write the new shard level level metadata to. If negative a uuid id shard generation should be
     *                        used
     */
    private ShardSnapshotMetaDeleteResult deleteFromShardSnapshotMeta(
        Set<SnapshotId> survivingSnapshots,
        IndexId indexId,
        int snapshotShardId,
        Collection<SnapshotId> snapshotIds,
        BlobContainer shardContainer,
        Set<String> blobs,
        BlobStoreIndexShardSnapshots snapshots,
        long indexGeneration,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory
    ) {
        // Build a list of snapshots that should be preserved
        List<SnapshotFiles> newSnapshotsList = new ArrayList<>();
        final Set<String> survivingSnapshotNames = survivingSnapshots.stream().map(SnapshotId::getName).collect(Collectors.toSet());
        for (SnapshotFiles point : snapshots) {
            if (survivingSnapshotNames.contains(point.snapshot())) {
                newSnapshotsList.add(point);
            }
        }
        String writtenGeneration = null;
        try {
            // Using survivingSnapshots instead of newSnapshotsList as shallow snapshots can be present which won't be part of
            // newSnapshotsList
            if (survivingSnapshots.isEmpty()) {
                // No shallow copy or full copy snapshot is surviving.
                return new ShardSnapshotMetaDeleteResult(indexId, snapshotShardId, ShardGenerations.DELETED_SHARD_GEN, blobs);
            } else {
                final BlobStoreIndexShardSnapshots updatedSnapshots;
                // If we have surviving non shallow snapshots, update index- file.
                if (newSnapshotsList.size() > 0) {
                    // Some full copy snapshots are surviving.
                    updatedSnapshots = new BlobStoreIndexShardSnapshots(newSnapshotsList);
                    if (indexGeneration < 0L) {
                        writtenGeneration = UUIDs.randomBase64UUID();
                        INDEX_SHARD_SNAPSHOTS_FORMAT.write(updatedSnapshots, shardContainer, writtenGeneration, compressor);
                    } else {
                        writtenGeneration = String.valueOf(indexGeneration);
                        writeShardIndexBlobAtomic(shardContainer, indexGeneration, updatedSnapshots);
                    }
                } else {
                    // Some shallow copy snapshots are surviving. In this case, since no full copy snapshots are present, we use
                    // EMPTY BlobStoreIndexShardSnapshots for updatedSnapshots which is used in unusedBlobs to compute stale files,
                    // and use DELETED_SHARD_GEN since index-N file would not be present anymore.
                    updatedSnapshots = BlobStoreIndexShardSnapshots.EMPTY;
                    writtenGeneration = ShardGenerations.DELETED_SHARD_GEN;
                }
                final Set<String> survivingSnapshotUUIDs = survivingSnapshots.stream().map(SnapshotId::getUUID).collect(Collectors.toSet());
                return new ShardSnapshotMetaDeleteResult(
                    indexId,
                    snapshotShardId,
                    writtenGeneration,
                    unusedBlobs(blobs, survivingSnapshotUUIDs, updatedSnapshots, remoteStoreLockManagerFactory)
                );
            }
        } catch (IOException e) {
            throw new RepositoryException(
                metadata.name(),
                "Failed to finalize snapshot deletion "
                    + snapshotIds
                    + " with shard index ["
                    + INDEX_SHARD_SNAPSHOTS_FORMAT.blobName(writtenGeneration)
                    + "]",
                e
            );
        }
    }

    /**
     * Utility for atomically writing shard level metadata to a numeric shard generation. This is only required for writing
     * numeric shard generations where atomic writes with fail-if-already-exists checks are useful in preventing repository corruption.
     */
    private void writeShardIndexBlobAtomic(
        BlobContainer shardContainer,
        long indexGeneration,
        BlobStoreIndexShardSnapshots updatedSnapshots
    ) throws IOException {
        assert indexGeneration >= 0 : "Shard generation must not be negative but saw [" + indexGeneration + "]";
        logger.trace(
            () -> new ParameterizedMessage("[{}] Writing shard index [{}] to [{}]", metadata.name(), indexGeneration, shardContainer.path())
        );
        final String blobName = INDEX_SHARD_SNAPSHOTS_FORMAT.blobName(String.valueOf(indexGeneration));
        writeAtomic(shardContainer, blobName, INDEX_SHARD_SNAPSHOTS_FORMAT.serialize(updatedSnapshots, blobName, compressor), true);
    }

    // Unused blobs are all previous index-, data- and meta-blobs and that are not referenced by the new index- as well as all
    // temporary blobs. If remoteStoreLockManagerFactory is non-null, the shallow-snap- files that do not belong to any of the
    // surviving snapshots are also added for cleanup.
    private static List<String> unusedBlobs(
        Set<String> blobs,
        Set<String> survivingSnapshotUUIDs,
        BlobStoreIndexShardSnapshots updatedSnapshots,
        RemoteStoreLockManagerFactory remoteStoreLockManagerFactory
    ) {
        return blobs.stream()
            .filter(
                blob -> blob.startsWith(SNAPSHOT_INDEX_PREFIX)
                    || (blob.startsWith(SNAPSHOT_PREFIX)
                        && blob.endsWith(".dat")
                        && survivingSnapshotUUIDs.contains(
                            blob.substring(SNAPSHOT_PREFIX.length(), blob.length() - ".dat".length())
                        ) == false)
                    || (remoteStoreLockManagerFactory != null
                        ? (blob.startsWith(SHALLOW_SNAPSHOT_PREFIX)
                            && blob.endsWith(".dat")
                            && survivingSnapshotUUIDs.contains(
                                blob.substring(SHALLOW_SNAPSHOT_PREFIX.length(), blob.length() - ".dat".length())
                            ) == false)
                        : false)
                    || (blob.startsWith(UPLOADED_DATA_BLOB_PREFIX) && updatedSnapshots.findNameFile(canonicalName(blob)) == null)
                    || FsBlobContainer.isTempBlobName(blob)
            )
            .collect(Collectors.toList());
    }

    /**
     * Loads information about shard snapshot
     */
    public IndexShardSnapshot loadShardSnapshot(BlobContainer shardContainer, SnapshotId snapshotId) {
        try {
            if (shardContainer.blobExists(INDEX_SHARD_SNAPSHOT_FORMAT.blobName(snapshotId.getUUID()))) {
                return INDEX_SHARD_SNAPSHOT_FORMAT.read(shardContainer, snapshotId.getUUID(), namedXContentRegistry);
            } else if (shardContainer.blobExists(REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT.blobName(snapshotId.getUUID()))) {
                return REMOTE_STORE_SHARD_SHALLOW_COPY_SNAPSHOT_FORMAT.read(shardContainer, snapshotId.getUUID(), namedXContentRegistry);
            } else {
                throw new SnapshotMissingException(metadata.name(), snapshotId.getName());
            }
        } catch (IOException ex) {
            throw new SnapshotException(
                metadata.name(),
                snapshotId,
                "failed to read shard snapshot file for [" + shardContainer.path() + ']',
                ex
            );
        }
    }

    /**
     * Loads all available snapshots in the repository using the given {@code generation} or falling back to trying to determine it from
     * the given list of blobs in the shard container.
     *
     * @param blobs      list of blobs in repository
     * @param generation shard generation
     * @return tuple of BlobStoreIndexShardSnapshots and the last snapshot index generation
     */
    private Tuple<BlobStoreIndexShardSnapshots, String> buildBlobStoreIndexShardSnapshots(
        Set<String> blobs,
        BlobContainer shardContainer,
        @Nullable String generation
    ) throws IOException {
        if (generation != null) {
            if (generation.equals(ShardGenerations.NEW_SHARD_GEN)) {
                return new Tuple<>(BlobStoreIndexShardSnapshots.EMPTY, ShardGenerations.NEW_SHARD_GEN);
            }
            return new Tuple<>(INDEX_SHARD_SNAPSHOTS_FORMAT.read(shardContainer, generation, namedXContentRegistry), generation);
        }
        final Tuple<BlobStoreIndexShardSnapshots, Long> legacyIndex = buildBlobStoreIndexShardSnapshots(blobs, shardContainer);
        return new Tuple<>(legacyIndex.v1(), String.valueOf(legacyIndex.v2()));
    }

    /**
     * Loads all available snapshots in the repository
     *
     * @param blobs list of blobs in repository
     * @return tuple of BlobStoreIndexShardSnapshots and the last snapshot index generation
     */
    private Tuple<BlobStoreIndexShardSnapshots, Long> buildBlobStoreIndexShardSnapshots(Set<String> blobs, BlobContainer shardContainer)
        throws IOException {
        long latest = latestGeneration(blobs);
        if (latest >= 0) {
            final BlobStoreIndexShardSnapshots shardSnapshots = INDEX_SHARD_SNAPSHOTS_FORMAT.read(
                shardContainer,
                Long.toString(latest),
                namedXContentRegistry
            );
            return new Tuple<>(shardSnapshots, latest);
        } else if (blobs.stream()
            .anyMatch(b -> b.startsWith(SNAPSHOT_PREFIX) || b.startsWith(INDEX_FILE_PREFIX) || b.startsWith(UPLOADED_DATA_BLOB_PREFIX))) {
                logger.warn(
                    "Could not find a readable index-N file in a non-empty shard snapshot directory [" + shardContainer.path() + "]"
                );
            }
        return new Tuple<>(BlobStoreIndexShardSnapshots.EMPTY, latest);
    }

    /**
     * Snapshot individual file
     * @param fileInfo file to be snapshotted
     */
    private void snapshotFile(
        BlobStoreIndexShardSnapshot.FileInfo fileInfo,
        IndexId indexId,
        ShardId shardId,
        SnapshotId snapshotId,
        IndexShardSnapshotStatus snapshotStatus,
        Store store
    ) throws IOException {
        final BlobContainer shardContainer = shardContainer(indexId, shardId);
        final String file = fileInfo.physicalName();
        try (IndexInput indexInput = store.openVerifyingInput(file, IOContext.READONCE, fileInfo.metadata())) {
            for (int i = 0; i < fileInfo.numberOfParts(); i++) {
                final long partBytes = fileInfo.partBytes(i);

                // Make reads abortable by mutating the snapshotStatus object
                final InputStream inputStream = new FilterInputStream(
                    maybeRateLimitSnapshots(new InputStreamIndexInput(indexInput, partBytes))
                ) {
                    @Override
                    public int read() throws IOException {
                        checkAborted();
                        return super.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        checkAborted();
                        return super.read(b, off, len);
                    }

                    private void checkAborted() {
                        if (snapshotStatus.isAborted()) {
                            logger.debug("[{}] [{}] Aborted on the file [{}], exiting", shardId, snapshotId, fileInfo.physicalName());
                            throw new AbortedSnapshotException();
                        }
                    }
                };
                final String partName = fileInfo.partName(i);
                logger.trace(() -> new ParameterizedMessage("[{}] Writing [{}] to [{}]", metadata.name(), partName, shardContainer.path()));
                shardContainer.writeBlob(partName, inputStream, partBytes, false);
            }
            Store.verify(indexInput);
            snapshotStatus.addProcessedFile(fileInfo.length());
        } catch (Exception t) {
            failStoreIfCorrupted(store, t);
            snapshotStatus.addProcessedFile(0);
            throw t;
        }
    }

    private static void failStoreIfCorrupted(Store store, Exception e) {
        if (Lucene.isCorruptionException(e)) {
            try {
                store.markStoreCorrupted((IOException) e);
            } catch (IOException inner) {
                inner.addSuppressed(e);
                logger.warn("store cannot be marked as corrupted", inner);
            }
        }
    }

    /**
     * The result of removing a snapshot from a shard folder in the repository.
     */
    private static final class ShardSnapshotMetaDeleteResult {

        // Index that the snapshot was removed from
        private final IndexId indexId;

        // Shard id that the snapshot was removed from
        private final int shardId;

        // Id of the new index-${uuid} blob that does not include the snapshot any more
        private final String newGeneration;

        // Blob names in the shard directory that have become unreferenced in the new shard generation
        private final Collection<String> blobsToDelete;

        ShardSnapshotMetaDeleteResult(IndexId indexId, int shardId, String newGeneration, Collection<String> blobsToDelete) {
            this.indexId = indexId;
            this.shardId = shardId;
            this.newGeneration = newGeneration;
            this.blobsToDelete = blobsToDelete;
        }
    }
}
