# 自动化与压力测试

项目使用 GitHub Actions 做持续集成，使用 k6 做 API 压力测试。

## 1. 自动化测试

`.github/workflows/ci.yml` 在推送和 Pull Request 时自动执行：

- Java 21 后端测试；
- JaCoCo 覆盖率报告；
- JaCoCo 行覆盖率不得低于当前基线 18%；
- 前端 TypeScript 检查和生产构建；
- 测试报告与前端构建产物上传到 Actions Artifacts。

本地执行：

```powershell
.\gradlew.bat test jacocoTestReport jacocoTestCoverageVerification
Set-Location frontend
corepack enable
pnpm install --frozen-lockfile
pnpm build
```

覆盖率报告位于 `app/build/reports/jacoco/test/html/index.html`。

## 2. k6 场景

| 脚本 | 是否写数据 | 用途 |
|---|---:|---|
| `k6/readonly.js` | 否 | 登录后查询个人信息和面试记录，测基础 API、JWT、DB 连接池 |
| `k6/interview-submit.js` | 是 | 提交答案，覆盖状态机、Redis 幂等、动态追问与 LLM 延迟 |
| `k6/video-upload.js` | 是 | 并发上传视频分片，覆盖应用带宽、校验、DB 和 OSS |

写场景不会自动创建数据。每条 fixture 必须对应测试账号拥有的、尚可答题的独立会话，避免多个虚拟用户争抢同一个会话。提交答案会调用追问模型并产生费用。

## 3. 本地运行

安装 k6 后：

```powershell
k6 run `
  -e PERF_BASE_URL=http://localhost:8080 `
  -e PERF_USERNAME=你的测试账号 `
  -e PERF_PASSWORD=你的测试密码 `
  -e PERF_VUS=10 `
  -e PERF_DURATION=30s `
  performance/k6/readonly.js
```

也可以使用 Docker（Windows PowerShell）：

```powershell
docker run --rm `
    -v "${PWD}:/work" `
    -w /work `
    grafana/k6:2.0.0 run `
    -e PERF_BASE_URL=http://host.docker.internal:8080 `
    -e PERF_USERNAME=你的测试账号 `
    -e PERF_PASSWORD=你的测试密码 `
    performance/k6/readonly.js
```

写场景先复制 fixture，确保使用隔离的测试环境：

```powershell
Copy-Item performance/k6/fixtures/sessions.example.json `
  performance/k6/fixtures/sessions.local.json
k6 run `
  -e PERF_BASE_URL=http://localhost:8080 `
  -e PERF_USERNAME=你的测试账号 `
  -e PERF_PASSWORD=你的测试密码 `
  -e PERF_ALLOW_WRITES=true `
  -e PERF_FIXTURE_FILE=./fixtures/sessions.local.json `
  performance/k6/interview-submit.js
```

## 4. GitHub Actions 压测

在 GitHub 仓库中创建名为 `performance` 的 Environment，建议开启人工审批，然后配置：

- Environment variable `K6_BASE_URL`：测试环境地址，例如 `https://test.example.com`；
- Secret `K6_USERNAME`：无限面试测试账号；
- Secret `K6_PASSWORD`：测试账号密码；
- Secret `K6_SESSION_FIXTURES_JSON`：写场景使用的 fixture JSON 数组。

进入 Actions → Performance test → Run workflow。`readonly` 可以直接运行；另外两个场景必须勾选 `confirm_write`。

不要把 `K6_BASE_URL` 配成生产域名。正式容量评估应先在与生产同规格的隔离环境运行，再逐级执行 10、25、50 个并发用户，并同时观察应用 CPU、JVM 堆、数据库连接、Redis 延迟、OSS 带宽和大模型调用限流。
