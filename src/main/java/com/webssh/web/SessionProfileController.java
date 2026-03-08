package com.webssh.web;

import com.webssh.session.SessionProfileStore;
import com.webssh.session.SshSessionProfile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * SSH 会话配置 CRUD 控制器，提供保存、查询、删除 SSH 连接配置的 REST API。
 * <p>
 * 所有接口均基于当前登录用户（Principal）进行数据隔离，用户只能操作自己的会话配置。
 * 会话配置包含主机、端口、用户名及加密存储的凭据，获取详情时会解密后返回给前端。
 * </p>
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionProfileController {

    private final SessionProfileStore store;

    public SessionProfileController(SessionProfileStore store) {
        this.store = store;
    }

    /**
     * 列出当前用户保存的所有 SSH 会话配置。
     * <p>
     * 返回的列表用于前端展示会话列表，不包含敏感凭据的明文，仅包含基本信息。
     * </p>
     *
     * @param principal 当前登录用户，由 Spring Security 注入
     * @return 当前用户的会话配置列表，可能为空列表
     */
    @GetMapping
    public List<SshSessionProfile> list(Principal principal) {
        return store.list(principal.getName());
    }

    /**
     * 根据 ID 获取指定会话的详细信息。
     * <p>
     * 详情包含解密后的凭据，供建立 SSH 连接时使用。若会话不存在或不属于当前用户，
     * 返回 404，避免通过错误信息泄露资源是否存在。
     * </p>
     *
     * @param id        会话 ID，路径变量
     * @param principal 当前登录用户
     * @return 会话详情（含解密凭据），不存在时返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<SshSessionProfile> get(@PathVariable String id, Principal principal) {
        SshSessionProfile profile = store.get(principal.getName(), id);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * 新增或更新 SSH 会话配置。
     * <p>
     * 若 profile 带 id 且已存在则更新，否则新增。凭据在 store 层会加密存储，
     * 此处直接透传，由业务层负责加密逻辑。
     * </p>
     *
     * @param profile   会话配置，请求体 JSON 反序列化
     * @param principal 当前登录用户，用于关联数据归属
     * @return 保存后的会话配置（含生成的 id 等）
     */
    @PostMapping
    public SshSessionProfile save(@RequestBody SshSessionProfile profile, Principal principal) {
        return store.save(principal.getName(), profile);
    }

    /**
     * 根据 ID 删除指定会话配置。
     * <p>
     * 删除不存在的会话时返回 404 及 deleted: false，便于前端区分"删除成功"与"资源不存在"。
     * </p>
     *
     * @param id        会话 ID，路径变量
     * @param principal 当前登录用户
     * @return 成功时 {@code { "deleted": true }}，不存在时 404 及 {@code { "deleted": false, "message": "会话不存在" }}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, Principal principal) {
        boolean deleted = store.delete(principal.getName(), id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("deleted", false, "message", "会话不存在"));
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
