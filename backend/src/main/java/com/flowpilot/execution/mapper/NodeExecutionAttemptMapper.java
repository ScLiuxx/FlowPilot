package com.flowpilot.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Mapper
public interface NodeExecutionAttemptMapper extends BaseMapper<NodeExecutionAttempt> {

    /**
     * Reads the attempts of a node.
     *
     * <p>The identifiers are selected first and the rows are read afterwards: the snapshots of an
     * attempt carry the input and the output of a node and can be larger than the sort buffer of
     * the database, which would make the sort fail with "out of sort memory".
     */
    @Select("SELECT id FROM node_execution_attempt WHERE node_execution_id = #{nodeExecutionId} ORDER BY attempt_no")
    List<Long> findIdsByNodeExecutionId(@Param("nodeExecutionId") Long nodeExecutionId);

    @Select("""
            <script>
            SELECT * FROM node_execution_attempt WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<NodeExecutionAttempt> selectByIds(@Param("ids") List<Long> ids);

    default List<NodeExecutionAttempt> findByNodeExecutionId(Long nodeExecutionId) {
        List<Long> ids = findIdsByNodeExecutionId(nodeExecutionId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, NodeExecutionAttempt> byId = selectByIds(ids).stream()
                .collect(Collectors.toMap(NodeExecutionAttempt::getId, Function.identity(),
                        (first, second) -> first));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Select("SELECT * FROM node_execution_attempt WHERE node_execution_id = #{nodeExecutionId} AND attempt_no = #{attemptNo}")
    NodeExecutionAttempt findByNodeAndAttempt(@Param("nodeExecutionId") Long nodeExecutionId,
                                              @Param("attemptNo") Integer attemptNo);
}
