package io.tapdata.proxy;

import io.tapdata.entity.annotations.Bean;
import io.tapdata.entity.annotations.Implementation;
import io.tapdata.modules.api.net.data.OutgoingData;
import io.tapdata.modules.api.net.message.MessageEntity;
import io.tapdata.modules.api.net.service.EventQueueService;
import io.tapdata.modules.api.net.service.MessageEntityService;
import io.tapdata.modules.api.proxy.data.NewDataReceived;
import io.tapdata.pdk.core.utils.timer.MaxFrequencyLimiter;
import io.tapdata.wsserver.channels.gateway.GatewaySessionManager;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Function;

@Implementation(value = EventQueueService.class, type = "sync")
public class SyncEventQueueService implements EventQueueService {
	private static final String TAG = SyncEventQueueService.class.getSimpleName();
	@Bean
	private MessageEntityService messageEntityService;

	@Bean
	private SubscribeMap subscribeMap;

	private MaxFrequencyLimiter maxFrequencyLimiter;
	private Set<String> cachingChangedSubscribeIds = new ConcurrentSkipListSet<>();
	@Bean
	private GatewaySessionManager gatewaySessionManager;

	public SyncEventQueueService() {
		maxFrequencyLimiter = new MaxFrequencyLimiter(500, () -> {
			Set<String> old = cachingChangedSubscribeIds;
			cachingChangedSubscribeIds = new ConcurrentSkipListSet<>();

			Map<EngineSessionHandler, List<String>> sessionSubscribeIdsMap = new HashMap<>();
			for(String subscribeId : old) {
				List<EngineSessionHandler> engineSessionHandlers = subscribeMap.getSubscribeIdSessionMap().get(subscribeId);
				if(engineSessionHandlers != null) {
					for(EngineSessionHandler engineSessionHandler : engineSessionHandlers) {
						List<String> list = sessionSubscribeIdsMap.computeIfAbsent(engineSessionHandler, engineSessionHandler1 -> new ArrayList<>());
						if(!list.contains(subscribeId)) {
							list.add(subscribeId);
						}
					}
				}
			}
			for(Map.Entry<EngineSessionHandler, List<String>> entry : sessionSubscribeIdsMap.entrySet()) {
				gatewaySessionManager.receiveOutgoingData(entry.getKey().getId(), new OutgoingData().message(new NewDataReceived().subscribeIds(entry.getValue())));
			}
		});
	}

	@Override
	public void offer(MessageEntity message) {
		messageEntityService.save(message);
		cachingChangedSubscribeIds.add(message.getSubscribeId());
		maxFrequencyLimiter.touch();
	}

}
