package com.somepro.infrastructure.persistence.species;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.species.model.Species;
import com.somepro.domain.species.repository.SpeciesRepository;
import com.somepro.infrastructure.persistence.species.converter.SpeciesPoConverter;
import com.somepro.infrastructure.persistence.species.po.SpeciesPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.Conditions;
import com.somepro.infrastructure.persistence.support.PagingQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * 物种名录仓储适配器（基础设施层）。
 *
 * 只负责名录本身的存取：编码分配、改资料、停用、按编码查、分页；
 * 阻塞 JDBC 走 {@link BlockingJdbc}、分页走 {@link PagingQuery}。
 *
 * 编码分配：speciesCode 为空时按 SP-NNNN 生成（并发撞码由 {@link BizNoGenerator} 重试）；
 * 指定编码时撞唯一索引转业务异常，一个编码只归一个物种。
 */
@Repository
public class SpeciesRepositoryImpl implements SpeciesRepository {

    /** 编码前缀：SP-（无年份段，如 SP-0001） */
    private static final String CODE_PREFIX = "SP-";

    private final SpeciesMapper speciesMapper;

    public SpeciesRepositoryImpl(SpeciesMapper speciesMapper) {
        this.speciesMapper = speciesMapper;
    }

    @Override
    public Mono<Species> create(Species species) {
        return BlockingJdbc.blocking(() -> {
            if (species.getSpeciesCode() != null && !species.getSpeciesCode().isBlank()) {
                try {
                    return doInsert(species, species.getSpeciesCode().trim());
                } catch (DuplicateKeyException e) {
                    throw new BizException("物种编码已存在：" + species.getSpeciesCode());
                }
            }
            return BizNoGenerator.insertWithRetry(
                    () -> speciesMapper.selectMaxSeq(CODE_PREFIX, CODE_PREFIX.length() + 1),
                    CODE_PREFIX,
                    code -> doInsert(species, code));
        });
    }

    @Override
    public Mono<Species> update(Species species) {
        return BlockingJdbc.blocking(() -> {
            SpeciesPO po = SpeciesPoConverter.toPo(species);
            speciesMapper.updateById(po);
            return SpeciesPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Species> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            SpeciesPO po = speciesMapper.selectById(id);
            return po == null ? null : SpeciesPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Species> findByCode(String speciesCode) {
        return BlockingJdbc.blocking(() -> {
            SpeciesPO po = speciesMapper.selectOne(Wrappers.<SpeciesPO>lambdaQuery()
                    .eq(SpeciesPO::getSpeciesCode, speciesCode));
            return po == null ? null : SpeciesPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<Species>> page(int pageNum, int pageSize,
                                          String name, String protectionLevel, String status) {
        return BlockingJdbc.blocking(() -> PagingQuery.page(pageNum, pageSize,
                () -> speciesMapper.selectList(Wrappers.<SpeciesPO>lambdaQuery()
                        .like(Conditions.hasText(name), SpeciesPO::getName, name)
                        .eq(Conditions.hasText(protectionLevel), SpeciesPO::getProtectionLevel, protectionLevel)
                        .eq(Conditions.hasText(status), SpeciesPO::getStatus, status)
                        .orderByAsc(SpeciesPO::getId)),
                SpeciesPoConverter::toDomain));
    }

    private Species doInsert(Species species, String speciesCode) {
        species.setSpeciesCode(speciesCode);
        SpeciesPO po = SpeciesPoConverter.toPo(species);
        po.setId(IdUtil.getSnowflakeNextId());
        speciesMapper.insert(po);
        return SpeciesPoConverter.toDomain(po);
    }
}
