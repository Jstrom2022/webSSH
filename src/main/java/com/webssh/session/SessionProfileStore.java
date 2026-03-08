package com.webssh.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话配置持久化服务。
 * <p>
 * 负责按用户隔离的 JSON 文件读写、会话的 CRUD 操作、凭据的加密存储和解密读取，
 * 以及用户级别的并发锁控制，避免同一用户并发写导致数据损坏。
 * </p>
 * <p>
 * {@link NormalizedRequest} 内部记录类用于校验和规范化输入，确保存储前数据合法且格式统一。
 * </p>
 */
@Service
public class SessionProfileStore {

    /** Jackson 反序列化 JSON 为 Profile 列表的类型引用 */
    private static final TypeReference<List<StoredSshSessionProfile>> PROFILE_LIST =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path rootDir;
    private final CredentialCryptoService cryptoService;
    /** 按用户名分组的锁，同一用户串行化读写，不同用户可并行 */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 构造会话存储服务。
     *
     * @param objectMapper    用于 JSON 序列化/反序列化
     * @param properties      存储目录配置
     * @param cryptoService  凭据加解密服务
     */
    public SessionProfileStore(ObjectMapper objectMapper,
                               SessionStoreProperties properties,
                               CredentialCryptoService cryptoService) {
        this.objectMapper = objectMapper;
        this.rootDir = Paths.get(properties.getDirectory()).toAbsolutePath().normalize();
        this.cryptoService = cryptoService;
    }

    /**
     * 列出指定用户的所有会话配置（摘要，不含凭据明文）。
     *
     * @param username 用户名
     * @return 按更新时间倒序排列的会话列表，不含 password/privateKey/passphrase
     */
    public List<SshSessionProfile> list(String username) {
        synchronized (lockFor(username)) {
            List<StoredSshSessionProfile> stored = sorted(readProfiles(username));
            List<SshSessionProfile> result = new ArrayList<>();
            for (StoredSshSessionProfile profile : stored) {
                result.add(toSummary(profile));
            }
            return result;
        }
    }

    /**
     * 获取指定会话的详情（含凭据明文）。
     *
     * @param username 用户名
     * @param id       会话 ID
     * @return 会话详情，含解密后的 password/privateKey/passphrase；不存在则返回 null
     */
    public SshSessionProfile get(String username, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        synchronized (lockFor(username)) {
            StoredSshSessionProfile found = findById(readProfiles(username), id);
            return found == null ? null : toDetail(found);
        }
    }

    /**
     * 保存或更新会话配置。
     * <p>
     * 若 saveCredentials 为 true 且未传入新凭据，则保留已有加密凭据（用于仅修改连接信息等场景）。
     * </p>
     *
     * @param username 用户名
     * @param profile  会话配置（可为新建或更新）
     * @return 保存后的会话摘要（不含凭据明文）
     */
    public SshSessionProfile save(String username, SshSessionProfile profile) {
        NormalizedRequest request = normalizeRequest(profile);
        synchronized (lockFor(username)) {
            List<StoredSshSessionProfile> profiles = readProfiles(username);
            int index = findIndexById(profiles, request.id());
            StoredSshSessionProfile existing = index >= 0 ? profiles.get(index) : null;
            StoredSshSessionProfile stored = toStored(request, existing);
            if (index >= 0) {
                profiles.set(index, stored);
            } else {
                profiles.add(stored);
            }
            writeProfiles(username, profiles);
            return toSummary(stored);
        }
    }

    /**
     * 删除指定会话。
     *
     * @param username 用户名
     * @param id       会话 ID
     * @return 是否成功删除
     */
    public boolean delete(String username, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        synchronized (lockFor(username)) {
            List<StoredSshSessionProfile> profiles = readProfiles(username);
            boolean removed = profiles.removeIf(p -> id.equals(p.getId()));
            if (removed) {
                writeProfiles(username, profiles);
            }
            return removed;
        }
    }

    /**
     * 获取指定用户的独占锁，用于串行化该用户的读写操作。
     * 使用 computeIfAbsent 保证每个用户名对应唯一锁对象。
     *
     * @param username 用户名
     * @return 该用户对应的锁对象
     */
    private Object lockFor(String username) {
        return locks.computeIfAbsent(username, ignored -> new Object());
    }

    /**
     * 从磁盘读取指定用户的会话配置列表。
     *
     * @param username 用户名
     * @return 会话列表，文件不存在或为空时返回空列表
     */
    private List<StoredSshSessionProfile> readProfiles(String username) {
        Path file = fileOf(username);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<StoredSshSessionProfile> profiles = objectMapper.readValue(file.toFile(), PROFILE_LIST);
            return profiles == null ? new ArrayList<>() : new ArrayList<>(profiles);
        } catch (IOException e) {
            throw new IllegalStateException("读取会话配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将会话配置列表写入磁盘。
     *
     * @param username 用户名
     * @param profiles 会话列表
     */
    private void writeProfiles(String username, List<StoredSshSessionProfile> profiles) {
        Path file = fileOf(username);
        try {
            Files.createDirectories(rootDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), sorted(profiles));
        } catch (IOException e) {
            throw new IllegalStateException("保存会话配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据用户名解析存储文件路径。
     * 将用户名中的非法文件名字符替换为下划线，防止路径遍历等安全问题。
     *
     * @param username 用户名
     * @return 该用户对应的 JSON 文件路径
     */
    private Path fileOf(String username) {
        String safe = username.replaceAll("[^a-zA-Z0-9._-]", "_");
        return rootDir.resolve(safe + ".json");
    }

    /**
     * 按更新时间倒序排序，最新修改的排在前面。
     *
     * @param input 原始列表
     * @return 排序后的列表（原地修改并返回）
     */
    private List<StoredSshSessionProfile> sorted(List<StoredSshSessionProfile> input) {
        input.sort(Comparator.comparingLong(StoredSshSessionProfile::getUpdatedAt).reversed());
        return input;
    }

    /** 在列表中按 ID 查找会话，未找到返回 null */
    private StoredSshSessionProfile findById(List<StoredSshSessionProfile> profiles, String id) {
        for (StoredSshSessionProfile profile : profiles) {
            if (id.equals(profile.getId())) {
                return profile;
            }
        }
        return null;
    }

    /** 在列表中按 ID 查找索引，未找到返回 -1 */
    private int findIndexById(List<StoredSshSessionProfile> profiles, String id) {
        for (int i = 0; i < profiles.size(); i++) {
            if (id.equals(profiles.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 将规范化请求转换为可存储的实体。
     * <p>
     * 若 saveCredentials 为 false，不存储任何凭据；若为 true 且请求中未提供新凭据，
     * 但已有存储的加密凭据，则复用已有凭据（避免用户仅修改名称等时清空密码）。
     * </p>
     *
     * @param request  已校验的请求
     * @param existing 同 ID 的已有实体，新建时为 null
     * @return 可写入磁盘的 StoredSshSessionProfile
     */
    private StoredSshSessionProfile toStored(NormalizedRequest request,
                                             StoredSshSessionProfile existing) {
        StoredSshSessionProfile stored = new StoredSshSessionProfile();
        stored.setId(request.id());
        stored.setName(request.name());
        stored.setHost(request.host());
        stored.setPort(request.port());
        stored.setUsername(request.username());
        stored.setAuthType(request.authType());
        stored.setHostFingerprint(request.hostFingerprint());
        stored.setUpdatedAt(System.currentTimeMillis());

        if (!request.saveCredentials()) {
            return stored;
        }

        // 用户勾选保存凭据但未传入新凭据，且已有加密凭据时，复用已有凭据
        if (existing != null && isBlank(request.password())
                && isBlank(request.privateKey())
                && isBlank(request.passphrase())
                && hasSavedCredentials(existing)) {
            copyEncryptedCredentials(existing, stored);
            return stored;
        }

        // 加密并存储新提供的凭据
        stored.setEncryptedPassword(encryptIfPresent(request.password()));
        stored.setEncryptedPrivateKey(encryptIfPresent(request.privateKey()));
        stored.setEncryptedPassphrase(encryptIfPresent(request.passphrase()));
        return stored;
    }

    /** 将已有实体的加密凭据复制到目标实体，用于“保留凭据”场景 */
    private void copyEncryptedCredentials(StoredSshSessionProfile source,
                                          StoredSshSessionProfile target) {
        target.setEncryptedPassword(source.getEncryptedPassword());
        target.setEncryptedPrivateKey(source.getEncryptedPrivateKey());
        target.setEncryptedPassphrase(source.getEncryptedPassphrase());
    }

    /** 若值非空则加密，否则返回 null */
    private String encryptIfPresent(String value) {
        if (isBlank(value)) {
            return null;
        }
        return cryptoService.encrypt(value);
    }

    /**
     * 将存储实体转换为摘要 DTO，不包含凭据明文。
     */
    private SshSessionProfile toSummary(StoredSshSessionProfile stored) {
        SshSessionProfile profile = new SshSessionProfile();
        profile.setId(stored.getId());
        profile.setName(stored.getName());
        profile.setHost(stored.getHost());
        profile.setPort(stored.getPort());
        profile.setUsername(stored.getUsername());
        profile.setAuthType(stored.getAuthType());
        profile.setHostFingerprint(stored.getHostFingerprint());
        profile.setUpdatedAt(stored.getUpdatedAt());
        profile.setSaveCredentials(hasSavedCredentials(stored));
        profile.setHasSavedCredentials(hasSavedCredentials(stored));
        return profile;
    }

    /**
     * 将存储实体转换为详情 DTO，包含解密后的凭据明文。
     */
    private SshSessionProfile toDetail(StoredSshSessionProfile stored) {
        SshSessionProfile profile = toSummary(stored);
        profile.setPassword(decryptIfPresent(stored.getEncryptedPassword()));
        profile.setPrivateKey(decryptIfPresent(stored.getEncryptedPrivateKey()));
        profile.setPassphrase(decryptIfPresent(stored.getEncryptedPassphrase()));
        return profile;
    }

    /** 若值非空则解密，否则返回 null */
    private String decryptIfPresent(String value) {
        if (isBlank(value)) {
            return null;
        }
        return cryptoService.decrypt(value);
    }

    /** 判断存储实体是否包含任意已保存的加密凭据 */
    private boolean hasSavedCredentials(StoredSshSessionProfile stored) {
        return !isBlank(stored.getEncryptedPassword())
                || !isBlank(stored.getEncryptedPrivateKey())
                || !isBlank(stored.getEncryptedPassphrase());
    }

    /**
     * 校验并规范化请求数据，生成不可变的 NormalizedRequest。
     * 校验失败时抛出 IllegalArgumentException。
     *
     * @param profile 原始请求
     * @return 规范化后的请求
     */
    private NormalizedRequest normalizeRequest(SshSessionProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("会话内容不能为空");
        }

        String name = trim(profile.getName());
        String host = trim(profile.getHost());
        String username = trim(profile.getUsername());
        String authType = trim(profile.getAuthType());
        String fingerprint = normalizeFingerprint(profile.getHostFingerprint());
        int port = profile.getPort() <= 0 ? 22 : profile.getPort();

        if (name == null || host == null || username == null) {
            throw new IllegalArgumentException("name、host、username 不能为空");
        }
        // 仅支持两种认证方式，防止非法值入库
        if (!"PASSWORD".equalsIgnoreCase(authType) && !"PRIVATE_KEY".equalsIgnoreCase(authType)) {
            throw new IllegalArgumentException("authType 只支持 PASSWORD 或 PRIVATE_KEY");
        }

        // 新建时无 id，由服务端生成 UUID
        String id = trim(profile.getId()) == null ? UUID.randomUUID().toString() : trim(profile.getId());
        return new NormalizedRequest(
                id,
                name,
                host,
                port,
                username,
                authType.toUpperCase(),
                fingerprint,
                profile.isSaveCredentials(),
                profile.getPassword(),
                profile.getPrivateKey(),
                profile.getPassphrase()
        );
    }

    /** 去除首尾空白，空字符串转为 null */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 判断字符串是否为空或仅空白 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 规范化指纹格式，统一为 SHA256:xxx 形式。
     */
    private String normalizeFingerprint(String value) {
        String v = trim(value);
        if (v == null) {
            return null;
        }
        if (v.startsWith("SHA256:")) {
            return "SHA256:" + v.substring("SHA256:".length()).trim();
        }
        return "SHA256:" + v;
    }

    /**
     * 规范化后的请求记录，用于在校验通过后传递到 toStored。
     * 使用 record 保证不可变，避免后续逻辑误改。
     */
    private record NormalizedRequest(
            String id,
            String name,
            String host,
            int port,
            String username,
            String authType,
            String hostFingerprint,
            boolean saveCredentials,
            String password,
            String privateKey,
            String passphrase
    ) {}
}
