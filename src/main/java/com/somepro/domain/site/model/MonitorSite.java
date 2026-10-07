package com.somepro.domain.site.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * 监测点聚合根（领域层）：挂在某个监测站底下的样线 / 样点 / 红外相机位。
 *
 * 业务规则：
 * - 类型 TRANSECT 样线 / SAMPLE_POINT 样点 / CAMERA 红外相机位；
 * - 生境 FOREST 林地 / WETLAND 湿地 / GRASSLAND 草地 / FARMLAND 农田 / DESERT 荒漠；
 * - 状态 ACTIVE 在册 / INACTIVE 停测，新登记默认在册；
 * - 挂载校验（目标站必须存在且未停用未关闭）需要查监测站仓储，由应用层编排，
 *   领域对象只保证自身字段不变量。
 *
 * 编号 siteNo 由仓储层在落库时分配（MP-2026-0001 式）。
 */
@Getter
@Setter
public class MonitorSite extends BaseEntity {

    /** 类型：样线 */
    public static final String TYPE_TRANSECT = "TRANSECT";
    /** 类型：样点 */
    public static final String TYPE_SAMPLE_POINT = "SAMPLE_POINT";
    /** 类型：红外相机位 */
    public static final String TYPE_CAMERA = "CAMERA";

    /** 生境：林地 */
    public static final String HABITAT_FOREST = "FOREST";
    /** 生境：湿地 */
    public static final String HABITAT_WETLAND = "WETLAND";
    /** 生境：草地 */
    public static final String HABITAT_GRASSLAND = "GRASSLAND";
    /** 生境：农田 */
    public static final String HABITAT_FARMLAND = "FARMLAND";
    /** 生境：荒漠 */
    public static final String HABITAT_DESERT = "DESERT";

    /** 状态：在册 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：停测 */
    public static final String STATUS_INACTIVE = "INACTIVE";

    private static final Set<String> TYPES = Set.of(TYPE_TRANSECT, TYPE_SAMPLE_POINT, TYPE_CAMERA);
    private static final Set<String> HABITATS = Set.of(
            HABITAT_FOREST, HABITAT_WETLAND, HABITAT_GRASSLAND, HABITAT_FARMLAND, HABITAT_DESERT);

    private Long id;

    /** 监测点编号（如 MP-2026-0001），全局唯一 */
    private String siteNo;

    /** 所属监测站 id */
    private Long stationId;

    /** 类型：TRANSECT / SAMPLE_POINT / CAMERA */
    private String siteType;

    /** 生境：FOREST / WETLAND / GRASSLAND / FARMLAND / DESERT */
    private String habitat;

    /** 位置描述 */
    private String location;

    /** 状态：ACTIVE / INACTIVE */
    private String status;

    /** 工厂方法：登记监测点，默认在册。 */
    public static MonitorSite create(Long stationId, String siteType, String habitat, String location) {
        MonitorSite site = new MonitorSite();
        site.attachTo(stationId);
        site.changeType(siteType);
        site.changeHabitat(habitat);
        site.setLocation(blankToNull(location));
        site.setStatus(STATUS_ACTIVE);
        return site;
    }

    public void attachTo(Long stationId) {
        if (stationId == null) {
            throw new BizException("所属监测站不能为空");
        }
        this.stationId = stationId;
    }

    public void changeType(String siteType) {
        if (siteType == null || !TYPES.contains(siteType)) {
            throw new BizException("监测点类型非法，仅支持 TRANSECT/SAMPLE_POINT/CAMERA");
        }
        this.siteType = siteType;
    }

    public void changeHabitat(String habitat) {
        if (habitat == null || !HABITATS.contains(habitat)) {
            throw new BizException("生境非法，仅支持 FOREST/WETLAND/GRASSLAND/FARMLAND/DESERT");
        }
        this.habitat = habitat;
    }

    /** 改资料：传入的字段才改（null 表示不动）。 */
    public void updateProfile(String siteType, String habitat, String location) {
        if (siteType != null) {
            changeType(siteType);
        }
        if (habitat != null) {
            changeHabitat(habitat);
        }
        if (location != null) {
            this.location = blankToNull(location);
        }
    }

    /** 停测：在册 -> 停测，重复停测结果不变（幂等）。 */
    public void deactivate() {
        this.status = STATUS_INACTIVE;
    }

    /** 恢复在册：停测 -> 在册，幂等。 */
    public void activate() {
        this.status = STATUS_ACTIVE;
    }

    /**
     * 点位是否在册（ACTIVE）：只有在册的点才派得进去任务、挂得上观测。
     * 停测的点不行 —— 派任务与录观测共用这一个谓词，判断只留一处。
     */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(this.status);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
