# 贡献说明

欢迎反馈输入体验、提供九键词例和提交改进。问题与建议请提交到本仓库的 Issues。

报告输入问题时，请说明应用版本、手机型号、Android 版本、使用的编辑器、输入方案，以及能够重现问题的拼音或数字序列。请使用不含私人信息的测试文字；截图中隐藏私人聊天内容。

开发环境与构建命令见 [docs/BUILD.md](docs/BUILD.md)，功能与隐私说明见 [README](README.md)。Android 适配代码位于 `app/`，平台无关的九键与 JNI 会话逻辑位于 `native/`，固定版本上游位于 `vendor/qingjian/`。

每个 PR 聚焦一项改动，说明问题、最终行为与验证结果。修改输入逻辑后运行：

```bash
bash scripts/dev.sh check
bash scripts/dev.sh apk
bash scripts/dev.sh jvm
```

涉及按键、候选上屏或选区操作时，请提供模拟器或真机的实际验证步骤，并同步使用说明。上游代码有自己的贡献约定，修改它之前请阅读 `vendor/qingjian/AGENTS.md` 与其引用文档。

请保留 GPL-3.0-or-later 许可与 NOTICE 中的来源署名，不提交签名密钥、服务凭据、用户数据或构建缓存。
