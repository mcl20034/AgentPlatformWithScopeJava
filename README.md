# 智能分析平台

当前已完成 V1–V6 基线：认证与权限、模型和数据源管理、MySQL/Elasticsearch 智能问答、字段语义与受控查询计划、证据与导出，以及 PostgreSQL + pgvector 知识库检索和多来源问答。

## 本地前置条件

- Java 21
- Maven 3.9+
- Node.js 22
- PostgreSQL + pgvector（平台数据库与知识库向量存储）

## 初始化平台数据库

创建空数据库和只供应用使用的账号，然后设置环境变量：

```powershell
$env:PLATFORM_DB_URL='jdbc:postgresql://127.0.0.1:5432/agent_platform'
$env:PLATFORM_DB_USERNAME='agent_platform'
$env:PLATFORM_DB_PASSWORD='<本机数据库密码>'
$env:PLATFORM_ADMIN_USERNAME='admin'
$env:PLATFORM_ADMIN_PASSWORD_FILE='D:\secure\agent-platform-admin-password.txt'
$env:PLATFORM_MASTER_KEY_FILE='D:\secure\agent-platform-master-key.txt'
$env:PLATFORM_ALLOWED_OUTBOUND_HOSTS='localhost,127.0.0.1,<模型或数据源主机>'
```

初始化密码文件由部署人员在项目目录外创建并限制读取权限。数据库中不存在可用管理员时，后端从该文件创建管理员，并强制首次登录修改密码。已有管理员后不会重复初始化。

主密钥文件保存 Base64 编码的 32 字节随机密钥，必须与数据库备份分开保管。模型和数据源只能连接 `PLATFORM_ALLOWED_OUTBOUND_HOSTS` 明确列出的主机。

可在 PowerShell 中生成主密钥内容：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes) | Set-Content -NoNewline 'D:\secure\agent-platform-master-key.txt'
```

## 启动后端

机器的全局 Java 仍为 Java 8 时，使用项目验证过的独立 Java 21 路径：

```powershell
$env:JAVA_HOME='D:\develop\jdk\openjdk-21.0.1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd backend
& 'D:\develop\apache-maven-3.9.8\bin\mvn.cmd' '-Dspring-boot.run.profiles=local' 'spring-boot:run'
```

后端默认监听 `http://localhost:8080`。生产环境不要启用 `local` profile，并应通过 HTTPS 使用 Secure Cookie。

## 启动前端

```powershell
cd frontend
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。Vite 将 `/api` 转发至本机后端。

## 验证

```powershell
$env:JAVA_HOME='D:\develop\jdk\openjdk-21.0.1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd backend
D:\develop\apache-maven-3.9.8\bin\mvn.cmd test

cd ..\frontend
npm ci
npm run build
```

后端集成测试使用独立 H2 内存库模拟 PostgreSQL 语法，只用于自动测试；实际运行仍要求 PostgreSQL。
