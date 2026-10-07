package com.somepro.infrastructure.persistence.demo;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.domain.demo.model.DemoItem;
import com.somepro.domain.demo.repository.DemoItemRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.demo.converter.DemoItemPoConverter;
import com.somepro.infrastructure.persistence.demo.po.DemoItemPO;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.PageQueries;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import static com.somepro.infrastructure.persistence.support.PageQueries.hasText;

/**
 * 仓储适配器：用 MyBatis-Plus 实现领域仓储端口（基础设施层）。
 *
 * ⚠️ 关键约定 1 —— 响应式 × 阻塞：MyBatis-Plus 是阻塞（JDBC）API，而本项目是 WebFlux 响应式链路，
 * 在 Netty event-loop 线程上执行 JDBC 会阻塞整个事件循环。所有 DB 调用都经由全仓唯一的桥接器
 * {@link BlockingJdbc}（取操作人 → 切 boundedElastic → 放 AuditContextHolder）。
 *
 * ⚠️ 关键约定 2 —— PO / 领域隔离：Mapper 只认 {@link DemoItemPO}，领域层只认 {@link DemoItem}，
 * 两者在本类里经 {@link DemoItemPoConverter} 互转。不要让 PO 泄到领域层或接口层。
 *
 * 其它约定：
 * - ID 由应用侧分配（雪花），PO 上为 IdType.INPUT。
 * - 软删除交给 @TableLogic：查询自动带 del_flag = 0，deleteById() 自动改写为置 1，不手写条件。
 * - 分页统一走 {@link PageQueries}（PageHelper），不要用 MyBatis-Plus 的 IPage。
 */
@Repository
public class DemoItemRepositoryImpl implements DemoItemRepository {

    private final DemoItemMapper demoItemMapper;

    public DemoItemRepositoryImpl(DemoItemMapper demoItemMapper) {
        this.demoItemMapper = demoItemMapper;
    }

    @Override
    public Mono<DemoItem> save(DemoItem item) {
        return BlockingJdbc.blocking(() -> {
            DemoItemPO po = DemoItemPoConverter.toPo(item);
            if (po.getId() == null) {
                po.setId(IdUtil.getSnowflakeNextId());
                demoItemMapper.insert(po);
            } else {
                demoItemMapper.updateById(po);
            }
            // insert 后框架会回填 id 与审计字段，转回领域对象一并返回
            return DemoItemPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<DemoItem> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            DemoItemPO po = demoItemMapper.selectById(id);
            // 返回 null 时 Mono.fromCallable 会自动转成空信号
            return po == null ? null : DemoItemPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<DemoItem>> page(int pageNum, int pageSize, String name) {
        return PageQueries.page(pageNum, pageSize,
                () -> demoItemMapper.selectList(Wrappers.<DemoItemPO>lambdaQuery()
                        .like(hasText(name), DemoItemPO::getName, name)),
                DemoItemPoConverter::toDomain);
    }

    @Override
    public Mono<Void> softDelete(Long id) {
        return BlockingJdbc.blocking(() -> {
            // @TableLogic 会把它翻译成 UPDATE t_demo_item SET del_flag = 1 WHERE id = ? AND del_flag = 0
            demoItemMapper.deleteById(id);
            return Boolean.TRUE;
        }).then();
    }
}
