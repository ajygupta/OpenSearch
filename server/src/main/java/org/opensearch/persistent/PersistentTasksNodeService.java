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

package org.opensearch.persistent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.cluster.node.tasks.cancel.CancelTasksResponse;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.gateway.GatewayService;
import org.opensearch.persistent.PersistentTasksCustomMetadata.PersistentTask;
import org.opensearch.tasks.Task;
import org.opensearch.tasks.TaskAwareRequest;
import org.opensearch.tasks.TaskId;
import org.opensearch.tasks.TaskManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * This component is responsible for coordination of execution of persistent tasks on individual nodes. It runs on all
 * nodes in the cluster and monitors cluster state changes to detect started commands.
 *
 * @opensearch.internal
 */
public class PersistentTasksNodeService implements ClusterStateListener {

    private static final Logger logger = LogManager.getLogger(PersistentTasksNodeService.class);

    private final Map<Long, AllocatedPersistentTask> runningTasks = new HashMap<>();
    private final PersistentTasksService persistentTasksService;
    private final PersistentTasksExecutorRegistry persistentTasksExecutorRegistry;
    private final TaskManager taskManager;
    private final NodePersistentTasksExecutor nodePersistentTasksExecutor;

    public PersistentTasksNodeService(
        PersistentTasksService persistentTasksService,
        PersistentTasksExecutorRegistry persistentTasksExecutorRegistry,
        TaskManager taskManager,
        NodePersistentTasksExecutor nodePersistentTasksExecutor
    ) {
        this.persistentTasksService = persistentTasksService;
        this.persistentTasksExecutorRegistry = persistentTasksExecutorRegistry;
        this.taskManager = taskManager;
        this.nodePersistentTasksExecutor = nodePersistentTasksExecutor;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.state().blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            // wait until the gateway has recovered from disk, otherwise if the only cluster-manager restarts
            // we start cancelling all local tasks before cluster has a chance to recover.
            return;
        }
        PersistentTasksCustomMetadata tasks = event.state().getMetadata().custom(PersistentTasksCustomMetadata.TYPE);
        PersistentTasksCustomMetadata previousTasks = event.previousState().getMetadata().custom(PersistentTasksCustomMetadata.TYPE);

        // Cluster State Local State Local Action
        // STARTED NULL Create as STARTED, Start
        // STARTED STARTED Noop - running
        // STARTED COMPLETED Noop - waiting for notification ack

        // NULL NULL Noop - nothing to do
        // NULL STARTED Remove locally, Mark as PENDING_CANCEL, Cancel
        // NULL COMPLETED Remove locally

        // Cluster-manager states:
        // NULL - doesn't exist in the cluster state
        // STARTED - exist in the cluster state

        // Local state:
        // NULL - we don't have task registered locally in runningTasks
        // STARTED - registered in TaskManager, requires cluster-manager notification when finishes
        // PENDING_CANCEL - registered in TaskManager, doesn't require cluster-manager notification when finishes
        // COMPLETED - not registered in TaskManager, notified, waiting for cluster-manager to remove it from CS so we can remove locally

        // When task finishes if it is marked as STARTED or PENDING_CANCEL it is marked as COMPLETED and unregistered,
        // If the task was STARTED, the cluster-manager notification is also triggered (this is handled by unregisterTask() method, which is
        // triggered by PersistentTaskListener

        if (Objects.equals(tasks, previousTasks) == false || event.nodesChanged()) {
            // We have some changes let's check if they are related to our node
            String localNodeId = event.state().getNodes().getLocalNodeId();
            Set<Long> notVisitedTasks = new HashSet<>(runningTasks.keySet());
            if (tasks != null) {
                for (PersistentTask<?> taskInProgress : tasks.tasks()) {
                    if (localNodeId.equals(taskInProgress.getExecutorNode())) {
                        Long allocationId = taskInProgress.getAllocationId();
                        AllocatedPersistentTask persistentTask = runningTasks.get(allocationId);
                        if (persistentTask == null) {
                            // New task - let's start it
                            try {
                                startTask(taskInProgress);
                            } catch (Exception e) {
                                logger.error(
                                    "Unable to start allocated task ["
                                        + taskInProgress.getTaskName()
                                        + "] with id ["
                                        + taskInProgress.getId()
                                        + "] and allocation id ["
                                        + taskInProgress.getAllocationId()
                                        + "]",
                                    e
                                );
                            }
                        } else {
                            // The task is still running
                            notVisitedTasks.remove(allocationId);
                        }
                    }
                }
            }

            for (Long id : notVisitedTasks) {
                AllocatedPersistentTask task = runningTasks.get(id);
                if (task.isCompleted()) {
                    // Result was sent to the caller and the caller acknowledged acceptance of the result
                    logger.trace(
                        "Found completed persistent task [{}] with id [{}] and allocation id [{}] - removing",
                        task.getAction(),
                        task.getPersistentTaskId(),
                        task.getAllocationId()
                    );
                    runningTasks.remove(id);
                } else {
                    // task is running locally, but cluster-manager doesn't know about it - that means that the persistent task was removed
                    // cancel the task without notifying master
                    logger.trace(
                        "Found unregistered persistent task [{}] with id [{}] and allocation id [{}] - cancelling",
                        task.getAction(),
                        task.getPersistentTaskId(),
                        task.getAllocationId()
                    );
                    cancelTask(id);
                }
            }

        }

    }

    private <Params extends PersistentTaskParams> void startTask(PersistentTask<Params> taskInProgress) {
        PersistentTasksExecutor<Params> executor = persistentTasksExecutorRegistry.getPersistentTaskExecutorSafe(
            taskInProgress.getTaskName()
        );

        TaskAwareRequest request = new TaskAwareRequest() {
            TaskId parentTaskId = new TaskId("cluster", taskInProgress.getAllocationId());

            @Override
            public void setParentTask(TaskId taskId) {
                throw new UnsupportedOperationException("parent task if for persistent tasks shouldn't change");
            }

            @Override
            public TaskId getParentTask() {
                return parentTaskId;
            }

            @Override
            public Task createTask(long id, String type, String action, TaskId parentTaskId, Map<String, String> headers) {
                return executor.createTask(id, type, action, parentTaskId, taskInProgress, headers);
            }
        };

        AllocatedPersistentTask task;
        try {
            task = (AllocatedPersistentTask) taskManager.register("persistent", taskInProgress.getTaskName() + "[c]", request);
        } catch (Exception e) {
            logger.error(
                "Fatal error registering persistent task ["
                    + taskInProgress.getTaskName()
                    + "] with id ["
                    + taskInProgress.getId()
                    + "] and allocation id ["
                    + taskInProgress.getAllocationId()
                    + "], removing from persistent tasks",
                e
            );
            notifyMasterOfFailedTask(taskInProgress, e);
            return;
        }

        boolean processed = false;
        try {
            task.init(persistentTasksService, taskManager, taskInProgress.getId(), taskInProgress.getAllocationId());
            logger.trace(
                "Persistent task [{}] with id [{}] and allocation id [{}] was created",
                task.getAction(),
                task.getPersistentTaskId(),
                task.getAllocationId()
            );
            try {
                runningTasks.put(taskInProgress.getAllocationId(), task);
                nodePersistentTasksExecutor.executeTask(taskInProgress.getParams(), taskInProgress.getState(), task, executor);
            } catch (Exception e) {
                // Submit task failure
                task.markAsFailed(e);
            }
            processed = true;
        } finally {
            if (processed == false) {
                // something went wrong - unregistering task
                logger.warn(
                    "Persistent task [{}] with id [{}] and allocation id [{}] failed to create",
                    task.getAction(),
                    task.getPersistentTaskId(),
                    task.getAllocationId()
                );
                taskManager.unregister(task);
            }
        }
    }

    private <Params extends PersistentTaskParams> void notifyMasterOfFailedTask(
        PersistentTask<Params> taskInProgress,
        Exception originalException
    ) {
        persistentTasksService.sendCompletionRequest(
            taskInProgress.getId(),
            taskInProgress.getAllocationId(),
            originalException,
            new ActionListener<PersistentTask<?>>() {
                @Override
                public void onResponse(PersistentTask<?> persistentTask) {
                    logger.trace(
                        "completion notification for failed task [{}] with id [{}] was successful",
                        taskInProgress.getTaskName(),
                        taskInProgress.getAllocationId()
                    );
                }

                @Override
                public void onFailure(Exception notificationException) {
                    notificationException.addSuppressed(originalException);
                    logger.warn(
                        new ParameterizedMessage(
                            "notification for task [{}] with id [{}] failed",
                            taskInProgress.getTaskName(),
                            taskInProgress.getAllocationId()
                        ),
                        notificationException
                    );
                }
            }
        );
    }

    /**
     * Unregisters and then cancels the locally running task using the task manager. No notification to cluster-manager will be send upon
     * cancellation.
     */
    private void cancelTask(Long allocationId) {
        AllocatedPersistentTask task = runningTasks.remove(allocationId);
        if (task.markAsCancelled()) {
            // Cancel the local task using the task manager
            String reason = "task has been removed, cancelling locally";
            persistentTasksService.sendCancelRequest(task.getId(), reason, new ActionListener<CancelTasksResponse>() {
                @Override
                public void onResponse(CancelTasksResponse cancelTasksResponse) {
                    logger.trace(
                        "Persistent task [{}] with id [{}] and allocation id [{}] was cancelled",
                        task.getAction(),
                        task.getPersistentTaskId(),
                        task.getAllocationId()
                    );
                }

                @Override
                public void onFailure(Exception e) {
                    // There is really nothing we can do in case of failure here
                    logger.warn(
                        () -> new ParameterizedMessage(
                            "failed to cancel task [{}] with id [{}] and allocation id [{}]",
                            task.getAction(),
                            task.getPersistentTaskId(),
                            task.getAllocationId()
                        ),
                        e
                    );
                }
            });
        }
    }

    /**
     * The executor status.
     *
     * @opensearch.internal
     */
    public static class Status implements Task.Status {

        public static final String NAME = "persistent_executor";

        private final AllocatedPersistentTask.State state;

        public Status(AllocatedPersistentTask.State state) {
            this.state = requireNonNull(state, "State cannot be null");
        }

        public Status(StreamInput in) throws IOException {
            state = AllocatedPersistentTask.State.valueOf(in.readString());
        }

        @Override
        public String getWriteableName() {
            return NAME;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("state", state.toString());
            builder.endObject();
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(state.toString());
        }

        @Override
        public String toString() {
            return Strings.toString(MediaTypeRegistry.JSON, this);
        }

        @Override
        public boolean isFragment() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Status status = (Status) o;
            return state == status.state;
        }

        @Override
        public int hashCode() {
            return Objects.hash(state);
        }
    }

}
