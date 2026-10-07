package com.somepro.domain.sample.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 采样送检与检测聚合根（领域层）：异常上报挂上来以后，该采样的采样、该送检的送检，
 * 检测结果回来再回填到这条样本上。一条样本一条记录。
 *
 * 业务规则：
 * - 一条样本挂一条上报（reportId），样本类型四选一：{@link #TYPE_BLOOD 血液} /
 *   {@link #TYPE_SWAB 拭子} / {@link #TYPE_TISSUE 组织} / {@link #TYPE_FECES 粪便}；
 * - 结果四态：{@link #RESULT_PENDING 待检} / {@link #RESULT_POSITIVE 阳性} /
 *   {@link #RESULT_NEGATIVE 阴性} / {@link #RESULT_INCONCLUSIVE 不确定}，新登记的默认待检；
 * - 检测结果只录一次：还悬着（待检）的才录得了，已出结果的同一条样本别来回翻
 *   （领域拦一道，仓储层按 result=PENDING 条件更新再兜底）；
 * - 结果一录，上报那头要从在办推到已采样；录成阳性的再跟着立一条疫病预警 ——
 *   联动落库（一个事务三头一起动）由仓储层编排，领域对象只保证自身字段不变量。
 *
 * 「上报还没结案、还在办才采得了」要查上报仓储，由应用层把关。
 * 编号 sampleNo 由仓储层在落库时分配（SM-YYYY-NNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class SampleTest extends BaseEntity {

    /** 样本类型：血液 */
    public static final String TYPE_BLOOD = "BLOOD";
    /** 样本类型：拭子 */
    public static final String TYPE_SWAB = "SWAB";
    /** 样本类型：组织 */
    public static final String TYPE_TISSUE = "TISSUE";
    /** 样本类型：粪便 */
    public static final String TYPE_FECES = "FECES";

    /** 检测结果：待检（新登记的默认态） */
    public static final String RESULT_PENDING = "PENDING";
    /** 检测结果：阳性 */
    public static final String RESULT_POSITIVE = "POSITIVE";
    /** 检测结果：阴性 */
    public static final String RESULT_NEGATIVE = "NEGATIVE";
    /** 检测结果：不确定 */
    public static final String RESULT_INCONCLUSIVE = "INCONCLUSIVE";

    private static final Set<String> SAMPLE_TYPES = Set.of(
            TYPE_BLOOD, TYPE_SWAB, TYPE_TISSUE, TYPE_FECES);

    /** 可回填的结果（待检是登记默认态，不是能录的结果） */
    private static final Set<String> TESTED_RESULTS = Set.of(
            RESULT_POSITIVE, RESULT_NEGATIVE, RESULT_INCONCLUSIVE);

    private Long id;

    /** 样本编号（如 SM-2026-0001），全局唯一 */
    private String sampleNo;

    /** 挂在哪条上报下（t_abnormal_report.id） */
    private Long reportId;

    /** 样本类型：BLOOD / SWAB / TISSUE / FECES */
    private String sampleType;

    /** 送检时刻（不传则取登记当下） */
    private LocalDateTime sentAt;

    /** 检测机构 */
    private String labName;

    /** 检测项目 */
    private String testItem;

    /** 检测结果：PENDING / POSITIVE / NEGATIVE / INCONCLUSIVE，登记默认待检 */
    private String result;

    /** 检测时刻（录结果时记，不传取录入当下） */
    private LocalDateTime testedAt;

    /**
     * 工厂方法：在一条上报下登记一份样本，立起来先落在待检。
     *
     * @param sentAt 送检时刻，null 时取登记当下
     */
    public static SampleTest create(Long reportId, String sampleType, LocalDateTime sentAt,
                                    String labName, String testItem) {
        SampleTest sample = new SampleTest();
        sample.attachReport(reportId);
        sample.changeSampleType(sampleType);
        sample.sentAt = sentAt != null ? sentAt : LocalDateTime.now();
        sample.labName = labName;
        sample.testItem = testItem;
        sample.result = RESULT_PENDING;
        return sample;
    }

    public void attachReport(Long reportId) {
        if (reportId == null) {
            throw new BizException("所属上报不能为空");
        }
        this.reportId = reportId;
    }

    public void changeSampleType(String sampleType) {
        if (sampleType == null || !SAMPLE_TYPES.contains(sampleType.trim())) {
            throw new BizException("样本类型非法，仅支持 BLOOD/SWAB/TISSUE/FECES");
        }
        this.sampleType = sampleType.trim();
    }

    /**
     * 检测结果回填：只有还悬着（待检）的才录得了；结果一录就不再回头改，
     * 同一条样本别来回翻（仓储层按 result=PENDING 条件更新兜底，并发只放行一下）。
     * 上报那头的联动（在办 → 已采样）与阳性立预警由仓储层在同一个事务里一起落。
     *
     * @param testedAt 检测时刻，null 时取录入当下
     */
    public void recordResult(String result, LocalDateTime testedAt) {
        if (result == null || !TESTED_RESULTS.contains(result.trim())) {
            throw new BizException("检测结果非法，仅支持 POSITIVE/NEGATIVE/INCONCLUSIVE");
        }
        if (!pending()) {
            throw new BizException("该样本检测结果已录入，同一条样本不重复录入");
        }
        this.result = result.trim();
        this.testedAt = testedAt != null ? testedAt : LocalDateTime.now();
    }

    /** 结果还悬着（待检）：只有待检的样本录得了检测结果。 */
    public boolean pending() {
        return RESULT_PENDING.equals(this.result);
    }

    /** 结果是阳性：阳性的才会跟着立疫病预警。 */
    public boolean positive() {
        return RESULT_POSITIVE.equals(this.result);
    }
}
