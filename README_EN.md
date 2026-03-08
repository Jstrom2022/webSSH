# WebSSH (Spring Boot)

A browser-based SSH client built with Java Spring Boot + WebSocket. Features multi-tab terminals, session management, SFTP file management, port forwarding, host fingerprint verification, mobile support, i18n, and login authentication.

## Features

- **Login Authentication** — Spring Security form login with in-memory user store (BCrypt encrypted)
- **Multi-tab SSH Terminal** — Each tab runs an independent WebSocket + xterm.js instance
- **Session Persistence** — Per-user session storage in local JSON files
- **Encrypted Credential Storage** — AES-GCM encryption with configurable master key
- **Host Fingerprint Verification** — SHA-256 fingerprint, auto-trust on first connect with auto-fill
- **Auth Methods** — Password and private key authentication (optional passphrase)
- **Terminal Resize Sync** — Automatic PTY resize on browser window changes
- **SFTP File Management** — Directory browsing, chunked upload, chunked download with ACK flow control, directory creation
- **SSH Port Forwarding** — Local forwarding (-L) and remote forwarding (-R)
- **Shell CWD Tracking** — Injects shell hooks to detect remote `$PWD` changes; SFTP panel auto-syncs
- **Terminal Themes** — 6 color schemes (Default Blue, Orange, Green, Amber, Purple, Red)
- **Internationalization** — 7 languages (简体中文, English, 日本語, 한국어, Deutsch, Français, Русский)
- **Fullscreen Mode** — Toggle fullscreen terminal display
- **Mobile Adaptability** — Responsive Web Design with optimized layouts, sidebar touch control, and file management interactions for mobile browsers

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 17 + Spring Boot 3.3 |
| Communication | Spring WebSocket (`/ws/ssh`) |
| Security | Spring Security (Form Login + BCrypt) |
| SSH Client | JSch (`com.github.mwiede:jsch:0.2.19`) |
| Frontend Terminal | xterm.js + xterm-addon-fit |
| Build Tool | Maven |

Frontend terminal dependencies are bundled as static resources:

- `/vendor/xterm/xterm.js` + `xterm.css`
- `/vendor/xterm-addon-fit/xterm-addon-fit.js`

## Project Structure

```
├── build.sh                          # Build & package script
├── start.sh                          # Start/stop/restart management script
├── pom.xml                           # Maven configuration
├── data/sessions/                    # Session data storage directory
├── release/                          # Build output directory
│   ├── webssh.jar
│   ├── start.sh
│   └── config/application.properties
└── src/main/
    ├── java/com/webssh/
    │   ├── WebSshApplication.java    # Application entry point
    │   ├── config/                   # Configuration (Security, WebSocket, property binding)
    │   ├── session/                  # Session persistence & credential encryption
    │   ├── web/                      # REST controllers (auth, session CRUD, page routing)
    │   └── ws/                       # WebSocket handler (SSH/SFTP/port forwarding)
    └── resources/
        ├── application.properties    # Application configuration
        └── static/                   # Frontend static resources
            ├── index.html            # Main page (terminal UI)
            ├── login.html            # Login page
            ├── app.js                # Frontend main logic
            ├── i18n.js               # Internationalization translations
            ├── style.css             # Main styles
            └── login.css             # Login page styles
```

## Default Configuration

Config file: [application.properties](src/main/resources/application.properties)

| Property | Default | Description |
|----------|---------|-------------|
| `webssh.auth.username` | `admin` | Login username |
| `webssh.auth.password` | `admin123` | Login password |
| `webssh.session-store.directory` | `./data/sessions` | Session data storage directory |
| `webssh.crypto.master-key` | `change-this-master-key-in-production` | Credential encryption master key |
| `webssh.ssh.allow-legacy-ssh-rsa` | `true` | Allow legacy ssh-rsa algorithm |
| `server.port` | `8080` | Server port |

> ⚠️ In production, always override the default credentials and encryption master key via environment variables or external configuration.

## Quick Start

### Development Mode

```bash
# 1. Clone the repository
git clone https://github.com/Jstrom2022/webSSH.git && cd webSSH

# 2. Start the application (requires Java 17+ and Maven)
mvn spring-boot:run

# 3. Open your browser
# http://localhost:8080
# Default credentials: admin / admin123
```

### Build & Package

```bash
./build.sh
```

This generates deployment files in the `release/` directory:

```
release/
├── webssh.jar                    # Executable JAR
├── start.sh                      # Management script
└── config/application.properties # External configuration (editable)
```

### Server Deployment

Upload the `release/` directory to your server and use the management script:

```bash
./start.sh start     # Start
./start.sh stop      # Stop
./start.sh restart   # Restart
./start.sh status    # Check status
```

Default JVM options: `-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200`

Override with the `JAVA_OPTS` environment variable:

```bash
JAVA_OPTS="-Xms256m -Xmx1g" ./start.sh start
```

## Legacy SSH Server Compatibility (ssh-rsa)

If you encounter this error when connecting:

> `Algorithm negotiation fail ... algorithmName="server_host_key" ... serverProposal="ssh-rsa"`

The target server only offers the `ssh-rsa` host key. Legacy compatibility is enabled by default:

```properties
webssh.ssh.allow-legacy-ssh-rsa=true
```

It is recommended to upgrade the server to `ssh-ed25519` or `rsa-sha2-*` first, then disable this option for improved security.

## Host Fingerprint Flow

1. On first connection, if `hostFingerprint` is empty, the server auto-trusts the current fingerprint and proceeds
2. After a successful connection, the server sends the actual fingerprint to the frontend, which auto-fills the session config
3. Subsequent connections strictly verify against the stored fingerprint; mismatches are rejected

## Session Storage & Credential Encryption

- **Session API**: `GET /api/sessions` · `GET /api/sessions/{id}` · `POST /api/sessions` · `DELETE /api/sessions/{id}`
- **Stored data**: Session name, host, port, username, auth method, host fingerprint
- Optional "Save encrypted credentials (password/private key)" checkbox
  - When enabled, credentials are encrypted with `AES/GCM/NoPadding` before being stored; decrypted on load
  - When disabled, passwords/private keys are not saved
- The list endpoint only returns `hasSavedCredentials` metadata, never exposing actual credentials

## WebSocket Protocol

Endpoint: `/ws/ssh`

### Client → Server

| Message Type | Description |
|-------------|-------------|
| `connect` | Establish SSH connection |
| `input` | Send terminal input |
| `resize` | Sync terminal size |
| `disconnect` | Disconnect SSH session |
| `sftp_list` | List directory contents |
| `sftp_mkdir` | Create directory |
| `sftp_upload_start` | Begin chunked upload |
| `sftp_upload_chunk` | Upload chunk data (Base64) |
| `sftp_upload` | Legacy single-shot upload |
| `sftp_download` | Download file |
| `sftp_download_ack` | Download chunk acknowledgment |
| `port_forward_add` | Add port forwarding rule |
| `port_forward_remove` | Remove port forwarding rule |
| `port_forward_list` | List current forwarding rules |

### Server → Client

| Message Type | Description |
|-------------|-------------|
| `info` | Informational message |
| `hostkey_required` | Host key confirmation required |
| `connected` | SSH connection established |
| `output` | Terminal output (Base64 encoded) |
| `sftp_list` | Directory listing result |
| `sftp_upload` | Upload result |
| `sftp_download_start` | Download started (includes file size) |
| `sftp_download_chunk` | Download chunk data |
| `sftp_download` | Legacy download result |
| `port_forward_list` | Port forwarding list |
| `error` | Error message |
| `disconnected` | SSH connection closed |

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/auth/me` | Get current logged-in user info |
| `GET` | `/api/sessions` | List all sessions for current user |
| `GET` | `/api/sessions/{id}` | Get session details (with decrypted credentials) |
| `POST` | `/api/sessions` | Create or update a session |
| `DELETE` | `/api/sessions/{id}` | Delete a session |

## SFTP Notes

- Uploads use chunked mode — the frontend splits files into chunks and sends them sequentially; no fixed size limit
- Downloads use chunked transfer with ACK flow control — 128KB per chunk to prevent browser memory overflow
- Very large files are still limited by browser memory and network stability
- SFTP operations run asynchronously in a dedicated IO thread pool, without blocking WebSocket message handling

## Architecture Highlights

- Each WebSocket session maps to a `ClientConnection`, storing the SSH session, channels, and all runtime state
- IO-intensive operations (SFTP) are dispatched to a dedicated thread pool (core threads ≥ 4, queue capacity 256) to avoid blocking WebSocket threads
- Max concurrent WebSocket connections: 200
- Max WebSocket message size: 8MB
- WebSocket session idle timeout: 30 minutes

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**. See the [LICENSE](LICENSE) and [COPYING](COPYING) files for details.
