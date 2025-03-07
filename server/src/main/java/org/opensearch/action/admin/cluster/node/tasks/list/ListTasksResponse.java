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

package org.opensearch.action.admin.cluster.node.tasks.list;

import org.opensearch.OpenSearchException;
import org.opensearch.action.TaskOperationFailure;
import org.opensearch.action.support.tasks.BaseTasksResponse;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.core.ParseField;
import org.opensearch.common.TriFunction;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ConstructingObjectParser;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.tasks.TaskId;
import org.opensearch.tasks.TaskInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.opensearch.core.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Returns the list of tasks currently running on the nodes
 *
 * @opensearch.internal
 */
public class ListTasksResponse extends BaseTasksResponse implements ToXContentObject {
    private static final String TASKS = "tasks";

    private final List<TaskInfo> tasks;

    private Map<String, List<TaskInfo>> perNodeTasks;

    private List<TaskGroup> groups;

    public ListTasksResponse(
        List<TaskInfo> tasks,
        List<TaskOperationFailure> taskFailures,
        List<? extends OpenSearchException> nodeFailures
    ) {
        super(taskFailures, nodeFailures);
        this.tasks = tasks == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    public ListTasksResponse(StreamInput in) throws IOException {
        super(in);
        tasks = Collections.unmodifiableList(in.readList(TaskInfo::new));
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeList(tasks);
    }

    protected static <T> ConstructingObjectParser<T, Void> setupParser(
        String name,
        TriFunction<List<TaskInfo>, List<TaskOperationFailure>, List<OpenSearchException>, T> ctor
    ) {
        ConstructingObjectParser<T, Void> parser = new ConstructingObjectParser<>(name, true, constructingObjects -> {
            int i = 0;
            @SuppressWarnings("unchecked")
            List<TaskInfo> tasks = (List<TaskInfo>) constructingObjects[i++];
            @SuppressWarnings("unchecked")
            List<TaskOperationFailure> tasksFailures = (List<TaskOperationFailure>) constructingObjects[i++];
            @SuppressWarnings("unchecked")
            List<OpenSearchException> nodeFailures = (List<OpenSearchException>) constructingObjects[i];
            return ctor.apply(tasks, tasksFailures, nodeFailures);
        });
        parser.declareObjectArray(optionalConstructorArg(), TaskInfo.PARSER, new ParseField(TASKS));
        parser.declareObjectArray(optionalConstructorArg(), (p, c) -> TaskOperationFailure.fromXContent(p), new ParseField(TASK_FAILURES));
        parser.declareObjectArray(optionalConstructorArg(), (p, c) -> OpenSearchException.fromXContent(p), new ParseField(NODE_FAILURES));
        return parser;
    }

    private static final ConstructingObjectParser<ListTasksResponse, Void> PARSER = setupParser(
        "list_tasks_response",
        ListTasksResponse::new
    );

    /**
     * Returns the list of tasks by node
     */
    public Map<String, List<TaskInfo>> getPerNodeTasks() {
        if (perNodeTasks == null) {
            perNodeTasks = tasks.stream().collect(Collectors.groupingBy(t -> t.getTaskId().getNodeId()));
        }
        return perNodeTasks;
    }

    /**
     * Get the tasks found by this request grouped by parent tasks.
     */
    public List<TaskGroup> getTaskGroups() {
        if (groups == null) {
            buildTaskGroups();
        }
        return groups;
    }

    private void buildTaskGroups() {
        Map<TaskId, TaskGroup.Builder> taskGroups = new HashMap<>();
        List<TaskGroup.Builder> topLevelTasks = new ArrayList<>();
        // First populate all tasks
        for (TaskInfo taskInfo : this.tasks) {
            taskGroups.put(taskInfo.getTaskId(), TaskGroup.builder(taskInfo));
        }

        // Now go through all task group builders and add children to their parents
        for (TaskGroup.Builder taskGroup : taskGroups.values()) {
            TaskId parentTaskId = taskGroup.getTaskInfo().getParentTaskId();
            if (parentTaskId.isSet()) {
                TaskGroup.Builder parentTask = taskGroups.get(parentTaskId);
                if (parentTask != null) {
                    // we found parent in the list of tasks - add it to the parent list
                    parentTask.addGroup(taskGroup);
                } else {
                    // we got zombie or the parent was filtered out - add it to the top task list
                    topLevelTasks.add(taskGroup);
                }
            } else {
                // top level task - add it to the top task list
                topLevelTasks.add(taskGroup);
            }
        }
        this.groups = Collections.unmodifiableList(topLevelTasks.stream().map(TaskGroup.Builder::build).collect(Collectors.toList()));
    }

    /**
     * Get the tasks found by this request.
     */
    public List<TaskInfo> getTasks() {
        return tasks;
    }

    /**
     * Convert this task response to XContent grouping by executing nodes.
     */
    public XContentBuilder toXContentGroupedByNode(XContentBuilder builder, Params params, DiscoveryNodes discoveryNodes)
        throws IOException {
        toXContentCommon(builder, params);
        builder.startObject("nodes");
        for (Map.Entry<String, List<TaskInfo>> entry : getPerNodeTasks().entrySet()) {
            DiscoveryNode node = discoveryNodes.get(entry.getKey());
            builder.startObject(entry.getKey());
            if (node != null) {
                // If the node is no longer part of the cluster, oh well, we'll just skip it's useful information.
                builder.field("name", node.getName());
                builder.field("transport_address", node.getAddress().toString());
                builder.field("host", node.getHostName());
                builder.field("ip", node.getAddress());

                builder.startArray("roles");
                for (DiscoveryNodeRole role : node.getRoles()) {
                    builder.value(role.roleName());
                }
                builder.endArray();

                if (!node.getAttributes().isEmpty()) {
                    builder.startObject("attributes");
                    for (Map.Entry<String, String> attrEntry : node.getAttributes().entrySet()) {
                        builder.field(attrEntry.getKey(), attrEntry.getValue());
                    }
                    builder.endObject();
                }
            }
            builder.startObject(TASKS);
            for (TaskInfo task : entry.getValue()) {
                builder.startObject(task.getTaskId().toString());
                task.toXContent(builder, params);
                builder.endObject();
            }
            builder.endObject();
            builder.endObject();
        }
        builder.endObject();
        return builder;
    }

    /**
     * Convert this response to XContent grouping by parent tasks.
     */
    public XContentBuilder toXContentGroupedByParents(XContentBuilder builder, Params params) throws IOException {
        toXContentCommon(builder, params);
        builder.startObject(TASKS);
        for (TaskGroup group : getTaskGroups()) {
            builder.field(group.getTaskInfo().getTaskId().toString());
            group.toXContent(builder, params);
        }
        builder.endObject();
        return builder;
    }

    /**
     * Presents a flat list of tasks
     */
    public XContentBuilder toXContentGroupedByNone(XContentBuilder builder, Params params) throws IOException {
        toXContentCommon(builder, params);
        builder.startArray(TASKS);
        for (TaskInfo taskInfo : getTasks()) {
            builder.startObject();
            taskInfo.toXContent(builder, params);
            builder.endObject();
        }
        builder.endArray();
        return builder;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        toXContentGroupedByNone(builder, params);
        builder.endObject();
        return builder;
    }

    public static ListTasksResponse fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public String toString() {
        return Strings.toString(MediaTypeRegistry.JSON, this, true, true);
    }
}
