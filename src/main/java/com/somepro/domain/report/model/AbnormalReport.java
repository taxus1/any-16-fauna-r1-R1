package com.somepro.domain.report.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.ObsSummary;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.species.model.Species;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 异常个体上报聚合根（领域层）：巡护碰到不正常的个体（受伤/死亡/疑似疫病），
 * 从那条观测上单独报一条上来，后头的送检、处置都从这条往下走。
 *
 * 业务规则：
 * - 一条上报挂一条观测（obsId）、落一个监测点（siteId，照观测抄），
 *   类别三选一：{@link #CATEGORY_INJURED 受伤} / {@link #CATEGORY_DEAD 死亡} /
 *   {@link #CATEGORY_SUSPECT_DISEASE 疑似疫病}；
 * - 类别得跟观测当时的健康状态对得上：伤着的报受伤、死了的报死亡、疑似疫病的报疑似疫病，
 *   串了不收（{@link #HEALTH_TO_CATEGORY}）；健康状态正常的观测压根报不了（应用层先拦）；
 * - 严重程度不用前端填，由系统按这单的来头算：死亡/疑似疫病一律 {@link #SEVERITY_HIGH 高}；
 *   受伤的看观测上抄的那份保护级别快照，国家一级/二级算高，其余算 {@link #SEVERITY_MEDIUM 中}；
 * - 状态机只顺不逆：已上报 REPORTED → 处置中 HANDLING → 已救护 RESCUED / 已采样 SAMPLED
 *   → 结案 CLOSED；不能跳级、不能回退，结案是终态，再推挡回；
 * - 处置时刻不由业务代码手写：表按现状用（无 handled_at 列），每次推进走 UPDATE，
 *   审计列 update_time 由 MetaObjectHandler 自动刷新，即这单的最近处置时刻。
 *
 * 「观测没作废、挂的巡护任务没取消、同一观测只挂一条未作废上报」要查观测/任务/上报仓储，
 * 由应用层编排把关（重复上报在仓储层事务内兜底）；领域对象只保证自身字段不变量。
 *
 * 编号 reportNo 由仓储层在落库时分配（AR-YYYY-NNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class AbnormalReport extends BaseEntity {

    /** 类别：受伤 */
    public static final String CATEGORY_INJURED = "INJURED";
    /** 类别：死亡 */
    public static final String CATEGORY_DEAD = "DEAD";
    /** 类别：疑似疫病 */
    public static final String CATEGORY_SUSPECT_DISEASE = "SUSPECT_DISEASE";

    /** 严重程度：中 */
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    /** 严重程度：高 */
    public static final String SEVERITY_HIGH = "HIGH";

    /** 状态：已上报 */
    public static final String STATUS_REPORTED = "REPORTED";
    /** 状态：处置中 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 状态：已救护 */
    public static final String STATUS_RESCUED = "RESCUED";
    /** 状态：已采样 */
    public static final String STATUS_SAMPLED = "SAMPLED";
    /** 状态：已结案（终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Set<String> CATEGORIES = Set.of(
            CATEGORY_INJURED, CATEGORY_DEAD, CATEGORY_SUSPECT_DISEASE);

    /** 可推进到的目标状态（REPORTED 是起点，不是推进目标） */
    private static final Set<String> ADVANCE_TARGETS = Set.of(
            STATUS_HANDLING, STATUS_RESCUED, STATUS_SAMPLED, STATUS_CLOSED);

    /** 观测健康状态 → 该报的上报类别：伤报伤、死报死、疑似疫病报疑似疫病，串了不收。 */
    private static final Map<String, String> HEALTH_TO_CATEGORY = Map.of(
            ObsSummary.HEALTH_INJURED, CATEGORY_INJURED,
            ObsSummary.HEALTH_DEAD, CATEGORY_DEAD,
            ObsSummary.HEALTH_SUSPECT, CATEGORY_SUSPECT_DISEASE);

    private Long id;

    /** 上报编号（如 AR-2026-0001），全局唯一 */
    private String reportNo;

    /** 从哪条观测报上来的（t_wildlife_obs.id） */
    private Long obsId;

    /** 在哪个监测点（照观测上的点位抄） */
    private Long siteId;

    /** 类别：INJURED / DEAD / SUSPECT_DISEASE */
    private String category;

    /** 严重程度：MEDIUM / HIGH（系统按来头算，不由前端填） */
    private String severity;

    /** 状态：REPORTED / HANDLING / RESCUED / SAMPLED / CLOSED */
    private String status;

    /** 上报时刻（不传则取登记当下） */
    private LocalDateTime reportedAt;

    /**
     * 工厂方法：从一条异常观测报一条上报，立起来先落在已上报。
     *
     * @param obsHealthStatus    观测当时的健康状态（应用层已验过不是正常）
     * @param obsProtectionLevel 观测上抄的保护级别快照（算受伤单的严重程度用）
     * @param reportedAt         上报时刻，null 时取登记当下
     */
    public static AbnormalReport create(Long obsId, Long siteId, String category,
                                        String obsHealthStatus, String obsProtectionLevel,
                                        LocalDateTime reportedAt) {
        AbnormalReport report = new AbnormalReport();
        report.attachObs(obsId);
        report.attachSite(siteId);
        report.changeCategory(category);
        report.matchObsHealth(obsHealthStatus);
        report.severity = deriveSeverity(report.category, obsProtectionLevel);
        report.status = STATUS_REPORTED;
        report.reportedAt = reportedAt != null ? reportedAt : LocalDateTime.now();
        return report;
    }

    public void attachObs(Long obsId) {
        if (obsId == null) {
            throw new BizException("来源观测不能为空");
        }
        this.obsId = obsId;
    }

    public void attachSite(Long siteId) {
        if (siteId == null) {
            throw new BizException("监测点不能为空");
        }
        this.siteId = siteId;
    }

    public void changeCategory(String category) {
        if (category == null || !CATEGORIES.contains(category.trim())) {
            throw new BizException("上报类别非法，仅支持 INJURED/DEAD/SUSPECT_DISEASE");
        }
        this.category = category.trim();
    }

    /**
     * 处置推进：REPORTED → HANDLING → RESCUED/SAMPLED → CLOSED，只能顺着走，
     * 不能跳级也不能回退；已结案是终态，再推挡回。
     * 推进落库走条件更新（仓储层按原状态卡），处置时刻由审计列 update_time 记下。
     */
    public void advance(String targetStatus) {
        if (targetStatus == null || !ADVANCE_TARGETS.contains(targetStatus.trim())) {
            throw new BizException("目标状态非法，仅支持 HANDLING/RESCUED/SAMPLED/CLOSED");
        }
        String target = targetStatus.trim();
        if (STATUS_CLOSED.equals(this.status)) {
            throw new BizException("上报已结案，不能再推进处置");
        }
        boolean allowed = switch (this.status) {
            case STATUS_REPORTED -> STATUS_HANDLING.equals(target);
            case STATUS_HANDLING -> STATUS_RESCUED.equals(target) || STATUS_SAMPLED.equals(target);
            case STATUS_RESCUED, STATUS_SAMPLED -> STATUS_CLOSED.equals(target);
            default -> false;
        };
        if (!allowed) {
            throw new BizException("处置只能顺着走：已上报→处置中→已救护/已采样→已结案，不能跳级也不能回退");
        }
        this.status = target;
    }

    /** 已结案（终态）：不能再采样、不能再推进。 */
    public boolean closed() {
        return STATUS_CLOSED.equals(this.status);
    }

    /**
     * 还在办（已上报/处置中）：只有在办的上报能登记样本。
     * 已救护/已采样的不走采样线，已结案的是终态。
     */
    public boolean inHandling() {
        return STATUS_REPORTED.equals(this.status) || STATUS_HANDLING.equals(this.status);
    }

    /** 类别得跟观测当时的健康状态对得上，串了不收。 */
    private void matchObsHealth(String obsHealthStatus) {
        String expected = HEALTH_TO_CATEGORY.get(obsHealthStatus);
        if (expected == null || !expected.equals(this.category)) {
            throw new BizException("上报类别与观测健康状态对不上：受伤报 INJURED、死亡报 DEAD、疑似疫病报 SUSPECT_DISEASE");
        }
    }

    /**
     * 严重程度按这单的来头算：死亡/疑似疫病一律高；受伤的看观测上的保护级别快照，
     * 国家一级/二级算高，其余算中。
     */
    private static String deriveSeverity(String category, String obsProtectionLevel) {
        if (CATEGORY_DEAD.equals(category) || CATEGORY_SUSPECT_DISEASE.equals(category)) {
            return SEVERITY_HIGH;
        }
        if (Species.LEVEL_NATIONAL_ONE.equals(obsProtectionLevel)
                || Species.LEVEL_NATIONAL_TWO.equals(obsProtectionLevel)) {
            return SEVERITY_HIGH;
        }
        return SEVERITY_MEDIUM;
    }
}
