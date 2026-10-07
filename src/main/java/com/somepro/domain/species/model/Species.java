package com.somepro.domain.species.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * 物种名录聚合根（领域层）：观测要用的底册，一条记录一个物种。
 *
 * 业务规则：
 * - 保护级别 NATIONAL_ONE 国家一级 / NATIONAL_TWO 国家二级 / PROVINCIAL 省级 / COMMON 一般，
 *   新录默认 COMMON；
 * - 状态 ENABLED 启用 / DISABLED 停用；停用只是不再启用，账还留着（不删除）。
 *
 * 编码 speciesCode 由仓储层在落库时分配（SP-0001 式），一个编码只归一个物种。
 */
@Getter
@Setter
public class Species extends BaseEntity {

    /** 保护级别：国家一级 */
    public static final String LEVEL_NATIONAL_ONE = "NATIONAL_ONE";
    /** 保护级别：国家二级 */
    public static final String LEVEL_NATIONAL_TWO = "NATIONAL_TWO";
    /** 保护级别：省级 */
    public static final String LEVEL_PROVINCIAL = "PROVINCIAL";
    /** 保护级别：一般 */
    public static final String LEVEL_COMMON = "COMMON";

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "ENABLED";
    /** 状态：停用 */
    public static final String STATUS_DISABLED = "DISABLED";

    private static final Set<String> LEVELS = Set.of(
            LEVEL_NATIONAL_ONE, LEVEL_NATIONAL_TWO, LEVEL_PROVINCIAL, LEVEL_COMMON);

    private Long id;

    /** 物种编码（如 SP-0001），全局唯一 */
    private String speciesCode;

    /** 中文名 */
    private String name;

    /** 学名 */
    private String latinName;

    /** 保护级别：NATIONAL_ONE / NATIONAL_TWO / PROVINCIAL / COMMON */
    private String protectionLevel;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 工厂方法：新录物种，保护级别默认一般，状态默认启用。 */
    public static Species create(String name, String latinName, String protectionLevel) {
        Species species = new Species();
        species.rename(name);
        species.setLatinName(blankToNull(latinName));
        species.changeProtectionLevel(protectionLevel == null ? LEVEL_COMMON : protectionLevel);
        species.setStatus(STATUS_ENABLED);
        return species;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("物种中文名不能为空");
        }
        this.name = name.trim();
    }

    public void changeProtectionLevel(String protectionLevel) {
        if (protectionLevel == null || !LEVELS.contains(protectionLevel)) {
            throw new BizException("保护级别非法，仅支持 NATIONAL_ONE/NATIONAL_TWO/PROVINCIAL/COMMON");
        }
        this.protectionLevel = protectionLevel;
    }

    /** 改资料：传入的字段才改（null 表示不动）。 */
    public void updateProfile(String name, String latinName, String protectionLevel) {
        if (name != null) {
            rename(name);
        }
        if (latinName != null) {
            this.latinName = blankToNull(latinName);
        }
        if (protectionLevel != null) {
            changeProtectionLevel(protectionLevel);
        }
    }

    /** 停用：账还留着不删除，重复停用结果不变（幂等）。 */
    public void disable() {
        this.status = STATUS_DISABLED;
    }

    /**
     * 物种是否在名录且启用（ENABLED）：只有启用的物种才录得进新观测。
     * 查不到/停用的都不收 —— 观测录入与改录共用这一个谓词，判断只留一处。
     */
    public boolean isEnabled() {
        return STATUS_ENABLED.equals(this.status);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
