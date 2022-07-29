package com.tapdata.tm.commons.dag.process;

import cn.hutool.core.bean.BeanUtil;
import com.google.common.collect.Lists;
import com.tapdata.manager.common.utils.JsonUtil;
import com.tapdata.tm.commons.dag.*;
import com.tapdata.tm.commons.dag.logCollector.VirtualTargetNode;
import com.tapdata.tm.commons.dag.vo.MigrateJsResultVo;
import com.tapdata.tm.commons.schema.*;
import com.tapdata.tm.commons.task.dto.Dag;
import com.tapdata.tm.commons.task.dto.SubTaskDto;
import com.tapdata.tm.commons.task.dto.TaskDto;
import com.tapdata.tm.commons.util.PdkSchemaConvert;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.utils.InstanceFactory;
import io.tapdata.entity.utils.JsonParser;
import io.tapdata.entity.utils.TypeHolder;
import io.tapdata.pdk.core.utils.TapConstants;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.tapdata.tm.commons.base.convert.ObjectIdDeserialize.toObjectId;

/**
 * @Author: Zed
 * @Date: 2021/11/5
 * @Description:
 */
@NodeType("migrate_js_processor")
@Getter
@Setter
@Slf4j
public class MigrateJsProcessorNode extends Node<List<Schema>> {
    @EqField
    private String script;

    @Override
    protected List<Schema> loadSchema(List<String> includes) {
        final List<String> predIds = new ArrayList<>();
        getPrePre(this, predIds);
        predIds.add(this.getId());
        Dag dag = this.getDag().toDag();
        dag = JsonUtil.parseJsonUseJackson(JsonUtil.toJsonUseJackson(dag), Dag.class);
        List<Node> nodes = dag.getNodes();

        Node target = new VirtualTargetNode();
        target.setId(UUID.randomUUID().toString());
        target.setName(target.getId());
        if (CollectionUtils.isNotEmpty(nodes)) {
            nodes = nodes.stream().filter(n -> predIds.contains(n.getId())).collect(Collectors.toList());
            nodes.add(target);
        }

        List<Edge> edges = dag.getEdges();
        if (CollectionUtils.isNotEmpty(edges)) {
            edges = edges.stream().filter(e -> (predIds.contains(e.getTarget()) || predIds.contains(e.getSource())) && !e.getSource().equals(this.getId())).collect(Collectors.toList());
            Edge edge = new Edge(this.getId(), target.getId());
            edges.add(edge);
        }

        ObjectId taskId = this.getDag().getTaskId();
        dag.setNodes(nodes);
        dag.setEdges(edges);
        DAG build = DAG.build(dag);
        build.getNodes().forEach(node -> node.getDag().setTaskId(taskId));

        SubTaskDto subTaskDto = new SubTaskDto();
        subTaskDto.setStatus(SubTaskDto.STATUS_WAIT_RUN);
        TaskDto taskDto = service.getTaskById(taskId == null ? null : taskId.toHexString());
        taskDto.setDag(null);
        taskDto.setSyncType(TaskDto.SYNC_TYPE_DEDUCE_SCHEMA);
        subTaskDto.setParentTask(taskDto);
        subTaskDto.setDag(build);
        subTaskDto.setParentId(taskDto.getId());
        subTaskDto.setId(new ObjectId());
        subTaskDto.setName(taskDto.getName() + "(100)");
        subTaskDto.setParentSyncType(taskDto.getSyncType());

        List<Schema> result = Lists.newArrayList();
        List<MigrateJsResultVo> jsResult;
        try {
            jsResult = service.getJsResult(getId(), target.getId(), subTaskDto);
        } catch (Exception e) {
            log.error("MigrateJsProcessorNode getJsResult ERROR", e);
            throw new RuntimeException(e);
        }
        if (CollectionUtils.isEmpty(jsResult)) {
            result = getInputSchema().get(0);
        } else {
            Map<String, MigrateJsResultVo> removeMap = jsResult.stream()
                    .filter(n -> "REMOVE".equals(n.getOp()))
                    .collect(Collectors.toMap(MigrateJsResultVo::getFieldName, Function.identity(), (e1, e2) -> e1));

            Map<String, MigrateJsResultVo> convertMap = jsResult.stream()
                    .filter(n -> "CONVERT".equals(n.getOp()))
                    .collect(Collectors.toMap(MigrateJsResultVo::getFieldName, Function.identity(), (e1, e2) -> e1));

            Map<String, TapField> createMap = jsResult.stream()
                    .filter(n -> "CREATE".equals(n.getOp()))
                    .collect(Collectors.toMap(MigrateJsResultVo::getFieldName, MigrateJsResultVo::getTapField, (e1, e2) -> e1));

            List<Schema> inputSchema = getInputSchema().get(0);
            if (CollectionUtils.isEmpty(inputSchema)) {
                return null;
            }

            // js result data
            for (Schema schema : inputSchema) {

                TapTable tapTable = PdkSchemaConvert.toPdk(schema);
                LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();

                if (!removeMap.isEmpty()) {
                    removeMap.keySet().forEach(nameFieldMap::remove);
                }

                if (!convertMap.isEmpty()) {
                    convertMap.keySet().forEach(name -> {
                        if (nameFieldMap.containsKey(name)) {
                            nameFieldMap.put(name, convertMap.get(name).getTapField());
                        }
                    });
                }

                if (!createMap.isEmpty()) {
                    nameFieldMap.putAll(createMap);
                }

                Schema s = PdkSchemaConvert.fromPdkSchema(tapTable);
                s.setDatabaseId(schema.getDatabaseId());
                result.add(s);
            }
        }

        return result;
    }

    @Override
    public List<Schema> mergeSchema(List<List<Schema>> inputSchemas, List<Schema> schemas) {
        //js节点的模型可以是直接虚拟跑出来的。 跑出来就是正确的模型，由引擎负责传值给tm
        return schemas;
    }

    public MigrateJsProcessorNode() {
        super(NodeEnum.migrate_js_processor.name(), NodeCatalog.processor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o instanceof MigrateJsProcessorNode) {
            Class className = MigrateJsProcessorNode.class;
            for (; className != Object.class; className = className.getSuperclass()) {
                java.lang.reflect.Field[] declaredFields = className.getDeclaredFields();
                for (java.lang.reflect.Field declaredField : declaredFields) {
                    EqField annotation = declaredField.getAnnotation(EqField.class);
                    if (annotation != null) {
                        try {
                            Object f2 = declaredField.get(o);
                            Object f1 = declaredField.get(this);
                            boolean b = fieldEq(f1, f2);
                            if (!b) {
                                return false;
                            }
                        } catch (IllegalAccessException e) {
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    protected List<Schema> saveSchema(Collection<String> predecessors, String nodeId, List<Schema> schema, DAG.Options options) {
        ObjectId taskId = taskId();
        schema.forEach(s -> {
            s.setTaskId(taskId);
            s.setNodeId(nodeId);
        });

        return service.createOrUpdateSchema(ownerId(), toObjectId(getConnectId()), schema, options, this);
    }

    @Override
    protected List<Schema> cloneSchema(List<Schema> schemas) {
        if (schemas == null) {
            return Collections.emptyList();
        }
        return SchemaUtils.cloneSchema(schemas);
    }

    private String getConnectId() {
        AtomicReference<String> connectionId = new AtomicReference<>("");

        getSourceNode().stream().findFirst().ifPresent(node -> connectionId.set(node.getConnectionId()));
        return connectionId.get();
    }

    private void getPrePre(Node node, List<String> preIds) {
        List<Node> predecessors = node.predecessors();
        if (CollectionUtils.isNotEmpty(predecessors)) {
            for (Node predecessor : predecessors) {
                preIds.add(predecessor.getId());
                getPrePre(predecessor, preIds);
            }
        }
    }
}