package com.aid.media.provider.impl;

import lombok.RequiredArgsConstructor;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.net.URI;

/** Records source references and owned temporary COS objects before an upstream submission. */
@Component
@RequiredArgsConstructor
public class MediaTaskFileRegistry {
    private final JdbcTemplate jdbc;
    private final OssConfigManager ossConfigManager;

    public void register(Long mediaTaskId, String token, String role, String region, String bucket,
                         String objectKey, boolean temporary) {
        jdbc.update("INSERT INTO aid_media_task_file "
                        + "(media_task_id, provider_token, file_role, storage_provider, region, bucket_name, object_key, temporary, cleanup_state, create_time) "
                        + "VALUES (?, ?, ?, 'COS', ?, ?, ?, ?, 'ACTIVE', NOW()) "
                        + "ON DUPLICATE KEY UPDATE media_task_id=COALESCE(VALUES(media_task_id), media_task_id), "
                        + "object_key=VALUES(object_key), temporary=VALUES(temporary), "
                        + "cleanup_state='ACTIVE', update_time=NOW()",
                mediaTaskId, token, role, region, bucket, objectKey, temporary ? 1 : 0);
    }

    public List<String> temporaryKeys(String token) {
        return jdbc.queryForList("SELECT object_key FROM aid_media_task_file WHERE provider_token=? "
                + "AND file_role IN ('SOURCE','BACKGROUND') AND temporary=1 AND cleanup_state='ACTIVE'", String.class, token);
    }

    public boolean hasEntries(String token) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM aid_media_task_file WHERE provider_token=?", Integer.class, token);
        return count != null && count > 0;
    }

    public void cleaned(String token, String key) {
        jdbc.update("UPDATE aid_media_task_file SET cleanup_state='CLEANED', update_time=NOW() "
                + "WHERE provider_token=? AND object_key=? AND temporary=1", token, key);
    }

    public StoredOutput storedOutput(String token, String role) {
        List<StoredOutput> rows = jdbc.query("SELECT stored_url, content_type, file_size FROM aid_media_task_file "
                        + "WHERE provider_token=? AND file_role=? AND stored_url IS NOT NULL LIMIT 1",
                (rs, row) -> new StoredOutput(rs.getString(1), rs.getString(2), rs.getLong(3)), token, role);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void storedOutput(String token, String role, String url, String contentType, long bytes) {
        jdbc.update("UPDATE aid_media_task_file SET stored_url=?, content_type=?, file_size=?, update_time=NOW() "
                        + "WHERE provider_token=? AND file_role=?",
                url, contentType, bytes, token, role);
    }

    public void released(String token) {
        jdbc.update("UPDATE aid_media_task_file SET cleanup_state='RELEASED', update_time=NOW() "
                + "WHERE provider_token=? AND temporary=0 AND cleanup_state='ACTIVE'", token);
    }

    public Long taskIdForProviderTask(String providerTaskId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM aid_media_task WHERE provider_task_id=? LIMIT 1",
                Long.class, providerTaskId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public List<TemporaryFile> pendingCleanup(int limit) {
        return jdbc.query("SELECT f.provider_token, f.object_key, f.region, f.bucket_name "
                        + "FROM aid_media_task_file f LEFT JOIN aid_media_task t ON t.id=f.media_task_id "
                        + "LEFT JOIN aid_media_task recovered ON recovered.provider_task_id=f.provider_token "
                        + "WHERE f.temporary=1 AND f.cleanup_state='ACTIVE' "
                        + "AND COALESCE(t.status,recovered.status) IN ('SUCCEEDED','FAILED','CANCELLED') "
                        + "AND (f.file_role NOT LIKE 'OUTPUT_%' OR "
                        + "COALESCE(t.status,recovered.status) <> 'SUCCEEDED' OR "
                        + "COALESCE(NULLIF(t.oss_url,''),NULLIF(recovered.oss_url,'')) IS NOT NULL) "
                        + "ORDER BY f.id LIMIT ?",
                (rs, row) -> new TemporaryFile(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)), Math.max(1, Math.min(limit, 100)));
    }

    public long pendingCleanupCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM aid_media_task_file WHERE temporary=1 "
                + "AND cleanup_state='ACTIVE'", Long.class);
        return count == null ? 0 : count;
    }

    /** File deletion must wait while a same-bucket original is still used by a CI task. */
    public Set<String> protectedOriginals(Collection<String> urls) {
        OssProperties site = ossConfigManager.getOssProperties();
        if (site == null || !"cos".equalsIgnoreCase(site.getUploadMode())
                || urls == null || urls.isEmpty()) return Set.of();
        Map<String, String> targets = new HashMap<>();
        for (String url : urls) {
            try {
                URI uri = URI.create(url);
                String expected = site.getCosBucketName() + ".cos." + site.getCosRegion() + ".myqcloud.com";
                String cdnHost = site.getEffectiveCdnDomain() == null ? null
                        : URI.create(site.getEffectiveCdnDomain()).getHost();
                if (uri.isAbsolute() && !expected.equalsIgnoreCase(uri.getHost())
                        && (cdnHost == null || !cdnHost.equalsIgnoreCase(uri.getHost()))) continue;
                String path = uri.getPath();
                if (path == null || !path.matches("/[A-Za-z0-9._/-]{1,1024}") || path.contains("..")) continue;
                targets.put(url, path.substring(1));
            } catch (RuntimeException ignored) { }
        }
        if (targets.isEmpty()) return Set.of();
        List<String> keys = new ArrayList<>(new HashSet<>(targets.values()));
        Set<String> inUse = new HashSet<>();
        for (int offset = 0; offset < keys.size(); offset += 200) {
            List<String> part = keys.subList(offset, Math.min(offset + 200, keys.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> arguments = new ArrayList<>();
            arguments.add(site.getCosRegion());
            arguments.add(site.getCosBucketName());
            arguments.addAll(part);
            inUse.addAll(jdbc.queryForList("SELECT object_key FROM aid_media_task_file "
                    + "WHERE storage_provider='COS' AND region=? AND bucket_name=? "
                    + "AND file_role IN ('SOURCE','BACKGROUND') AND temporary=0 "
                    + "AND cleanup_state='ACTIVE' AND object_key IN (" + placeholders + ")",
                    String.class, arguments.toArray()));
        }
        Set<String> result = new HashSet<>();
        targets.forEach((url, key) -> { if (inUse.contains(key)) result.add(url); });
        return result;
    }

    public record TemporaryFile(String token, String objectKey, String region, String bucket) { }
    public record StoredOutput(String url, String contentType, long fileSize) { }
}
