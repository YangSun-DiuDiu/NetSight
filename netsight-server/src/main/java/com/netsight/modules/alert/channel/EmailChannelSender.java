package com.netsight.modules.alert.channel;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件通道（channelType = email）
 * config_json: {smtpHost, smtpPort, username, password, from, sslEnabled}
 * 接收人：request.receiverList（邮箱地址列表）
 */
@Slf4j
@Component
public class EmailChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "email"; }

    @Override
    public String getChannelName() { return "邮件通知"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null) return fail("未配置邮件参数", start);
            String host = (String) cfg.get("smtpHost");
            String port = String.valueOf(cfg.getOrDefault("smtpPort", "465"));
            String username = (String) cfg.get("username");
            String password = (String) cfg.get("password");
            String from = (String) cfg.getOrDefault("from", username);
            boolean ssl = Boolean.parseBoolean(String.valueOf(cfg.getOrDefault("sslEnabled", "true")));

            if (!StringUtils.hasText(host) || !StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                return fail("邮件 SMTP 参数不完整", start);
            }
            if (request.getReceiverList() == null || request.getReceiverList().isEmpty()) {
                return fail("邮件接收人为空", start);
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", port);
            props.put("mail.smtp.auth", "true");
            if (ssl) {
                props.put("mail.smtp.ssl.enable", "true");
            }
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            for (String to : request.getReceiverList()) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
            msg.setSubject("NetSight 设备告警", "UTF-8");
            msg.setContent(request.getContent() == null ? "" : request.getContent(), "text/html;charset=UTF-8");
            msg.setSentDate(new Date());

            Transport.send(msg);
            return SendResult.builder().success(true).thirdPartyMsgId("MAIL-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
