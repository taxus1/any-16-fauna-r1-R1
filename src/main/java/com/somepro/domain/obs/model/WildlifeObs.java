package com.somepro.domain.obs.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 野生动物观测记录聚合根（领域层）：巡护当场看到一只/一群动物就落一条。
 *
 * 业务规则：
 * - 一条观测必须挂在一条巡护任务（taskId）下、落在一个监测点（siteId）上，物种编码照名录填；
 * - 健康状态四选一：{@link ObsSummary#HEALTH_NORMAL 正常} /
 *   {@link ObsSummary#HEALTH_INJURED 受伤} / {@link ObsSummary#HEALTH_DEAD 死亡} /
 *   {@link ObsSummary#HEALTH_SUSPECT 疑似疫病}，新记的默认正常；
 * - 个体数量必须是正数，零和负数一律不收；
 * - protectionLevel 是「观测当时保护级别」的快照：录入时由应用层照着物种名录当前级别抄一份进来，
 *   抄进来以后就不再跟着名录变（名录后调级别，老观测仍是当初那份），对账才说得清；
 * - 换物种改录时，快照跟着新物种重抄一份；不换物种只改别的字段，快照原样保留。
 *
 * 两条录入前置（任务正在执行、物种在名录且启用）要查任务/物种仓储，由应用层编排把关；
 * 领域对象只保证自身字段不变量。
 *
 * 编号 obsNo 由仓储层在落库时分配（WO-YYYY-NNNNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class WildlifeObs extends BaseEntity {

    private static final Set<String> HEALTH_STATUSES = Set.of(
            ObsSummary.HEALTH_NORMAL, ObsSummary.HEALTH_INJURED,
            ObsSummary.HEALTH_DEAD, ObsSummary.HEALTH_SUSPECT);

    private Long id;

    /** 观测编号（如 WO-2026-000001），全局唯一 */
    private String obsNo;

    /** 挂在哪条巡护任务下（t_patrol_task.id），挂上后不改 */
    private Long taskId;

    /** 在哪个监测点看到的（t_monitor_site.id） */
    private Long siteId;

    /** 物种编码（照物种名录填，不拿别的编码糊弄） */
    private String speciesCode;

    /** 观测当时的保护级别快照：从名录抄来，此后不跟着名录变 */
    private String protectionLevel;

    /** 个体数量：正数 */
    private Integer individualCount;

    /** 健康状态：NORMAL / INJURED / DEAD / SUSPECT */
    private String healthStatus;

    /** 观测时刻（不传则取登记当下） */
    private LocalDateTime observedAt;

    /** 记录人 */
    private String recorder;

    /**
     * 工厂方法：登记一条新观测。
     *
     * @param protectionLevel 应用层从物种名录查到的当前保护级别（抄成快照）
     * @param healthStatus    健康状态，null 时默认正常
     * @param observedAt      观测时刻，null 时取登记当下
     */
    public static WildlifeObs create(Long taskId, Long siteId, String speciesCode, String protectionLevel,
                                     Integer individualCount, String healthStatus,
                                     LocalDateTime observedAt, String recorder) {
        WildlifeObs obs = new WildlifeObs();
        obs.attachTask(taskId);
        obs.attachSite(siteId);
        obs.attachSpecies(speciesCode);
        obs.snapshotProtectionLevel(protectionLevel);
        obs.changeCount(individualCount);
        obs.changeHealth(healthStatus == null ? ObsSummary.HEALTH_NORMAL : healthStatus);
        obs.observedAt = observedAt != null ? observedAt : LocalDateTime.now();
        obs.recorder = blankToNull(recorder);
        return obs;
    }

    /** 观测必须挂在某条巡护任务下，挂上后不再挪（改观测不换任务）。 */
    public void attachTask(Long taskId) {
        if (taskId == null) {
            throw new BizException("巡护任务不能为空");
        }
        this.taskId = taskId;
    }

    public void attachSite(Long siteId) {
        if (siteId == null) {
            throw new BizException("监测点不能为空");
        }
        this.siteId = siteId;
    }

    public void attachSpecies(String speciesCode) {
        if (speciesCode == null || speciesCode.isBlank()) {
            throw new BizException("物种编码不能为空");
        }
        this.speciesCode = speciesCode.trim();
    }

    /** 抄一份保护级别快照：来源只能是名录里在启用的物种（应用层把关），这里只守非空。 */
    public void snapshotProtectionLevel(String protectionLevel) {
        if (protectionLevel == null || protectionLevel.isBlank()) {
            throw new BizException("保护级别快照不能为空");
        }
        this.protectionLevel = protectionLevel.trim();
    }

    /** 个体数量必须是正数：零和负数一律不收。 */
    public void changeCount(Integer individualCount) {
        if (individualCount == null) {
            throw new BizException("个体数量不能为空");
        }
        if (individualCount <= 0) {
            throw new BizException("个体数量必须为正数，零和负数不收");
        }
        this.individualCount = individualCount;
    }

    public void changeHealth(String healthStatus) {
        if (healthStatus == null || !HEALTH_STATUSES.contains(healthStatus)) {
            throw new BizException("健康状态非法，仅支持 NORMAL/INJURED/DEAD/SUSPECT");
        }
        this.healthStatus = healthStatus;
    }

    /**
     * 算不算异常个体：受伤/死亡/疑似疫病（口径统一用 {@link ObsSummary#ABNORMAL_HEALTH}）。
     * 正常的个体没什么可上报的，完成回报数异常条数也是这个口径。
     */
    public boolean abnormal() {
        return ObsSummary.ABNORMAL_HEALTH.contains(this.healthStatus);
    }

    /**
     * 改录：传入的字段才改（null 表示不动）。任务归属不开放修改。
     * 换物种（speciesCode 非空）时必须连新物种的保护级别快照一起带来，由应用层验过名录后传入；
     * 不换物种则 protectionLevel 传 null，老快照原样保留。
     */
    public void revise(Long siteId, String speciesCode, String protectionLevel, Integer individualCount,
                       String healthStatus, LocalDateTime observedAt, String recorder) {
        if (siteId != null) {
            attachSite(siteId);
        }
        if (speciesCode != null) {
            attachSpecies(speciesCode);
            // 物种换了，快照必须跟着重抄，不允许新物种挂着老级别的账
            snapshotProtectionLevel(protectionLevel);
        }
        if (individualCount != null) {
            changeCount(individualCount);
        }
        if (healthStatus != null) {
            changeHealth(healthStatus);
        }
        if (observedAt != null) {
            this.observedAt = observedAt;
        }
        if (recorder != null) {
            this.recorder = blankToNull(recorder);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
