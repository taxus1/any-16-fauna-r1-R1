package com.somepro.infrastructure.persistence.station;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.station.model.MonitorStation;
import com.somepro.domain.station.repository.MonitorStationRepository;
import com.somepro.infrastructure.persistence.station.converter.MonitorStationPoConverter;
import com.somepro.infrastructure.persistence.station.po.MonitorStationPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.PageQueries;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

import static com.somepro.infrastructure.persistence.support.PageQueries.hasText;

/**
 * 监测站仓储适配器：用 MyBatis-Plus 实现领域仓储端口（基础设施层）。
 *
 * 所有 DB 调用经共享 {@link BlockingJdbc} 桥接到 boundedElastic（取操作人 → 切线程 →
 * 放 AuditContextHolder）；分页统一走 {@link PageQueries}（startPage 后必 clearPage）。
 * PO 与领域对象在本类里经 {@link MonitorStationPoConverter} 互转，不泄到外层。
 *
 * 编号分配：stationNo 为空时按 ST-YYYY-NNNN 生成（序号取号段内最大值 +1，并发撞号由
 * {@link BizNoGenerator} 重试）；调用方指定编号时直接落库，撞唯一索引转成业务异常，
 * 不把底层 DuplicateKeyException 甩给上层。
 */
@Repository
public class MonitorStationRepositoryImpl implements MonitorStationRepository {

    /** 编号前缀：ST-年-（如 ST-2026-） */
    private static final String NO_PREFIX = "ST-";

    private final MonitorStationMapper stationMapper;

    public MonitorStationRepositoryImpl(MonitorStationMapper stationMapper) {
        this.stationMapper = stationMapper;
    }

    @Override
    public Mono<MonitorStation> create(MonitorStation station) {
        return BlockingJdbc.blocking(() -> {
            if (station.getStationNo() != null && !station.getStationNo().isBlank()) {
                try {
                    return doInsert(station, station.getStationNo().trim());
                } catch (DuplicateKeyException e) {
                    throw new BizException("监测站编号已存在：" + station.getStationNo());
                }
            }
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> stationMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(station, no));
        });
    }

    @Override
    public Mono<MonitorStation> update(MonitorStation station) {
        return BlockingJdbc.blocking(() -> {
            MonitorStationPO po = MonitorStationPoConverter.toPo(station);
            stationMapper.updateById(po);
            return MonitorStationPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<MonitorStation> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            MonitorStationPO po = stationMapper.selectById(id);
            return po == null ? null : MonitorStationPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<MonitorStation>> page(int pageNum, int pageSize,
                                                 String name, String level, String region, String status) {
        return PageQueries.page(pageNum, pageSize,
                () -> stationMapper.selectList(Wrappers.<MonitorStationPO>lambdaQuery()
                        .like(hasText(name), MonitorStationPO::getName, name)
                        .eq(hasText(level), MonitorStationPO::getLevel, level)
                        .like(hasText(region), MonitorStationPO::getRegion, region)
                        .eq(hasText(status), MonitorStationPO::getStatus, status)
                        .orderByAsc(MonitorStationPO::getId)),
                MonitorStationPoConverter::toDomain);
    }

    /** 落库：雪花 id + 编号，审计字段由 MetaObjectHandler 填充。 */
    private MonitorStation doInsert(MonitorStation station, String stationNo) {
        station.setStationNo(stationNo);
        MonitorStationPO po = MonitorStationPoConverter.toPo(station);
        po.setId(IdUtil.getSnowflakeNextId());
        stationMapper.insert(po);
        return MonitorStationPoConverter.toDomain(po);
    }
}
