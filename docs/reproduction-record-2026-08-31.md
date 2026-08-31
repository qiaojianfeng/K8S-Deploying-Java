# Kubernetes Jenkins BuildKit GHCR 复刻记录

**记录日期：** 2026-08-31  
**项目仓库：** `https://github.com/qiaojianfeng/K8S-Deploying-Java`  
**结论：** 集群、CI/CD、镜像推送、Helm 摘要部署、PostgreSQL 与 HTTPS 访问链路均已验证完成。

> 本记录不包含 GitHub PAT、Jenkins 管理员密码、数据库密码、Headlamp Token 或 Docker 配置内容。

## 1. 服务器参数

| 节点 | Kubernetes 角色 | 业务 IP | 系统 | CPU | 内存 | 根盘 | Kubernetes |
| --- | --- | --- | --- | ---: | ---: | ---: | --- |
| `k8s-master` | control-plane | `192.168.123.10` | Ubuntu 24.04.4 LTS ARM64 | 2 vCPU | 3.8 GiB | 29 GiB | v1.36.4 |
| `k8s-node1` | worker | `192.168.123.11` | Ubuntu 24.04.4 LTS ARM64 | 2 vCPU | 3.8 GiB | 29 GiB | v1.36.4 |
| `k8s-node2` | worker | `192.168.123.12` | Ubuntu 24.04.4 LTS ARM64 | 2 vCPU | 3.8 GiB | 29 GiB | v1.36.4 |

- 容器运行时：containerd 2.2.1。
- 三个节点在 2026-08-31 均为 `Ready`。
- RouterOS 已完成桥接网络与 BGP 配置；Kubernetes 业务网段为 `192.168.123.0/24`。

## 2. 核心组件与关键配置

| 组件 | 命名空间/位置 | 最终状态或配置 |
| --- | --- | --- |
| Jenkins | `ci/jenkins-0` | `Running`，使用 Kubernetes 临时 Agent |
| Jenkins Job | `spring-app/main` | Multibranch Pipeline，Fork 仓库为构建源 |
| BuildKit | Jenkins Agent `buildkit` 容器 | Rootless BuildKit，推送 OCI 镜像与 GHCR Registry Cache |
| Maven 缓存 | `ci/maven-cache` | NFS RWX PVC，容量 10 GiB，挂载到 `/home/jenkins/.m2/repository` |
| BuildKit 缓存 | GHCR | `ghcr.io/qiaojianfeng/spring-app:buildcache` |
| PostgreSQL | `spring-app/postgresql-0` | `Running`，NFS PVC 持久化 |
| 应用 | `spring-app` Deployment | 2 副本，Service `spring-app:8080` |
| Ingress | Traefik | `app.k8s.lab`，HTTP/HTTPS 路由 |
| 镜像拉取加速 | 三个节点 containerd | Docker Hub 使用 `docker.1ms.run` mirror；实际 BusyBox 完整拉取验证成功 |

关键仓库配置：

- `ci/jenkins-project.json`：Maven、BuildKit、Helm Agent 参数，GHCR 缓存引用，代理与 `NO_PROXY` 设置。
- `ci/jenkins-agent.yaml`：Jenkins Agent Pod、DNS `ndots:1`、BuildKit 与 Maven 缓存挂载。
- `ci/maven-cache-storage.yaml`：NFS PV/PVC 定义。
- `Dockerfile`：Microsoft OpenJDK 21 基础镜像，以数值 UID/GID `10001:10001` 运行应用。

## 3. 最终成功构建

| 字段 | 结果 |
| --- | --- |
| Jenkins 构建 | `spring-app/main #17` |
| 构建结果 | `Finished: SUCCESS` |
| 构建源提交 | `018a99e` (`Avoid existing base image app group`) |
| 镜像仓库 | `ghcr.io/qiaojianfeng/spring-app` |
| 部署镜像摘要 | `sha256:ffc206134eb5863e76002b4065b68a3cf75a125346beaebf32cc8a28ef7ff983` |
| Maven 测试 | 21 个测试通过 |
| Helm 结果 | `STATUS: deployed` |
| 部署方式 | Helm 使用 `image.repository` + `image.digest`，避免可变 tag |

## 4. 部署验证

2026-08-31 核验结果：

| 资源 | 结果 |
| --- | --- |
| `deployment/spring-app` | `2/2` Ready，`2/2` Available |
| `pod/spring-app-98d557858-f957g` | `1/1 Running`，位于 `k8s-node1` |
| `pod/spring-app-98d557858-hrghk` | `1/1 Running`，位于 `k8s-node2` |
| `pod/postgresql-0` | `1/1 Running` |
| `service/spring-app` | ClusterIP `10.96.156.108:8080` |
| `ingress/spring-app` | Host `app.k8s.lab`，Traefik Ingress |
| 外部入口 | `https://app.k8s.lab:30443/` 返回 HTTP `200` |

## 5. 问题处理记录

| 问题 | 原因 | 处理与结果 |
| --- | --- | --- |
| Jenkins Agent 无法连接 Controller | Pod DNS 的 `ndots:5` 使 Jenkins Service 名称被错误搜索 | Jenkins 内部 URL 使用末尾点号 FQDN，Agent DNS 设置为 `ndots:1` |
| Maven Central 返回不可达 Fake-IP | CoreDNS 上游 DNS 受到本机代理 Fake-IP 影响 | CoreDNS 改用真实上游 DNS；Maven Agent 配置本机代理 |
| Docker Hub 基础镜像层下载极慢或卡住 | 直连/代理到 Docker Hub 的大层传输不稳定 | 应用基础镜像切换到 Microsoft OpenJDK，并为 Microsoft Registry 配置直连；containerd 增加已验证的 Docker Hub mirror |
| Maven 每次重新下载依赖 | 临时 Agent 使用 `emptyDir` 作为 Maven 仓库 | 创建 NFS RWX PVC `maven-cache`；缓存命中后 Maven 测试与打包约 10 秒完成 |
| BuildKit Cache Importer 提示 `not found` | 前序构建失败，尚未导出 GHCR cache | 成功的 #17 构建已执行 cache export；后续构建可导入 `buildcache` |
| NFS Maven PVC 无法挂载 | 新目录未加入 NFS export | 创建 `/srv/nfs/k8s/maven-cache` 并加入 NFS 导出，节点临时挂载验证通过 |
| 新构建无法调度 | 早期失败构建残留 Jenkins Agent Pod 占用资源 | 删除确认无任务的残留 Agent Pod，构建恢复调度 |
| Microsoft OpenJDK 构建失败 | 基础镜像已有 `app` 组，重复 `groupadd app` 返回 exit code 9 | 删除冗余用户/组创建，保留数值非 root 用户运行方式 |
| GitHub commit status 未回写 | PAT 缺少 `repo:status` 或仓库协作者权限 | 不影响 Checkout、构建、GHCR 推送与部署；如需状态回写，补充对应权限 |

## 6. 复刻后操作建议

1. 后续修改应用代码后，在 Jenkins `spring-app/main` 触发构建；成功构建将推送新的 GHCR 镜像摘要并执行 Helm 滚动发布。
2. 监控 NFS、Jenkins 和 PostgreSQL。这三个组件当前是实验环境单点，不具备生产级高可用。
3. 定期检查 `maven-cache` 与 GHCR cache 的容量；必要时按版本策略清理旧镜像。
4. 如需 GitHub 页面显示 Jenkins 提交状态，为 PAT 增加 `repo:status`，并确保该账号是 Fork 的协作者。
