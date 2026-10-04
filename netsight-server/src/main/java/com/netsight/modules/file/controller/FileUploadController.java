package com.netsight.modules.file.controller;

import com.netsight.common.core.R;
import com.netsight.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传控制器（维修照片等）
 */
@Slf4j
@RestController
@RequestMapping("/file")
public class FileUploadController {

    @Value("${file.upload-dir:/opt/nams-server/uploads}")
    private String uploadDir;

    @Value("${file.access-prefix:/uploads}")
    private String accessPrefix;

    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 上传维修照片
     * POST /file/upload
     */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ServiceException(500, "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ServiceException(500, "文件大小不能超过10MB");
        }

        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }

        // 按日期分目录
        String dateDir = LocalDate.now().format(DATE_FMT);
        String fileName = UUID.randomUUID().toString().replace("-", "") + ext;
        String relativePath = dateDir + "/" + fileName;

        try {
            File dir = new File(uploadDir + "/" + dateDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File dest = new File(uploadDir + "/" + relativePath);
            file.transferTo(dest);

            Map<String, Object> result = new HashMap<>();
            result.put("url", accessPrefix + "/" + relativePath);
            result.put("name", originalName);
            result.put("size", file.getSize());
            return R.ok(result);
        } catch (IOException e) {
            log.error("文件上传失败: {}", e.getMessage());
            throw new ServiceException(500, "文件上传失败");
        }
    }
}
