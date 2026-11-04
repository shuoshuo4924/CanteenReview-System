package org.example.canteenreview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件存储：只负责把上传的图片落盘并返回可访问 URL。
 * 菜品图、评价晒图都走它，避免各 Service 各写一份保存逻辑。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    // 允许上传的图片扩展名白名单（统一按小写比较）
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    // 图片保存目录（从配置读取，避免在多处硬编码路径）
    @Value("${app.image-dir}")
    private String imageDir;

    // 浏览器访问图片的 URL 前缀
    @Value("${app.image-url-prefix}")
    private String imageUrlPrefix;

    /**
     * 保存上传的图片，成功返回可访问的 URL，失败返回 null。
     * 文件名由 时间戳 + UUID + 原扩展名 重新生成，绝不直接使用用户文件名，防止路径穿越等问题。
     */
    public String saveImage(MultipartFile imageFile) {
        if (imageFile == null || imageFile.isEmpty()) {
            return null;
        }
        String extension = extractExtension(imageFile.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("拒绝上传非图片文件：{}", imageFile.getOriginalFilename());
            return null;
        }
        try {
            File dir = new File(imageDir);
            if (!dir.exists() && !dir.mkdirs()) {
                log.error("无法创建图片目录：{}", dir.getAbsolutePath());
                return null;
            }

            String newFileName = System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().replace("-", "") + "." + extension;
            imageFile.transferTo(new File(dir, newFileName));

            return imageUrlPrefix + newFileName;
        } catch (Exception e) {
            log.error("图片保存失败", e);
            return null;
        }
    }

    /**
     * 删除本站上传的图片文件：换图、删菜、删评价时调用，否则图片目录只增不减。
     * 传 null / 空 / 站外地址 / 非法文件名时安静跳过，不会误删目录外的文件。
     */
    public void deleteImage(String imageUrl) {
        String fileName = toFileName(imageUrl);
        if (fileName == null) {
            return;
        }
        File file = new File(new File(imageDir), fileName);
        if (!file.isFile()) {
            return;
        }
        if (!file.delete()) {
            log.warn("图片删除失败：{}", file.getAbsolutePath());
        }
    }

    /**
     * 把入库的 /images/xxx.jpg 还原成文件名。
     * 只要文件名里出现目录分隔符或 .. 就拒绝，防止拼接出目录外的路径。
     */
    private String toFileName(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank() || imageUrlPrefix == null || imageUrlPrefix.isBlank()) {
            return null;
        }
        if (!imageUrl.startsWith(imageUrlPrefix)) {
            return null;
        }
        String fileName = imageUrl.substring(imageUrlPrefix.length());
        if (fileName.isBlank()
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("..")
                || fileName.startsWith(".")) {
            return null;
        }
        String extension = extractExtension(fileName);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            return null;
        }
        return fileName;
    }

    /**
     * 从原始文件名中截取扩展名（小写、不含点），取不到返回 null。
     */
    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
