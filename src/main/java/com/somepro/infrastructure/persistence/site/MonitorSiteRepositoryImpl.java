package com.somepro.infrastructure.persistence.site;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import com.somepro.infrastructure.persistence.site.converter.MonitorSitePoConverter;
import com.somepro.infrastructure.persistence.site.po.MonitorSitePO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.Conditions;
import com.somepro.infrastructure.persistence.support.PagingQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 监测点仓储适配器（基础设施层）。
 *
 * 只负责点位本身的存取：编号分配、改资料、状态切换、撤点、分页与名下点位计数；
 * 阻塞 JDBC 走 {@link BlockingJdbc}、分页走 {@link PagingQuery}。
 *
 * 编号分配：siteNo 为空时按 MP-YYYY-NNNN 生成（并发撞号由 {@link BizNoGenerator} 重试）；
 * 指定编号时撞唯一索引转业务异常。点位统计（countByStationIdAndStatus）与点位名单
 * 走同一张表、同一套 del_flag 过滤，监测站详情里的数字和点位模块自然对得上。
 */
@Repository
public class MonitorSiteRepositoryImpl implements MonitorSiteRepository {

    /** 编号前缀：MP-年-（如 MP-2026-） */
    private static final String NO_PREFIX = "MP-";

    private final MonitorSiteMapper siteMapper;

    public MonitorSiteRepositoryImpl(MonitorSiteMapper siteMapper) {
        this.siteMapper = siteMapper;
    }

    @Override
    public Mono<MonitorSite> create(MonitorSite site) {
        return BlockingJdbc.blocking(() -> {
            if (site.getSiteNo() != null && !site.getSiteNo().isBlank()) {
                try {
                    return doInsert(site, site.getSiteNo().trim());
                } catch (DuplicateKeyException e) {
                    throw new BizException("监测点编号已存在：" + site.getSiteNo());
                }
            }
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> siteMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(site, no));
        });
    }

    @Override
    public Mono<MonitorSite> update(MonitorSite site) {
        return BlockingJdbc.blocking(() -> {
            MonitorSitePO po = MonitorSitePoConverter.toPo(site);
            siteMapper.updateById(po);
            return MonitorSitePoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<MonitorSite> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            MonitorSitePO po = siteMapper.selectById(id);
            return po == null ? null : MonitorSitePoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<MonitorSite>> page(int pageNum, int pageSize,
                                              Long stationId, String siteType, String habitat, String status) {
        return BlockingJdbc.blocking(() -> PagingQuery.page(pageNum, pageSize,
                () -> siteMapper.selectList(Wrappers.<MonitorSitePO>lambdaQuery()
                        .eq(stationId != null, MonitorSitePO::getStationId, stationId)
                        .eq(Conditions.hasText(siteType), MonitorSitePO::getSiteType, siteType)
                        .eq(Conditions.hasText(habitat), MonitorSitePO::getHabitat, habitat)
                        .eq(Conditions.hasText(status), MonitorSitePO::getStatus, status)
                        .orderByAsc(MonitorSitePO::getId)),
                MonitorSitePoConverter::toDomain));
    }

    @Override
    public Mono<Long> countByStationIdAndStatus(Long stationId, String status) {
        return BlockingJdbc.blocking(() -> siteMapper.selectCount(Wrappers.<MonitorSitePO>lambdaQuery()
                .eq(MonitorSitePO::getStationId, stationId)
                .eq(MonitorSitePO::getStatus, status)));
    }

    @Override
    public Mono<Void> softDelete(Long id) {
        return BlockingJdbc.blocking(() -> {
            // @TableLogic：UPDATE t_monitor_site SET del_flag = 1 WHERE id = ? AND del_flag = 0
            siteMapper.deleteById(id);
            return Boolean.TRUE;
        }).then();
    }

    private MonitorSite doInsert(MonitorSite site, String siteNo) {
        site.setSiteNo(siteNo);
        MonitorSitePO po = MonitorSitePoConverter.toPo(site);
        po.setId(IdUtil.getSnowflakeNextId());
        siteMapper.insert(po);
        return MonitorSitePoConverter.toDomain(po);
    }
}
