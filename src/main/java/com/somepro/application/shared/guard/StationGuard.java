package com.somepro.application.shared.guard;

import com.somepro.common.exception.BizException;
import com.somepro.domain.station.model.MonitorStation;
import com.somepro.domain.station.repository.MonitorStationRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 监测站准入校验（应用层共享）：所有「往站底下办业务」的入口都来这取站、这判断，
 * 全仓就这一份「站得存在且在运行」的口径 —— 派巡护任务、挂监测点走的是同一条。
 *
 * 判断本身在领域对象上（{@link MonitorStation#operating()}），这里只管取数与按场景
 * 组织报错文案；不同入口对同一种拒绝要回不同的话（派任务说「不能派发巡护任务」、
 * 挂点说「不能挂载监测点」），文案由调用方传入，判断不重复。
 */
@Component
public class StationGuard {

    private final MonitorStationRepository stationRepository;

    public StationGuard(MonitorStationRepository stationRepository) {
        this.stationRepository = stationRepository;
    }

    /**
     * 取站并要求在运行（ACTIVE）。
     *
     * @param stationId       站 id
     * @param nullMessage     没传站 id 时的报错文案（按入口组织）
     * @param missingMessage  站查不到时的报错文案（按入口组织）
     * @param unavailableVerb 站停用/关闭时报错里的动作措辞，如「派发巡护任务」「挂载监测点」
     */
    public Mono<MonitorStation> requireOperating(Long stationId, String nullMessage, String missingMessage,
                                                 String unavailableVerb) {
        if (stationId == null) {
            return Mono.error(new BizException(nullMessage));
        }
        return stationRepository.findById(stationId)
                .switchIfEmpty(Mono.error(new BizException(missingMessage)))
                .flatMap(station -> {
                    if (station.operating()) {
                        return Mono.just(station);
                    }
                    // 关闭与停用给不同的话，保持各入口原有的报错顺序与文案
                    String reason = MonitorStation.STATUS_CLOSED.equals(station.getStatus()) ? "已关闭" : "已停用";
                    return Mono.error(new BizException("监测站" + reason + "，不能" + unavailableVerb));
                });
    }
}
