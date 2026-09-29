# Laya 决策服务部署说明

把 Laya 作为 Mobile Agent 的**可选专用决策后端**运行在你自己的电脑/服务器上。
APK 不内嵌 Python/PyTorch/模型权重；本目录只包含服务端启动说明与连通性自测脚本。

> 状态：脚本与说明已按上游源码核对，但**本次未在本机实际安装/下载权重/启动服务**
> （需要用户明确同意后才下载模型与产生推理开销）。未执行项见文末。

## 服务端是什么

Laya 官方 `laya/serve.py` 暴露与 Jev 相同的 `POST /v1/systemone` 线协议
（`choice`/`score`/`noul` 题型 + `{input_tokens, output_tokens}` usage），
客户端用同一个 `SystemOneHttpClient` 即可对接，仅 endpoint/model/密钥不同。

## 启动（最小路径）

```bash
# 1. 获取并锁定已核对的源码版本
git clone https://github.com/NandhaKishorM/laya.git
cd laya
git checkout 6d942c92081fbc139e736bbd9ac0023223c29b7f   # 本说明核对的提交

# 2. 安装（按上游 README；首次运行会从 Hugging Face 下载 checkpoint，体积与流量需你同意）
pip install -e .

# 3. 配置
cp /path/to/deploy/laya/.env.example .env   # 修改 LAYA_API_KEY 等
set -a; source .env; set +a

# 4. 启动
python -m laya.serve        # 默认 0.0.0.0:8000
```

## 手机端怎么填

- 服务跑在**电脑/云**上，手机要填**那台机器可达的地址**，不是 `localhost`——
  手机的 localhost 是手机自己。同 Wi-Fi 下填电脑的局域网 IP，如 `http://192.168.1.20:8000`。
- `LAYA_HOST=0.0.0.0` 是**服务端监听**地址，不是客户端要填的目标。
- **明文 http 仅 debug 构建可用于局域网联调**；Release 构建只接受 `https://` 决策端点。
  公共网络部署必须套 TLS（反向代理 + `LAYA_ROOT_PATH`）并设置 `LAYA_API_KEY`。
- 设置路径：设置 → 专用决策后端 → Laya → 填 base URL / 模型名 /（可选）密钥 →
  打开"允许数据出站" → 测试连接。

## 环境变量（上游 serve.py 实测名）

| 变量 | 含义 | 默认 |
|---|---|---|
| `LAYA_HOST` / `LAYA_PORT` | 绑定地址/端口 | `0.0.0.0` / `8000` |
| `LAYA_API_KEY` | 设置后要求 `Authorization: Bearer <key>` | 不校验 |
| `LAYA_DEVICE` | torch 设备偏好（拿不到 GPU 静默回退 CPU） | 自动 |
| `LAYA_PRELOAD` | 启动时预加载 checkpoint | `1` |
| `LAYA_MODELS` | 逗号分隔的预加载列表 | 全部 |
| `LAYA_THREADS` | torch intra-op 线程上限（CPU 推理） | torch 默认 |
| `LAYA_AUTO_TASK` | 自动路由到 typed-decisions checkpoint | `0` |
| `LAYA_MAX_LOADED` | 常驻 checkpoint 上限 | `2` |
| `LAYA_MAX_CONCURRENT` | 过鉴权后的并发上限，超出 503 | `16` |
| `LAYA_MAX_TOKEN_BUDGET` | 单请求 max_len/head_max_len 上限 | `8192` |
| `LAYA_ROOT_PATH` | 反代后的 URL 前缀 | 无 |
| `LAYA_LOG_LEVEL` | uvicorn 日志级别 | `info` |

服务端内置限额（源码常量）：state ≤ 50000 字符、questions ≤ 64、choice 选项 ≤ 100、
请求体 ≤ 2MB、score 档位 ≤ 32、选项总数 ≤ 512；超限返回 413/422。
客户端 `model` 字段填 Laya checkpoint 名（如 `typed-decisions`）；
不认识的值（如 `jev-1`）会被服务端当作"未指定"自动路由。

## 自测

```bash
./smoke_test.sh http://<服务器地址>:8000 [API_KEY]
```

覆盖：`/health` 探活 → 合成 `choice` 请求（无真实用户数据）→
Token 预算检查（`max_len` 超 `LAYA_MAX_TOKEN_BUDGET` 应被 422 拒绝）。

## 本次未执行的步骤

- 未实际 `pip install` / 下载 Hugging Face checkpoint（体积与可能的下载成本需用户同意）。
- 未在真实 Laya 上跑通 smoke test；脚本行为按上游源码与接口说明编写，需你在服务端就绪后执行。
- 未做任何 TLS/公网部署验证。
