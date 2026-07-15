# 0.2 发布清单

## 构建前

- [ ] `pom.xml`、镜像标签和 CHANGELOG 版本一致。
- [ ] V37–V40 未修改，新增迁移编号连续。
- [ ] 历史修复 dry-run 无阻塞异常。
- [ ] 工作树只包含本次发布改动。

## 自动验证

- [ ] `npm run check`
- [ ] `npm run test:unit`
- [ ] `mvn clean verify -Dfrontend.skip=true`
- [ ] `java scripts/CheckCoverageBaseline.java`
- [ ] `mvn -Pdocker-integration-tests test -Dfrontend.skip=true`
- [ ] Playwright 核心流程冒烟
- [ ] `git diff --check`
- [ ] JAR、Git 构建信息和 SBOM 已生成

## 发布

- [ ] 执行 `scripts/build-release.ps1`，版本与 `pom.xml` 一致。
- [ ] 校验 `SHA256SUMS.txt`。
- [ ] Synology `.env` 已配置独立备份目录。
- [ ] 执行 `sh update.sh <version> [image-tar]`。
- [ ] 脚本返回 `health=UP` 且 `/actuator/info` 版本匹配。

## 冒烟

- [ ] 登录与权限。
- [ ] 附件上传、预览和下载。
- [ ] 采购入库、销售出库。
- [ ] 收付款和冲销。
- [ ] 维修领料、调拨、盘点。
- [ ] 租赁跨月账单和归还末期账单。
- [ ] 重复付款/导入确认只产生一份事实。

## 发布后

- [ ] 统计接口无未预期的 `dataWarnings`。
- [ ] 日志包含请求关联 ID 和构建版本。
- [ ] 日备份任务成功，独立存储存在副本。
- [ ] 记录发布日期、Git 提交、镜像摘要和备份位置。
