package com.netsight.gateway.localapi;

import com.netsight.gateway.core.ConfigHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地日志读取：返回运行日志尾部 N 行（管理页面"系统-日志"用）。
 */
@Slf4j
@Service
public class LocalLogService {

    private final ConfigHolder configHolder;

    public LocalLogService(ConfigHolder configHolder) {
        this.configHolder = configHolder;
    }

    public String tail(int lines) {
        if (lines <= 0) {
            lines = 200;
        }
        if (lines > 2000) {
            lines = 2000;
        }
        Path logFile = Paths.get(configHolder.getLogDir(), "netsight.log");
        if (!Files.exists(logFile)) {
            // 本地开发回退：项目 logs 目录
            Path dev = Paths.get("logs", "netsight.log");
            if (Files.exists(dev)) {
                logFile = dev;
            } else {
                return "日志文件不存在: " + logFile;
            }
        }
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = raf.length();
            long pos = Math.max(0, length - lines * 300L); // 按每行约 300 字节粗定位
            raf.seek(pos);
            // 跳到完整行起点
            if (pos > 0) {
                raf.readLine();
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = raf.readLine()) != null) {
                sb.append(new String(line.getBytes(StandardCharsets_ISO_8859_1), java.nio.charset.StandardCharsets.UTF_8)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("读取日志失败: {}", e.getMessage(), e);
            return "读取日志失败: " + e.getMessage();
        }
    }

    private static final java.nio.charset.Charset StandardCharsets_ISO_8859_1 =
            java.nio.charset.StandardCharsets.ISO_8859_1;
}
