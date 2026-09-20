package com.netsight.modules.alert.channel;

/**
 * 通知通道统一接口（SPI 标准）
 * 所有通知通道必须实现此接口，由通道中心自动发现并加载实现类，
 * 按 channelType 路由到对应实现。新增通道不得修改此接口定义。
 *
 * 新增通道开发规范（见方案 5.8）：
 * 1. 新增实现类 XxxChannelSender implements NotificationChannelSender
 * 2. getChannelType() 返回唯一通道标识（如 "sms" / "wechat" / "email"）
 * 3. send() 完成通道协议对接（API 地址/密钥从配置读取，不硬编码）
 * 4. 异常统一包装为 SendResult 返回，不向外抛运行时异常
 * 5. 每次发送记录入参、第三方返回、耗时（由通道中心统一记日志）
 * 6. 同一 bizId 重复调用时通道侧做幂等处理
 */
public interface NotificationChannelSender {

    /**
     * 通道类型标识，全局唯一，用于路由匹配
     *
     * @return 如 "sms" / "wechat" / "email" / "dingtalk"
     */
    String getChannelType();

    /**
     * 通道显示名称，用于后台配置展示
     *
     * @return 如 "短信通道" / "微信公众号"
     */
    String getChannelName();

    /**
     * 发送通知（核心方法）
     *
     * @param request 统一发送请求对象，含接收人、已渲染内容、模板变量、业务ID
     * @return 发送结果，含成功/失败、错误信息、第三方返回ID
     */
    SendResult send(SendRequest request);

    /**
     * 通道健康检查，用于定时巡检通道可用性
     *
     * @param config 渠道实例配置（已解密），含 token/appKey 等
     * @return true=通道正常 false=通道异常
     */
    boolean healthCheck(java.util.Map<String, Object> config);
}
