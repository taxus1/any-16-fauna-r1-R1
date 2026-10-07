package com.somepro.domain.task.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 巡护任务聚合根（领域层）：从监测站向监测点派发的一次巡护安排。
 *
 * 业务规则：
 * - 类型 ROUTINE 常规 / SPECIAL 专项 / EMERGENCY 应急；
 * - 状态 PENDING 待执行 / IN_PROGRESS 执行中 / DONE 已完成 / CANCELLED 已取消，
 *   新派下来的默认 PENDING；
 * - 计划日期不能早于今天（不能往过去派）；
 * - 只有还没动身（PENDING）的任务才开得了工：开工置 IN_PROGRESS 并记下开工时刻，
 *   已开工/已完成/已取消的都别重复开；
 * - 只有正在执行（IN_PROGRESS）的任务才回报得了完成：回报置 DONE 并记下完成时刻，
 *   同时把这一趟的观测账（总条数、异常条数）归拢写回，此后冻结不再翻动；
 * - 已结束（DONE）/已取消的任务不再收新观测，待执行（还没开工）的也不收——
 *   只有正在执行（IN_PROGRESS）的任务才收新观测（见 {@link #acceptsObservation()}），想补录得另开任务；
 * - 只有还没走完的任务（待执行/执行中）能改资料；已完成是终态，不再改、不再取消；
 * - 「站必须在运行、点必须在册」的校验要查监测站/监测点仓储，由应用层编排，
 *   领域对象只保证自身字段不变量；「同点同日不挂两条未完成任务」同理在应用层校验。
 *
 * 编号 taskNo 由仓储层在落库时分配（PT-2026-0001 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class PatrolTask extends BaseEntity {

    /** 类型：常规巡护 */
    public static final String TYPE_ROUTINE = "ROUTINE";
    /** 类型：专项巡护 */
    public static final String TYPE_SPECIAL = "SPECIAL";
    /** 类型：应急巡护 */
    public static final String TYPE_EMERGENCY = "EMERGENCY";

    /** 状态：待执行 */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态：执行中 */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 状态：已完成 */
    public static final String STATUS_DONE = "DONE";
    /** 状态：已取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> TYPES = Set.of(TYPE_ROUTINE, TYPE_SPECIAL, TYPE_EMERGENCY);

    private Long id;

    /** 巡护任务编号（如 PT-2026-0001），全局唯一 */
    private String taskNo;

    /** 派给的监测站 id */
    private Long stationId;

    /** 目标监测点 id */
    private Long siteId;

    /** 巡护类型：ROUTINE / SPECIAL / EMERGENCY */
    private String patrolType;

    /** 计划日期（今天往后的日子） */
    private LocalDate plannedDate;

    /** 执行人 */
    private String executor;

    /** 状态：PENDING / IN_PROGRESS / DONE / CANCELLED */
    private String status;

    /** 该任务下观测记录条数（完成回报时汇总回写，此后冻结） */
    private Integer obsCount;

    /** 其中异常个体条数（受伤/死亡/疑似疫病，完成回报时汇总回写） */
    private Integer abnormalCount;

    /** 开工时刻（开工时记下） */
    private LocalDateTime startedAt;

    /** 完成时刻（完成回报时记下） */
    private LocalDateTime finishedAt;

    /** 工厂方法：派发巡护任务，默认待执行。 */
    public static PatrolTask create(Long stationId, Long siteId, String patrolType,
                                    LocalDate plannedDate, String executor) {
        PatrolTask task = new PatrolTask();
        task.assignTo(stationId, siteId);
        task.changeType(patrolType);
        task.changePlannedDate(plannedDate);
        task.setExecutor(blankToNull(executor));
        task.setStatus(STATUS_PENDING);
        return task;
    }

    public void assignTo(Long stationId, Long siteId) {
        if (stationId == null) {
            throw new BizException("所属监测站不能为空");
        }
        if (siteId == null) {
            throw new BizException("监测点不能为空");
        }
        this.stationId = stationId;
        this.siteId = siteId;
    }

    public void changeType(String patrolType) {
        if (patrolType == null || !TYPES.contains(patrolType)) {
            throw new BizException("巡护类型非法，仅支持 ROUTINE/SPECIAL/EMERGENCY");
        }
        this.patrolType = patrolType;
    }

    public void changePlannedDate(LocalDate plannedDate) {
        if (plannedDate == null) {
            throw new BizException("计划日期不能为空");
        }
        if (plannedDate.isBefore(LocalDate.now())) {
            throw new BizException("计划日期不能早于今天");
        }
        this.plannedDate = plannedDate;
    }

    /** 还没走完（待执行/执行中）的任务才允许改、才占「同点同日」的位。 */
    public boolean unfinished() {
        return STATUS_PENDING.equals(this.status) || STATUS_IN_PROGRESS.equals(this.status);
    }

    /** 只有正在执行（IN_PROGRESS）的任务才收新观测：待执行还没开工、已完成账已冻结、
     * 已取消已销账，都不再往里录。观测能不能录只照这一个谓词把关。 */
    public boolean acceptsObservation() {
        return STATUS_IN_PROGRESS.equals(this.status);
    }

    /** 已取消（销账）的任务不能再作为上报的来源：取消即销账，一并拦下。 */
    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(this.status);
    }

    /**
     * 改任务：传入的字段才改（null 表示不动）。
     * 已完成的任务是终态，不允许再改；已取消的任务已销账（逻辑删除），走不到这里。
     */
    public void updateProfile(Long stationId, Long siteId, String patrolType,
                              LocalDate plannedDate, String executor) {
        if (!unfinished()) {
            throw new BizException("已完成的任务不能再修改");
        }
        if (stationId != null || siteId != null) {
            assignTo(stationId != null ? stationId : this.stationId,
                    siteId != null ? siteId : this.siteId);
        }
        if (patrolType != null) {
            changeType(patrolType);
        }
        if (plannedDate != null) {
            changePlannedDate(plannedDate);
        }
        if (executor != null) {
            this.executor = blankToNull(executor);
        }
    }

    /**
     * 取消：待执行/执行中 -> 已取消。已完成的不能取消；已取消的已销账，走不到这里。
     * 取消后不占「同点同日」的位，那天可以重派。
     */
    public void cancel() {
        if (STATUS_DONE.equals(this.status)) {
            throw new BizException("已完成的任务不能取消");
        }
        this.status = STATUS_CANCELLED;
    }

    /**
     * 开工：待执行 -> 执行中，记下开工时刻。
     * 已开工/已完成/已取消的都别重复开 —— 手快点两下，第二下没有可动的，直接拦下。
     */
    public void start() {
        if (STATUS_IN_PROGRESS.equals(this.status)) {
            throw new BizException("任务已开工，请勿重复开工");
        }
        if (STATUS_DONE.equals(this.status)) {
            throw new BizException("任务已完成，不能再开工");
        }
        if (STATUS_CANCELLED.equals(this.status)) {
            throw new BizException("任务已取消，不能再开工");
        }
        if (!STATUS_PENDING.equals(this.status)) {
            throw new BizException("任务状态非法，不能开工");
        }
        this.status = STATUS_IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    /**
     * 完成回报：执行中 -> 已完成，记下完成时刻，并把这一趟的观测账归拢写回（此后冻结）。
     * 还没开工的直接报完成不行；已完成的重复回报也没有可动的，直接拦下。
     *
     * @param obsCount      任务名下观测记录总条数（应用层按观测记录数清后传入）
     * @param abnormalCount 其中异常个体条数（受伤/死亡/疑似疫病）
     */
    public void complete(int obsCount, int abnormalCount) {
        if (STATUS_PENDING.equals(this.status)) {
            throw new BizException("任务还没开工，不能直接报完成");
        }
        if (STATUS_DONE.equals(this.status)) {
            throw new BizException("任务已完成，请勿重复回报");
        }
        if (STATUS_CANCELLED.equals(this.status)) {
            throw new BizException("任务已取消，不能报完成");
        }
        if (!STATUS_IN_PROGRESS.equals(this.status)) {
            throw new BizException("任务状态非法，不能报完成");
        }
        this.status = STATUS_DONE;
        this.finishedAt = LocalDateTime.now();
        this.obsCount = obsCount;
        this.abnormalCount = abnormalCount;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
