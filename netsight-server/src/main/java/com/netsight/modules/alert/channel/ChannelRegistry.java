package com.netsight.modules.alert.channel;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知通道注册中心（SPI 自动发现）
 * 启动时自动收集所有 NotificationChannelSender 实现类，
 * 建立 channelType → 实现类 的路由表。
 * 事件中心路由分发时只需传入 channelType，无需修改核心代码。
 */
@Slf4j
@Component
public class ChannelRegistry {

    private final Map<String, NotificationChannelSender> channelMap = new HashMap<>();

    /**
     * 构造时注入所有通道实现（Spring 自动收集 List<NotificationChannelSender>）
     */
    public ChannelRegistry(List<NotificationChannelSender> senders) {
        for (NotificationChannelSender sender : senders) {
            NotificationChannelSender exist = channelMap.put(sender.getChannelType(), sender);
            if (exist != null) {
                log.warn("通道类型重复注册: {}，已覆盖为 {}", sender.getChannelType(), sender.getClass().getSimpleName());
            }
            log.info("通知通道已注册: [{}] {}", sender.getChannelType(), sender.getChannelName());
        }
    }

    /**
     * 按通道类型获取适配器（不存在或未注册抛业务异常）
     */
    public NotificationChannelSender get(String channelType) {
        NotificationChannelSender sender = channelMap.get(channelType);
        if (sender == null) {
            throw new ServiceException(ResultCode.CHANNEL_NOT_SUPPORT);
        }
        return sender;
    }

    /**
     * 判断通道是否已注册
     */
    public boolean contains(String channelType) {
        return channelMap.containsKey(channelType);
    }

    /**
     * 全部已注册通道类型
     */
    public Map<String, NotificationChannelSender> all() {
        return channelMap;
    }
}
