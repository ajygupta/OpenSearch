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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.action.admin.cluster.snapshots.get;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.util.CollectionUtil;
import org.opensearch.action.ActionListener;
import org.opensearch.action.StepListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.SnapshotsInProgress;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.common.regex.Regex;
import org.opensearch.repositories.IndexId;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.RepositoryData;
import org.opensearch.snapshots.SnapshotException;
import org.opensearch.snapshots.SnapshotId;
import org.opensearch.snapshots.SnapshotInfo;
import org.opensearch.snapshots.SnapshotMissingException;
import org.opensearch.snapshots.SnapshotsService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.unmodifiableList;

/**
 * Transport Action for get snapshots operation
 *
 * @opensearch.internal
 */
public class TransportGetSnapshotsAction extends TransportClusterManagerNodeAction<GetSnapshotsRequest, GetSnapshotsResponse> {

    private static final Logger logger = LogManager.getLogger(TransportGetSnapshotsAction.class);

    private final RepositoriesService repositoriesService;

    @Inject
    public TransportGetSnapshotsAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        RepositoriesService repositoriesService,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            GetSnapshotsAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            GetSnapshotsRequest::new,
            indexNameExpressionResolver
        );
        this.repositoriesService = repositoriesService;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.GENERIC;
    }

    @Override
    protected GetSnapshotsResponse read(StreamInput in) throws IOException {
        return new GetSnapshotsResponse(in);
    }

    @Override
    protected ClusterBlockException checkBlock(GetSnapshotsRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }

    @Override
    protected void clusterManagerOperation(
        final GetSnapshotsRequest request,
        final ClusterState state,
        final ActionListener<GetSnapshotsResponse> listener
    ) {
        try {
            final String repository = request.repository();
            final SnapshotsInProgress snapshotsInProgress = state.custom(SnapshotsInProgress.TYPE);
            final Map<String, SnapshotId> allSnapshotIds = new HashMap<>();
            final List<SnapshotInfo> currentSnapshots = new ArrayList<>();
            for (SnapshotInfo snapshotInfo : sortedCurrentSnapshots(snapshotsInProgress, repository)) {
                SnapshotId snapshotId = snapshotInfo.snapshotId();
                allSnapshotIds.put(snapshotId.getName(), snapshotId);
                currentSnapshots.add(snapshotInfo);
            }

            final StepListener<RepositoryData> repositoryDataListener = new StepListener<>();
            if (isCurrentSnapshotsOnly(request.snapshots()) == false) {
                repositoriesService.getRepositoryData(repository, repositoryDataListener);
            } else {
                // Setting repositoryDataListener response to be null if the request has only current snapshot
                repositoryDataListener.onResponse(null);
            }
            repositoryDataListener.whenComplete(repositoryData -> {
                if (repositoryData != null) {
                    for (SnapshotId snapshotId : repositoryData.getSnapshotIds()) {
                        allSnapshotIds.put(snapshotId.getName(), snapshotId);
                    }
                }

                final Set<SnapshotId> toResolve = new HashSet<>();
                if (isAllSnapshots(request.snapshots())) {
                    toResolve.addAll(allSnapshotIds.values());
                } else {
                    for (String snapshotOrPattern : request.snapshots()) {
                        if (GetSnapshotsRequest.CURRENT_SNAPSHOT.equalsIgnoreCase(snapshotOrPattern)) {
                            toResolve.addAll(currentSnapshots.stream().map(SnapshotInfo::snapshotId).collect(Collectors.toList()));
                        } else if (Regex.isSimpleMatchPattern(snapshotOrPattern) == false) {
                            if (allSnapshotIds.containsKey(snapshotOrPattern)) {
                                toResolve.add(allSnapshotIds.get(snapshotOrPattern));
                            } else if (request.ignoreUnavailable() == false) {
                                throw new SnapshotMissingException(repository, snapshotOrPattern);
                            }
                        } else {
                            for (Map.Entry<String, SnapshotId> entry : allSnapshotIds.entrySet()) {
                                if (Regex.simpleMatch(snapshotOrPattern, entry.getKey())) {
                                    toResolve.add(entry.getValue());
                                }
                            }
                        }
                    }

                    if (toResolve.isEmpty()
                        && request.ignoreUnavailable() == false
                        && isCurrentSnapshotsOnly(request.snapshots()) == false) {
                        throw new SnapshotMissingException(repository, request.snapshots()[0]);
                    }
                }

                final List<SnapshotInfo> snapshotInfos;
                if (request.verbose()) {
                    snapshotInfos = snapshots(snapshotsInProgress, repository, new ArrayList<>(toResolve), request.ignoreUnavailable());
                } else {
                    if (repositoryData != null) {
                        // want non-current snapshots as well, which are found in the repository data
                        snapshotInfos = buildSimpleSnapshotInfos(toResolve, repositoryData, currentSnapshots);
                    } else {
                        // only want current snapshots
                        snapshotInfos = currentSnapshots.stream().map(SnapshotInfo::basic).collect(Collectors.toList());
                        CollectionUtil.timSort(snapshotInfos);
                    }
                }
                listener.onResponse(new GetSnapshotsResponse(snapshotInfos));
            }, listener::onFailure);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Returns a list of currently running snapshots from repository sorted by snapshot creation date
     *
     * @param snapshotsInProgress snapshots in progress in the cluster state
     * @param repositoryName repository name
     * @return list of snapshots
     */
    private static List<SnapshotInfo> sortedCurrentSnapshots(@Nullable SnapshotsInProgress snapshotsInProgress, String repositoryName) {
        List<SnapshotInfo> snapshotList = new ArrayList<>();
        List<SnapshotsInProgress.Entry> entries = SnapshotsService.currentSnapshots(
            snapshotsInProgress,
            repositoryName,
            Collections.emptyList()
        );
        for (SnapshotsInProgress.Entry entry : entries) {
            snapshotList.add(new SnapshotInfo(entry));
        }
        CollectionUtil.timSort(snapshotList);
        return unmodifiableList(snapshotList);
    }

    /**
     * Returns a list of snapshots from repository sorted by snapshot creation date
     *
     * @param snapshotsInProgress snapshots in progress in the cluster state
     * @param repositoryName      repository name
     * @param snapshotIds         snapshots for which to fetch snapshot information
     * @param ignoreUnavailable   if true, snapshots that could not be read will only be logged with a warning,
     *                            if false, they will throw an error
     * @return list of snapshots
     */
    private List<SnapshotInfo> snapshots(
        @Nullable SnapshotsInProgress snapshotsInProgress,
        String repositoryName,
        List<SnapshotId> snapshotIds,
        boolean ignoreUnavailable
    ) {
        final Set<SnapshotInfo> snapshotSet = new HashSet<>();
        final Set<SnapshotId> snapshotIdsToIterate = new HashSet<>(snapshotIds);
        // first, look at the snapshots in progress
        final List<SnapshotsInProgress.Entry> entries = SnapshotsService.currentSnapshots(
            snapshotsInProgress,
            repositoryName,
            snapshotIdsToIterate.stream().map(SnapshotId::getName).collect(Collectors.toList())
        );
        // filter and incorporate the snapshots in progress
        for (SnapshotsInProgress.Entry entry : entries) {
            if (snapshotIdsToIterate.remove(entry.snapshot().getSnapshotId())) {
                snapshotSet.add(new SnapshotInfo(entry));
            }
        }
        // then, look in the repository
        final Repository repository = repositoriesService.repository(repositoryName);
        for (SnapshotId snapshotId : snapshotIdsToIterate) {
            try {
                snapshotSet.add(repository.getSnapshotInfo(snapshotId));
            } catch (Exception ex) {
                if (ignoreUnavailable) {
                    logger.warn(() -> new ParameterizedMessage("failed to get snapshot [{}]", snapshotId), ex);
                } else {
                    if (ex instanceof SnapshotException) {
                        throw ex;
                    }
                    throw new SnapshotException(repositoryName, snapshotId, "Snapshot could not be read", ex);
                }
            }
        }
        final ArrayList<SnapshotInfo> snapshotList = new ArrayList<>(snapshotSet);
        CollectionUtil.timSort(snapshotList);
        return unmodifiableList(snapshotList);
    }

    private boolean isAllSnapshots(String[] snapshots) {
        return (snapshots.length == 0) || (snapshots.length == 1 && GetSnapshotsRequest.ALL_SNAPSHOTS.equalsIgnoreCase(snapshots[0]));
    }

    private boolean isCurrentSnapshotsOnly(String[] snapshots) {
        return (snapshots.length == 1 && GetSnapshotsRequest.CURRENT_SNAPSHOT.equalsIgnoreCase(snapshots[0]));
    }

    private List<SnapshotInfo> buildSimpleSnapshotInfos(
        final Set<SnapshotId> toResolve,
        final RepositoryData repositoryData,
        final List<SnapshotInfo> currentSnapshots
    ) {
        List<SnapshotInfo> snapshotInfos = new ArrayList<>();
        for (SnapshotInfo snapshotInfo : currentSnapshots) {
            if (toResolve.remove(snapshotInfo.snapshotId())) {
                snapshotInfos.add(snapshotInfo.basic());
            }
        }
        Map<SnapshotId, List<String>> snapshotsToIndices = new HashMap<>();
        for (IndexId indexId : repositoryData.getIndices().values()) {
            for (SnapshotId snapshotId : repositoryData.getSnapshots(indexId)) {
                if (toResolve.contains(snapshotId)) {
                    snapshotsToIndices.computeIfAbsent(snapshotId, (k) -> new ArrayList<>()).add(indexId.getName());
                }
            }
        }
        for (SnapshotId snapshotId : toResolve) {
            final List<String> indices = snapshotsToIndices.getOrDefault(snapshotId, Collections.emptyList());
            CollectionUtil.timSort(indices);
            snapshotInfos.add(new SnapshotInfo(snapshotId, indices, Collections.emptyList(), repositoryData.getSnapshotState(snapshotId)));
        }
        CollectionUtil.timSort(snapshotInfos);
        return unmodifiableList(snapshotInfos);
    }
}
