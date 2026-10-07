package com.somepro.domain.station.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * 监测站聚合根（领域层）。
 *
 * 纯领域对象：只描述业务与不变量，不带任何持久化注解（表映射在基础设施层的 MonitorStationPO）。
 *
 * 业务规则：
 * - 层级只有 PROVINCIAL 省级 / MUNICIPAL 市级 / COUNTY 县级 三档；
 * - 状态 ACTIVE 运行 / SUSPENDED 停用 / CLOSED 关闭，新立默认 ACTIVE；
 * - CLOSED 是终态：不再改资料、不再停用，重复关闭是幂等空操作 —— 状态只按明确动作单向流转，
 *   不做「点一下开、再点一下关」的开关式翻转；
 * - 关闭前名下不能有在册（ACTIVE）监测点，点位停测或撤掉之后才允许关。
 *
 * 编号 stationNo 由仓储层在落库时分配（ST-2026-0001 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class MonitorStation extends BaseEntity {

    /** 层级：省级 */
    public static final String LEVEL_PROVINCIAL = "PROVINCIAL";
    /** 层级：市级 */
    public static final String LEVEL_MUNICIPAL = "MUNICIPAL";
    /** 层级：县级 */
    public static final String LEVEL_COUNTY = "COUNTY";

    /** 状态：运行 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：停用 */
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    /** 状态：关闭（终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Set<String> LEVELS = Set.of(LEVEL_PROVINCIAL, LEVEL_MUNICIPAL, LEVEL_COUNTY);

    private Long id;

    /** 监测站编号（如 ST-2026-0001），全局唯一 */
    private String stationNo;

    /** 监测站名称 */
    private String name;

    /** 层级：PROVINCIAL / MUNICIPAL / COUNTY */
    private String level;

    /** 所在辖区 */
    private String region;

    /** 负责人 */
    private String leader;

    /** 联系电话 */
    private String phone;

    /** 状态：ACTIVE / SUSPENDED / CLOSED */
    private String status;

    /** 工厂方法：新立监测站，保证初始不变量（默认运行状态）。 */
    public static MonitorStation create(String name, String level, String region, String leader, String phone) {
        MonitorStation station = new MonitorStation();
        station.rename(name);
        station.changeLevel(level);
        station.changeRegion(region);
        station.setLeader(blankToNull(leader));
        station.setPhone(blankToNull(phone));
        station.setStatus(STATUS_ACTIVE);
        return station;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("监测站名称不能为空");
        }
        this.name = name.trim();
    }

    public void changeLevel(String level) {
        if (level == null || !LEVELS.contains(level)) {
            throw new BizException("监测站层级非法，仅支持 PROVINCIAL/MUNICIPAL/COUNTY");
        }
        this.level = level;
    }

    public void changeRegion(String region) {
        if (region == null || region.isBlank()) {
            throw new BizException("所在辖区不能为空");
        }
        this.region = region.trim();
    }

    /**
     * 改资料：传入的字段才改（null 表示不动）。已关闭的站是终态，不允许再改。
     */
    public void updateProfile(String name, String level, String region, String leader, String phone) {
        if (STATUS_CLOSED.equals(this.status)) {
            throw new BizException("监测站已关闭，不能再修改");
        }
        if (name != null) {
            rename(name);
        }
        if (level != null) {
            changeLevel(level);
        }
        if (region != null) {
            changeRegion(region);
        }
        if (leader != null) {
            this.leader = blankToNull(leader);
        }
        if (phone != null) {
            this.phone = blankToNull(phone);
        }
    }

    /**
     * 停用：运行 -> 停用。重复停用结果不变（幂等），已关闭是终态不能再停用。
     */
    public void suspend() {
        if (STATUS_CLOSED.equals(this.status)) {
            throw new BizException("监测站已关闭，不能再停用");
        }
        this.status = STATUS_SUSPENDED;
    }

    /**
     * 关闭：名下还有在册（ACTIVE）监测点时不允许关闭，等点位停测或撤掉后再关。
     * 重复关闭是幂等空操作 —— 状态保持 CLOSED 不再翻动。
     *
     * @param activeSiteCount 名下在册监测点数量（由应用层查询后传入，领域层不直接访问仓储）
     */
    public void close(long activeSiteCount) {
        if (STATUS_CLOSED.equals(this.status)) {
            return;
        }
        if (activeSiteCount > 0) {
            throw new BizException("名下还有在册监测点，不能关闭");
        }
        this.status = STATUS_CLOSED;
    }

    /**
     * 还在运行（ACTIVE）：只有运行中的站能挂点位、接任务。
     * 停用/关闭的站这两件事都办不了 —— 挂点与派任务共用这一个口径。
     */
    public boolean operating() {
        return STATUS_ACTIVE.equals(this.status);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
