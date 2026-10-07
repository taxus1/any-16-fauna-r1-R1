package com.somepro.application.obs;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.obs.service.ObservationGuard;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.species.model.Species;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 野生动物观测应用层：编排观测用例（录入、修改、查看、作废、条件分页）。
 *
 * 「能不能录」（任务正在执行、点位在册、物种在名录且启用）的取数与判断统一收在
 * {@link ObservationGuard}，新录与改录（换点/换物种）都调它，本类只做用例编排。
 *
 * 保护级别快照：录入时照物种名录里该物种「当前写着的」级别抄一份进观测
 * （protection_level），抄进来就不跟着名录变；以后名录把级别调高调低，老观测仍是当初那份。
 * 修改观测时换了物种，快照照新物种当前级别重抄一份；没换物种，老快照原样保留。
 */
@Service
public class WildlifeObsAppService {

    private final WildlifeObsRepository obsRepository;
    private final ObservationGuard guard;

    public WildlifeObsAppService(WildlifeObsRepository obsRepository,
                                 ObservationGuard guard) {
        this.obsRepository = obsRepository;
        this.guard = guard;
    }

    /**
     * 当场录一条观测。健康状态不传默认正常（领域对象兜底），观测时刻不传取登记当下。
     * 编号由仓储层按 WO-YYYY-NNNNNN 生成；保护级别照名录当前值抄成快照。
     */
    public Mono<WildlifeObs> record(Long taskId, Long siteId, String speciesCode, Integer individualCount,
                                    String healthStatus, LocalDateTime observedAt, String recorder) {
        return guard.requireOngoingTask(taskId)
                .then(guard.requireExistingSite(siteId))
                .then(guard.requireEnabledSpecies(speciesCode))
                .flatMap(species -> {
                    WildlifeObs obs = WildlifeObs.create(taskId, siteId, speciesCode,
                            species.getProtectionLevel(), individualCount, healthStatus, observedAt, recorder);
                    return obsRepository.create(obs);
                });
    }

    /**
     * 改录：点位/物种/数量/健康状态/观测时刻/记录人传啥改啥，任务归属不改。
     * 换点位要重新验点在册；换物种要重新验「在名录且启用」，并照新物种当前级别重抄快照。
     */
    public Mono<WildlifeObs> revise(Long id, Long siteId, String speciesCode, Integer individualCount,
                                    String healthStatus, LocalDateTime observedAt, String recorder) {
        return obsRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("观测记录不存在")))
                .flatMap(obs -> {
                    Long targetSiteId = siteId != null ? siteId : obs.getSiteId();
                    String incomingCode = normalizeCode(speciesCode);
                    // 物种编码变了才需要重新验名录并重抄快照；没变就沿用老快照（不跟名录后续调整）
                    boolean speciesChanged = incomingCode != null
                            && !incomingCode.equals(obs.getSpeciesCode());
                    Mono<Species> speciesCheck = speciesChanged
                            ? guard.requireEnabledSpecies(incomingCode)
                            : Mono.empty();
                    // 物种没变时 speciesCheck 是空 Mono：用 Optional 兜成「无新级别」，
                    // 保证后续 flatMap 仍会执行（空 Mono 直接 flatMap 会把更新整个吞掉）
                    return guard.requireExistingSite(targetSiteId)
                            .then(speciesCheck.map(Optional::of).defaultIfEmpty(Optional.empty()))
                            .flatMap(newSpecies -> {
                                String snapshot = newSpecies.map(Species::getProtectionLevel).orElse(null);
                                obs.revise(siteId, incomingCode, snapshot,
                                        individualCount, healthStatus, observedAt, recorder);
                                return obsRepository.update(obs);
                            });
                });
    }

    /** 查看单条在册观测（已作废的翻不到，底子仍在库里）。 */
    public Mono<WildlifeObs> detail(Long id) {
        return obsRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("观测记录不存在")));
    }

    /** 作废：逻辑删除（del_flag=1），清单里不再翻到，底子留在库里备查。 */
    public Mono<Void> voidObs(Long id) {
        return obsRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("观测记录不存在")))
                .flatMap(obs -> obsRepository.voidObs(obs.getId()));
    }

    /** 条件分页：任务/点位/物种/健康状态随意拼，可带观测时刻区间，全空翻整份在册观测。 */
    public Mono<PageResult<WildlifeObs>> pageObs(int pageNum, int pageSize,
                                                 Long taskId, Long siteId, String speciesCode,
                                                 String healthStatus,
                                                 LocalDateTime observedFrom, LocalDateTime observedTo) {
        return obsRepository.page(pageNum, pageSize, taskId, siteId,
                normalizeCode(speciesCode), healthStatus, observedFrom, observedTo);
    }

    private static String normalizeCode(String speciesCode) {
        return (speciesCode == null || speciesCode.isBlank()) ? null : speciesCode.trim();
    }
}
