package com.somepro.domain.alert.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.species.model.Species;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 疫病预警与处置聚合根（领域层）：样本检测一录成阳性，这条预警就跟着立起来，
 * 后头按处置一步步往前走，直到解除、归档。
 *
 * 业务规则：
 * - 一条预警挂一条上报（reportId）、一份阳性样本（sampleId）；同一份阳性样本只立一条
 *   （样本那条链的条件更新把并发串行化，仓储层再数一道兜底，前后脚递两回也只落一条）；
 * - 预警级别不由人填，按这单的来头算：在上报当初判定的严重程度之上，再叠观测上抄的
 *   那份物种保护级别快照（不跟名录后来的改动跑），落到蓝/黄/橙/红四档，
 *   保护得越重、级别越高（{@link #deriveLevel}）；
 * - 状态机只顺不逆：已发布 RAISED → 处置中 HANDLING → 已解除 RESOLVED → 已归档 CLOSED；
 *   不能跳级、不能回退，已归档是终态，再推挡回；
 * - 每推一步记下时刻：表按现状用（无处置时刻列），推进走 UPDATE，审计列 update_time
 *   由 MetaObjectHandler 自动刷新，即这条预警的最近处置时刻；解除这一步另记 resolved_at，
 *   解除时刻不能早于发布时刻。
 *
 * 「阳性才立、同一样本只立一条」落在样本检测那条链的同一个事务里（仓储层编排）；
 * 「解除时把挂的上报跟着收尾到已结案」由仓储层在推进事务里联动。领域对象只保证自身字段不变量。
 *
 * 编号 alertNo 由仓储层在落库时分配（AL-YYYY-NNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class EpiAlert extends BaseEntity {

    /** 预警级别：蓝色 */
    public static final String LEVEL_BLUE = "BLUE";
    /** 预警级别：黄色 */
    public static final String LEVEL_YELLOW = "YELLOW";
    /** 预警级别：橙色 */
    public static final String LEVEL_ORANGE = "ORANGE";
    /** 预警级别：红色 */
    public static final String LEVEL_RED = "RED";

    /** 状态：已发布（立起来的初始态） */
    public static final String STATUS_RAISED = "RAISED";
    /** 状态：处置中 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 状态：已解除 */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 状态：已归档（终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 可推进到的目标状态（RAISED 是起点，不是推进目标） */
    private static final Set<String> ADVANCE_TARGETS = Set.of(
            STATUS_HANDLING, STATUS_RESOLVED, STATUS_CLOSED);

    /** 严重程度基线分：沿用上报那套口径（中/高），高比中重一档 */
    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of(
            AbnormalReport.SEVERITY_MEDIUM, 1,
            AbnormalReport.SEVERITY_HIGH, 2);

    /** 物种保护级别分：保护得越重分值越高（国家一级 > 国家二级 > 省级 > 一般） */
    private static final Map<String, Integer> PROTECTION_WEIGHT = Map.of(
            Species.LEVEL_COMMON, 1,
            Species.LEVEL_PROVINCIAL, 2,
            Species.LEVEL_NATIONAL_TWO, 3,
            Species.LEVEL_NATIONAL_ONE, 4);

    private Long id;

    /** 预警编号（如 AL-2026-0001），全局唯一 */
    private String alertNo;

    /** 挂在哪条上报下（t_abnormal_report.id） */
    private Long reportId;

    /** 由哪份阳性样本触发（t_sample_test.id），同一份样本只立一条 */
    private Long sampleId;

    /** 预警级别：BLUE / YELLOW / ORANGE / RED（系统按来头算，不由人填） */
    private String alertLevel;

    /** 状态：RAISED / HANDLING / RESOLVED / CLOSED */
    private String status;

    /** 处置措施（推进处置时随手记，可空） */
    private String disposalMethod;

    /** 预警发布时刻（立起来时记） */
    private LocalDateTime raisedAt;

    /** 解除时刻（推进到已解除时记，不能早于发布时刻） */
    private LocalDateTime resolvedAt;

    /**
     * 工厂方法：阳性样本立一条预警，立起来先落在已发布。
     *
     * @param reportSeverity    挂的那条上报当初判定的严重程度（MEDIUM/HIGH，上报模块定死的口径）
     * @param obsProtectionLevel 观测上抄的保护级别快照（不跟名录后来的改动跑）
     */
    public static EpiAlert raise(Long reportId, Long sampleId,
                                 String reportSeverity, String obsProtectionLevel) {
        EpiAlert alert = new EpiAlert();
        alert.attachReport(reportId);
        alert.attachSample(sampleId);
        alert.alertLevel = deriveLevel(reportSeverity, obsProtectionLevel);
        alert.status = STATUS_RAISED;
        alert.raisedAt = LocalDateTime.now();
        return alert;
    }

    public void attachReport(Long reportId) {
        if (reportId == null) {
            throw new BizException("所属上报不能为空");
        }
        this.reportId = reportId;
    }

    public void attachSample(Long sampleId) {
        if (sampleId == null) {
            throw new BizException("来源样本不能为空");
        }
        this.sampleId = sampleId;
    }

    /**
     * 处置推进：RAISED → HANDLING → RESOLVED → CLOSED，只能顺着走，
     * 不能跳级也不能回退；已归档是终态，再推挡回。
     * 推进落库走条件更新（仓储层按原状态卡），每推一步的时刻由审计列 update_time 记下；
     * 推进到已解除时另记解除时刻，解除时刻不能早于发布时刻。
     * 推进到已解除时，挂的那条上报跟着收尾到已结案（联动由仓储层在同一个事务里落）。
     *
     * @param disposalMethod 处置措施，null 或空白表示这一步不记
     * @param resolvedAt     解除时刻，仅目标为已解除时生效，null 时取推进当下
     */
    public void advance(String targetStatus, String disposalMethod, LocalDateTime resolvedAt) {
        if (targetStatus == null || !ADVANCE_TARGETS.contains(targetStatus.trim())) {
            throw new BizException("目标状态非法，仅支持 HANDLING/RESOLVED/CLOSED");
        }
        String target = targetStatus.trim();
        if (STATUS_CLOSED.equals(this.status)) {
            throw new BizException("预警已归档，不能再推进处置");
        }
        boolean allowed = switch (this.status) {
            case STATUS_RAISED -> STATUS_HANDLING.equals(target);
            case STATUS_HANDLING -> STATUS_RESOLVED.equals(target);
            case STATUS_RESOLVED -> STATUS_CLOSED.equals(target);
            default -> false;
        };
        if (!allowed) {
            throw new BizException("处置只能顺着走：已发布→处置中→已解除→已归档，不能跳级也不能回退");
        }
        LocalDateTime resolveMoment = null;
        if (STATUS_RESOLVED.equals(target)) {
            resolveMoment = resolvedAt != null ? resolvedAt : LocalDateTime.now();
            if (this.raisedAt != null && resolveMoment.isBefore(this.raisedAt)) {
                throw new BizException("解除时刻不能早于发布时刻");
            }
        }
        this.status = target;
        if (disposalMethod != null && !disposalMethod.isBlank()) {
            this.disposalMethod = disposalMethod.trim();
        }
        if (resolveMoment != null) {
            this.resolvedAt = resolveMoment;
        }
    }

    /** 已解除：解除这一步要记 resolved_at，并联动把挂的上报收尾到已结案。 */
    public boolean resolved() {
        return STATUS_RESOLVED.equals(this.status);
    }

    /**
     * 预警级别按这单的来头算，照着上报那摊的口径往上接：上报当初判的严重程度定基线
     * （高=2 / 中=1），再叠观测快照上的物种保护级别（国家一级=4 / 国家二级=3 / 省级=2 /
     * 一般=1），两数合计落档 —— 合计 6 及以上红、4 及以上橙、3 黄、其余蓝。
     * 保护得越重、级别越高；上报判得重的，同级保护下预警也更高。
     */
    private static String deriveLevel(String severity, String protectionLevel) {
        Integer severityWeight = SEVERITY_WEIGHT.get(severity);
        if (severityWeight == null) {
            throw new BizException("上报严重程度非法，无法定预警级别");
        }
        Integer protectionWeight = PROTECTION_WEIGHT.get(protectionLevel);
        if (protectionWeight == null) {
            throw new BizException("物种保护级别快照非法，无法定预警级别");
        }
        int score = severityWeight + protectionWeight;
        if (score >= 6) {
            return LEVEL_RED;
        }
        if (score >= 4) {
            return LEVEL_ORANGE;
        }
        if (score >= 3) {
            return LEVEL_YELLOW;
        }
        return LEVEL_BLUE;
    }
}
